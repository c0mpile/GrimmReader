package com.c0mpile.grimmreader.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryScopeTest {
    @Test fun roundTrips() {
        listOf(LibraryScope.All, LibraryScope.Unsorted, LibraryScope.Local(7), LibraryScope.Server(42)).forEach {
            assertEquals(it, LibraryScope.decode(it.encode()))
        }
    }

    @Test fun unknownValuesFallBackToAll() {
        listOf(null, "", "library:x", "local:x", "device", "something").forEach { assertEquals(LibraryScope.All, LibraryScope.decode(it)) }
    }
}
