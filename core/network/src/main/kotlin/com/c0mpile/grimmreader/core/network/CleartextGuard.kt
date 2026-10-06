package com.c0mpile.grimmreader.core.network

import com.c0mpile.grimmreader.core.common.AppError
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Thrown before any connection is made when a request would use unencrypted HTTP to a host without opt-in. */
class CleartextNotPermittedException : IOException("Cleartext HTTP not permitted for this host") {
    val error = AppError.CleartextNotPermitted()
}

/**
 * Application interceptor that enforces the cleartext policy and follows redirects itself, so every hop
 * is checked before OkHttp opens a socket. The client must be built with `followRedirects(false)`.
 */
class CleartextGuardInterceptor(
    private val policy: NetworkPolicy,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        repeat(MAX_REDIRECTS + 1) {
            check(request)
            val response = chain.proceed(request)
            val next = followUp(request, response) ?: return response
            response.close()
            request = next
        }
        throw IOException("Too many redirects")
    }

    private fun check(request: Request) {
        if (!request.url.isHttps && !policy.isCleartextAllowed(request.url.host)) {
            throw CleartextNotPermittedException()
        }
    }

    private fun followUp(
        request: Request,
        response: Response,
    ): Request? {
        val code = response.code
        val isRedirect = code in HTTP_MULT_CHOICE..HTTP_SEE_OTHER || code == HTTP_TEMP_REDIRECT || code == HTTP_PERM_REDIRECT
        if (!isRedirect) return null
        val location = response.header("Location") ?: return null
        val url = request.url.resolve(location) ?: return null
        val builder = request.newBuilder().url(url)
        val keepMethod = code == HTTP_TEMP_REDIRECT || code == HTTP_PERM_REDIRECT
        if (!keepMethod && request.method != "GET" && request.method != "HEAD") {
            builder.method("GET", null)
            builder.removeHeader("Content-Type").removeHeader("Content-Length")
        }
        if (!url.isSameOrigin(request.url)) builder.removeHeader("Authorization").removeHeader("Cookie")
        return builder.build()
    }

    private companion object {
        const val MAX_REDIRECTS = 20
        const val HTTP_MULT_CHOICE = 300
        const val HTTP_SEE_OTHER = 303
        const val HTTP_TEMP_REDIRECT = 307
        const val HTTP_PERM_REDIRECT = 308
    }
}

/** Scheme, host and port all equal (what OkHttp uses to decide whether credentials may follow a redirect). */
fun HttpUrl.isSameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host.equals(other.host, ignoreCase = true) && port == other.port
