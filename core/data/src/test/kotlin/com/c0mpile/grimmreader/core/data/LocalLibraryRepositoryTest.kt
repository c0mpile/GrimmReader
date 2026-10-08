package com.c0mpile.grimmreader.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.c0mpile.grimmreader.core.data.library.LocalLibraryRepository
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.LibraryEntity
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.files.DocumentStore
import com.c0mpile.grimmreader.core.model.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalLibraryRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, GrimmDatabase::class.java).allowMainThreadQueries().build()

    @After fun close() = db.close()

    private fun repo(prefs: AppPreferences = AppPreferences(PreferenceDataStoreFactory.create { File(tmp.root, "p.preferences_pb") })) =
        LocalLibraryRepository(db.localLibraryDao(), db.bookDao(), prefs, DocumentStore(context), Dispatchers.IO)

    private fun server(vararg libraries: Pair<Long, String>) = libraries.mapIndexed { i, (id, name) -> LibraryEntity(1, id, name, "", i) }

    private suspend fun serverRow() = db.serverDao().upsert(ServerEntity(baseUrl = "https://grimmory.example.com"))

    @Test fun everyServerLibraryGetsALocalOneAndBooksFollowIt() =
        runTest {
            serverRow()
            val repo = repo()
            val book =
                db.bookDao().insert(
                    BookEntity(source = BookSource.SERVER, serverRowId = 1, serverBookId = 9, serverLibraryId = 2, title = "Sample A"),
                )

            val ids = repo.syncWithServer(1, server(1L to "Books", 2L to "Comics"))

            assertEquals(listOf("Books", "Comics"), repo.libraries.first().map { it.name })
            assertEquals(
                ids[2L],
                db
                    .bookDao()
                    .get(book)
                    ?.book
                    ?.localLibraryId,
            )
            // A second sync adds nothing and follows a rename on the server.
            repo.syncWithServer(1, server(1L to "Ebooks", 2L to "Comics"))
            assertEquals(listOf("Ebooks", "Comics"), repo.libraries.first().map { it.name })
        }

    @Test fun anUnlinkedLibraryOfTheSameNameIsLinkedInsteadOfDuplicated() =
        runTest {
            serverRow()
            val repo = repo()
            val own = repo.create("comics")

            val ids = repo.syncWithServer(1, server(2L to "Comics"))

            assertEquals(own, ids[2L])
            assertEquals(listOf("Comics" to 2L), repo.libraries.first().map { it.name to it.serverLibraryId })
        }

    @Test fun aLibraryTheServerDropsStaysUnlinked() =
        runTest {
            serverRow()
            val repo = repo()
            repo.syncWithServer(1, server(1L to "Books", 2L to "Comics"))

            repo.syncWithServer(1, server(1L to "Books"))
            assertEquals(listOf(1L, null), repo.libraries.first().map { it.serverLibraryId })

            repo.unlinkServer()
            assertEquals(listOf(null, null), repo.libraries.first().map { it.serverLibraryId })
        }

    @Test fun onlyLocalBooksAreMovedAndLinkedLibrariesKeepTheServersName() =
        runTest {
            serverRow()
            val repo = repo()
            val ids = repo.syncWithServer(1, server(1L to "Books"))
            val own = repo.create("Magazines")
            val local = db.bookDao().insert(BookEntity(source = BookSource.LOCAL, title = "Sample B"))
            val remote = db.bookDao().insert(BookEntity(source = BookSource.SERVER, serverRowId = 1, serverBookId = 3, title = "Sample C"))

            repo.assign(local, own)
            repo.assign(remote, own)
            repo.rename(ids.getValue(1L), "Mine")
            repo.rename(own, "Magazine rack")

            assertEquals(
                own,
                db
                    .bookDao()
                    .get(local)
                    ?.book
                    ?.localLibraryId,
            )
            assertNull(
                db
                    .bookDao()
                    .get(remote)
                    ?.book
                    ?.localLibraryId,
            )
            assertEquals(listOf("Books", "Magazine rack"), repo.libraries.first().map { it.name })
        }

    @Test fun earlierBookFoldersBecomeLibraries() =
        runTest {
            val tree = "content://com.android.externalstorage.documents/tree/primary%3AComics"
            val prefs = AppPreferences(PreferenceDataStoreFactory.create { File(tmp.root, "q.preferences_pb") })
            prefs.setBookFolders(setOf(tree))

            val libraries = repo(prefs).libraries.first()

            assertEquals(listOf(tree), libraries.map { it.folderUri })
            assertEquals(emptyList<String>(), prefs.bookFolders.first())
        }
}
