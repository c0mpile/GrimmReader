package com.c0mpile.grimmreader.core.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Covers are kept at thumbnail size: an EPUB or CBZ cover is often a full page scan of several MB, and decoding
 * hundreds of those while a library grid scrolls makes it stutter. The longest side is capped at [MAX_SIDE] px,
 * enough for the largest cover the app shows (book detail) on a dense screen.
 */
object CoverThumbnail {
    const val MAX_SIDE = 720

    /** Covers at or below this size are kept as they are (already small, or saved by [shrink] before). */
    const val SMALL_BYTES = 256 * 1024

    private const val JPEG_QUALITY = 85

    /** [bytes] scaled down to fit [MAX_SIDE] as JPEG, or unchanged when small enough or not a decodable image. */
    fun shrink(bytes: ByteArray): ByteArray {
        if (bytes.size <= SMALL_BYTES) return bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return bytes
        var sample = 1
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return bytes
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        val scaled =
            if (scale >= 1f) {
                decoded
            } else {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).roundToInt().coerceAtLeast(1),
                    (decoded.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            }
        val out = ByteArrayOutputStream()
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return if (ok && out.size() in 1 until bytes.size) out.toByteArray() else bytes
    }
}
