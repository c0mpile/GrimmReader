package com.c0mpile.grimmreader.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Normalises what a user types as a server address into `scheme://host[:port][/prefix]` (no trailing slash).
 * Accepts `host`, `host:port`, IPv4, `[IPv6]:port`, full URLs and a path prefix. Strips query, fragment,
 * a trailing `/` and anything from `/api` on. The default scheme is https; `http` is only accepted when
 * the user explicitly allowed unencrypted HTTP.
 */
object ServerUrlParser {
    sealed interface Result {
        data class Ok(
            val baseUrl: String,
            val host: String,
            val isCleartext: Boolean,
        ) : Result

        data object Empty : Result

        data object Invalid : Result

        /** The address is `http://` but unencrypted HTTP was not allowed. */
        data object CleartextNotAllowed : Result
    }

    fun parse(
        input: String,
        allowCleartext: Boolean,
    ): Result {
        val trimmed = input.trim()
        val scheme =
            Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")
                .find(trimmed)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        val url =
            when {
                trimmed.isEmpty() -> return Result.Empty
                scheme != null && scheme != "http" && scheme != "https" -> null
                else -> (if (scheme == null) "https://$trimmed" else trimmed).toHttpUrlOrNull()?.takeIf { it.host.isNotBlank() }
            }
        return when {
            url == null -> Result.Invalid
            url.scheme == "http" && !allowCleartext -> Result.CleartextNotAllowed
            else -> Result.Ok(baseUrl = normalise(url), host = url.host, isCleartext = url.scheme == "http")
        }
    }

    private fun normalise(url: HttpUrl): String {
        val segments = url.pathSegments.filter { it.isNotEmpty() }.takeWhile { it.lowercase() != "api" }
        val prefix = if (segments.isEmpty()) "" else segments.joinToString("/", prefix = "/")
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return "${url.scheme}://$host$port$prefix"
    }
}
