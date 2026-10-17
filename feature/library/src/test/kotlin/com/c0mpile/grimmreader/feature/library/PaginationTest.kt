package com.c0mpile.grimmreader.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PaginationTest {
    private val items = (1..250).toList()

    @Test fun firstPageHoldsTheFirstItems() {
        val slice = items.pageOf(0, 100)
        assertEquals((1..100).toList(), slice.items)
        assertEquals(3, slice.pageCount)
        assertEquals(250, slice.total)
    }

    @Test fun lastPageHoldsTheRemainder() {
        val slice = items.pageOf(2, 100)
        assertEquals((201..250).toList(), slice.items)
        assertEquals(2, slice.page)
    }

    @Test fun aPagePastTheEndShowsTheLastOne() {
        val slice = items.pageOf(40, 100)
        assertEquals(2, slice.page)
        assertEquals((201..250).toList(), slice.items)
    }

    @Test fun anExactMultipleHasNoEmptyTrailingPage() {
        assertEquals(2, (1..200).toList().pageOf(0, 100).pageCount)
    }

    @Test fun anEmptyListIsOneEmptyPage() {
        val slice = emptyList<Int>().pageOf(3, 50)
        assertEquals(0, slice.page)
        assertEquals(1, slice.pageCount)
        assertEquals(emptyList<Int>(), slice.items)
    }
}
