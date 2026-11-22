package com.u1.slicer.data

/**
 * Strict, declarative NIGHT PRINT settings import.
 * Parsing NEVER runs G-code or sends any instruction to a printer.
 */
object NightPrintCode {
    const val HEADER = "NIGHTPRINT-SETTINGS/V1"

    data class Preset(
        val printer: String, val material: String,
        val nozzleC: Int, val bedC: Int,
        val printMmS: Float, val travelMmS: Float, val firstLayerMmS: Float,
        val retractMm: Float, val retractMmS: Float,
        val layerMm: Float, val wallCount: Int,
        val infillPercent: Float, val infillMmS: Int,
        val support: Boolean, val brimMm: Float
    ) {
        fun preview(): String = buildString {
            appendLine("Material: $material | A1 mini (confirm machine selection)")
            appendLine("Nozzle: $nozzleC C | Bed: $bedC C")
            appendLine("Outer wall: $printMmS mm/s | Travel: $travelMmS mm/s")
            appendLine("First layer: $firstLayerMmS mm/s | Sparse infill: $infillMmS mm/s")
            appendLine("Retraction: $retractMm mm at $retractMmS mm/s")
            appendLine("Layer: $layerMm mm | Walls: $wallCount | Infill: $infillPercent%")
            append("Support: $support | Brim: $brimMm mm")
        }
    }

    private val required = setOf(
        "printer", "material", "nozzle_c", "bed_c", "print_mm_s", "travel_mm_s",
        "first_layer_mm_s", "retract_mm", "retract_mm_s", "layer_mm", "wall_count",
        "infill_percent", "infill_mm_s", "support", "brim_mm"
    )

    fun parse(code: String): Preset {
        require(code.length in 1..4096) { "Code length must be 1..4096" }
        val lines = code.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        require(lines.firstOrNull() == HEADER) { "Missing NIGHTPRINT-SETTINGS/V1 header" }
        val fields = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            require('=' in line) { "Invalid line: $line" }
            val parts = line.split('=', limit = 2).map { it.trim() }
            val key = parts[0]
            val value = parts[1]
            require(key in required) { "Unsupported key: $key" }
            require(value.isNotEmpty()) { "Blank key: $key" }
            require(fields.putIfAbsent(key, value) == null) { "Duplicate key: $key" }
        }
        require(fields.keys == required) {
            "Missing keys: ${(required - fields.keys).sorted().joinToString()}"
        }
        fun integer(name: String, low: Int, high: Int): Int =
            fields.getValue(name).toIntOrNull()?.takeIf { it in low..high }
                ?: error("$name must be $low..$high")
        fun decimal(name: String, low: Float, high: Float): Float =
            fields.getValue(name).toFloatOrNull()?.takeIf { it.isFinite() && it in low..high }
                ?: error("$name must be $low..$high")

        val printer = fields.getValue("printer")
        require(printer == "BAMBU_A1_MINI") { "Only BAMBU_A1_MINI codes are supported" }
        val material = fields.getValue("material")
        require(material == "TPU" || material == "PETG") { "Only TPU and PETG codes are supported" }
        val nozzle = integer("nozzle_c", 190, 270)
        val bed = integer("bed_c", 30, 90)
        val speed = decimal("print_mm_s", 5f, 120f)
        val initial = decimal("first_layer_mm_s", 5f, 60f)
        val infillSpeed = integer("infill_mm_s", 5, 120)
        val retract = decimal("retract_mm", 0f, 3f)
        val retractSpeed = decimal("retract_mm_s", 5f, 60f)
        if (material == "TPU") {
            require(nozzle in 210..250 && bed in 35..70) { "TPU preset temperature out of range" }
            require(speed <= 60f && initial <= 30f && infillSpeed <= 60 && retract <= 1.5f) {
                "TPU preset too aggressive: review speeds/retraction"
            }
        }
        val support = when (fields.getValue("support")) {
            "true" -> true
            "false" -> false
            else -> error("support must be true or false")
        }
        return Preset(
            printer, material, nozzle, bed, speed,
            decimal("travel_mm_s", 10f, 400f), initial, retract, retractSpeed,
            decimal("layer_mm", 0.08f, 0.32f), integer("wall_count", 1, 8),
            decimal("infill_percent", 0f, 100f), infillSpeed, support,
            decimal("brim_mm", 0f, 20f)
        )
    }
}
