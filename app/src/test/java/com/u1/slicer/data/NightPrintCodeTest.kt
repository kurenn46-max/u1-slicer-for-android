package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NightPrintCodeTest {
    private val valid = """
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
    """.trimIndent()

    @Test fun acceptsValidTpu() {
        val p = NightPrintCode.parse(valid)
        assertEquals("TPU", p.material)
        assertEquals(225, p.nozzleC)
        assertEquals(50, p.bedC)
        assertEquals(30f, p.printMmS)
        assertEquals(2, p.wallCount)
        assertEquals(25, p.infillMmS)
        assertTrue(!p.support)
    }

    @Test fun rejectsIncorrectMaterial() {
        reject(valid.replace("material=TPU", "material=PLA"))
    }

    @Test fun rejectsUnsafeTemp() {
        reject(valid.replace("nozzle_c=225", "nozzle_c=310"))
    }

    @Test fun rejectsExcessSpeedForTpu() {
        reject(valid.replace("print_mm_s=30", "print_mm_s=200"))
    }

    @Test fun rejectsDuplicateKey() {
        reject(valid + "\nprint_mm_s=25")
    }

    @Test fun rejectsCommandsAndUnknownKeys() {
        reject(valid + "\nrun_gcode=G28")
    }

    @Test fun rejectsWrongPrinter() {
        reject(valid.replace("printer=BAMBU_A1_MINI", "printer=SNAPMAKER_U1"))
    }

    @Test fun rejectsNaN() {
        reject(valid.replace("infill_percent=15", "infill_percent=NaN"))
    }

    private fun reject(code: String) {
        assertThrows(IllegalArgumentException::class.java) { NightPrintCode.parse(code) }
    }
}
