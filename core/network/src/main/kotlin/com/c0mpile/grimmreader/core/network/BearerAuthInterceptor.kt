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
    private val credentials: () -> BearerCredentials?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // One read: origin and token come from the same snapshot, so a token can never be paired with
        // another server's origin while the configured server changes.
        val current = credentials()
        if (current == null || !request.url.isSameOrigin(current.origin)) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer ${current.accessToken}").build())
    }
}

/** An access token together with the exact origin it was issued for. */
data class BearerCredentials(
    val origin: HttpUrl,
    val accessToken: String,
) {
    override fun toString() = "BearerCredentials(origin=$origin, accessToken=***)"
}
