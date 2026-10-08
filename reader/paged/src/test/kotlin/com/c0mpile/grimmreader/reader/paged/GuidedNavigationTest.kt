package com.c0mpile.grimmreader.reader.paged

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuidedNavigationTest {
    private val left = Rect(0f, 0f, 0.5f, 1f)
    private val right = Rect(0.5f, 0f, 1f, 1f)

    @Test fun fullPageComesFirstOnlyWhenThereAreSeveralPanels() {
        assertEquals(listOf(FULL_PAGE, left, right), guidedStops(listOf(left, right), fullPageFirst = true))
        assertEquals(listOf(left, right), guidedStops(listOf(left, right), fullPageFirst = false))
        assertEquals(listOf(left), guidedStops(listOf(left), fullPageFirst = true))
        assertEquals(listOf(FULL_PAGE), guidedStops(emptyList(), fullPageFirst = false))
    }

    @Test fun stepsThroughStopsAndAcrossPages() =
        runBlocking {
            val counts = mapOf(0 to 3, 1 to 1, 2 to 2)
            val count: suspend (Int) -> Int = { counts.getValue(it) }
            assertEquals(GuidedStop(0, 1), nextStop(GuidedStop(0, 0), true, 3, count))
            assertEquals(GuidedStop(1, 0), nextStop(GuidedStop(0, 2), true, 3, count))
            assertEquals(GuidedStop(2, 0), nextStop(GuidedStop(1, 0), true, 3, count))
            assertNull(nextStop(GuidedStop(2, 1), true, 3, count))
            // Back to the previous page lands on its last stop.
            assertEquals(GuidedStop(1, 0), nextStop(GuidedStop(2, 0), false, 3, count))
            assertEquals(GuidedStop(0, 2), nextStop(GuidedStop(1, 0), false, 3, count))
            assertEquals(GuidedStop(0, 1), nextStop(GuidedStop(0, 2), false, 3, count))
            assertNull(nextStop(GuidedStop(0, 0), false, 3, count))
        }

    @Test fun theFullPageIsFittedAndCentred() {
        val view = frame(FULL_PAGE, Size(1000f, 1500f), Size(1000f, 1000f))
        assertEquals(1000f / 1500f, view.scale, 1e-4f)
        assertEquals(Offset((1000f - 1000f * view.scale) / 2, 0f), view.offset)
    }

    @Test fun aPanelIsZoomedAndCentredButThePageEdgeStaysPut() {
        // A full-height panel with padding would need less than the fitted page: the page stays fitted.
        val view = frame(left, Size(1000f, 1000f), Size(1000f, 1000f))
        assertEquals(1f, view.scale, 1e-4f)
        assertEquals(0f, view.offset.x, 1e-3f)
        // A panel at the left edge: zoomed, but the page's left edge stays at the viewport's left edge.
        val edge = frame(Rect(0f, 0.4f, 0.25f, 0.6f), Size(1000f, 1000f), Size(1000f, 1000f))
        assertEquals(0f, edge.offset.x, 1e-3f)
        val middle = frame(Rect(0.25f, 0.4f, 0.5f, 0.6f), Size(1000f, 1000f), Size(1000f, 1000f))
        assertEquals(3.7736f, middle.scale, 1e-3f)
        assertEquals(500f - 375f * middle.scale, middle.offset.x, 1e-2f)
        assertEquals(500f - 500f * middle.scale, middle.offset.y, 1e-2f)
    }

    @Test fun zoomIsCappedForTinyPanels() {
        val view = frame(Rect(0.5f, 0.5f, 0.52f, 0.52f), Size(1000f, 1000f), Size(1000f, 1000f))
        assertEquals(4f, view.scale, 1e-4f)
    }
}
