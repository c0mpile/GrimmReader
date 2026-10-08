package com.c0mpile.grimmreader.reader.paged

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.c0mpile.grimmreader.core.model.ReadingDirection
import java.io.Closeable

/** Pages of a comic or PDF. Indices are 0-based; the API's page numbers are 1-based. */
interface PageSource : Closeable {
    val pageCount: Int
    val readingDirection: ReadingDirection

    /** Decodes page [index] scaled to fit within [maxSize] (never larger than the source). */
    suspend fun decode(
        index: Int,
        maxSize: IntSize,
    ): ImageBitmap?
}

/**
 * Panels of a page for guided view: rectangles in page coordinates (0..1) in reading order. When nothing
 * reliable is found the list holds one rectangle for the whole page.
 */
fun interface PanelProvider {
    suspend fun panels(index: Int): List<Rect>
}
