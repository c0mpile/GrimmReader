package com.c0mpile.grimmreader.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import com.c0mpile.grimmreader.core.database.entity.DownloadState
import com.c0mpile.grimmreader.core.database.entity.OutboxOpEntity
import com.c0mpile.grimmreader.core.database.entity.ReadingPositionEntity
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: GrimmDatabase

    @Before fun open() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GrimmDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After fun close() = db.close()

    private suspend fun serverBook(
        serverId: Long,
        bookServerId: Long,
        title: String,
    ) = db.bookDao().insert(
        BookEntity(source = BookSource.SERVER, serverRowId = serverId, serverBookId = bookServerId, title = title),
    )

    @Test fun refreshKeepsDownloadedBooksOnly() =
        runTest {
            val server = db.serverDao().upsert(ServerEntity(baseUrl = "https://grimmory.example.com"))
            val kept = serverBook(server, 1, "Sample one")
            val downloaded = serverBook(server, 2, "Sample two")
            serverBook(server, 3, "Sample three")
            db.bookFileDao().upsert(BookFileEntity(bookId = downloaded, format = BookFormat.EPUB, localUri = "books/2/1.epub"))

            db.bookDao().deleteServerBooksNotIn(server, keep = listOf(1))

            val titles =
                db
                    .bookDao()
                    .observeLibrary(null)
                    .first()
                    .map { it.book.title }
            assertEquals(listOf("Sample one", "Sample two"), titles)
            assertEquals(kept, db.bookDao().byServerId(server, 1)?.id)
        }

    @Test fun localBooksWithoutAFileAreSetAside() =
        runTest {
            val shown = db.bookDao().insert(BookEntity(source = BookSource.LOCAL, title = "Shown"))
            db.bookFileDao().upsert(
                BookFileEntity(bookId = shown, format = BookFormat.EPUB, localUri = "books/local/a.epub", partialMd5 = "a"),
            )
            val aside = db.bookDao().insert(BookEntity(source = BookSource.LOCAL, title = "Aside"))
            db.bookFileDao().upsert(BookFileEntity(bookId = aside, format = BookFormat.EPUB, localUri = null, partialMd5 = "b"))
            val server = db.serverDao().upsert(ServerEntity(baseUrl = "https://grimmory.example.com"))
            val remote = serverBook(server, 1, "Remote")
            db.bookFileDao().upsert(BookFileEntity(bookId = remote, format = BookFormat.EPUB, localUri = null, partialMd5 = "c"))

            val titles =
                db
                    .bookDao()
                    .observeLibrary(null)
                    .first()
                    .map { it.book.title }
            assertEquals(listOf("Remote", "Shown"), titles)
            assertEquals(listOf(aside), db.bookFileDao().setAside().map { it.bookId })
            assertEquals(1, db.bookFileDao().observeSetAsideCount().first())
        }

    @Test fun removingServerDetachesBooks() =
        runTest {
            val server = db.serverDao().upsert(ServerEntity(baseUrl = "https://grimmory.example.com"))
            val book = serverBook(server, 7, "Sample")
            db.bookDao().detachFromServer(server)
            db.serverDao().delete(server)
            val row = db.bookDao().get(book)!!.book
            assertEquals(BookSource.LOCAL, row.source)
            assertNull(row.serverRowId)
            assertEquals(7L, row.serverBookId)
        }

    @Test fun markSyncedOnlyClearsTheVersionThatWasSent() =
        runTest {
            val book = db.bookDao().insert(BookEntity(source = BookSource.LOCAL, title = "Local"))
            db.readingPositionDao().upsert(ReadingPositionEntity(book, "{}", 10f, localUpdatedAt = 1, dirty = true))
            db.readingPositionDao().upsert(ReadingPositionEntity(book, "{}", 12f, localUpdatedAt = 2, dirty = true))
            db.readingPositionDao().markSynced(book, serverSeenAt = 100, ifUpdatedAt = 1)
            assertTrue(db.readingPositionDao().get(book)!!.dirty)
            db.readingPositionDao().markSynced(book, serverSeenAt = 100, ifUpdatedAt = 2)
            assertFalse(db.readingPositionDao().get(book)!!.dirty)
        }

    @Test fun outboxReplaceCoalescesProgress() =
        runTest {
            db.outboxDao().replace(OutboxOpEntity(kind = "progress", entityId = 5, payload = "a", createdAt = 1))
            db.outboxDao().replace(OutboxOpEntity(kind = "progress", entityId = 5, payload = "b", createdAt = 2))
            db.outboxDao().replace(OutboxOpEntity(kind = "progress", entityId = 6, payload = "c", createdAt = 3))
            assertEquals(listOf("b", "c"), db.outboxDao().due(now = 10).map { it.payload })
        }

    @Test fun downloadQueueJoinsTheBookAndUpdatesNeverRecreateACancelledRow() =
        runTest {
            val book = db.bookDao().insert(BookEntity(source = BookSource.SERVER, serverBookId = 7, title = "ebook sample 1"))
            val file = db.bookFileDao().upsert(BookFileEntity(bookId = book, format = BookFormat.CBZ))
            db.downloadDao().insert(DownloadEntity(bookFileId = file, updatedAt = 1))
            db.downloadDao().update(file, DownloadState.RUNNING, done = 10, total = 40, error = null, now = 2)
            db.downloadDao().update(file, DownloadState.RUNNING, done = 20, total = null, error = null, now = 3)

            val row =
                db
                    .downloadDao()
                    .observeQueue()
                    .first()
                    .single()
            assertEquals("ebook sample 1", row.title)
            assertEquals(book, row.bookId)
            assertEquals(BookFormat.CBZ, row.format)
            assertEquals(7L, row.serverBookId)
            assertEquals(20L, row.download.bytesDone)
            assertEquals(40L, row.download.bytesTotal)

            db.downloadDao().delete(file)
            db.downloadDao().update(file, DownloadState.RUNNING, done = 30, total = 40, error = null, now = 4)
            assertTrue(
                db
                    .downloadDao()
                    .observeQueue()
                    .first()
                    .isEmpty(),
            )
        }
}
