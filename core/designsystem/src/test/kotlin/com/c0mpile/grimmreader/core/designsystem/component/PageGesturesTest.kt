package com.c0mpile.grimmreader.core.designsystem.component

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageGesturesTest {
    @Test fun zonesFollowTheEdgeWidth() {
        val turns = PageTurns(edge = 0.2f)
        assertEquals(PageZone.LEFT, turns.zone(199f, 1000f))
        assertEquals(PageZone.MIDDLE, turns.zone(200f, 1000f))
        assertEquals(PageZone.MIDDLE, turns.zone(800f, 1000f))
        assertEquals(PageZone.RIGHT, turns.zone(801f, 1000f))
    }

    @Test fun swipesMustBeLongAndMostlyHorizontal() {
        assertTrue(isSwipe(Offset(-150f, 20f), 100f))
        assertTrue(isSwipe(Offset(150f, -70f), 100f))
        assertFalse(isSwipe(Offset(-90f, 0f), 100f))
        assertFalse(isSwipe(Offset(150f, 80f), 100f))
    }
}
