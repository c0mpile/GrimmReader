package com.c0mpile.grimmreader.core.files

import java.io.File
import java.util.zip.ZipInputStream

/** libarchive is native, so JVM tests read a zip through the same sequential interface. */
class ZipSequential(
    file: File,
) : SequentialArchive {
    private val zip = ZipInputStream(file.inputStream())

    override fun nextEntry(): String? {
        var entry = zip.nextEntry
        while (entry != null && entry.isDirectory) entry = zip.nextEntry
        return entry?.name
    }

    override fun read(buffer: ByteArray): Int = zip.read(buffer)

    override fun close() = zip.close()
}
