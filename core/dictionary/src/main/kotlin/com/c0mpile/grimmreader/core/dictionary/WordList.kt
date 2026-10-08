package com.c0mpile.grimmreader.core.dictionary

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A sorted StarDict word list (`.idx` or `.syn`), memory-mapped so a large dictionary costs no heap beyond
 * one int per word. Each entry is a NUL-terminated UTF-8 word followed by [tail] bytes of data. Entries are
 * sorted ASCII-case-insensitively, ties broken by plain byte order (StarDict's `stardict_strcmp`).
 */
internal class WordList private constructor(
    private val buf: ByteBuffer,
    private val starts: IntArray,
    private val tail: Int,
) {
    val size: Int get() = starts.size

    fun word(index: Int): String {
        val start = starts[index]
        val end = wordEnd(start)
        val bytes = ByteArray(end - start)
        buf.duplicate().apply { position(start) }.get(bytes)
        return bytes.decodeToString()
    }

    /** The big-endian 32-bit value [at] bytes after the word's NUL. */
    fun int(
        index: Int,
        at: Int,
    ): Long = buf.getInt(wordEnd(starts[index]) + 1 + at).toLong() and UINT_MASK

    /** The big-endian 64-bit value [at] bytes after the word's NUL. */
    fun long(
        index: Int,
        at: Int,
    ): Long = buf.getLong(wordEnd(starts[index]) + 1 + at)

    /** Indices of the entries equal to [key] ignoring ASCII case (often one; "Polish" and "polish" are two). */
    fun find(key: String): IntRange {
        val bytes = key.encodeToByteArray()
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareAsciiCase(mid, bytes) < 0) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < size && compareAsciiCase(end, bytes) == 0) end++
        return lo until end
    }

    /** g_ascii_strcasecmp of entry [index] against [key]. */
    private fun compareAsciiCase(
        index: Int,
        key: ByteArray,
    ): Int {
        var p = starts[index]
        for (b in key) {
            val c = buf.get(p).toInt() and BYTE_MASK
            if (c == 0) return -1
            val diff = asciiLower(c) - asciiLower(b.toInt() and BYTE_MASK)
            if (diff != 0) return diff
            p++
        }
        return if (buf.get(p).toInt() == 0) 0 else 1
    }

    private fun wordEnd(start: Int): Int {
        var p = start
        while (buf.get(p).toInt() != 0) p++
        return p
    }

    companion object {
        private const val BYTE_MASK = 0xFF
        private const val UINT_MASK = 0xFFFF_FFFFL
        private const val MAX_WORD_BYTES = 256
        private const val MAX_BYTES = Int.MAX_VALUE.toLong()

        /**
         * Maps [file] and finds where each entry starts. Throws [IOException] when the file is not a list of
         * entries with [tail] data bytes (a word longer than StarDict's limit, a cut-off last entry).
         */
        fun open(
            file: File,
            tail: Int,
        ): WordList {
            val buf =
                RandomAccessFile(file, "r").use { raf ->
                    if (raf.length() > MAX_BYTES) throw IOException("Word list too large")
                    raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                }
            val limit = buf.limit()
            var starts = IntArray(minOf(limit / (tail + 2) + 1, INITIAL_CAPACITY))
            var count = 0
            var p = 0
            while (p < limit) {
                var end = p
                while (end < limit && buf.get(end).toInt() != 0) end++
                if (end - p > MAX_WORD_BYTES || end + 1 + tail > limit) throw IOException("Damaged word list")
                if (count == starts.size) starts = starts.copyOf(count * 2)
                starts[count++] = p
                p = end + 1 + tail
            }
            return WordList(buf, starts.copyOf(count), tail)
        }

        private const val INITIAL_CAPACITY = 1 shl 16

        private fun asciiLower(c: Int) = if (c in 'A'.code..'Z'.code) c + ('a' - 'A') else c
    }
}
