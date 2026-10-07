package com.c0mpile.grimmreader.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.c0mpile.grimmreader.api.grimmory.BookmarkDto
import com.c0mpile.grimmreader.api.grimmory.GrimmoryApi
import com.c0mpile.grimmreader.core.data.bookmark.BookmarkRepository
import com.c0mpile.grimmreader.core.data.server.NetworkPolicyImpl
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class BookmarkRepositoryTest {
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

    private val repo: BookmarkRepository by lazy {
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
        BookmarkRepository(context, db, db.bookmarkDao(), db.outboxDao(), db.bookDao(), session, Dispatchers.IO)
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

    private fun book(
        source: BookSource,
        format: BookFormat,
    ): Long =
        runBlocking {
            val id =
                db.bookDao().insert(
                    BookEntity(
                        source = source,
                        serverBookId =
                            if (source ==
                                BookSource.SERVER
                            ) {
                                101
                            } else {
                                null
                            },
                        title = "Sample",
                        addedAt = 0,
                    ),
                )
            db.bookFileDao().upsert(BookFileEntity(bookId = id, format = format))
            id
        }

    private fun ops() = runBlocking { db.outboxDao().due(Long.MAX_VALUE) }

    @Test fun localBooksNeverQueueAnything() =
        runBlocking {
            val id = book(BookSource.LOCAL, BookFormat.CBZ)
            repo.add(id, null, 3, "Page 3", 10f)
            val added = repo.observe(id).first().single()
            assertEquals(3, added.page)
            assertTrue(ops().isEmpty())
            repo.remove(added.id)
            assertTrue(repo.observe(id).first().isEmpty())
            assertTrue(ops().isEmpty())
        }

    @Test fun serverBookQueuesTheWebRequestAndAnUnsyncedRemoveCancelsIt() =
        runBlocking {
            val id = book(BookSource.SERVER, BookFormat.CBZ)
            repo.add(id, null, 12, "Page 12", 40f)
            val op = ops().single()
            assertEquals(BookmarkRepository.KIND_CREATE, op.kind)
            // Comics store the page as text in `cfi`, like the web reader.
            assertTrue(op.payload, op.payload.contains(""""cfi":"12""""))
            repo.remove(
                repo
                    .observe(id)
                    .first()
                    .single()
                    .id,
            )
            assertTrue(ops().isEmpty())
            assertTrue(db.bookmarkDao().forBook(id).isEmpty())
        }

    @Test fun pushStoresTheServerIdThenDeletesWithA404AsSuccess() =
        runBlocking {
            val id = book(BookSource.SERVER, BookFormat.PDF)
            repo.add(id, null, 4, "Page 4", null)
            server.enqueue(MockResponse.Builder().body("""{"id":903,"bookId":101,"pageNumber":4,"title":"Page 4"}""").build())
            assertTrue(repo.push(ops().single(), api))
            db.outboxDao().delete(ops().single().id)
            val request = server.takeRequest()
            assertEquals("""{"bookId":101,"pageNumber":4,"title":"Page 4","pdfBookmark":true}""", request.body?.utf8())
            val row = db.bookmarkDao().forBook(id).single()
            assertEquals(903L, row.serverId)

            repo.remove(row.id)
            assertTrue("hidden while the delete is pending", repo.observe(id).first().isEmpty())
            server.enqueue(MockResponse.Builder().code(404).build())
            assertTrue(repo.push(ops().single(), api))
            assertEquals("/api/v1/bookmarks/903", server.takeRequest().url.encodedPath)
            assertTrue(db.bookmarkDao().forBook(id).isEmpty())
        }

    @Test fun aSuccessfulDeleteIsDoneAtOnce() =
        runBlocking {
            val id = book(BookSource.SERVER, BookFormat.CBZ)
            repo.add(id, null, 1, "Page 1", null)
            server.enqueue(MockResponse.Builder().body("""{"id":77,"cfi":"1"}""").build())
            assertTrue(repo.push(ops().single(), api))
            db.outboxDao().delete(ops().single().id)
            repo.remove(
                db
                    .bookmarkDao()
                    .forBook(id)
                    .single()
                    .id,
            )
            // The server answers 204 No Content (Grimmory v3.5.0).
            server.enqueue(MockResponse.Builder().code(204).build())
            assertTrue(repo.push(ops().single(), api))
            assertTrue(db.bookmarkDao().forBook(id).isEmpty())
        }

    @Test fun aConflictAdoptsTheExistingServerBookmark() =
        runBlocking {
            val id = book(BookSource.SERVER, BookFormat.EPUB)
            val cfi = "epubcfi(/6/14!/4,/2/1:0,/10/1:42)"
            repo.add(id, cfi, null, "Chapter Two", 12f)
            server.enqueue(MockResponse.Builder().code(409).build())
            server.enqueue(MockResponse.Builder().body("""[{"id":7,"cfi":"other"},{"id":901,"cfi":"$cfi"}]""").build())
            assertTrue(repo.push(ops().single(), api))
            assertEquals(
                901L,
                db
                    .bookmarkDao()
                    .forBook(id)
                    .single()
                    .serverId,
            )
        }

    @Test fun serverErrorsAreRetried() =
        runBlocking {
            val id = book(BookSource.SERVER, BookFormat.CBZ)
            repo.add(id, null, 2, "Page 2", null)
            server.enqueue(MockResponse.Builder().code(503).build())
            assertEquals(false, repo.push(ops().single(), api))
            assertNull(
                db
                    .bookmarkDao()
                    .forBook(id)
                    .single()
                    .serverId,
            )
        }

    @Test fun mergeAddsWebBookmarksAdoptsOwnUploadsAndDropsOnesDeletedElsewhere() =
        runBlocking {
            val id = book(BookSource.SERVER, BookFormat.CBZ)
            repo.add(id, null, 5, "Page 5", null) // pending upload, also already on the server as 905
            repo.add(id, null, 8, "Page 8", null)
            val eight = db.bookmarkDao().forBook(id).first { it.page == 8 }
            // Synced earlier (so no upload queued), since deleted on the web.
            db.bookmarkDao().setServerId(eight.id, 908)
            db.outboxDao().deleteFor(BookmarkRepository.KIND_CREATE, eight.id)
            repo.merge(
                id,
                BookFormat.CBZ,
                listOf(
                    BookmarkDto(id = 905, cfi = "5", title = "Page 5"),
                    BookmarkDto(id = 912, cfi = "12", title = "Splash page"),
                    BookmarkDto(id = 913, positionMs = 1000),
                    BookmarkDto(id = 914, cfi = "epubcfi(/6/2!/4)"),
                ),
            )
            val rows = db.bookmarkDao().forBook(id).sortedBy { it.page }
            assertEquals(listOf(5 to 905L, 12 to 912L), rows.map { it.page to it.serverId })
            assertEquals("Splash page", rows[1].title)
            assertTrue("the adopted upload is no longer queued", ops().isEmpty())
        }
}
