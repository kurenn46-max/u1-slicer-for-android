package com.u1.slicer.data

import java.io.File

/** Read-only audit for single-filament NIGHT PRINT jobs on Bambu Lab A1 mini. */
object NightPrintGcodeAudit {
    data class Report(
        val passed: Boolean, val expectedMaterial: String,
        val recognizedMaterials: Set<String>, val printerModel: String?,
        val initialNozzleC: Int?, val bedCommandsC: Set<Int>,
        val errors: List<String>, val warnings: List<String>
    )

    fun inspect(file: File, expectedMaterial: String, nozzleC: Int, bedC: Int): Report {
        require(expectedMaterial in setOf("TPU", "PETG")) { "Unsupported target material" }
        require(nozzleC in 190..270 && bedC in 30..90) { "Invalid temperature expectation" }
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (!file.isFile || file.length() == 0L) {
            return Report(false, expectedMaterial, emptySet(), null, null, emptySet(),
                listOf("G-code file is missing or empty"), emptyList())
        }
        val materials = mutableSetOf<String>()
        val machineTags = mutableSetOf<String>()
        val initialNozzleTags = mutableSetOf<Int>()
        val beds = mutableSetOf<Int>()
        val nozzleCommands = mutableSetOf<Int>()
        var expectedNozzleCommandFound = false
        val materialCommand = Regex("^\\s*M1002\\s+set_filament_type:([A-Za-z0-9_]+)", RegexOption.IGNORE_CASE)
        val materialMeta = Regex("^;\\s*filament_type\\s*=\\s*(.*)", RegexOption.IGNORE_CASE)
        val machineMeta = Regex("^;\\s*printer_model\\s*=\\s*(.+)", RegexOption.IGNORE_CASE)
        val nozzleMeta = Regex("^;\\s*nozzle_temperature_initial_layer\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val bedCommand = Regex("^\\s*M1(?:40|90)\\s+S(\\d+)(?:\\s|;|$)", RegexOption.IGNORE_CASE)
        val nozzleCommand = Regex("^\\s*M1(?:04|09)\\s+S(\\d+)(?:\\s|;|$)", RegexOption.IGNORE_CASE)
        file.useLines { lines ->
            lines.forEach { line ->
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
                    if (it > 0) nozzleCommands.add(it)
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
        if (!expectedNozzleCommandFound) errors.add("No nozzle-temperature command found for $nozzleC C")
        if (nozzleCommands.any { it > nozzleC }) {
            errors.add("Unexpected nozzle temperature above $nozzleC C: " + nozzleCommands.sorted())
        }
        if (bedC !in beds) errors.add("No heated-bed command found for $bedC C; found " + beds.sorted())
        if (beds.any { it > bedC }) {
            errors.add("Unexpected bed temperature above $bedC C: " + beds.sorted())
        }
        if (beds.any { it < bedC }) warnings.add("Other lower bed temperature commands: " + beds.sorted())
        return Report(errors.isEmpty(), expectedMaterial, materials, machineTags.singleOrNull(),
            initialNozzleTags.singleOrNull(), beds, errors, warnings)
    }
}
