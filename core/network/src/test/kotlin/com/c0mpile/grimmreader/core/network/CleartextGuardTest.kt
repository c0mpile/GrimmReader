package com.c0mpile.grimmreader.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CleartextGuardTest {
    private val lan = MockWebServer()
    private val other = MockWebServer()

    @Before fun start() {
        lan.start()
        other.start()
    }

    @After fun stop() {
        lan.close()
        other.close()
    }

    private fun client(vararg cleartextHosts: String) = GuardedHttpClient.create(TestPolicy(cleartextHosts.toSet()), "test")

    @Test(expected = CleartextNotPermittedException::class)
    fun httpWithoutOptInIsRefusedBeforeConnecting() {
        try {
            client().newCall(Request.Builder().url(lan.url("/api/v1/healthcheck")).build()).execute()
        } finally {
            assertEquals(0, lan.requestCount)
        }
    }

    @Test fun httpToTheOptedInHostWorks() {
        lan.enqueue(MockResponse.Builder().body("ok").build())
        val body = client(lan.hostName).newCall(Request.Builder().url(lan.url("/")).build()).execute().use { it.body.string() }
        assertEquals("ok", body)
    }

    @Test fun redirectToAnotherCleartextHostIsRefused() {
        // `other` is reached through 127.0.0.1, a different host name than the opted-in one.
        val target =
            other
                .url("/stolen")
                .newBuilder()
                .host("127.0.0.1")
                .build()
        lan.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .addHeader("Location", target.toString())
                .build(),
        )
        try {
            client(lan.hostName).newCall(Request.Builder().url(lan.url("/")).build()).execute()
            throw AssertionError("expected CleartextNotPermittedException")
        } catch (_: CleartextNotPermittedException) {
            assertEquals(1, lan.requestCount)
            assertEquals(0, other.requestCount)
        }
    }

    @Test fun redirectWithinTheSameHostIsFollowed() {
        lan.enqueue(
            MockResponse
                .Builder()
                .code(301)
                .addHeader("Location", "/moved")
                .build(),
        )
        lan.enqueue(MockResponse.Builder().body("here").build())
        val body = client(lan.hostName).newCall(Request.Builder().url(lan.url("/")).build()).execute().use { it.body.string() }
        assertEquals("here", body)
        lan.takeRequest()
        assertEquals("/moved", lan.takeRequest().url.encodedPath)
    }
}
