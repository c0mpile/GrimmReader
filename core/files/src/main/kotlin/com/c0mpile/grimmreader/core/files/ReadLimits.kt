package com.c0mpile.grimmreader.core.files

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Upper bounds for data read from untrusted archives (never trust an entry's declared size). */
object ReadLimits {
    const val XML_BYTES = 1L shl 20
    const val IMAGE_BYTES = 16L shl 20
    const val SNIFF_BYTES = 256L
}

/**
 * Reads at most [max] bytes. Returns null when the stream is longer, so a zip bomb or an oversized entry is
 * skipped instead of exhausting memory.
 */
fun InputStream.readBounded(max: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
    }
}

private const val BUFFER = 8192
