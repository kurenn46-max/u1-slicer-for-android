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
        val layerNozzlePeakC: Int? = null
    )

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

        val materialCommand = Regex("^\\s*M1002\\s+set_filament_type:([A-Za-z0-9_]+)", RegexOption.IGNORE_CASE)
        val materialMeta = Regex("^;\\s*filament_type\\s*=\\s*(.*)", RegexOption.IGNORE_CASE)
        val machineMeta = Regex("^;\\s*printer_model\\s*=\\s*(.+)", RegexOption.IGNORE_CASE)
        val nozzleMeta = Regex("^;\\s*nozzle_temperature_initial_layer\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val bedCommand = Regex("^\\s*M1(?:40|90)\\s+S(\\d+)(?:\\s|;|$)", RegexOption.IGNORE_CASE)
        val nozzleCommand = Regex("^\\s*M1(?:04|09)\\s+S(\\d+)(?:\\s|;|$)", RegexOption.IGNORE_CASE)
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
                bedCommand.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    if (it != 0) beds.add(it)
                }
                nozzleCommand.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    if (it == nozzleC) expectedNozzleCommandFound = true
                    if (it > 0) {
                        if (layerStarted) layerNozzles.add(it) else startupNozzles.add(it)
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

        // TPU startup purging can legitimately use up to 250 C (depending on the
        // machine's own startup G-code). Allow this only with explicit approval.
        // Other/unknown startup heating remains blocked.
        val highStartup = startupNozzles.filter { it > nozzleC }
        val ceiling = if (expectedMaterial == "TPU") 250 else nozzleC
        val beyondCeiling = highStartup.filter { it > ceiling }
        if (beyondCeiling.isNotEmpty()) {
            errors.add("Startup nozzle exceeds material cap $ceiling C: " + beyondCeiling.sorted())
        }
        val needsApproval = highStartup.isNotEmpty() && beyondCeiling.isEmpty()
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
            startupNozzles.maxOrNull(), layerNozzles.maxOrNull()
        )
    }
}
