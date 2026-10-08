package com.c0mpile.grimmreader.reader.comic.panels

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PanelDetectorTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** A [w]×[h] page in [background] with filled boxes (pixel coordinates, end-exclusive). */
    private fun page(
        w: Int,
        h: Int,
        background: Int,
        vararg boxes: Pair<IntArray, Int>,
    ): Raster {
        val px = IntArray(w * h) { background }
        for ((b, color) in boxes) {
            for (y in b[1] until b[3]) for (x in b[0] until b[2]) px[y * w + x] = color
        }
        return Raster(w, h, px)
    }

    private fun box(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) = intArrayOf(x0, y0, x1, y1)

    private fun assertNear(
        expected: Rect,
        actual: Rect,
    ) {
        val tolerance = 0.01f
        assertTrue(
            "$expected vs $actual",
            abs(expected.left - actual.left) < tolerance &&
                abs(expected.top - actual.top) < tolerance &&
                abs(expected.right - actual.right) < tolerance &&
                abs(expected.bottom - actual.bottom) < tolerance,
        )
    }

    @Test fun gridWithWhiteGuttersReadsRowByRowLeftToRight() {
        val gray = 0xFF606060.toInt()
        val raster =
            page(
                1000,
                1500,
                white,
                box(50, 50, 480, 700) to gray,
                box(520, 50, 950, 700) to black,
                box(50, 750, 950, 1100) to gray,
                box(50, 1150, 300, 1450) to black,
                box(340, 1150, 950, 1450) to gray,
            )
        val panels = PanelDetector.detect(raster)
        assertEquals(5, panels.size)
        assertNear(Rect(0.05f, 50 / 1500f, 0.48f, 700 / 1500f), panels[0])
        assertNear(Rect(0.52f, 50 / 1500f, 0.95f, 700 / 1500f), panels[1])
        assertNear(Rect(0.05f, 0.5f, 0.95f, 1100 / 1500f), panels[2])
        assertNear(Rect(0.05f, 1150 / 1500f, 0.3f, 1450 / 1500f), panels[3])
        assertNear(Rect(0.34f, 1150 / 1500f, 0.95f, 1450 / 1500f), panels[4])
    }

    @Test fun blackGuttersInsideAWhiteMargin() {
        val art = 0xFFC08040.toInt()
        val raster =
            page(
                800,
                1200,
                white,
                box(40, 40, 760, 1160) to black,
                box(60, 60, 390, 1140) to art,
                box(410, 60, 740, 1140) to art,
            )
        val panels = PanelDetector.detect(raster)
        assertEquals(2, panels.size)
        assertTrue(panels[0].left < panels[1].left)
    }

    @Test fun aSplashPageFallsBackToTheWholePageWithoutItsMargin() {
        val raster = page(800, 1200, white, box(80, 120, 720, 1080) to black)
        assertEquals(listOf(Rect(0.1f, 0.1f, 0.9f, 0.9f)), PanelDetector.detect(raster))
    }

    @Test fun anEmptyPageIsTheFullPage() {
        assertEquals(listOf(PanelDetector.FULL_PAGE), PanelDetector.detect(page(400, 600, white)))
    }

    @Test fun rowBandsKeepPanelsOfOneRowTogether() {
        val ordered =
            readingOrder(
                listOf(
                    Box(500, 10, 900, 400),
                    Box(10, 450, 900, 800),
                    Box(10, 0, 480, 410),
                ),
            )
        assertEquals(listOf(Box(10, 0, 480, 410), Box(500, 10, 900, 400), Box(10, 450, 900, 800)), ordered)
    }

    @Test fun downscaleAveragesPixels() {
        val raster = Raster(4, 2, intArrayOf(white, black, white, black, white, black, white, black)).downscaled(2)
        assertEquals(2, raster.width)
        assertEquals(1, raster.height)
        assertEquals(0xFF7F7F7F.toInt(), raster.pixels[0])
    }
}
