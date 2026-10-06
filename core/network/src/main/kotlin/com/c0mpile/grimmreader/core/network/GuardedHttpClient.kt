package com.c0mpile.grimmreader.core.network

import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.internal.tls.OkHostnameVerifier
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext

/**
 * Builds the ONE OkHttpClient of the app (security invariant). Every HTTP path (API, Coil, OPDS, downloads,
 * sync worker, connection test) uses it or a `newBuilder()` derivative that only adds interceptors.
 * Never construct another OkHttpClient, HttpURLConnection or URL.openStream elsewhere.
 */
object GuardedHttpClient {
    const val MAX_REQUESTS_PER_HOST = 4
    private const val CONNECT_TIMEOUT_S = 15L
    private const val READ_TIMEOUT_S = 60L

    fun create(
        policy: NetworkPolicy,
        userAgent: String,
    ): OkHttpClient {
        val trustManager = PinningTrustManager(policy)
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return OkHttpClient
            .Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(UserAgentInterceptor(userAgent))
            .addInterceptor(CleartextGuardInterceptor(policy))
            .addInterceptor(RetryInterceptor())
            .addNetworkInterceptor(RetryAfterSanitizer())
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(PinAwareHostnameVerifier(policy))
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * OkHttp's internal RetryAndFollowUpInterceptor parses a 503's `Retry-After` with Integer.valueOf, so a
 * hostile value such as "99999999999999999" throws NumberFormatException out of the call. Network
 * interceptors see the response first: clamp numeric values to a small range, drop malformed ones.
 */
internal class RetryAfterSanitizer : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val value = response.header("Retry-After") ?: return response
        val seconds = value.trim().toBigIntegerOrNull()
        val builder = response.newBuilder().removeHeader("Retry-After")
        if (seconds != null) builder.header("Retry-After", seconds.coerceIn(0.toBigInteger(), MAX_SECONDS.toBigInteger()).toString())
        return builder.build()
    }

    private companion object {
        const val MAX_SECONDS = 3600
    }
}

private class UserAgentInterceptor(
    private val userAgent: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain
                .request()
                .newBuilder()
                .header("User-Agent", userAgent)
                .build(),
        )
}

/** A pinned self-signed certificate often lacks a matching name (IP address, LAN name); the pin is stronger. */
private class PinAwareHostnameVerifier(
    private val policy: NetworkPolicy,
) : HostnameVerifier {
    override fun verify(
        hostname: String,
        session: javax.net.ssl.SSLSession,
    ): Boolean {
        if (OkHostnameVerifier.verify(hostname, session)) return true
        val pin = policy.pinnedSpki(hostname) ?: return false
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        return spkiSha256(leaf) == pin
    }
}

/**
 * Retries idempotent requests on 429/5xx and I/O errors with exponential backoff and jitter, honouring
 * `Retry-After` (seconds). At most [MAX_ATTEMPTS] attempts; cleartext refusals and certificate failures are never
 * retried.
 */
class RetryInterceptor(
    private val sleep: (Long) -> Unit = Thread::sleep,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val idempotent = request.method == "GET" || request.method == "HEAD" || request.method == "PUT"
        var attempt = 1
        while (true) {
            val lastAttempt = !idempotent || attempt >= MAX_ATTEMPTS
            val response =
                try {
                    chain.proceed(request)
                } catch (e: CleartextNotPermittedException) {
                    throw e
                } catch (e: IOException) {
                    if (lastAttempt || chain.call().isCanceled() || e.isCertificateFailure()) throw e
                    null
                }
            if (response != null && (lastAttempt || !retryable(response.code))) return response
            // Clamp before converting: a negative or huge header must not overflow or make sleep() throw.
            val retryAfter = response?.header("Retry-After")?.toLongOrNull()?.coerceIn(0L, MAX_DELAY_MS / MILLIS)
            response?.close()
            sleep(retryAfter?.times(MILLIS) ?: backoff(attempt))
            attempt++
        }
    }

    private fun retryable(code: Int) = code == TOO_MANY_REQUESTS || code in SERVER_ERRORS

    private fun backoff(attempt: Int): Long {
        val base = BASE_DELAY_MS shl (attempt - 1)
        return (base + (Math.random() * base / 2).toLong()).coerceAtMost(MAX_DELAY_MS)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BASE_DELAY_MS = 500L
        const val MAX_DELAY_MS = 10_000L
        const val MILLIS = 1000L
        const val TOO_MANY_REQUESTS = 429
        val SERVER_ERRORS = 500..599
    }
}
