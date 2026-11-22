package com.u1.slicer.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NightPrintJobPackageTest {
    private val sample = """
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

    private fun archive(vararg files: Pair<String, ByteArray>): ByteArray {
        val buf = ByteArrayOutputStream()
        ZipOutputStream(buf).use { zip ->
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buf.toByteArray()
    }

    @Test fun importsJobAndPrintSettingsWithoutGcode() {
        val outputDir = Files.createTempDirectory("nightprintjob").toFile()
        try {
            val bytes = archive(
                "settings.txt" to sample.toByteArray(),
                "model.3mf" to byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            )
            val job = NightPrintJobPackage.unpack(ByteArrayInputStream(bytes), outputDir)
            assertEquals("TPU", job.preset.material)
            assertEquals(30f, job.preset.printMmS)
            assertTrue(job.model.name.endsWith(".3mf"))
            assertEquals(64, job.sha256.length)
        } finally { outputDir.deleteRecursively() }
    }

    @Test fun rejectsPathTraversal() {
        reject(archive("../settings.txt" to sample.toByteArray(),
            "model.3mf" to byteArrayOf(0x50, 0x4B, 3, 4)))
    }

    @Test fun rejectsExtraGcodeEntry() {
        reject(archive("settings.txt" to sample.toByteArray(),
            "model.3mf" to byteArrayOf(0x50, 0x4B, 3, 4),
            "model.gcode" to "G28".toByteArray()))
    }

    @Test fun rejectsContradictoryMaterial() {
        reject(archive("settings.txt" to sample.replace("material=TPU", "material=PLA").toByteArray(),
            "model.3mf" to byteArrayOf(0x50, 0x4B, 3, 4)))
    }

    @Test fun rejectsMissingSettings() {
        reject(archive("model.3mf" to byteArrayOf(0x50, 0x4B, 3, 4)))
    }

    @Test fun rejectsBadModelSignature() {
        reject(archive("settings.txt" to sample.toByteArray(), "model.3mf" to byteArrayOf(0, 0, 0, 0)))
    }

    private fun reject(bytes: ByteArray) {
        val outputDir = Files.createTempDirectory("nightprintjob-reject").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                NightPrintJobPackage.unpack(ByteArrayInputStream(bytes), outputDir)
            }
        } finally { outputDir.deleteRecursively() }
    }
}
