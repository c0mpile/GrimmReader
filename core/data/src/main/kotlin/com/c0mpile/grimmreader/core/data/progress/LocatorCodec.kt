package com.c0mpile.grimmreader.core.data.progress

import com.c0mpile.grimmreader.core.model.Locator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stores a [Locator] as JSON in Room. */
object LocatorCodec {
    @Serializable
    private sealed interface Stored

    @Serializable
    @SerialName("epub")
    private data class Epub(
        val cfi: String,
        val href: String? = null,
        val percent: Float,
        val contentSourcePercent: Float? = null,
    ) : Stored

    @Serializable
    @SerialName("page")
    private data class Page(
        val page: Int,
        val pageCount: Int,
    ) : Stored

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(locator: Locator): String =
        json.encodeToString<Stored>(
            when (locator) {
                is Locator.Epub -> Epub(locator.cfi, locator.href, locator.percent, locator.contentSourcePercent)
                is Locator.Page -> Page(locator.page, locator.pageCount)
            },
        )

    fun decode(value: String): Locator? =
        when (val stored = runCatching { json.decodeFromString<Stored>(value) }.getOrNull()) {
            is Epub -> Locator.Epub(stored.cfi, stored.href, stored.percent, stored.contentSourcePercent)
            is Page -> Locator.Page(stored.page, stored.pageCount)
            null -> null
        }
}
