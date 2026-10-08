package com.c0mpile.grimmreader.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.c0mpile.grimmreader.core.data.library.FolderDuplicates
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.ReadingPositionEntity
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.files.DocumentStore
import com.c0mpile.grimmreader.core.files.LocalFileStore
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FolderDuplicatesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, GrimmDatabase::class.java).allowMainThreadQueries().build()
    private val books = "content://com.android.externalstorage.documents/tree/primary%3ABooks"
    private val downloads = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FGrimm"
    private val inFolder = "$books/document/primary%3ABooks%2Fa.epub"

    @After fun close() = db.close()

    private suspend fun book(
        source: BookSource,
        localUri: String,
        md5: String = "same",
        readAt: Long? = null,
    ): Long {
        val id = db.bookDao().insert(BookEntity(source = source, title = "Sample A", serverRowId = null))
        db.bookFileDao().upsert(BookFileEntity(bookId = id, format = BookFormat.EPUB, localUri = localUri, sizeBytes = 1, partialMd5 = md5))
        readAt?.let { db.readingPositionDao().upsert(ReadingPositionEntity(id, "{}", 10f, it)) }
        return id
    }

    private fun appFile(name: String) = File(tmp.root, name).apply { writeText("x") }

    private suspend fun merger(downloadFolder: String? = null): FolderDuplicates {
        val prefs = AppPreferences(PreferenceDataStoreFactory.create { File(tmp.root, "p.preferences_pb") })
        prefs.setBookFolders(setOf(books))
        prefs.setDownloadFolder(downloadFolder)
        return FolderDuplicates(
            prefs,
            db.bookDao(),
            db.bookFileDao(),
            db.readingPositionDao(),
            LocalFileStore(context, DocumentStore(context)),
        )
    }

    private suspend fun uriOf(bookId: Long) =
        db
            .bookFileDao()
            .forBook(bookId)
            .single()
            .localUri

    @Test fun importedCopyReadLastKeepsItsEntryAndReadsTheFolderFile() =
        runTest {
            val imported = appFile("imported.epub")
            val importedId = book(BookSource.LOCAL, imported.path, readAt = 200)
            val folderId = book(BookSource.LOCAL, inFolder, readAt = 100)

            assertEquals(1, merger().merge(listOf(books)))

            assertEquals(inFolder, uriOf(importedId))
            assertNull(db.bookDao().get(folderId))
            assertFalse(imported.exists())
        }

    @Test fun folderEntryReadLastStaysAndTheImportedCopyGoes() =
        runTest {
            val imported = appFile("imported.epub")
            val importedId = book(BookSource.LOCAL, imported.path, readAt = 100)
            val folderId = book(BookSource.LOCAL, inFolder, readAt = 200)

            assertEquals(1, merger().merge(listOf(books)))

            assertNull(db.bookDao().get(importedId))
            assertEquals(inFolder, uriOf(folderId))
            assertFalse(imported.exists())
        }

    @Test fun serverBookAlwaysStaysAndUsesTheFolderFile() =
        runTest {
            val download = appFile("download.book")
            val serverId = book(BookSource.SERVER, download.path)
            val folderId = book(BookSource.LOCAL, inFolder, readAt = 999)

            assertEquals(1, merger().merge(listOf(books)))

            assertEquals(inFolder, uriOf(serverId))
            assertNull(db.bookDao().get(folderId))
            assertFalse(download.exists())
        }

    @Test fun downloadSavedToTheDownloadFolderCountsAsTheAppsCopy() =
        runTest {
            val serverId = book(BookSource.SERVER, "$downloads/document/primary%3ADownload%2FGrimm%2Fa.epub")
            book(BookSource.LOCAL, inFolder)

            assertEquals(1, merger(downloadFolder = downloads).merge(listOf(books)))
            assertEquals(inFolder, uriOf(serverId))
        }

    @Test fun userFilesAndOtherContentAreNeverMerged() =
        runTest {
            val other = "$books/document/primary%3ABooks%2Fcopy-of-a.epub"
            val elsewhere = "content://com.android.externalstorage.documents/tree/primary%3AOther/document/primary%3AOther%2Fa.epub"
            val a = book(BookSource.LOCAL, inFolder)
            val b = book(BookSource.LOCAL, other)
            val c = book(BookSource.LOCAL, elsewhere)
            val unrelated = book(BookSource.LOCAL, appFile("b.epub").path, md5 = "different")

            assertEquals(0, merger().merge(listOf(books)))
            listOf(a, b, c, unrelated).forEach {
                assertEquals(
                    it,
                    db
                        .bookDao()
                        .get(it)
                        ?.book
                        ?.id,
                )
            }
        }
}
