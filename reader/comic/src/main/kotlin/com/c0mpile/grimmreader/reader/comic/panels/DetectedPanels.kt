package com.c0mpile.grimmreader.reader.comic.panels

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.IntSize
import com.c0mpile.grimmreader.reader.paged.PageSource
import com.c0mpile.grimmreader.reader.paged.PanelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Panels found on the pages of [source] by [PanelDetector], one page at a time, kept for as long as the book
 * is open. Each page is decoded again at a small size for this (the detector works at most at 1280 px). A
 * page that cannot be decoded yet (a streamed page still loading or failed) is the full page and is tried
 * again next time.
 */
class DetectedPanels(
    private val source: PageSource,
) : PanelProvider {
    private val lock = Mutex()
    private val found = ConcurrentHashMap<Int, List<Rect>>()

    override suspend fun panels(index: Int): List<Rect> =
        found[index] ?: lock.withLock {
            found[index] ?: detect(index)?.also { found[index] = it } ?: listOf(PanelDetector.FULL_PAGE)
        }

    private suspend fun detect(index: Int): List<Rect>? {
        // Decoded so that the shorter side is at least DETECT_SIZE (power-of-two sampling); the detector
        // scales it down further.
        val image = source.decode(index, IntSize(DETECT_SIZE, DETECT_SIZE)) ?: return null
        return withContext(Dispatchers.Default) {
            val bitmap = image.asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            PanelDetector.detect(Raster(bitmap.width, bitmap.height, pixels))
        }
    }

    private companion object {
        const val DETECT_SIZE = 640
    }
}
