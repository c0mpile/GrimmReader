package com.c0mpile.grimmreader.core.files

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * An archive read front to back. Solid 7z and RAR archives have no cheap random access, so they are read
 * once in order. Data of an entry not read is skipped by the next [nextEntry] call.
 */
interface SequentialArchive : Closeable {
    /** Name of the next regular file, or null at the end. */
    fun nextEntry(): String?

    /** Reads the current entry's data into [buffer]; -1 at the end of the entry. */
    fun read(buffer: ByteArray): Int

    /** The current entry's data as a stream (not closed by the caller's `use`; the archive owns it). */
    fun entryStream(): InputStream =
        object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (off == 0 && len == b.size) return this@SequentialArchive.read(b)
                val tmp = ByteArray(len)
                val n = this@SequentialArchive.read(tmp)
                if (n > 0) tmp.copyInto(b, off, 0, n)
                return n
            }
        }
}

private const val BYTE_MASK = 0xFF

class EncryptedArchiveException : IOException("This archive is password-protected")

/**
 * CB7 and CBR through libarchive (BSD; its RAR 4/5 readers are clean-room, no UnRAR code). Native errors
 * become [IOException]s.
 */
class LibarchiveArchive(
    file: File,
) : SequentialArchive {
    private val archive = Archive.readNew()
    private val direct = ByteBuffer.allocateDirect(BUFFER)

    init {
        try {
            Archive.readSupportFormat7zip(archive)
            Archive.readSupportFormatRar(archive)
            Archive.readSupportFormatRar5(archive)
            Archive.readOpenFileName(archive, file.path.toByteArray(), BUFFER.toLong())
        } catch (e: ArchiveException) {
            Archive.readFree(archive)
            throw IOException("Cannot open archive", e)
        }
    }

    override fun nextEntry(): String? = native { findNext() }

    private fun findNext(): String? {
        var entry = Archive.readNextHeader(archive)
        while (entry != 0L) {
            if (ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG) {
                if (ArchiveEntry.isEncrypted(entry)) throw EncryptedArchiveException()
                val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.decodeToString()
                if (name != null) return name
            }
            entry = Archive.readNextHeader(archive)
        }
        return null
    }

    override fun read(buffer: ByteArray): Int =
        native {
            direct.clear()
            direct.limit(minOf(buffer.size, BUFFER))
            Archive.readData(archive, direct)
            val n = direct.position()
            if (n == 0) return@native -1
            direct.flip()
            direct.get(buffer, 0, n)
            n
        }

    override fun close() {
        Archive.readFree(archive)
    }

    private inline fun <T> native(block: () -> T): T =
        try {
            block()
        } catch (e: ArchiveException) {
            throw IOException("Archive error", e)
        }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
