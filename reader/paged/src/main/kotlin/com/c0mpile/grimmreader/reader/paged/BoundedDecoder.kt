package com.c0mpile.grimmreader.reader.paged

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.unit.IntSize
import java.io.InputStream

/**
 * Decodes untrusted page images without exhausting memory: reads the bounds first, then decodes with an
 * `inSampleSize` that fits [maxSize] and keeps the result under [MAX_PIXELS].
 */
object BoundedDecoder {
    const val MAX_PIXELS = 16_000_000L

    fun decode(
        open: () -> InputStream,
        maxSize: IntSize,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        return open().use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Largest power of two that fits the target, plus whatever keeps the pixel count under the cap. */
    fun sampleSize(
        width: Int,
        height: Int,
        maxSize: IntSize,
    ): Int {
        var sample = 1
        val targetW = maxSize.width.coerceAtLeast(1)
        val targetH = maxSize.height.coerceAtLeast(1)
        while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) sample *= 2
        while (width.toLong() / sample * (height.toLong() / sample) > MAX_PIXELS) sample *= 2
        return sample
    }
}
