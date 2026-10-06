package com.c0mpile.grimmreader.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectCredentialTest {
    private val server = MockWebServer().apply { start() }
    private val otherPort = MockWebServer().apply { start() }

    @After fun stop() {
        server.close()
        otherPort.close()
    }

    private fun client(authOrigin: okhttp3.HttpUrl? = null) =
        GuardedHttpClient
            .create(TestPolicy(setOf(server.hostName)), "test")
            .newBuilder()
            .addInterceptor(BearerAuthInterceptor { authOrigin?.let { BearerCredentials(it, "secret-token") } })
            .build()

    @Test fun explicitAuthorizationIsDroppedWhenTheRedirectChangesPort() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .addHeader("Location", otherPort.url("/x").toString())
                .build(),
        )
        otherPort.enqueue(MockResponse.Builder().body("ok").build())
        val request =
            Request
                .Builder()
                .url(server.url("/"))
                .header("Authorization", "Bearer secret-token")
                .build()
        client().newCall(request).execute().close()
        assertEquals("Bearer secret-token", server.takeRequest().headers["Authorization"])
        assertNull(otherPort.takeRequest().headers["Authorization"])
    }

    @Test fun bearerIsOnlyAddedForTheServerOrigin() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .addHeader("Location", otherPort.url("/x").toString())
                .build(),
        )
        otherPort.enqueue(MockResponse.Builder().body("ok").build())
        client(authOrigin = server.url("/")).newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        assertEquals("Bearer secret-token", server.takeRequest().headers["Authorization"])
        assertNull(otherPort.takeRequest().headers["Authorization"])
    }

    @Test fun hostileRetryAfterValuesDoNotCrash() {
        for (value in listOf("-5", "99999999999999999", "soon")) {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(503)
                    .addHeader("Retry-After", value)
                    .build(),
            )
            server.enqueue(MockResponse.Builder().body("ok").build())
        }
        val sleeps = mutableListOf<Long>()
        val client =
            GuardedHttpClient
                .create(TestPolicy(setOf(server.hostName)), "test")
                .newBuilder()
                .apply { interceptors().removeAll { it is RetryInterceptor } }
                .addInterceptor(RetryInterceptor { sleeps += it })
                .build()
        repeat(3) { assertEquals(200, client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.code }) }
        // OkHttp may retry a 503 with Retry-After 0 itself; what matters is no crash and bounded sleeps.
        assertTrue(sleeps.all { it in 0L..10_000L })
    }
}
