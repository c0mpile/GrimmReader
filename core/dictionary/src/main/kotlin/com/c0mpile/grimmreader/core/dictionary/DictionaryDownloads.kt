package com.c0mpile.grimmreader.core.dictionary

import android.content.Context
import com.c0mpile.grimmreader.core.common.AppScope
import com.c0mpile.grimmreader.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

sealed interface DictionaryDownload {
    /** [total] is 0 when unknown. */
    data class Running(
        val bytes: Long,
        val total: Long,
    ) : DictionaryDownload

    data object Installing : DictionaryDownload

    data class Failed(
        val message: String,
    ) : DictionaryDownload
}

/**
 * Downloads catalogue dictionaries the user asked for, one archive at a time per entry, into the cache, checks
 * the published checksum and installs through [Dictionaries]. Runs in the app scope so leaving the screen does
 * not stop it; nothing starts on its own.
 */
@Singleton
class DictionaryDownloads
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val client: dagger.Lazy<OkHttpClient>,
        private val dictionaries: Dictionaries,
        @IoDispatcher private val io: CoroutineDispatcher,
        @AppScope private val scope: CoroutineScope,
    ) {
        private val dir = File(context.cacheDir, "dictionary-downloads")
        private val jobs = mutableMapOf<String, Job>()
        private val _state = MutableStateFlow<Map<String, DictionaryDownload>>(emptyMap())

        /** Downloads by catalogue id; finished ones disappear (they show up in [Dictionaries.installed]). */
        val state: StateFlow<Map<String, DictionaryDownload>> = _state.asStateFlow()

        @Synchronized
        fun start(entry: CatalogEntry) {
            if (jobs[entry.id]?.isActive == true) return
            set(entry.id, DictionaryDownload.Running(0, entry.sizeBytes))
            jobs[entry.id] =
                scope.launch(io) {
                    val file = File(dir, "${entry.id.replace(':', '-')}.part")
                    try {
                        download(entry, file)
                        set(entry.id, DictionaryDownload.Installing)
                        val result = dictionaries.importDownload(entry.fileName, file, entry.id)
                        if (result.added.isEmpty() && result.duplicates == 0) {
                            set(entry.id, DictionaryDownload.Failed("The download held no usable dictionary."))
                        } else {
                            set(entry.id, null)
                        }
                    } catch (e: CancellationException) {
                        set(entry.id, null)
                        throw e
                    } catch (e: IOException) {
                        set(entry.id, DictionaryDownload.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Download failed"))
                    } catch (e: LinkageError) {
                        // A decompressor's native code failed to load; report it instead of crashing the app.
                        set(entry.id, DictionaryDownload.Failed("Unpacking is not supported on this device (${e.javaClass.simpleName})."))
                    } finally {
                        file.delete()
                    }
                }
        }

        @Synchronized
        fun cancel(id: String) {
            jobs.remove(id)?.cancel()
            set(id, null)
        }

        fun dismiss(id: String) = set(id, null)

        private suspend fun download(
            entry: CatalogEntry,
            file: File,
        ) {
            dir.mkdirs()
            val digest = entry.checksum?.let { MessageDigest.getInstance(if (it.startsWith("sha512:")) "SHA-512" else "SHA-256") }
            client.get().newCall(Request.Builder().url(entry.url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("The server answered ${response.code}.")
                val total = response.body.contentLength().takeIf { it > 0 } ?: entry.sizeBytes
                response.body.byteStream().use { input -> file.outputStream().use { out -> copy(entry, input, out, total, digest) } }
            }
            val expected = entry.checksum?.substringAfter(':')?.lowercase()
            val actual = digest?.digest()?.joinToString("") { "%02x".format(it) }
            if (expected != null && actual != expected) throw IOException("The download is damaged (checksum mismatch).")
        }

        /** Copies [input] to [out], feeding [digest] and reporting progress; stops when cancelled or oversized. */
        private suspend fun copy(
            entry: CatalogEntry,
            input: InputStream,
            out: OutputStream,
            total: Long,
            digest: MessageDigest?,
        ) {
            val limit = if (entry.sizeBytes > 0) entry.sizeBytes + SIZE_SLACK else DictionaryUnpacker.MAX_FILE_BYTES
            val buffer = ByteArray(BUFFER)
            var bytes = 0L
            var reported = 0L
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) return
                bytes += n
                if (bytes > limit) throw IOException("The download is larger than announced.")
                digest?.update(buffer, 0, n)
                out.write(buffer, 0, n)
                if (bytes - reported >= REPORT_EVERY) {
                    reported = bytes
                    set(entry.id, DictionaryDownload.Running(bytes, total))
                }
            }
        }

        private fun set(
            id: String,
            value: DictionaryDownload?,
        ) = _state.update { if (value == null) it - id else it + (id to value) }

        private companion object {
            const val BUFFER = 64 shl 10
            const val REPORT_EVERY = 256L shl 10
            const val SIZE_SLACK = 1L shl 20
        }
    }
