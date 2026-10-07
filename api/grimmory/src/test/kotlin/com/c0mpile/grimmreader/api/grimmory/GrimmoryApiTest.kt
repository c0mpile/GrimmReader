package com.c0mpile.grimmreader.api.grimmory

import com.c0mpile.grimmreader.core.network.GuardedHttpClient
import com.c0mpile.grimmreader.core.network.NetworkPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrimmoryApiTest {
    private val server = MockWebServer().apply { start() }

    private val api =
        GrimmoryApi.create(
            server.url("/grimmory/"),
            GuardedHttpClient.create(
                object : NetworkPolicy {
                    override fun isCleartextAllowed(host: String) = host == server.hostName

                    override fun pinnedSpki(host: String): String? = null
                },
                "test",
            ),
        )

    @After fun stop() = server.close()

    private fun respond(fixture: String) = server.enqueue(MockResponse.Builder().body(Fixtures.read(fixture)).build())

    @Test fun pathPrefixIsKept() =
        runTest {
            respond("token.json")
            api.login(LoginRequestDto("reader", "secret"))
            assertEquals("/grimmory/api/v1/auth/login", server.takeRequest().url.encodedPath)
        }

    @Test fun parsesBookPageIgnoringUnknownFields() =
        runTest {
            respond("books_page.json")
            val page = api.books(page = 0, size = 2, libraryId = 1)
            assertEquals(listOf(101L, 102L), page.content.map { it.id })
            assertTrue(page.hasNext)
            assertEquals("CBX", page.content[1].primaryFileType)
            assertEquals(
                "libraryId=1",
                server
                    .takeRequest()
                    .url.query
                    ?.split('&')
                    ?.first { it.startsWith("libraryId") },
            )
        }

    @Test fun permissionFlagsAreTheTrueBooleans() =
        runTest {
            respond("users_me.json")
            assertEquals(setOf("canDownload", "canAccessOpds"), api.me().permissionFlags())
        }

    @Test fun bookmarksListCreateAndDelete() =
        runTest {
            respond("bookmarks.json")
            val list = api.bookmarks(101)
            assertEquals(listOf(901L, 902L, 903L, 904L), list.map { it.id })
            assertEquals("12", list[1].cfi)
            assertEquals(4, list[2].pageNumber)
            assertEquals("/grimmory/api/v1/bookmarks/book/101", server.takeRequest().url.encodedPath)

            server.enqueue(
                MockResponse
                    .Builder()
                    .code(409)
                    .body("""{"message":"Bookmark already exists at this location"}""")
                    .build(),
            )
            val created = api.createBookmark(CreateBookmarkDto(bookId = 101, pageNumber = 4, title = "Page 4", pdfBookmark = true))
            assertEquals(409, created.code())
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertEquals("""{"bookId":101,"pageNumber":4,"title":"Page 4","pdfBookmark":true}""", post.body?.utf8())

            server.enqueue(MockResponse.Builder().code(204).build())
            assertTrue(api.deleteBookmark(901).isSuccessful)
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/grimmory/api/v1/bookmarks/901", delete.url.encodedPath)
        }

    @Test fun progressWriteSendsPerFormatAndFileProgressWithoutNulls() =
        runTest {
            server.enqueue(MockResponse.Builder().code(200).build())
            val cfi = "epubcfi(/6/16!/4,/88,/104/1:114)"
            api.updateProgress(
                101,
                UpdateProgressDto(
                    fileProgress = FileProgressDto(501, cfi, "OEBPS/ch7.xhtml", 9.18f),
                    epubProgress = EpubProgressDto(cfi = cfi, href = "OEBPS/ch7.xhtml", percentage = 9.18f),
                ),
            )
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals(setOf("fileProgress", "epubProgress"), body.keys)
            assertFalse((body["epubProgress"] as JsonObject).containsKey("contentSourceProgressPercent"))
        }

    @Test fun readsProgressWithoutFileProgress() =
        runTest {
            respond("progress.json")
            val progress = api.progress(101)
            assertEquals("epubcfi(/6/42!/4,/212/1:157,/268/1:68)", progress.epubProgress?.cfi)
        }
}
