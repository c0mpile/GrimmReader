package com.c0mpile.grimmreader.core.common

import java.security.MessageDigest

/**
 * KOReader's partial MD5 ("koreader hash"): MD5 over 1 KiB samples at offsets 256, 1 KiB, 4 KiB, …, 1 GiB,
 * stopping at the first offset at or past the end of the file. Grimmory computes the same value, so it
 * links a local file to a server book without uploading anything.
 */
object PartialMd5 {
    private const val SAMPLE = 1024
    private const val LAST_STEP = 10

    /** [readAt] fills the buffer from the given offset and returns the number of bytes read (≤ 0 at EOF). */
    fun compute(
        size: Long,
        readAt: (offset: Long, buffer: ByteArray) -> Int,
    ): String {
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(SAMPLE)
        val offsets = (-1..LAST_STEP).map { i -> if (i < 0) SAMPLE.toLong() shr 2 else SAMPLE.toLong() shl (2 * i) }
        for (offset in offsets.takeWhile { it < size }) {
            val n = readAt(offset, buffer)
            if (n <= 0) break
            md5.update(buffer, 0, n)
        }
        return md5.digest().joinToString("") { "%02x".format(it) }
    }

    fun compute(bytes: ByteArray): String =
        compute(bytes.size.toLong()) { offset, buffer ->
            val n = minOf(buffer.size.toLong(), bytes.size - offset).toInt()
            bytes.copyInto(buffer, 0, offset.toInt(), offset.toInt() + n)
            n
        }
}
