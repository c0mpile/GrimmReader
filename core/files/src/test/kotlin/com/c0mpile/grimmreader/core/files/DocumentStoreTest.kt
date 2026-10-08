package com.c0mpile.grimmreader.core.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentStoreTest {
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ABooks"

    @Test fun onlyBookFilesArePickedUp() {
        listOf("a.epub", "B.EPUB", "c.azw3", "d.fb2", "e.pdf", "f.cbz", "g.mobi").forEach { assertTrue(it, DocumentStore.isBookName(it)) }
        // Plain zips are rarely comics; 7z/RAR comics and other files are not books here.
        listOf("h.zip", "i.cbr", "j.cb7", "k.txt", "noext", "l.epub.part").forEach { assertFalse(it, DocumentStore.isBookName(it)) }
    }

    @Test fun documentsBelongToTheTreeTheyWereReachedThrough() {
        assertTrue(DocumentStore.inTree("$tree/document/primary%3ABooks%2Fa.epub", tree))
        assertFalse(DocumentStore.inTree("$tree%2FSub/document/primary%3ABooks%2FSub%2Fa.epub", tree))
        assertFalse(DocumentStore.inTree("/data/user/0/app/files/books/local/a.epub", tree))
    }

    @Test fun contentUrisAreDocumentsAndPathsAreNot() {
        assertTrue(DocumentStore.isDocument("$tree/document/primary%3ABooks%2Fa.epub"))
        assertFalse(DocumentStore.isDocument("/data/user/0/app/files/books/local/a.epub"))
    }
}
