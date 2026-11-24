package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.bambu.BambuImportedConfigComposer
import com.u1.slicer.data.NightPrintGcodeAudit
import com.u1.slicer.data.NightPrintRecipeContract
import com.u1.slicer.data.NightPrintJobPackage
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.SlicerTarget
import org.json.JSONArray
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

    /**
     * Persist real generated G-code evidence *before* an assertion can fail.
     * Otherwise a material/bed mismatch loses its evidence, and CI only gets
     * the assertion text.  No G-code is executed or sent to a printer here.
     * These tests use synthetic geometry, never the user's private models.
     */
    private fun recordNativeSliceDiagnostics(
        gcode: File,
        reportName: String,
        expectedMaterial: String,
        nozzleC: Int,
        bedC: Int
    ) {
        val audit = NightPrintGcodeAudit.inspect(gcode, expectedMaterial, nozzleC, bedC)
        val relevant = gcode.useLines { lines ->
            lines.map { it.trim() }.filter { line ->
                line.startsWith("M104 ", true) ||
                    line.startsWith("M109 ", true) ||
                    line.startsWith("M140 ", true) ||
                    line.startsWith("M190 ", true) ||
                    line.startsWith("M1002 ", true) ||
                    line.startsWith("; filament_type", true) ||
                    line.startsWith("; printer_model", true) ||
                    line.startsWith("; nozzle_temperature_initial_layer", true)
            }.take(80).toList()
        }
        val evidence = JSONObject().apply {
            put("type", "pre_assertion_native_gcode_diagnostics")
            put("expectedMaterial", expectedMaterial)
            put("observedMaterials", JSONArray(audit.recognizedMaterials.sorted()))
            put("expectedNozzleC", nozzleC)
            put("observedInitialNozzleC", audit.initialNozzleC)
            put("expectedBedC", bedC)
            put("observedBedCommandsC", JSONArray(audit.bedCommandsC.sorted()))
            put("printerModel", audit.printerModel)
            put("errors", JSONArray(audit.errors))
            put("warnings", JSONArray(audit.warnings))
            put("startupHeatApprovalPossible", NightPrintGcodeAudit.canRequestStartupHeatApproval(audit))
            put("gcodeBytes", gcode.length())
            put("relevantCommands", JSONArray(relevant))
            put("status", if (audit.passed) "PASS" else "FAIL_OR_REVIEW")
        }.toString(2)
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            reportName
        ).writeText(evidence)
        Log.i("NIGHTPRINT_E2E", "G-code diagnostic " + reportName +
            ": " + audit.errors.joinToString("; ") +
            "; material=" + audit.recognizedMaterials + "; bed=" + audit.bedCommandsC)
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
                // The test must pass the ENTIRE imported recipe through JNI,
                // exactly as the app does, not a partial set of flags.
                nightPrintSparseInfillSpeed = p.infillMmS.toFloat(),
                nightPrintFilamentColor = p.colorHex.orEmpty(),
                bambuExplicitOverrides =
                    NightPrintRecipeContract.explicitOverrides(p).keys.joinToString(
                        separator = "|", prefix = "|", postfix = "|"
                    )
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
        recordNativeSliceDiagnostics(
            generatedFile, "nightprint-native-e2e-report.json", "TPU", 225, 50
        )
        assertTrue(generated.contains("; printer_model = Bambu Lab A1 mini"))
        assertTrue(generated.contains(";LAYER_CHANGE"))
        assertTrue(
            "No real extruding moves in G-code",
            Regex("""(?m)^G1[^\r\n]*\bE-?\d""").containsMatchIn(generated)
        )

        val realRecipe = NightPrintRecipeContract.verify(generatedFile, job.preset)
        assertTrue(
            "Real STL G-code did not preserve the requested one-click recipe: " +
                realRecipe.mismatches.joinToString("; "),
            realRecipe.passed
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
    /**
     * User-equivalent path: generic geometry-only 3MF from .npjob is embedded
     * with the selected TPU filament profile BEFORE reaching the JNI engine.
     * This is separate from the raw STL direct-native regression above.
     */
    @Test fun geometryOnly3mfNpjobKeepsTpuAfterProfileEmbedAndNativeSlice() {
        val modelXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" unit="millimeter">
              <resources><object id="1" type="model"><mesh>
                <vertices>
                  <vertex x="0" y="0" z="0"/><vertex x="20" y="0" z="0"/>
                  <vertex x="20" y="20" z="0"/><vertex x="0" y="20" z="0"/>
                  <vertex x="0" y="0" z="5"/><vertex x="20" y="0" z="5"/>
                  <vertex x="20" y="20" z="5"/><vertex x="0" y="20" z="5"/>
                </vertices>
                <triangles>
                  <triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="3" v3="2"/>
                  <triangle v1="4" v2="5" v3="6"/><triangle v1="4" v2="6" v3="7"/>
                  <triangle v1="0" v2="1" v3="5"/><triangle v1="0" v2="5" v3="4"/>
                  <triangle v1="1" v2="2" v3="6"/><triangle v1="1" v2="6" v3="5"/>
                  <triangle v1="2" v2="3" v3="7"/><triangle v1="2" v2="7" v3="6"/>
                  <triangle v1="3" v2="0" v3="4"/><triangle v1="3" v2="4" v3="7"/>
                </triangles>
              </mesh></object></resources><build><item objectid="1"/></build>
            </model>
        """.trimIndent()
        val inner3mf = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                fun entry(name: String, value: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("[Content_Types].xml",
                    """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>""")
                entry("_rels/.rels",
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="r0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>""")
                entry("3D/3dmodel.model", modelXml)
            }
        }.toByteArray()
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
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("settings.txt"))
                zip.write(settings.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("model.3mf"))
                zip.write(inner3mf)
                zip.closeEntry()
            }
        }.toByteArray()
        val job = NightPrintJobPackage.unpack(ByteArrayInputStream(zipBytes), dir)
        assertEquals("TPU", job.preset.material)
        val info = com.u1.slicer.bambu.ThreeMfParser.parse(job.model)
        val embedder = com.u1.slicer.bambu.ProfileEmbedder(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        // This reproduces the real A1 mini targeting layer rather than
        // trusting ProfileEmbedder's historical Snapmaker/U1 default stack.
        val embeddedConfig = BambuImportedConfigComposer.compose(
            target = SlicerTarget.BambuA1Mini,
            explicitOverrides = NightPrintRecipeContract.explicitOverrides(job.preset),
        ).config
        assertEquals(listOf("TPU"), embeddedConfig["filament_type"])
        val embedded = embedder.embed(job.model, embeddedConfig, dir, info)
        assertTrue("Native cannot load TPU-profile-embedded generic 3MF",
            native.loadModel(embedded.absolutePath))
        val p = job.preset
        val config = resolveTargetedSliceConfig(
            SlicerTarget.BambuA1Mini,
            SliceConfig(
                extruderCount = 1,
                filamentType = p.material,
                filamentTypes = arrayOf(p.material),
                nozzleTemp = p.nozzleC,
                bedTemp = p.bedC,
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
                extruderTemps = intArrayOf(p.nozzleC),
                filamentNozzleTempInitialLayers = intArrayOf(p.nozzleC),
                filamentBedTempInitialLayers = intArrayOf(p.bedC),
                filamentMaxVolumetricSpeeds = floatArrayOf(2.0f),
                nightPrintSparseInfillSpeed = p.infillMmS.toFloat(),
                nightPrintFilamentColor = p.colorHex.orEmpty(),
                bambuExplicitOverrides = 
                    NightPrintRecipeContract.explicitOverrides(p).keys.joinToString(
                        separator = "|", prefix = "|", postfix = "|"
                    )
            )
        )
        val result = native.slice(config)
        assertNotNull("Native 3MF slice returned null", result)
        assertTrue("Native 3MF slice failed: ${result!!.errorMessage}", result.success)
        val out = File(result.gcodePath)
        assertTrue("No real 3MF G-code generated", out.isFile && out.length() > 1000)
        recordNativeSliceDiagnostics(
            out, "nightprint-3mf-profile-e2e-report.json", "TPU", 225, 50
        )
        val realRecipe = NightPrintRecipeContract.verify(out, job.preset)
        assertTrue(
            "Embedded 3MF native G-code did not preserve the requested recipe: " +
                realRecipe.mismatches.joinToString("; "),
            realRecipe.passed
        )
        val initial = NightPrintGcodeAudit.inspect(out, "TPU", 225, 50)
        assertEquals("Profile embedding did not preserve TPU in generated G-code",
            setOf("TPU"), initial.recognizedMaterials)
        assertTrue(
            "Only the startup-heat consent gate may require review: ${initial.errors}",
            initial.passed || NightPrintGcodeAudit.canRequestStartupHeatApproval(initial)
        )
        val finalAudit = NightPrintGcodeAudit.inspect(out, "TPU", 225, 50,
            startupHeatApproved = true)
        assertTrue("Profile-embedded 3MF G-code did not pass safety audit: ${finalAudit.errors}",
            finalAudit.passed)
        val report = JSONObject().apply {
            put("fixture", "synthetic_geometry_only_3mf_in_npjob")
            put("path", "nightprint_profile_embed_then_real_native_slice")
            put("printer", finalAudit.printerModel)
            put("material", finalAudit.recognizedMaterials.joinToString(","))
            put("gcode_bytes", out.length())
            put("startup_peak_c", finalAudit.startupNozzlePeakC ?: -1)
            put("audit_passed", finalAudit.passed)
            put("result", "PASS")
        }.toString(2)
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "nightprint-3mf-profile-e2e-report.json"
        ).writeText(report)
        Log.i("NIGHTPRINT_3MF_E2E", report.replace("\n", " "))
    }

}
