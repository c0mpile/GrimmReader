package com.c0mpile.grimmreader.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.c0mpile.grimmreader.api.grimmory.TokenDto
import com.c0mpile.grimmreader.core.data.server.NetworkPolicyImpl
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.data.stream.OnlineReading
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.datastore.SecretCipher
import com.c0mpile.grimmreader.core.datastore.SecretStore
import com.c0mpile.grimmreader.core.files.LocalFileStore
import com.c0mpile.grimmreader.core.model.BookFormat
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
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

private class NoCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain

    override fun decrypt(sealed: ByteArray) = sealed
}

@RunWith(AndroidJUnit4::class)
class OnlineReadingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GrimmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val paths = CopyOnWriteArrayList<String>()
    private val epub = "book bytes".toByteArray()

    @After fun stop() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun online(dispatch: (RecordedRequest) -> MockResponse): OnlineReading =
        runBlocking {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        paths += request.url.encodedPath
                        if (request.headers["Authorization"] != "Bearer t") return MockResponse.Builder().code(401).build()
                        return dispatch(request)
                    }
                }
            server.start()
            val policy = NetworkPolicyImpl()
            val secrets = SecretStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") }, NoCipher())
            val id = db.serverDao().upsert(ServerEntity(baseUrl = server.url("/").toString().trimEnd('/'), allowCleartext = true))
            val session = ServerSession(db.serverDao(), secrets, policy, Lazy { GuardedHttpClient.create(policy, "test") }, scope)
            session.storeTokens(id, server.url("/"), TokenDto("t", "r"))
            withTimeout(5_000) { session.server.first { it != null } }
            while (!policy.isCleartextAllowed(server.hostName)) kotlinx.coroutines.delay(10)
            OnlineReading(session, LocalFileStore(ApplicationProvider.getApplicationContext()), Dispatchers.IO)
        }

    @Test fun ebookIsFetchedOnceThenServedFromTheCache() =
        runBlocking {
            val reading = online { MockResponse.Builder().body(Buffer().write(epub)).build() }
            val progress = mutableListOf<Float?>()

            val (first, _) = reading.book(7, 70, BookFormat.EPUB) { progress += it }
            val (second, _) = reading.book(7, 70, BookFormat.EPUB) {}

            assertEquals(first, second)
            assertEquals(epub.toList(), first.readBytes().toList())
            assertEquals(listOf("/api/v1/books/7/content"), paths)
            assertTrue(first.path.startsWith(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir.path))
        }

    @Test fun aReplacedServerFileIsFetchedAgain() =
        runBlocking {
            val reading = online { MockResponse.Builder().body(Buffer().write(epub)).build() }
            reading.book(7, 70, BookFormat.EPUB) {}
            reading.book(7, 71, BookFormat.EPUB) {}
            assertEquals(2, paths.size)
        }

    @Test fun oversizedPagesAreRefusedAndNotCached() =
        runBlocking {
            val reading =
                online {
                    MockResponse
                        .Builder()
                        .body(Buffer().write(ByteArray(16)))
                        .setHeader("Content-Length", OnlineReading.MAX_PAGE + 1)
                        .build()
                }
            try {
                reading.comicPage(9, 1)
                fail("expected IOException")
            } catch (_: IOException) {
            }
            val pages = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "stream")
            assertTrue(pages.walk().none { it.isFile && it.name.startsWith("1") })
        }

    @Test fun pageListAndPagesComeFromTheServer() =
        runBlocking {
            val reading =
                online { r ->
                    if (r.url.encodedPath.endsWith("/pages")) {
                        MockResponse.Builder().body("[1,2,3]").build()
                    } else {
                        MockResponse.Builder().body("page").build()
                    }
                }
            assertEquals(listOf(1, 2, 3), reading.comicPages(9))
            assertEquals("page", reading.comicPage(9, 2).readText())
            reading.comicPage(9, 2)
            assertEquals(listOf("/api/v1/cbx/9/pages", "/api/v1/media/book/9/cbx/pages/2"), paths)
        }
}
