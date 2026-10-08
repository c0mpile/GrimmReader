package com.c0mpile.grimmreader.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.c0mpile.grimmreader.api.grimmory.GrimmoryApi
import com.c0mpile.grimmreader.core.data.progress.ProgressRepository
import com.c0mpile.grimmreader.core.data.server.NetworkPolicyImpl
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.BookmarkEntity
import com.c0mpile.grimmreader.core.database.entity.OutboxOpEntity
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.datastore.SecretCipher
import com.c0mpile.grimmreader.core.datastore.SecretStore
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.Locator
import com.c0mpile.grimmreader.core.model.ReadStatus
import com.c0mpile.grimmreader.core.network.GuardedHttpClient
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProgressResetTest {
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

    private val repo: ProgressRepository by lazy {
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
        ProgressRepository(context, db, db.readingPositionDao(), db.outboxDao(), db.bookDao(), session, Dispatchers.IO)
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

    private fun book(serverBookId: Long?): Long =
        runBlocking {
            val source = if (serverBookId != null) BookSource.SERVER else BookSource.LOCAL
            val id =
                db.bookDao().insert(
                    BookEntity(
                        source = source,
                        serverRowId = serverRow.takeIf { serverBookId != null },
                        serverBookId = serverBookId,
                        title = "ebook sample",
                        readStatus = ReadStatus.READING,
                        progressPercent = 40f,
                        lastReadAt = 1_000,
                    ),
                )
            db.bookFileDao().upsert(BookFileEntity(bookId = id, format = BookFormat.CBZ, serverFileId = serverBookId?.plus(1000)))
            id
        }

    private fun ops() = runBlocking { db.outboxDao().due(Long.MAX_VALUE) }

    private fun resetOp(serverBookId: Long) =
        OutboxOpEntity(
            id = serverBookId,
            kind = ProgressRepository.KIND_RESET,
            entityId = serverBookId,
            payload = "$serverBookId",
            createdAt = 0,
        )

    private fun ok() =
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("[]")
                .build(),
        )

    private fun fail(code: Int) = server.enqueue(MockResponse.Builder().code(code).build())

    private fun sentIds(): List<Long> =
        Json
            .parseToJsonElement(server.takeRequest().body!!.utf8())
            .jsonArray
            .map { it.jsonPrimitive.long }

    @Test fun resetClearsProgressKeepsBookmarksAndQueuesOnlyServerBooks() =
        runBlocking {
            val remote = book(101)
            val local = book(null)
            repo.save(remote, Locator.Page(4, 10))
            repo.save(local, Locator.Page(4, 10))
            db.bookmarkDao().insert(BookmarkEntity(bookId = remote, page = 2, title = "Page 2", createdAt = 0))
            assertEquals(listOf(ProgressRepository.KIND_PROGRESS), ops().map { it.kind })

            repo.reset(listOf(remote, local))

            for (id in listOf(remote, local)) {
                val row = db.bookDao().get(id)!!
                assertNull(row.position)
                assertNull(row.book.progressPercent)
                assertNull(row.book.lastReadAt)
                assertEquals(ReadStatus.UNREAD, row.book.readStatus)
            }
            assertEquals(1, db.bookmarkDao().forBook(remote).size)
            // The pending upload is replaced by the reset; the local book never queues anything.
            val op = ops().single()
            assertEquals(ProgressRepository.KIND_RESET to remote, op.kind to op.entityId)
            assertEquals("101", op.payload)
        }

    @Test fun aReadAfterTheResetIsSentAfterIt() =
        runBlocking {
            val id = book(101)
            repo.reset(listOf(id))
            repo.save(id, Locator.Page(2, 10))
            assertEquals(listOf(ProgressRepository.KIND_RESET, ProgressRepository.KIND_PROGRESS), ops().map { it.kind })
        }

    @Test fun bulkResetSendsOneRequest() =
        runBlocking {
            ok()
            val done = ProgressRepository.pushResets(listOf(resetOp(1), resetOp(2), resetOp(3)), api, bulk = true)
            assertEquals(3, done.size)
            val request = server.takeRequest()
            assertEquals("/api/v1/books/reset-progress", request.url.encodedPath)
            assertEquals("BOOKLORE", request.url.queryParameter("type"))
            assertEquals("POST", request.method)
            assertEquals(listOf(1L, 2L, 3L), Json.parseToJsonElement(request.body!!.utf8()).jsonArray.map { it.jsonPrimitive.long })
            assertEquals(1, server.requestCount)
        }

    @Test fun withoutTheBulkPermissionEachBookIsSentAlone() =
        runBlocking {
            repeat(2) { ok() }
            val done = ProgressRepository.pushResets(listOf(resetOp(1), resetOp(2)), api, bulk = false)
            assertEquals(2, done.size)
            assertEquals(listOf(listOf(1L), listOf(2L)), listOf(sentIds(), sentIds()))
        }

    @Test fun aRejectedBulkRequestFallsBackToSingleRequests() =
        runBlocking {
            fail(403)
            ok()
            fail(404) // This book is gone from the server: dropped, not retried.
            val done = ProgressRepository.pushResets(listOf(resetOp(1), resetOp(2)), api, bulk = true)
            assertEquals(listOf(1L, 2L), done.map { it.entityId })
            assertEquals(listOf(listOf(1L, 2L), listOf(1L), listOf(2L)), listOf(sentIds(), sentIds(), sentIds()))
        }

    @Test fun aServerErrorStopsAndKeepsTheRestForLater() =
        runBlocking {
            ok()
            fail(503)
            val done = ProgressRepository.pushResets(listOf(resetOp(1), resetOp(2), resetOp(3)), api, bulk = false)
            assertEquals(listOf(1L), done.map { it.entityId })
            assertEquals(2, server.requestCount)
            assertTrue(done.none { it.entityId == 3L })
        }
}
