package com.c0mpile.grimmreader.reader.pdf

import androidx.compose.ui.unit.IntSize
import com.c0mpile.grimmreader.reader.paged.BoundedDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRenderSizeTest {
    @Test fun scalesUpToFitTheTarget() {
        // A4 in points, on a 1080x2400 screen with 2x zoom headroom: limited by width.
        assertEquals(IntSize(2160, 3057), PdfPageSource.renderSize(595, 842, IntSize(2160, 4800)))
    }

    @Test fun landscapePagesAreLimitedByHeight() {
        assertEquals(IntSize(1415, 1000), PdfPageSource.renderSize(842, 595, IntSize(4000, 1000)))
    }

    @Test fun hugePagesAreCappedByPixels() {
        val size = PdfPageSource.renderSize(14_400, 14_400, IntSize(100_000, 100_000))
        assertTrue(size.width.toLong() * size.height <= BoundedDecoder.MAX_PIXELS + 2 * size.width)
    }

    @Test fun degenerateSizesStayPositive() {
        assertEquals(IntSize(1, 1), PdfPageSource.renderSize(0, 0, IntSize(0, 0)))
    }
}
