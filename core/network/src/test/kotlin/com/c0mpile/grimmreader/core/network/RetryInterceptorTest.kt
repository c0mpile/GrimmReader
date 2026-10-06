package com.c0mpile.grimmreader.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryInterceptorTest {
    private val server = MockWebServer().apply { start() }

    @After fun stop() = server.close()

    @Test fun retriesServerErrorsHonouringRetryAfter() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(503)
                .addHeader("Retry-After", "2")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(429).build())
        server.enqueue(MockResponse.Builder().body("done").build())
        val sleeps = mutableListOf<Long>()
        val client =
            GuardedHttpClient
                .create(TestPolicy(setOf(server.hostName)), "test")
                .newBuilder()
                .apply { interceptors().removeAll { it is RetryInterceptor } }
                .addInterceptor(RetryInterceptor { sleeps += it })
                .build()
        val body = client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body.string() }
        assertEquals("done", body)
        assertEquals(3, server.requestCount)
        assertEquals(2000L, sleeps.first())
    }

    @Test fun postIsNotRetried() {
        server.enqueue(MockResponse.Builder().code(503).build())
        val client = GuardedHttpClient.create(TestPolicy(setOf(server.hostName)), "test")
        val request =
            Request
                .Builder()
                .url(server.url("/"))
                .post(okhttp3.RequestBody.EMPTY)
                .build()
        assertEquals(503, client.newCall(request).execute().use { it.code })
        assertEquals(1, server.requestCount)
    }
}
