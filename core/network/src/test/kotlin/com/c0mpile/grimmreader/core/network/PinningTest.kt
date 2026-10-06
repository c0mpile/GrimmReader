package com.c0mpile.grimmreader.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PinningTest {
    private val server = MockWebServer()

    private fun serve(certificate: HeldCertificate) {
        server.useHttps(
            HandshakeCertificates
                .Builder()
                .heldCertificate(certificate)
                .build()
                .sslSocketFactory(),
        )
        server.start()
    }

    @After fun stop() = server.close()

    private fun selfSigned(withName: Boolean): HeldCertificate =
        HeldCertificate
            .Builder()
            .apply {
                if (withName) {
                    addSubjectAlternativeName(
                        "localhost",
                    ).addSubjectAlternativeName("127.0.0.1")
                }
            }.build()

    private fun get(policy: NetworkPolicy): String = getFrom(policy)

    private fun getFrom(policy: NetworkPolicy): String =
        GuardedHttpClient
            .create(policy, "test")
            .newCall(
                Request
                    .Builder()
                    .url(
                        server
                            .url("/")
                            .newBuilder()
                            .host("localhost")
                            .build(),
                    ).build(),
            ).execute()
            .use { it.body.string() }

    private fun pinsFor(pin: String) = mapOf(server.hostName.lowercase() to pin, "127.0.0.1" to pin, "localhost" to pin)

    @Test fun selfSignedWithoutPinIsRejectedWithItsFingerprint() {
        val cert = selfSigned(withName = true)
        serve(cert)
        try {
            get(TestPolicy())
            throw AssertionError("expected handshake failure")
        } catch (e: IOException) {
            val untrusted = e.findCause<UntrustedCertificateException>() ?: throw AssertionError("no certificate error", e)
            assertEquals(spkiSha256(cert.certificate), untrusted.spkiSha256)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun pinnedSelfSignedIsAcceptedEvenWithoutMatchingName() {
        val cert = selfSigned(withName = false)
        serve(cert)
        server.enqueue(MockResponse.Builder().body("pinned").build())
        assertEquals("pinned", get(TestPolicy(pins = pinsFor(spkiSha256(cert.certificate)))))
    }

    @Test fun changedKeyIsRejectedWithoutRetrying() {
        serve(selfSigned(withName = true))
        val stalePin = spkiSha256(HeldCertificate.Builder().build().certificate)
        var handshakes = 0
        val pins = TestPolicy(pins = pinsFor(stalePin))
        val counting =
            object : NetworkPolicy by pins {
                override fun pinnedSpki(host: String): String? = pins.pinnedSpki(host).also { handshakes++ }
            }
        try {
            get(counting)
            throw AssertionError("expected handshake failure")
        } catch (e: IOException) {
            assertTrue(e.findCause<PinMismatchException>() != null)
            assertTrue(e.isCertificateFailure())
            // One handshake on the reachable address; the retry interceptor must not try again.
            assertEquals(1, handshakes)
        }
    }
}
