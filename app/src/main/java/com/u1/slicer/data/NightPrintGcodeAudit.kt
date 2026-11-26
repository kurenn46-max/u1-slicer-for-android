package com.u1.slicer.data

import java.io.File

/**
 * Read-only review of a single-filament A1 mini G-code. No commands are executed.
 *
 * The startup purge may temporarily use a different temperature from printing.
 * It is never silently approved: elevated startup heat requires a separate
 * explicit review before the G-code is eligible for printing.
 */
object NightPrintGcodeAudit {
    data class Report(
        val passed: Boolean,
        val expectedMaterial: String,
        val recognizedMaterials: Set<String>,
        val printerModel: String?,
        val initialNozzleC: Int?,
        val bedCommandsC: Set<Int>,
        val errors: List<String>,
        val warnings: List<String>,
        val requiresStartupHeatApproval: Boolean = false,
        val startupNozzlePeakC: Int? = null,
        val layerNozzlePeakC: Int? = null,
        val petgStartupUnqualified: Boolean = false
    )

    /** Never offer an override when there is any fault besides the single consent gate. */
    fun canRequestStartupHeatApproval(report: Report): Boolean =
        !report.passed && report.requiresStartupHeatApproval &&
            report.startupNozzlePeakC != null &&
            report.errors == listOf("Startup elevated nozzle temperature requires explicit approval")

    fun inspect(
        file: File,
        expectedMaterial: String,
        nozzleC: Int,
        bedC: Int,
        startupHeatApproved: Boolean = false
    ): Report {
        require(expectedMaterial in setOf("TPU", "PETG")) { "Unsupported target material" }
        require(nozzleC in 190..270 && bedC in 30..90) { "Invalid temperature expectation" }

        if (!file.isFile || file.length() == 0L) {
            return Report(false, expectedMaterial, emptySet(), null, null, emptySet(),
                listOf("G-code file is missing or empty"), emptyList())
        }

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val materials = mutableSetOf<String>()
        val machineTags = mutableSetOf<String>()
        val initialNozzleTags = mutableSetOf<Int>()
        val beds = mutableSetOf<Int>()
        val startupNozzles = mutableSetOf<Int>()
        val layerNozzles = mutableSetOf<Int>()
        var expectedNozzleCommandFound = false
        var layerStarted = false
        var startupLastNozzleTarget: Int? = null
        var startupReturnedToTargetWithWait = false

        val materialCommand = Regex("^\\s*M1002\\s+set_filament_type:([A-Za-z0-9_]+)", RegexOption.IGNORE_CASE)
        val materialMeta = Regex("^\\s*;\\s*filament_type\\s*=\\s*(.*)", RegexOption.IGNORE_CASE)
        val machineMeta = Regex("^\\s*;\\s*printer_model\\s*=\\s*(.+)", RegexOption.IGNORE_CASE)
        val nozzleMeta = Regex("^\\s*;\\s*nozzle_temperature_initial_layer\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
        // S/R temperature parameters may follow a tool or heater selector (e.g. M104 T0 S250).
        // Reject unknown temperature syntax instead of silently overlooking a hotend command.
        val temperatureCommand = Regex("^\\s*M(104|109|140|190)(?!\\d)", RegexOption.IGNORE_CASE)
        val temperatureParam = Regex("(?:^|\\s)([SR])(-?\\d+(?:\\.\\d+)?)(?=\\s|$)", RegexOption.IGNORE_CASE)
        val layerMarker = Regex("^;\\s*LAYER_CHANGE\\s*$", RegexOption.IGNORE_CASE)

        file.useLines { lines ->
            lines.forEach { line ->
                if (layerMarker.matches(line.trim())) layerStarted = true
                materialCommand.find(line)?.groupValues?.get(1)?.uppercase()?.let {
                    if (it != "UNKNOWN") materials.add(it)
                }
                materialMeta.find(line)?.groupValues?.get(1)?.let { raw ->
                    raw.split(',', ';').map { it.trim().uppercase() }
                        .filter { it.isNotBlank() && it != "UNKNOWN" }
                        .forEach(materials::add)
                }
                machineMeta.find(line)?.groupValues?.get(1)?.trim()?.let(machineTags::add)
                nozzleMeta.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let(initialNozzleTags::add)
                val executable = line.substringBefore(';').trim()
                temperatureCommand.find(executable)?.let { command ->
                    val parameters = executable.substring(command.range.last + 1)
                    val targets = temperatureParam.findAll(parameters).toList()
                    val type = command.groupValues[1]
                    val target = targets.singleOrNull()?.groupValues?.get(2)?.toDoubleOrNull()
                    if (target == null || !target.isFinite() || target < 0.0 ||
                        target > 500.0 || target % 1.0 != 0.0 ||
                        (targets.singleOrNull()?.groupValues?.get(1)?.uppercase() == "R" &&
                            type != "109" && type != "190")
                    ) {
                        errors.add("Unrecognized or unsafe temperature command: " + executable.take(120))
                    } else {
                        val celsius = target.toInt()
                        if (type == "140" || type == "190") {
                            if (celsius != 0) beds.add(celsius)
                        } else {
                            if (celsius == nozzleC) expectedNozzleCommandFound = true
                            if (!layerStarted) {
                                startupLastNozzleTarget = celsius
                                if (celsius > nozzleC) startupReturnedToTargetWithWait = false
                                if (type == "109" && celsius == nozzleC) startupReturnedToTargetWithWait = true
                            }
                            if (celsius > 0) {
                                if (layerStarted) layerNozzles.add(celsius) else startupNozzles.add(celsius)
                            }
                        }
                    }
                }
            }
        }

        if (materials.isEmpty()) errors.add("Missing filament identity in G-code")
        if (materials.any { it != expectedMaterial }) {
            errors.add("Material mismatch: expected $expectedMaterial; G-code declares " + materials.sorted())
        }
        if (machineTags.size != 1 || !machineTags.single().equals("Bambu Lab A1 mini", ignoreCase = true)) {
            errors.add("Machine mismatch/missing: expected Bambu Lab A1 mini; found " + machineTags.sorted())
        }
        if (initialNozzleTags.size != 1 || initialNozzleTags.single() != nozzleC) {
            errors.add("Initial nozzle mismatch/missing: expected $nozzleC C; found " + initialNozzleTags.sorted())
        }
        if (!layerStarted) errors.add("Missing ;LAYER_CHANGE marker; unable to distinguish startup from printing")
        if (!expectedNozzleCommandFound) errors.add("No nozzle-temperature command found for $nozzleC C")

        // All layer-phase temperatures must remain within the selected print setting.
        val overLayer = layerNozzles.filter { it > nozzleC }
        if (overLayer.isNotEmpty()) errors.add("High nozzle temperature AFTER layer start: " + overLayer.sorted())

        // Machine startup purging is distinct from layer temperature.
        // Elevated heat always requires explicit approval.
        val highStartup = startupNozzles.filter { it > nozzleC }
        // Narrow PETG 235 C recipe exception for the A1 mini's 240/250 C
        // startup purge. It is a review gate, NOT a new print temperature.
        // Do not accept arbitrary elevated temperatures or a purge that has
        // not returned to the selected target before the first layer.
        val petgPurgeRecipe = expectedMaterial == "PETG" && nozzleC == 235
        val ceiling = if (expectedMaterial == "TPU" || petgPurgeRecipe) 250 else nozzleC
        val beyondCeiling = highStartup.filter { it > ceiling || (petgPurgeRecipe && it !in setOf(240, 250)) }
        if (petgPurgeRecipe && highStartup.isNotEmpty() &&
            (!startupReturnedToTargetWithWait || startupLastNozzleTarget != nozzleC)) {
            errors.add("PETG startup purge did not return to 235 C with M109 before layer start")
        }
        if (beyondCeiling.isNotEmpty()) {
            errors.add("Startup nozzle exceeds material cap $ceiling C: " + beyondCeiling.sorted())
        }
        // Setpoint return alone is not proof of cooldown. Until the specific
        // PETG spool limit and A1 mini firmware sequence are verified, keep
        // elevated PETG startup diagnostic-only, even with user consent.
        val petgStartupUnqualified = petgPurgeRecipe && highStartup.isNotEmpty()
        if (petgStartupUnqualified) {
            errors.add("PETG elevated startup heat is not qualified for printing")
        }
        val needsApproval = !petgPurgeRecipe && highStartup.isNotEmpty() && beyondCeiling.isEmpty()
        if (needsApproval) {
            warnings.add(
                "Startup purge reaches ${highStartup.maxOrNull()} C; printing target is $nozzleC C. Manual review required."
            )
            if (!startupHeatApproved) errors.add("Startup elevated nozzle temperature requires explicit approval")
        }

        if (bedC !in beds) errors.add("No heated-bed command found for $bedC C; found " + beds.sorted())
        if (beds.any { it > bedC }) errors.add("Unexpected bed temperature above $bedC C: " + beds.sorted())
        if (beds.any { it < bedC }) warnings.add("Lower bed temperatures also present: " + beds.sorted())

        return Report(
            errors.isEmpty(), expectedMaterial, materials, machineTags.singleOrNull(),
            initialNozzleTags.singleOrNull(), beds, errors, warnings, needsApproval,
            startupNozzles.maxOrNull(), layerNozzles.maxOrNull(), petgStartupUnqualified
        )
    }
}
