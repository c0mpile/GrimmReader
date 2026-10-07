package com.c0mpile.grimmreader.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.c0mpile.grimmreader.api.grimmory.GrimmoryApi
import com.c0mpile.grimmreader.api.grimmory.ShelfDto
import com.c0mpile.grimmreader.core.data.server.NetworkPolicyImpl
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.data.shelf.ShelfMirror
import com.c0mpile.grimmreader.core.data.shelf.ShelfRepository
import com.c0mpile.grimmreader.core.data.shelf.ShelfSnapshot
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.datastore.SecretCipher
import com.c0mpile.grimmreader.core.datastore.SecretStore
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.network.GuardedHttpClient
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShelfRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db =
        Room
            .inMemoryDatabaseBuilder(context, GrimmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer()
    private val policy = NetworkPolicyImpl()

    private val repo: ShelfRepository by lazy {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val secrets =
            SecretStore(
                PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") },
                object : SecretCipher {
                    override fun encrypt(plain: ByteArray) = plain

                    override fun decrypt(sealed: ByteArray) = sealed
                },
            )
        val session = ServerSession(db.serverDao(), secrets, policy, Lazy { GuardedHttpClient.create(policy, "test") }, scope)
        ShelfRepository(context, db, db.shelfDao(), db.bookDao(), db.outboxDao(), session, Dispatchers.IO)
    }

    private val api: GrimmoryApi by lazy {
        server.start()
        policy.setConfigured(mapOf(server.hostName to (true to null)))
        GrimmoryApi.create(server.url("/"), GuardedHttpClient.create(policy, "test"))
    }

    @After fun close() {
        scope.cancel()
        server.close()
        db.close()
    }

    private val serverRow by lazy { runBlocking { db.serverDao().upsert(ServerEntity(baseUrl = "https://grimmory.example.com")) } }

    private fun book(
        source: BookSource,
        serverBookId: Long?,
    ): Long =
        runBlocking {
            val id =
                db.bookDao().insert(
                    BookEntity(
                        source = source,
                        serverRowId = serverRow.takeIf { source == BookSource.SERVER },
                        serverBookId = serverBookId,
                        title = "Sample",
                        addedAt = 0,
                    ),
                )
            db.bookFileDao().upsert(BookFileEntity(bookId = id, format = BookFormat.EPUB))
            id
        }

    private fun members(bookId: Long) =
        runBlocking {
            db
                .bookDao()
                .get(bookId)!!
                .shelves
                .map { it.shelfId }
                .toSet()
        }

    private fun ops() = runBlocking { db.outboxDao().due(Long.MAX_VALUE) }

    @Test fun shelvingIsLocalFirstAndARefreshKeepsPendingChanges() =
        runBlocking {
            val id = book(BookSource.SERVER, 101)
            assertTrue(repo.setShelved(id, 5, on = true))
            assertEquals(setOf(5L), members(id))
            assertEquals(ShelfRepository.KIND_ASSIGN, ops().single().kind)
            // A refresh that does not know about the change yet keeps it.
            db.withTransaction {
                ShelfMirror(db.shelfDao(), db.bookDao(), db.outboxDao()).apply(
                    serverRow,
                    ShelfSnapshot(listOf(ShelfDto(5, "Favorites", "heart")), emptyList(), mapOf(5L to emptyList()), emptyMap()),
                )
            }
            assertEquals(setOf(5L), members(id))
        }

    @Test fun localBooksCannotBeShelved() =
        runBlocking {
            assertFalse(repo.setShelved(book(BookSource.LOCAL, null), 5, on = true))
            assertTrue(ops().isEmpty())
        }

    @Test fun pushSendsTheWebRequestAndUndoesARejectedAdd() =
        runBlocking {
            val id = book(BookSource.SERVER, 101)
            repo.setShelved(id, 6, on = true)
            server.enqueue(MockResponse.Builder().body("[]").build())
            assertTrue(repo.push(ops().single(), api))
            assertEquals("""{"bookIds":[101],"shelvesToAssign":[6],"shelvesToUnassign":[]}""", server.takeRequest().body?.utf8())
            db.outboxDao().delete(ops().single().id)

            repo.setShelved(id, 7, on = true)
            server.enqueue(MockResponse.Builder().code(403).build())
            assertTrue(repo.push(ops().single(), api))
            assertEquals(setOf(6L), members(id))
        }

    @Test fun serverErrorsAreRetried() =
        runBlocking {
            val id = book(BookSource.SERVER, 101)
            repo.setShelved(id, 6, on = false)
            server.enqueue(MockResponse.Builder().code(502).build())
            assertEquals(false, repo.push(ops().single(), api))
        }
}
