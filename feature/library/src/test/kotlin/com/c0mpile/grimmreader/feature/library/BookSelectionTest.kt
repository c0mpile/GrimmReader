package com.c0mpile.grimmreader.feature.library

import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookFile
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSelectionTest {
    private fun book(
        id: Long,
        source: BookSource,
        localUri: String? = null,
    ) = Book(
        id = id,
        source = source,
        title = "ebook sample $id",
        files = listOf(BookFile(id = id, bookId = id, format = BookFormat.EPUB, localUri = localUri)),
    )

    private val online = book(1, BookSource.SERVER)
    private val downloaded = book(2, BookSource.SERVER, "/data/books/2.epub")
    private val inFolder = book(3, BookSource.LOCAL, "content://folder/3.epub")
    private val imported = book(4, BookSource.LOCAL, "/data/books/4.epub")

    @Test fun deleteOnlyTouchesFilesOnTheDeviceAndDownloadOnlyMissingServerFiles() {
        val summary = SelectionSummary(listOf(online, downloaded, inFolder, imported), canDownload = true)
        assertTrue(summary.hasServerBooks)
        assertEquals(listOf(1L), summary.toDownload.map { it.id })
        assertEquals(listOf(2L, 3L, 4L), summary.toDelete.map { it.id })
        assertEquals(1, summary.inFolders)
        assertEquals(1, summary.serverCopies)
        val text = deleteText(summary)
        assertTrue(text.contains("Nothing is deleted from the server."))
        assertTrue(text.contains("1 file in folders you added"))
        assertTrue(text.contains("The 3 files are deleted from this device."))
        assertTrue(text.contains("1 book without a file on this device is left alone."))
    }

    @Test fun localBooksOfferNoDownloadAndNeitherDoesAnAccountWithoutPermission() {
        assertFalse(SelectionSummary(listOf(inFolder, imported), canDownload = true).hasServerBooks)
        assertTrue(SelectionSummary(listOf(online), canDownload = false).toDownload.isEmpty())
    }

    @Test fun resetMentionsTheServerOnlyForServerBooksAndKeepsAnnotations() {
        val local = resetText(SelectionSummary(listOf(imported), canDownload = true))
        assertFalse(local.contains("server"))
        assertTrue(local.startsWith("This book starts from the beginning"))
        assertTrue(local.contains("Bookmarks, highlights and notes are kept."))
        assertTrue(resetText(SelectionSummary(listOf(online, imported), canDownload = true)).contains("also reset on the server"))
    }

    @Test fun selectAllTogglesAndUnlistedBooksAreDropped() {
        val selection = BookSelection(emptySet())
        selection.toggle(2)
        selection.toggleAll(listOf(1, 2, 3))
        assertEquals(setOf(1L, 2L, 3L), selection.ids)
        selection.toggleAll(listOf(1, 2, 3))
        assertFalse(selection.active)
        selection.toggleAll(listOf(1, 2, 3))
        selection.retain(setOf(2, 3))
        assertEquals(setOf(2L, 3L), selection.ids)
    }

    @Test fun downloadSkipsServerBooksAlreadyOnTheDeviceWithTheSameTitleAuthorAndSize() {
        fun sized(
            id: Long,
            source: BookSource,
            title: String,
            kb: Long,
            uri: String?,
        ) = book(id, source, uri).copy(
            title = title,
            authors = listOf("Ada"),
            files =
                listOf(
                    BookFile(
                        id,
                        id,
                        BookFormat.EPUB,
                        localUri = uri,
                        sizeBytes =
                            kb * 1024,
                    ),
                ),
        )
        val same = sized(10, BookSource.SERVER, "The Book", 812, null)
        val otherSize = sized(11, BookSource.SERVER, "The Book Two", 500, null)
        val fresh = sized(12, BookSource.SERVER, "Fresh", 100, null)
        val device =
            listOf(
                sized(20, BookSource.LOCAL, "the book", 812, "content://folder/20"),
                sized(21, BookSource.LOCAL, "The Book Two", 900, "/x"),
            )
        val summary = SelectionSummary(listOf(same, otherSize, fresh), canDownload = true, deviceBooks = device)
        assertEquals(listOf(10L), summary.alreadyHere.map { it.id })
        assertEquals(listOf(11L, 12L), summary.toDownload.map { it.id })
    }
}
