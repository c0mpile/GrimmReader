package com.c0mpile.grimmreader.core.dictionary

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** The articles of a dictionary (`.dict`, or `.dict.dz` in dictzip format), read by offset and size. */
internal interface DictData : Closeable {
    fun read(
        offset: Long,
        size: Int,
    ): ByteArray

    companion object {
        /** A larger article is taken as damage rather than allocated. */
        const val MAX_ARTICLE_BYTES = 4 shl 20

        fun open(file: File): DictData = if (file.name.endsWith(".dz")) DictZip.open(file) else PlainDict(file)
    }
}

private class PlainDict(
    file: File,
) : DictData {
    private val raf = RandomAccessFile(file, "r")

    @Synchronized
    override fun read(
        offset: Long,
        size: Int,
    ): ByteArray {
        if (size !in 0..DictData.MAX_ARTICLE_BYTES || offset < 0 || offset + size > raf.length()) throw IOException("Bad article")
        val bytes = ByteArray(size)
        raf.seek(offset)
        raf.readFully(bytes)
        return bytes
    }

    override fun close() = raf.close()
}

/**
 * dictzip: a gzip file whose deflate stream is flushed every [chunkLength] bytes of output, with the compressed
 * size of each chunk in the header's "RA" field, so any chunk can be inflated on its own.
 */
internal class DictZip private constructor(
    private val raf: RandomAccessFile,
    private val chunkLength: Int,
    /** Where each chunk starts in the file; one more entry than chunks, the last is where the data ends. */
    private val chunkStarts: LongArray,
) : DictData {
    private var cachedIndex = -1
    private var cached = ByteArray(0)

    @Synchronized
    override fun read(
        offset: Long,
        size: Int,
    ): ByteArray {
        if (size !in 0..DictData.MAX_ARTICLE_BYTES || offset < 0) throw IOException("Bad article")
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val at = offset + done
            val index = (at / chunkLength).toInt()
            if (index >= chunkStarts.size - 1) throw IOException("Article past the end")
            val chunk = chunk(index)
            val from = (at - index.toLong() * chunkLength).toInt()
            val n = minOf(size - done, chunk.size - from)
            if (n <= 0) throw IOException("Short chunk")
            System.arraycopy(chunk, from, out, done, n)
            done += n
        }
        return out
    }

    private fun chunk(index: Int): ByteArray {
        if (index == cachedIndex) return cached
        val compressed = ByteArray((chunkStarts[index + 1] - chunkStarts[index]).toInt())
        raf.seek(chunkStarts[index])
        raf.readFully(compressed)
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val buffer = ByteArray(chunkLength)
            var n = 0
            while (n < chunkLength && !inflater.finished()) {
                val got = inflater.inflate(buffer, n, chunkLength - n)
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += got
            }
            cached = buffer.copyOf(n)
            cachedIndex = index
            return cached
        } catch (e: DataFormatException) {
            throw IOException("Damaged dictionary data", e)
        } finally {
            inflater.end()
        }
    }

    override fun close() = raf.close()

    companion object {
        private const val ID1 = 0x1F
        private const val ID2 = 0x8B
        private const val DEFLATE = 8
        private const val FHCRC = 2
        private const val FEXTRA = 4
        private const val FNAME = 8
        private const val FCOMMENT = 16
        private const val FIXED_HEADER = 10
        private const val FLAGS_AT = 3

        /** True when [file] is gzip with a dictzip "RA" field (else it has to be inflated whole). */
        fun isDictZip(file: File): Boolean = runCatching { RandomAccessFile(file, "r").use { parse(it) } }.getOrNull() != null

        fun open(file: File): DictZip {
            val raf = RandomAccessFile(file, "r")
            val parsed = runCatching { parse(raf) }.getOrNull()
            if (parsed == null) {
                raf.close()
                throw IOException("Not a dictzip file")
            }
            return DictZip(raf, parsed.first, parsed.second)
        }

        /** Chunk length and chunk start offsets, or null when this is not (intact) dictzip. */
        private fun parse(raf: RandomAccessFile): Pair<Int, LongArray>? {
            val head = ByteArray(FIXED_HEADER)
            raf.readFully(head)
            if (!isGzipWithExtra(head)) return null
            val flags = head[FLAGS_AT].toInt()
            val extra = ByteArray(raf.u16le())
            raf.readFully(extra)
            val (length, sizes) = randomAccess(extra) ?: return null
            if (flags and FNAME != 0) raf.skipZeroTerminated()
            if (flags and FCOMMENT != 0) raf.skipZeroTerminated()
            if (flags and FHCRC != 0) raf.seek(raf.filePointer + 2)
            val starts = LongArray(sizes.size + 1)
            starts[0] = raf.filePointer
            sizes.forEachIndexed { i, size -> starts[i + 1] = starts[i] + size }
            return if (starts.last() > raf.length()) null else length to starts
        }

        private fun isGzipWithExtra(head: ByteArray): Boolean {
            val magic = u8(head[0]) == ID1 && u8(head[1]) == ID2
            return magic && head[2].toInt() == DEFLATE && head[FLAGS_AT].toInt() and FEXTRA != 0
        }

        /** The "RA" subfield: version 1, chunk length, chunk count, then each chunk's compressed size. */
        private fun randomAccess(extra: ByteArray): Pair<Int, IntArray>? {
            var p = 0
            while (p + SUBFIELD_HEADER <= extra.size) {
                val len = u16le(extra, p + 2)
                val data = p + SUBFIELD_HEADER
                if (data + len > extra.size) return null
                if (extra[p] == 'R'.code.toByte() && extra[p + 1] == 'A'.code.toByte() && len >= RA_HEADER) {
                    val chunkLength = u16le(extra, data + 2)
                    val count = u16le(extra, data + RA_COUNT_AT)
                    if (u16le(extra, data) != 1 || chunkLength == 0 || RA_HEADER + count * 2 > len) return null
                    return chunkLength to IntArray(count) { u16le(extra, data + RA_HEADER + it * 2) }
                }
                p = data + len
            }
            return null
        }

        private const val SUBFIELD_HEADER = 4
        private const val RA_HEADER = 6
        private const val RA_COUNT_AT = 4
        private const val BYTE_MASK = 0xFF
        private const val BYTE_BITS = 8

        private fun u8(b: Byte) = b.toInt() and BYTE_MASK

        private fun u16le(
            a: ByteArray,
            at: Int,
        ) = u8(a[at]) or (u8(a[at + 1]) shl BYTE_BITS)

        private fun RandomAccessFile.u16le(): Int {
            val lo = read()
            val hi = read()
            if (lo < 0 || hi < 0) throw IOException("Truncated header")
            return lo or (hi shl BYTE_BITS)
        }

        private fun RandomAccessFile.skipZeroTerminated() {
            while (true) {
                when (read()) {
                    -1 -> throw IOException("Truncated header")
                    0 -> return
                }
            }
        }
    }
}
