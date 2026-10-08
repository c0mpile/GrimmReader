package com.c0mpile.grimmreader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test fun pagePercentMatchesWebRounding() {
        // Web: round(page / N * 1000) / 10
        assertEquals(11.3f, Locator.Page(42, 373).percent)
        assertEquals(50.1f, Locator.Page(187, 373).percent)
        assertEquals(100f, Locator.Page(373, 373).percent)
    }

    @Test fun formatFromExtension() {
        assertEquals(BookFormat.MOBI, BookFormat.fromExtension("AZW3"))
        assertEquals(BookFormat.CBZ, BookFormat.fromExtension("cbz"))
        assertEquals(null, BookFormat.fromExtension("cbr"))
        assertEquals(null, BookFormat.fromExtension("cb7"))
        assertEquals(null, BookFormat.fromExtension("m4b"))
    }

    @Test fun adminImpliesEveryPermission() {
        assertTrue(Permissions(setOf(Permissions.ADMIN)).has(Permissions.CAN_DOWNLOAD))
        assertFalse(Permissions(setOf(Permissions.CAN_DOWNLOAD)).has(Permissions.CAN_UPLOAD))
    }
}
