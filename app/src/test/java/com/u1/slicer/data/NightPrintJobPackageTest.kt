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

    private fun valid3mf(): ByteArray = archive(
        "[Content_Types].xml" to (
            """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>""" +
            """</Types>"""
        ).toByteArray(),
        "3D/3dmodel.model" to (
            """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">""" +
            """<resources><object id="1" type="model"><mesh><vertices>""" +
            """<vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/>""" +
            """<vertex x="0" y="10" z="0"/></vertices><triangles>""" +
            """<triangle v1="0" v2="1" v3="2"/></triangles></mesh></object></resources>""" +
            """<build><item objectid="1"/></build></model>"""
        ).toByteArray()
    )

    @Test fun importsJobAndPrintSettingsWithoutGcode() {
        val outputDir = Files.createTempDirectory("nightprintjob").toFile()
        try {
            val bytes = archive(
                "settings.txt" to sample.toByteArray(),
                "model.3mf" to valid3mf(),
            )
            val job = NightPrintJobPackage.unpack(ByteArrayInputStream(bytes), outputDir)
            assertEquals("TPU", job.preset.material)
            assertEquals(30f, job.preset.printMmS)
            assertTrue(job.model.name.endsWith(".3mf"))
            assertEquals(64, job.sha256.length)
        } finally { outputDir.deleteRecursively() }
    }

    @Test fun rejectsFakeZipEvenWithPkHeader() {
        reject(archive("settings.txt" to sample.toByteArray(),
            "model.3mf" to byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test fun rejectsNested3mfWithoutRealModelPart() {
        val fake = archive("[Content_Types].xml" to "<Types/>".toByteArray(),
            "random.txt" to "not a 3MF model".toByteArray())
        reject(archive("settings.txt" to sample.toByteArray(), "model.3mf" to fake))
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
