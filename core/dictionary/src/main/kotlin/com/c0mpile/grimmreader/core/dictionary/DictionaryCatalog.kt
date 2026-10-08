package com.c0mpile.grimmreader.core.dictionary

import com.c0mpile.grimmreader.core.common.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class DictionarySource(
    val label: String,
    val licence: String,
) {
    WIKTIONARY("Wiktionary", "Wiktionary data, CC BY-SA 4.0, converted by the wiktionary_stardict project"),
    FREEDICT("FreeDict", "FreeDict, free licences (mostly GPL); see each dictionary's source"),
}

/**
 * A downloadable dictionary. [from]/[to] are language codes; [checksum] is "sha256:…" or "sha512:…" (catalogue
 * entries without one are left out).
 */
data class CatalogEntry(
    val id: String,
    val source: DictionarySource,
    val from: String,
    val to: String,
    val sizeBytes: Long,
    val words: Int?,
    val url: String,
    val fileName: String,
    val checksum: String?,
    val version: String,
) {
    val title: String get() = if (from == to) "${language(from)} (${language(from)} definitions)" else "${language(from)} → ${language(to)}"
}

/** "deu" → "German"; the code itself when the system has no name for it. */
fun language(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() && it != code } ?: code

/**
 * The download catalogue: Wiktionary dictionaries (latest GitHub release of wiktionary_stardict) and the
 * StarDict releases in FreeDict's database. Fetched only when the user opens it, kept for the session.
 * Uses the app's single guarded client; these hosts are https, no credentials are ever attached.
 */
@Singleton
class DictionaryCatalog
    @Inject
    constructor(
        private val client: dagger.Lazy<OkHttpClient>,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        private val lock = Mutex()
        private var cached: List<CatalogEntry>? = null

        /** Throws [IOException] only when both sources fail; one failing source just leaves its entries out. */
        suspend fun entries(refresh: Boolean = false): List<CatalogEntry> =
            lock.withLock {
                cached?.takeUnless { refresh }?.let { return@withLock it }
                withContext(io) {
                    coroutineScope {
                        val wiktionary = async { runCatching { wiktionary() } }
                        val freedict = async { runCatching { freedict() } }
                        val results = listOf(wiktionary.await(), freedict.await())
                        results
                            .firstOrNull { it.isFailure }
                            ?.takeIf {
                                results.all { r ->
                                    r.isFailure
                                }
                            }?.exceptionOrNull()
                            ?.let { throw IOException(it) }
                        results.flatMap { it.getOrDefault(emptyList()) }.also { cached = it }
                    }
                }
            }

        private fun wiktionary(): List<CatalogEntry> = parseWiktionary(get(WIKTIONARY_RELEASE))

        private fun freedict(): List<CatalogEntry> = parseFreeDict(get(FREEDICT_DATABASE))

        private fun get(url: String): String {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()
            return client.get().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                // At most MAX_CATALOG_BYTES; one byte more means the answer is not a catalogue.
                val source = response.body.source()
                source.request(MAX_CATALOG_BYTES + 1)
                source.buffer.takeIf { it.size <= MAX_CATALOG_BYTES }?.readUtf8() ?: throw IOException("Catalogue too large")
            }
        }

        companion object {
            const val WIKTIONARY_RELEASE = "https://api.github.com/repos/xxyzz/wiktionary_stardict/releases/latest"
            const val FREEDICT_DATABASE = "https://freedict.org/freedict-database.json"
            private const val MAX_CATALOG_BYTES = 8L shl 20
            private val json = Json { ignoreUnknownKeys = true }

            /** Download URLs must stay on https at the source's own hosts (GitHub redirects to its asset host). */
            private val WIKTIONARY_URL =
                Regex("https://github\\.com/xxyzz/wiktionary_stardict/releases/download/[^/?#]+/[a-z-]+\\.tar\\.zst")
            private val FREEDICT_URL = Regex("https://download\\.freedict\\.org/dictionaries/[^?#]+\\.stardict\\.tar\\.xz")
            private val SHA256 = Regex("sha256:[0-9a-f]{64}")
            private val SHA512 = Regex("[0-9a-f]{128}")
            private val PAIR = Regex("([a-z]{2,3}(?:-[a-z]+)?)-([a-z]{2,3})")

            internal fun parseWiktionary(text: String): List<CatalogEntry> {
                val release = json.decodeFromString<GitHubRelease>(text)
                return release.assets.mapNotNull { asset ->
                    val pair = PAIR.matchEntire(asset.name.removeSuffix(".tar.zst")) ?: return@mapNotNull null
                    if (!asset.name.endsWith(".tar.zst") || !WIKTIONARY_URL.matches(asset.url)) return@mapNotNull null
                    // Downloads may be redirected to another host; the checksum is what makes them trustworthy.
                    val digest = asset.digest?.takeIf { SHA256.matches(it) } ?: return@mapNotNull null
                    CatalogEntry(
                        id = "wiktionary:${pair.value}",
                        source = DictionarySource.WIKTIONARY,
                        from = pair.groupValues[1],
                        to = pair.groupValues[2],
                        sizeBytes = asset.size,
                        words = null,
                        url = asset.url,
                        fileName = asset.name,
                        checksum = digest,
                        version = release.tag,
                    )
                }
            }

            internal fun parseFreeDict(text: String): List<CatalogEntry> =
                json.decodeFromString<List<FreeDictEntry>>(text).mapNotNull { dict ->
                    val release = dict.releases.lastOrNull { it.platform == "stardict" } ?: return@mapNotNull null
                    val pair = PAIR.matchEntire(dict.name ?: return@mapNotNull null) ?: return@mapNotNull null
                    if (!FREEDICT_URL.matches(release.url)) return@mapNotNull null
                    val checksum = release.checksum?.lowercase()?.takeIf { SHA512.matches(it) } ?: return@mapNotNull null
                    CatalogEntry(
                        id = "freedict:${pair.value}",
                        source = DictionarySource.FREEDICT,
                        from = pair.groupValues[1],
                        to = pair.groupValues[2],
                        sizeBytes = release.size?.toLongOrNull() ?: 0,
                        words = dict.headwords?.toIntOrNull(),
                        url = release.url,
                        fileName = release.url.substringAfterLast('/'),
                        checksum = "sha512:$checksum",
                        version = release.version.orEmpty(),
                    )
                }
        }
    }

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tag: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String,
    val digest: String? = null,
)

@Serializable
internal data class FreeDictEntry(
    val name: String? = null,
    val headwords: String? = null,
    val releases: List<FreeDictRelease> = emptyList(),
)

@Serializable
internal data class FreeDictRelease(
    val platform: String? = null,
    @SerialName("URL") val url: String = "",
    val size: String? = null,
    val checksum: String? = null,
    val version: String? = null,
)
