package com.c0mpile.grimmreader.core.data.download

import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFileNameTest {
    private fun book(
        title: String,
        vararg authors: String,
    ) = BookEntity(source = BookSource.SERVER, title = title, authors = authors.joinToString(BookEntity.AUTHOR_SEPARATOR))

    @Test fun titleAndFirstAuthor() {
        assertEquals("Sample A - Author One.epub", DownloadWorker.fileName(book("Sample A", "Author One", "Author Two"), BookFormat.EPUB))
        assertEquals("Sample Comic.cbz", DownloadWorker.fileName(book("Sample Comic"), BookFormat.CBZ))
    }

    @Test fun charactersFileSystemsRejectAreReplaced() {
        assertEquals("A_B_ C_ _D_ - X.pdf", DownloadWorker.fileName(book("A/B: C? *D|", "X"), BookFormat.PDF))
        assertEquals("book.epub", DownloadWorker.fileName(book("..."), BookFormat.EPUB))
    }

    @Test fun longNamesAreCut() {
        val name = DownloadWorker.fileName(book("x".repeat(500)), BookFormat.EPUB)
        assertEquals(120 + ".epub".length, name.length)
    }
}
