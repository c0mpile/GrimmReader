package com.c0mpile.grimmreader.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer …` only to requests for the configured server's exact origin (scheme, host,
 * port). It runs inside the cleartext guard, so it also sees every redirect hop; an origin check here is
 * what keeps a token from following a redirect to another host or port.
 */
class BearerAuthInterceptor(
    private val origin: () -> HttpUrl?,
    private val token: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val server = origin()
        val bearer = token()
        if (server == null || bearer == null || !request.url.isSameOrigin(server)) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $bearer").build())
    }
}
