package com.c0mpile.grimmreader.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.c0mpile.grimmreader.api.grimmory.TokenDto
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.server.NetworkPolicyImpl
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.datastore.SecretCipher
import com.c0mpile.grimmreader.core.datastore.SecretStore
import com.c0mpile.grimmreader.core.files.LocalFileStore
import com.c0mpile.grimmreader.core.network.GuardedHttpClient
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

private class PlainCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain

    override fun decrypt(sealed: ByteArray) = sealed
}

@RunWith(AndroidJUnit4::class)
class LibraryRefreshTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val db =
        Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                GrimmDatabase::class.java,
            ).allowMainThreadQueries()
            .build()

    @After fun stop() {
        server.close()
        db.close()
    }

    private val page0 =
        """{"content":[{"id":1,"title":"Sample A","authors":["Ada"],"libraryId":1,"primaryFileId":11,"primaryFileType":"EPUB"},
           {"id":2,"title":"Sample Audio","primaryFileId":12,"primaryFileType":"AUDIOBOOK"}],"hasNext":true}"""
    private val page1 =
        """{"content":[{"id":3,"title":"Sample Comic","libraryId":2,"primaryFileId":13,"primaryFileType":"CBX",
           "primaryFileName":"c.cbz"}],"hasNext":false}"""

    private val libraries =
        """[{"id":1,"name":"Books","allowedFormats":["EPUB","PDF"]},{"id":2,"name":"Comics","allowedFormats":["CBX","PDF"]}]"""

    // Real time and a real scope: Room, DataStore and OkHttp all work on their own threads here.
    @Test fun refreshMirrorsServerBooksAndRefreshesAnExpiredToken() =
        runBlocking {
            val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var refreshed = false
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val auth = request.headers["Authorization"]
                        return when {
                            request.url.encodedPath.endsWith("/auth/refresh") -> {
                                refreshed = true
                                MockResponse.Builder().body("""{"accessToken":"fresh","refreshToken":"r2","expires":7200}""").build()
                            }
                            auth != "Bearer fresh" -> MockResponse.Builder().code(401).build()
                            request.url.encodedPath.endsWith("/libraries") -> MockResponse.Builder().body(libraries).build()
                            request.url.queryParameter("page") == "0" -> MockResponse.Builder().body(page0).build()
                            else -> MockResponse.Builder().body(page1).build()
                        }
                    }
                }
            server.start()
            val policy = NetworkPolicyImpl()
            val client = GuardedHttpClient.create(policy, "test")
            val secrets =
                SecretStore(
                    PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") },
                    PlainCipher(),
                )
            val serverId = db.serverDao().upsert(ServerEntity(baseUrl = server.url("/").toString().trimEnd('/'), allowCleartext = true))
            val session = ServerSession(db.serverDao(), secrets, policy, Lazy { client }, backgroundScope)
            session.storeTokens(serverId, server.url("/"), TokenDto("stale", "r1"))
            withTimeout(5_000) { session.server.first { it != null } }
            // Wait until the session loaded the stored token.
            while (!policy.isCleartextAllowed(server.hostName)) kotlinx.coroutines.delay(10)
            val repo =
                LibraryRepository(
                    db,
                    db.bookDao(),
                    db.bookFileDao(),
                    db.libraryDao(),
                    session,
                    LocalFileStore(ApplicationProvider.getApplicationContext()),
                    AppPreferences(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "p.preferences_pb") }),
                    Dispatchers.IO,
                )

            val count = repo.refresh().getOrThrow()

            assertEquals(2, count)
            assertEquals(true, refreshed)
            val books = repo.observeLibrary().first()
            assertEquals(listOf("Sample A", "Sample Comic"), books.map { it.title })
            assertEquals(11L, books[0].files.single().serverFileId)
            assertEquals(listOf(1L, 2L), books.map { it.libraryId })
            val libs = repo.observeLibraries().first { it.isNotEmpty() }
            assertEquals(listOf("Books" to false, "Comics" to true), libs.map { it.name to it.isComics })
            assertEquals(true, secrets.get(ServerSession.tokensKey(serverId))?.contains("\"fresh\""))

            // The automatic refresh skips a library mirrored recently, and runs again once it is stale.
            val requests = server.requestCount
            assertNull(repo.refreshIfStale())
            assertEquals(requests, server.requestCount)
            val later = System.currentTimeMillis() + LibraryRepository.STALE_AFTER_MS + 1
            assertEquals(2, repo.refreshIfStale(now = later)?.getOrThrow())
            assertTrue(server.requestCount > requests)
            backgroundScope.cancel()
        }

    @Test fun tokenIsNotReusedAfterTheServerUrlChanges() =
        runBlocking {
            val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val other = MockWebServer().apply { start() }
            server.start()
            other.enqueue(MockResponse.Builder().body("[]").build())
            val policy = NetworkPolicyImpl()
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "t.preferences_pb") }
            val secrets = SecretStore(store, PlainCipher())
            // Token issued by `server`; then the same row is pointed at `other` (different port).
            val id = db.serverDao().upsert(ServerEntity(baseUrl = server.url("/").toString().trimEnd('/'), allowCleartext = true))
            val session = ServerSession(db.serverDao(), secrets, policy, Lazy { GuardedHttpClient.create(policy, "test") }, backgroundScope)
            session.storeTokens(id, server.url("/"), TokenDto("token-for-first-server", "refresh-for-first-server"))
            db.serverDao().upsert(ServerEntity(id = id, baseUrl = other.url("/").toString().trimEnd('/'), allowCleartext = true))
            withTimeout(5_000) { session.server.first { it?.baseUrl?.contains(":${other.port}") == true } }
            while (!policy.isCleartextAllowed(other.hostName)) kotlinx.coroutines.delay(10)
            kotlinx.coroutines.delay(200)

            session.api()!!.libraries()

            assertEquals(null, other.takeRequest().headers["Authorization"])
            other.close()
            backgroundScope.cancel()
        }

    @Test fun lateUnauthorizedFromTheOldServerNeverGetsTheNewTokens() =
        runBlocking {
            val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val newServer = MockWebServer().apply { start() }
            server.start()
            server.enqueue(MockResponse.Builder().code(401).build())
            val policy = NetworkPolicyImpl()
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "u.preferences_pb") }
            val secrets = SecretStore(store, PlainCipher())
            val id = db.serverDao().upsert(ServerEntity(baseUrl = server.url("/").toString().trimEnd('/'), allowCleartext = true))
            val session = ServerSession(db.serverDao(), secrets, policy, Lazy { GuardedHttpClient.create(policy, "test") }, backgroundScope)
            policy.setPending(server.hostName, cleartext = true, pin = null)
            session.storeTokens(id, server.url("/"), TokenDto("old-access", "old-refresh"))
            val client = session.authedClient()
            // Re-login to another server reusing the same row before the old server's 401 is handled.
            session.storeTokens(id, newServer.url("/"), TokenDto("new-access", "new-refresh"))

            client
                .newCall(
                    okhttp3.Request
                        .Builder()
                        .url(server.url("/api/v1/libraries"))
                        .build(),
                ).execute()
                .close()

            val first = server.takeRequest()
            assertEquals(null, first.headers["Authorization"])
            assertEquals(1, server.requestCount)
            assertEquals(0, newServer.requestCount)
            newServer.close()
            backgroundScope.cancel()
        }
}
