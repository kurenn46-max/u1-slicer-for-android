package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuImportedConfigComposer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.data.NightPrintCode
import com.u1.slicer.data.NightPrintGcodeAudit
import com.u1.slicer.data.NightPrintRecipeContract
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeParser
import com.u1.slicer.gcode.MoveType
import com.u1.slicer.slice.SlicerTarget
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NightPrintQualityE2ETest {
    @Test fun steppedSocketAndPlugStlSliceWithPetg() = verifyFixture("stl")
    @Test fun steppedSocketAndPlug3mfSliceWithEmbeddedPetg() = verifyFixture("3mf")

    private fun verifyFixture(format: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.cacheDir, "nightprint-quality-$format")
        dir.deleteRecursively()
        check(dir.mkdirs())
        assertTrue("Real ARM64 engine must be loaded", NativeLibrary.isLoaded)
        val native = NativeLibrary()
        try {
            native.clearModel()
            val fixture = File(dir, "nightprint-clearance.$format")
            instrumentation.context.assets.open(fixture.name).use { input ->
                fixture.outputStream().use { input.copyTo(it) }
            }
            val recipe = NightPrintCode.parse("""
                NIGHTPRINT-SETTINGS/V1
                printer=BAMBU_A1_MINI
                material=PETG
                nozzle_c=235
                bed_c=70
                print_mm_s=30
                travel_mm_s=150
                first_layer_mm_s=20
                retract_mm=0.5
                retract_mm_s=25
                layer_mm=0.20
                wall_count=2
                infill_percent=15
                infill_mm_s=25
                support=false
                brim_mm=0
                color_hex=#000000
            """.trimIndent())
            val overrides = NightPrintRecipeContract.explicitOverrides(recipe)
            val model = if (format == "3mf") {
                val embedded = BambuImportedConfigComposer.compose(
                    target = SlicerTarget.BambuA1Mini, explicitOverrides = overrides
                ).config
                ProfileEmbedder(instrumentation.targetContext).embed(
                    fixture, embedded, dir, ThreeMfParser.parse(fixture)
                )
            } else fixture
            assertTrue("JNI could not load $format", native.loadModel(model.absolutePath))
            val config = resolveTargetedSliceConfig(SlicerTarget.BambuA1Mini, SliceConfig(
                extruderCount = 1,
                filamentType = recipe.material,
                filamentTypes = arrayOf(recipe.material),
                nozzleTemp = recipe.nozzleC,
                bedTemp = recipe.bedC,
                layerHeight = recipe.layerMm,
                firstLayerHeight = recipe.layerMm,
                printSpeed = recipe.printMmS,
                travelSpeed = recipe.travelMmS,
                firstLayerSpeed = recipe.firstLayerMmS,
                retractLength = recipe.retractMm,
                retractSpeed = recipe.retractMmS,
                perimeters = recipe.wallCount,
                fillDensity = recipe.infillPercent / 100f,
                supportEnabled = recipe.support,
                brimWidth = recipe.brimMm,
                extruderTemps = intArrayOf(recipe.nozzleC),
                filamentNozzleTempInitialLayers = intArrayOf(recipe.nozzleC),
                filamentBedTempInitialLayers = intArrayOf(recipe.bedC),
                nightPrintSparseInfillSpeed = recipe.infillMmS.toFloat(),
                nightPrintFilamentColor = recipe.colorHex.orEmpty(),
                bambuExplicitOverrides = overrides.keys.joinToString("|", "|", "|")
            ))
            val result = checkNotNull(native.slice(config))
            assertTrue("Real $format slice failed: " + result.errorMessage, result.success)
            val gcode = File(result.gcodePath)
            assertTrue("Missing real $format G-code", gcode.isFile && gcode.length() > 1000L)

            // No startup approval is supplied, and no held G-code is exported.
            val safety = NightPrintGcodeAudit.inspect(gcode, "PETG", 235, 70)
            val settings = NightPrintRecipeContract.verify(gcode, recipe)
            val equivalent = File(dir, "preview.gcode")
            // Orca/Bambu use two spellings for otherwise identical preview labels.
            val sourceLayer = mutableListOf(0)
            var layerNumber = 0
            gcode.useLines { lines ->
                equivalent.bufferedWriter().use { output ->
                    lines.forEach { line ->
                        if (line.trim() == ";LAYER_CHANGE") layerNumber++
                        sourceLayer.add(layerNumber)
                        output.appendLine(line.replaceFirst("; FEATURE: ", ";TYPE:"))
                    }
                }
            }
            val features = setOf(
                "Outer wall", "Inner wall", "Sparse infill", "Internal solid infill",
                "Solid infill", "Top surface", "Bottom surface", "Gap infill"
            )
            val layers = GcodeParser.parse(equivalent).layers.flatMap { it.moves }
                .filter { it.type == MoveType.EXTRUDE && it.featureLabel in features && sourceLayer[it.lineNumber] > 0 }
                .groupBy { sourceLayer[it.lineNumber] }.values.toList()

            // Translation cancels out by using the socket perimeter as the local origin.
            val sampled = layers.drop(3).take(9).filterIndexed { i, _ -> i % 4 == 0 }
            val lower = sampled.map { moves ->
                val originX = moves.minOf { minOf(it.x0, it.x1) }
                val originY = moves.minOf { minOf(it.y0, it.y1) }
                val plug = moves.flatMap { listOf(it.x0 - originX, it.x1 - originX) }.filter { it > 21 }
                val holeClear = moves.none { move ->
                    val steps = ceil(maxOf(abs(move.x1 - move.x0), abs(move.y1 - move.y0)) / .25f)
                        .toInt().coerceAtLeast(1)
                    (0..steps).any { i ->
                        val t = i.toFloat() / steps
                        val x = move.x0 + t * (move.x1 - move.x0) - originX
                        val y = move.y0 + t * (move.y1 - move.y0) - originY
                        x in 6.8f..10.8f && y in 6.5f..9.5f
                    }
                }
                holeClear && plug.isNotEmpty() && plug.min() in 23f..25f &&
                    plug.max() - plug.min() in 6.5f..8.2f
            }
            val thinLayers = layers.takeLast(12).count { moves ->
                val xSpan = moves.maxOf { maxOf(it.x0, it.x1) } - moves.minOf { minOf(it.x0, it.x1) }
                val ySpan = moves.maxOf { maxOf(it.y0, it.y1) } - moves.minOf { minOf(it.y0, it.y1) }
                xSpan <= 1f && ySpan >= 13f
            }
            val geometry = lower.size == 3 && lower.all { it } && thinLayers >= 9 && layers.size in 34..38
            val qualified = safety.passed || safety.errors == listOf(
                "PETG elevated startup heat is not qualified for printing"
            )
            val verdict = JSONObject()
                .put("format", format).put("engine", "real_android_native_jni")
                .put("gcode_bytes", gcode.length())
                .put("gcode_sha256", MessageDigest.getInstance("SHA-256").digest(gcode.readBytes())
                    .joinToString("") { "%02x".format(it) })
                .put("layers", layers.size).put("hole_and_plug_layers", lower.count { it })
                .put("thin_fin_layers", thinLayers).put("geometry_passed", geometry)
                .put("recipe_passed", settings.passed)
                .put("startup_peak_c", safety.startupNozzlePeakC ?: -1)
                .put("layer_peak_c", safety.layerNozzlePeakC ?: -1)
                .put("safety_errors", JSONArray(safety.errors))
                .put("safety", when {
                    safety.passed -> "VERIFIED"
                    qualified -> "HOLD"
                    else -> "FAIL"
                })
            Log.i("NIGHTPRINT_QUALITY", verdict.toString())
            assertTrue("Geometry check failed: $verdict", geometry)
            assertTrue("PETG recipe mismatch: " + settings.mismatches, settings.passed)
            assertTrue("Unexpected PETG safety errors: " + safety.errors, qualified)
        } finally {
            native.clearModel()
            dir.deleteRecursively()
        }
    }
}
