package com.c0mpile.grimmreader.core.dictionary

import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Collects StarDict files from what the user picked or downloaded (loose files, or .zip, .tar, .tar.gz,
 * .tar.bz2, .tar.xz, .tar.zst archives) into [staging], one flat folder. Only dictionary files are written,
 * under their own base name, each capped at [MAX_FILE_BYTES]; paths inside archives are ignored, so nothing
 * lands outside [staging]. Gzipped `.idx` files and `.dict.dz` files that are plain gzip are inflated.
 */
internal class DictionaryUnpacker(
    private val staging: File,
) {
    private var written = 0L

    fun add(
        name: String,
        input: InputStream,
    ) {
        val lower = name.lowercase()
        when {
            ARCHIVES.any { lower.endsWith(it) } -> archive(lower, input).use { each(it) }
            isDictionaryFile(lower) -> save(baseName(name), input)
        }
    }

    private fun archive(
        name: String,
        input: InputStream,
    ): ArchiveInputStream<*> {
        val buffered = BufferedInputStream(input)
        return when {
            name.endsWith(".zip") -> ZipArchiveInputStream(buffered)
            name.endsWith(".tar") -> TarArchiveInputStream(buffered)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> TarArchiveInputStream(GzipCompressorInputStream(buffered))
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") -> TarArchiveInputStream(BZip2CompressorInputStream(buffered))
            name.endsWith(".tar.xz") -> TarArchiveInputStream(XZCompressorInputStream(buffered))
            else -> TarArchiveInputStream(ZstdCompressorInputStream(buffered))
        }
    }

    private fun each(archive: ArchiveInputStream<*>) {
        var entries = 0
        while (true) {
            val entry = archive.nextEntry ?: break
            if (++entries > MAX_ENTRIES) throw IOException("Too many files in the archive")
            if (entry.isDirectory || !archive.canReadEntryData(entry)) continue
            val name = baseName(entry.name)
            if (isDictionaryFile(name.lowercase())) save(name, archive)
        }
    }

    private fun save(
        name: String,
        input: InputStream,
    ) {
        if (name.startsWith(".") || name.isBlank()) return
        val lower = name.lowercase()
        when {
            lower.endsWith(".idx.gz") -> copy(GZIPInputStream(input), File(staging, name.dropLast(GZ.length)))
            lower.endsWith(".dict.dz") || lower.endsWith(".dict.gz") -> {
                val raw = File(staging, name.dropLast(GZ.length) + ".dz.tmp")
                copy(input, raw)
                val plain = File(staging, name.dropLast(GZ.length))
                if (DictZip.isDictZip(raw)) {
                    raw.renameTo(File(staging, plain.name + ".dz"))
                } else {
                    raw.inputStream().use { copy(GZIPInputStream(it), plain) }
                    raw.delete()
                }
            }
            else -> copy(input, File(staging, name))
        }
    }

    private fun copy(
        input: InputStream,
        to: File,
    ) {
        to.outputStream().use { out ->
            val buffer = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                written += n
                if (total > MAX_FILE_BYTES || written > MAX_TOTAL_BYTES) throw IOException("Dictionary file too large")
                out.write(buffer, 0, n)
            }
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 2L shl 30
        const val MAX_TOTAL_BYTES = 6L shl 30
        private const val MAX_ENTRIES = 10_000
        private const val BUFFER = 64 shl 10
        private const val GZ = ".gz"

        val ARCHIVES = listOf(".zip", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".tar.zst")
        private val FILES = listOf(".ifo", ".idx", ".idx.gz", ".syn", ".dict", ".dict.dz", ".dict.gz")

        fun isDictionaryFile(lowerName: String) = FILES.any { lowerName.endsWith(it) }

        fun isImportable(name: String): Boolean =
            name.lowercase().let { lower ->
                isDictionaryFile(lower) ||
                    ARCHIVES.any { lower.endsWith(it) }
            }

        private fun baseName(path: String) = path.substringAfterLast('/').substringAfterLast('\\')
    }
}
