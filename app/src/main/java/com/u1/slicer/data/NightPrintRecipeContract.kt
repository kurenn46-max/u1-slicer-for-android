package com.u1.slicer.data

import java.io.File
import kotlin.math.abs

/**
 * Contract between one-file NIGHT PRINT recipes and the Bambu A1 mini slicer.
 *
 * Only recipe-owned process and filament keys are overridden. The machine's
 * hardware, start/end G-code and motion limits remain target-owned.
 *
 * This validates Orca's emitted configuration metadata, NOT the physical speed
 * of every G1 move (which may be lowered by volumetric/cooling/acceleration
 * limits). Actual material and heater commands must also pass NightPrintGcodeAudit.
 */
object NightPrintRecipeContract {
    data class Mismatch(val key: String, val expected: String, val found: String?)
    data class Result(val mismatches: List<Mismatch>) {
        val passed: Boolean get() = mismatches.isEmpty()
    }

    /** Explicit overrides, used both when embedding 3MF and when tagging JNI intent. */
    fun explicitOverrides(recipe: NightPrintCode.Preset): Map<String, Any> = linkedMapOf<String, Any>(
        "filament_type" to listOf(recipe.material),
        "nozzle_temperature" to listOf(recipe.nozzleC.toString()),
        "nozzle_temperature_initial_layer" to listOf(recipe.nozzleC.toString()),
        "hot_plate_temp" to listOf(recipe.bedC.toString()),
        "hot_plate_temp_initial_layer" to listOf(recipe.bedC.toString()),
        "cool_plate_temp" to listOf(recipe.bedC.toString()),
        "cool_plate_temp_initial_layer" to listOf(recipe.bedC.toString()),
        "textured_plate_temp" to listOf(recipe.bedC.toString()),
        "textured_plate_temp_initial_layer" to listOf(recipe.bedC.toString()),
        "layer_height" to decimal(recipe.layerMm),
        "initial_layer_print_height" to decimal(recipe.layerMm),
        "wall_loops" to recipe.wallCount.toString(),
        "sparse_infill_density" to decimal(recipe.infillPercent) + "%",
        "outer_wall_speed" to decimal(recipe.printMmS),
        "travel_speed" to decimal(recipe.travelMmS),
        "initial_layer_speed" to decimal(recipe.firstLayerMmS),
        "initial_layer_infill_speed" to decimal(recipe.firstLayerMmS),
        "sparse_infill_speed" to recipe.infillMmS.toString(),
        "retraction_length" to listOf(decimal(recipe.retractMm)),
        "retraction_speed" to listOf(decimal(recipe.retractMmS)),
        "enable_support" to if (recipe.support) "1" else "0",
        "brim_width" to decimal(recipe.brimMm),
    ).apply {
        recipe.colorHex?.let { put("filament_colour", listOf(it.uppercase())) }
    }

    /**
     * Refuse to certify an output if even one explicitly set value is missing
     * or different in the resulting Orca G-code metadata.
     */
    fun verify(gcode: File, recipe: NightPrintCode.Preset): Result {
        if (!gcode.isFile || gcode.length() == 0L) {
            return Result(listOf(Mismatch("gcode", "non-empty file", null)))
        }
        val expected = explicitOverrides(recipe).mapValues { (_, value) ->
            when (value) {
                is List<*> -> value.joinToString(",")
                else -> value.toString()
            }
        }
        val emitted = linkedMapOf<String, MutableSet<String>>()
        val metadataLine = Regex("^\\s*;\\s*([A-Za-z0-9_]+)\\s*=\\s*(.*?)\\s*$")
        // Inspect real G1 retract moves as well as declared metadata.
        // Ignore startup purge and printer end macros outside object printing.
        val retractCommand = Regex("^\\s*G1\\s+E-(\\d*\\.?\\d+)\\s+F(\\d+(?:\\.\\d+)?)\\s*(?:;.*)?$", RegexOption.IGNORE_CASE)
        val actualRetractions = linkedSetOf<Pair<Double, Double>>()
        var inPrint = false
        gcode.useLines { lines ->
            lines.forEach { line ->
                if (line.trim().equals(";LAYER_CHANGE", ignoreCase = true) ||
                    line.trim().equals("; CHANGE_LAYER", ignoreCase = true) ||
                    line.trim().equals(";CHANGE_LAYER", ignoreCase = true)) inPrint = true
                if (line.trim().startsWith("; stop printing object", ignoreCase = true)) inPrint = false
                if (inPrint) {
                    retractCommand.matchEntire(line)?.let { match ->
                        val mm = match.groupValues[1].toDoubleOrNull()
                        val speedMmMin = match.groupValues[2].toDoubleOrNull()
                        if (mm != null && speedMmMin != null) actualRetractions.add(mm to speedMmMin / 60.0)
                    }
                }
                val match = metadataLine.matchEntire(line) ?: return@forEach
                val key = match.groupValues[1]
                if (key in expected) {
                    emitted.getOrPut(key) { linkedSetOf() }.add(match.groupValues[2])
                }
            }
        }
        val mismatches = expected.mapNotNull { (key, wanted) ->
            val values = emitted[key]
            if (values.isNullOrEmpty() || values.any { !sameValue(key, wanted, it) }) {
                Mismatch(key, wanted, values?.joinToString(" | "))
            } else null
        }
        if (actualRetractions.isNotEmpty()) {
            val wrong = actualRetractions.filter { (length, speed) ->
                abs(length - recipe.retractMm) > 0.01 ||
                    abs(speed - recipe.retractMmS) > 0.01
            }
            if (wrong.isNotEmpty()) {
                val found = wrong.take(4).joinToString("; ") { (length, speed) ->
                    "$length mm @ $speed mm/s"
                }
                return Result(mismatches + Mismatch("actual_print_retractions",
                    "${recipe.retractMm} mm @ ${recipe.retractMmS} mm/s", found))
            }
        }
        return Result(mismatches)
    }

    private fun sameValue(key: String, wanted: String, found: String): Boolean {
        if (key == "filament_type" || key == "filament_colour") {
            return wanted.equals(found.trim(), ignoreCase = true)
        }
        val left = wanted.trim().removeSuffix("%").toDoubleOrNull()
        val right = found.trim().removeSuffix("%").toDoubleOrNull()
        return left != null && right != null && left.isFinite() && right.isFinite() &&
            abs(left - right) <= 0.01
    }

    private fun decimal(number: Float): String = number.toString().removeSuffix(".0")
}
