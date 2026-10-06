package com.c0mpile.grimmreader.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.server.NetworkPolicyImpl
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
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
            secrets.put(ServerSession.key(serverId, "access"), "stale")
            secrets.put(ServerSession.key(serverId, "refresh"), "r1")
            val session = ServerSession(db.serverDao(), secrets, policy, Lazy { client }, backgroundScope)
            withTimeout(5_000) { session.server.first { it != null } }
            // Wait until the session loaded the stored token.
            while (!policy.isCleartextAllowed(server.hostName)) kotlinx.coroutines.delay(10)
            val repo =
                LibraryRepository(
                    db,
                    db.bookDao(),
                    db.bookFileDao(),
                    session,
                    LocalFileStore(ApplicationProvider.getApplicationContext()),
                    Dispatchers.IO,
                )

            val count = repo.refresh().getOrThrow()

            assertEquals(2, count)
            assertEquals(true, refreshed)
            val books = repo.observeLibrary().first()
            assertEquals(listOf("Sample A", "Sample Comic"), books.map { it.title })
            assertEquals(11L, books[0].files.single().serverFileId)
            assertEquals("fresh", secrets.get(ServerSession.key(serverId, "access")))
            backgroundScope.cancel()
        }
}
