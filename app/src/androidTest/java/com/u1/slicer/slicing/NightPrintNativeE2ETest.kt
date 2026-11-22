package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.data.NightPrintGcodeAudit
import com.u1.slicer.data.NightPrintJobPackage
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.SlicerTarget
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Requires an Android emulator/device capable of loading the arm64 JNI slicer.
 * This is a genuine NativeLibrary.slice() run: it never talks to any printer.
 * Synthetic public test asset (tetrahedron), not the user's private design.
 */
@RunWith(AndroidJUnit4::class)
class NightPrintNativeE2ETest {
    private lateinit var native: NativeLibrary
    private lateinit var dir: File

    @Before fun setup() {
        assertTrue("JNI slicer must load; never silently skip", NativeLibrary.isLoaded)
        native = NativeLibrary()
        native.clearModel()
        dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "nightprint-native-e2e"
        ).apply { deleteRecursively(); check(mkdirs()) }
    }

    @After fun teardown() {
        native.clearModel()
        dir.deleteRecursively()
    }

    @Test fun tpuNpjobSlicesWithRealNativeEngineAndRejectsBadGcode() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val stl = assetContext.assets.open("tetrahedron.stl").use { it.readBytes() }
        val settings = """
            NIGHTPRINT-SETTINGS/V1
            printer=BAMBU_A1_MINI
            material=TPU
            nozzle_c=225
            bed_c=50
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
        """.trimIndent()

        val npjob = ByteArrayOutputStream().also { destination ->
            ZipOutputStream(destination).use { zip ->
                zip.putNextEntry(ZipEntry("settings.txt"))
                zip.write(settings.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("model.stl"))
                zip.write(stl)
                zip.closeEntry()
            }
        }.toByteArray()
        val job = NightPrintJobPackage.unpack(ByteArrayInputStream(npjob), dir)
        assertEquals("TPU", job.preset.material)
        assertEquals("#000000", job.preset.colorHex)
        assertTrue("Native loadModel failed", native.loadModel(job.model.absolutePath))

        val p = job.preset
        val config = resolveTargetedSliceConfig(
            SlicerTarget.BambuA1Mini,
            SliceConfig(
                extruderCount = 1,
                nozzleTemp = p.nozzleC,
                bedTemp = p.bedC,
                filamentType = p.material,
                filamentTypes = arrayOf(p.material),
                layerHeight = p.layerMm,
                firstLayerHeight = p.layerMm,
                printSpeed = p.printMmS,
                travelSpeed = p.travelMmS,
                firstLayerSpeed = p.firstLayerMmS,
                retractLength = p.retractMm,
                retractSpeed = p.retractMmS,
                perimeters = p.wallCount,
                fillDensity = p.infillPercent / 100f,
                supportEnabled = p.support,
                brimWidth = p.brimMm,
                filamentNozzleTempInitialLayers = intArrayOf(p.nozzleC),
                filamentBedTempInitialLayers = intArrayOf(p.bedC),
                filamentMaxVolumetricSpeeds = floatArrayOf(2.0f),
                // The app marks these as explicitly selected when applying .npjob.
                // Without these flags the Bambu STL PLA/220C baseline may win.
                bambuExplicitOverrides =
                    "|filament_type|nozzle_temperature|hot_plate_temp|textured_plate_temp|" +
                    "layer_height|wall_loops|sparse_infill_density|enable_support|brim_width|"
            )
        )
        assertEquals("BAMBU_A1_MINI", config.machineTarget)
        assertEquals(180f, config.bedSizeX, 0f)
        assertEquals(180f, config.bedSizeY, 0f)
        val result = native.slice(config)
        assertNotNull("Native slicer returned null", result)
        assertTrue("Native slicer failed: ${result!!.errorMessage}", result.success)
        val generatedFile = File(result.gcodePath)
        assertTrue("No actual G-code produced", generatedFile.isFile && generatedFile.length() > 1000L)
        val generated = generatedFile.readText()
        assertTrue(generated.contains("; printer_model = Bambu Lab A1 mini"))
        assertTrue(generated.contains(";LAYER_CHANGE"))
        assertTrue(
            "No real extruding moves in G-code",
            Regex("""(?m)^G1[^\r\n]*\bE-?\d""").containsMatchIn(generated)
        )

        val initial = NightPrintGcodeAudit.inspect(generatedFile, "TPU", 225, 50)
        assertEquals("Native output must declare TPU", setOf("TPU"), initial.recognizedMaterials)
        assertTrue(
            "Unexpected errors cannot be approved: ${initial.errors}",
            initial.passed || NightPrintGcodeAudit.canRequestStartupHeatApproval(initial)
        )
        val approved = NightPrintGcodeAudit.inspect(generatedFile, "TPU", 225, 50, true)
        assertTrue("Real G-code safety audit failure: ${approved.errors}", approved.passed)

        // Mutate the *actual generated G-code*, not a synthetic standalone string.
        val badMaterial = File(dir, "bad-material.gcode").apply {
            writeText(generated + "\n; filament_type = PLA\n")
        }
        val badMaterialAudit = NightPrintGcodeAudit.inspect(badMaterial, "TPU", 225, 50)
        assertFalse(badMaterialAudit.passed)
        assertFalse(NightPrintGcodeAudit.canRequestStartupHeatApproval(badMaterialAudit))
        val hotLayer = File(dir, "hot-layer.gcode").apply {
            writeText(generated + "\nM104 T0 S270\n")
        }
        val hotLayerAudit = NightPrintGcodeAudit.inspect(hotLayer, "TPU", 225, 50, true)
        assertFalse(hotLayerAudit.passed)

        val evidence = JSONObject().apply {
            put("fixture", "public_tetrahedron_STL_in_npjob")
            put("engine", "real_android_native_jni")
            put("printer", config.machineTarget)
            put("material", approved.recognizedMaterials.joinToString(","))
            put("nozzle_c", p.nozzleC)
            put("bed_c", p.bedC)
            put("gcode_bytes", generatedFile.length())
            put("layer_markers", generated.lineSequence().count { it.trim() == ";LAYER_CHANGE" })
            put("startup_peak_c", approved.startupNozzlePeakC ?: -1)
            put("approval_required", initial.requiresStartupHeatApproval)
            put("final_audit_pass", approved.passed)
            put("bad_material_rejected", !badMaterialAudit.passed)
            put("hot_layer_rejected", !hotLayerAudit.passed)
            put("test", "PASS")
        }.toString(2)
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "nightprint-native-e2e-report.json"
        ).writeText(evidence)
        Log.i("NIGHTPRINT_E2E", evidence.replace("\n", " "))
    }
}
