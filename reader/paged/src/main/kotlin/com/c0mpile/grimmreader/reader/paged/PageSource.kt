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
 * Turns a page into a sequence of "stops" (rectangles in page coordinates, 0..1) for the camera to frame.
 * Full-page reading has one stop per page; guided view (M4) has one per panel and falls back to the full page.
 */
fun interface NavigationModel {
    fun stops(index: Int): List<Rect>
}

val FullPageNavigation = NavigationModel { listOf(Rect(0f, 0f, 1f, 1f)) }
