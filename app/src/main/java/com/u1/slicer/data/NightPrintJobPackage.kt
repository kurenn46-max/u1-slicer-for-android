package com.u1.slicer.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * NIGHT PRINT job exchange format: a ZIP containing exactly
 * model.3mf (or model.stl) and settings.txt.
 * No G-code or scripts are permitted. This reader cannot send a print job.
 */
object NightPrintJobPackage {
    data class Job(val model: File, val preset: NightPrintCode.Preset, val code: String, val sha256: String)

    private const val MAX_ARCHIVE_UNPACKED = 50L * 1024 * 1024
    private const val MAX_MODEL = 49L * 1024 * 1024
    private const val MAX_SETTINGS = 4096L
    private const val MAX_ENTRIES = 2

    fun unpack(source: InputStream, workingDir: File): Job {
        require(workingDir.isDirectory || workingDir.mkdirs()) { "Invalid working directory" }
        var model: File? = null
        var settings: String? = null
        var total = 0L
        var entries = 0
        var modelName: String? = null
        try {
            ZipInputStream(BufferedInputStream(source)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entries++
                    require(entries <= MAX_ENTRIES) { "Unexpected extra package entries" }
                    require(!entry.isDirectory) { "Directory entries are not allowed" }
                    val name = entry.name
                    require(name == "settings.txt" || name == "model.3mf" || name == "model.stl") {
                        "Unexpected job entry: " + name
                    }
                    if (name == "settings.txt") {
                        require(settings == null) { "Duplicate settings.txt" }
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var count = 0L
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            count += n
                            total += n
                            require(count <= MAX_SETTINGS && total <= MAX_ARCHIVE_UNPACKED) {
                                "Oversized settings entry"
                            }
                            out.write(buffer, 0, n)
                        }
                        settings = out.toString("UTF-8")
                    } else {
                        require(model == null) { "Multiple models are not allowed" }
                        val suffix = if (name.endsWith(".3mf")) ".3mf" else ".stl"
                        val target = File.createTempFile("nightprint_job_", suffix, workingDir)
                        model = target
                        modelName = name
                        var count = 0L
                        target.outputStream().use { out ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                count += n
                                total += n
                                require(count <= MAX_MODEL && total <= MAX_ARCHIVE_UNPACKED) {
                                    "Model exceeds package limit"
                                }
                                out.write(buffer, 0, n)
                            }
                        }
                        require(count > 0L) { "Empty model entry" }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            require(entries == 2 && model != null && settings != null) {
                "Package requires one model and settings.txt"
            }
            val modelFile = model ?: throw IllegalArgumentException("Missing model")
            val code = settings ?: throw IllegalArgumentException("Missing settings")
            val preset = NightPrintCode.parse(code)
            // Minimum format check; the native 3MF/STL importer performs full mesh validation.
            if (modelName == "model.3mf") {
                val bytes = ByteArray(4)
                val read = modelFile.inputStream().use { it.read(bytes) }
                require(read == 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                    "model.3mf is not a ZIP/3MF file"
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            modelFile.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return Job(modelFile, preset, code, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (t: Throwable) {
            model?.delete()
            throw t
        }
    }
}
