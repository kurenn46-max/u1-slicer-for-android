package com.u1.slicer.data

import com.u1.slicer.bambu.BambuImportedConfigComposer
import com.u1.slicer.slice.SlicerTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for the real 2026-11-24 A1 mini QA3 output, where values reverted. */
class NightPrintRecipeContractTest {
    private val code = """
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

    private val recipe get() = NightPrintCode.parse(code)

    private fun metadata(overrides: Map<String, Any>): String =
        overrides.entries.joinToString("\n") { (key, value) ->
            val text = if (value is List<*>) value.joinToString(",") else value.toString()
            "; $key = $text"
        }

    private fun check(text: String): NightPrintRecipeContract.Result {
        val file = File.createTempFile("night-print-contract-test", ".gcode")
        return try {
            file.writeText(text)
            NightPrintRecipeContract.verify(file, recipe)
        } finally {
            file.delete()
        }
    }

    @Test fun mapsEveryRequestedSpeedMaterialAndRetractionWithoutChangingMachine() {
        val values = NightPrintRecipeContract.explicitOverrides(recipe)
        assertEquals(listOf("TPU"), values["filament_type"])
        assertEquals(listOf("#000000"), values["filament_colour"])
        assertEquals("30", values["outer_wall_speed"])
        assertEquals("150", values["travel_speed"])
        assertEquals("20", values["initial_layer_speed"])
        assertEquals("20", values["initial_layer_infill_speed"])
        assertEquals("25", values["sparse_infill_speed"])
        assertEquals(listOf("0.5"), values["retraction_length"])
        assertEquals(listOf("25"), values["retraction_speed"])
        assertEquals(listOf("50"), values["cool_plate_temp"])
        assertEquals(listOf("50"), values["textured_plate_temp_initial_layer"])
        assertFalse(values.keys.any { it.startsWith("machine_") || it == "printer_model" })
    }

    @Test fun importedProjectCannotOverwriteJobLocalRecipeOrMachineIdentity() {
        val imported = mapOf(
            "outer_wall_speed" to "60",
            "retraction_length" to listOf("0.8"),
            "filament_colour" to listOf("#F2754E"),
            "printer_model" to "Snapmaker U1",
            "machine_start_gcode" to "M104 S270"
        )
        val result = BambuImportedConfigComposer.compose(
            target = SlicerTarget.BambuA1Mini,
            sourceConfig = imported,
            explicitOverrides = NightPrintRecipeContract.explicitOverrides(recipe)
        )
        assertEquals("30", result.config["outer_wall_speed"])
        assertEquals(listOf("0.5"), result.config["retraction_length"])
        assertEquals(listOf("#000000"), result.config["filament_colour"])
        assertEquals("Bambu Lab A1 mini", result.config["printer_model"])
        assertFalse(result.config["machine_start_gcode"] == "M104 S270")
    }

    @Test fun matchingMetadataIsAccepted() {
        assertTrue(check(metadata(NightPrintRecipeContract.explicitOverrides(recipe))).passed)
    }

    @Test fun realQa3RegressionIsRejectedWithSpecificKeys() {
        val mismatched = metadata(NightPrintRecipeContract.explicitOverrides(recipe))
            .replace("; outer_wall_speed = 30", "; outer_wall_speed = 60")
            .replace("; travel_speed = 150", "; travel_speed = 120")
            .replace("; initial_layer_speed = 20", "; initial_layer_speed = 30")
            .replace("; initial_layer_infill_speed = 20", "; initial_layer_infill_speed = 60")
            .replace("; retraction_length = 0.5", "; retraction_length = 0.8")
            .replace("; retraction_speed = 25", "; retraction_speed = 30")
            .replace("; filament_colour = #000000", "; filament_colour = #F2754E")
        val report = check(mismatched)
        assertFalse(report.passed)
        assertEquals(
            setOf("outer_wall_speed", "travel_speed", "initial_layer_speed",
                "initial_layer_infill_speed", "retraction_length", "retraction_speed",
                "filament_colour"),
            report.mismatches.map { it.key }.toSet()
        )
    }

    @Test fun missingMetadataIsNeverSilentlyApproved() {
        val report = check("; printer_model = Bambu Lab A1 mini")
        assertFalse(report.passed)
        assertTrue(report.mismatches.any { it.key == "retraction_length" })
    }

    @Test fun contradictoryDuplicateMetadataIsRejected() {
        val correct = metadata(NightPrintRecipeContract.explicitOverrides(recipe))
        assertFalse(check(correct + "\n; retraction_length = 0.8\n").passed)
    }

    @Test fun noMaterialColorStillDoesNotAssumeBlack() {
        val withoutColor = NightPrintCode.parse(code.replace("color_hex=#000000", ""))
        assertFalse("filament_colour" in NightPrintRecipeContract.explicitOverrides(withoutColor))
    }
}
