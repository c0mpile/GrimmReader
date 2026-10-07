package com.c0mpile.grimmreader.core.data.stream

import com.c0mpile.grimmreader.api.grimmory.GrimmoryUrls
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.files.FormatSniffer
import com.c0mpile.grimmreader.core.files.LocalFileStore
import com.c0mpile.grimmreader.core.model.BookFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reading server books without downloading them. Ebooks are fetched whole into an evictable cache (foliate
 * needs the complete file; EPUBs are small); comics are streamed page by page from the server, which extracts
 * pages itself. Nothing here is a kept download: the cache is trimmed to [MAX_CACHE] bytes, Android may clear
 * it, and signing out deletes it. Uses the authenticated guarded client.
 */
@Singleton
class OnlineReading
    @Inject
    constructor(
        private val session: ServerSession,
        private val files: LocalFileStore,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        private val bookLock = Mutex()

        @Volatile private var client: OkHttpClient? = null

        private fun client(): OkHttpClient = client ?: session.authedClient().also { client = it }

        /**
         * The whole book file, from the cache or the server. [onProgress] gets 0..1, or null when the size is
         * unknown. The returned format is sniffed from the bytes (the server's content type is unreliable).
         */
        suspend fun book(
            serverBookId: Long,
            serverFileId: Long?,
            expected: BookFormat,
            onProgress: (Float?) -> Unit,
        ): Pair<File, BookFormat> =
            withContext(io) {
                bookLock.withLock {
                    val dir = bookDir(serverBookId)
                    // Keyed by file id, so a replaced server file is fetched again.
                    val target = File(dir, "book-${serverFileId ?: 0}")
                    if (!target.exists()) {
                        val base = session.baseUrl() ?: throw IOException("No server")
                        val part = File(dir, "${target.name}.part")
                        fetch(GrimmoryUrls.content(base, serverBookId), part, limit = null, onProgress)
                        if (!part.renameTo(target)) throw IOException("Could not store the book")
                    }
                    touch(dir)
                    trim(keep = dir)
                    target to (FormatSniffer.sniff(target) ?: expected)
                }
            }

        /** 1-based page numbers of a comic as the server lists them. */
        suspend fun comicPages(serverBookId: Long): List<Int> =
            withContext(io) {
                val api = session.api() ?: throw IOException("No server")
                val pages =
                    try {
                        api.cbxPages(serverBookId)
                    } catch (e: HttpException) {
                        throw IOException("HTTP ${e.code()}", e)
                    }
                val dir = bookDir(serverBookId)
                touch(dir)
                trim(keep = dir)
                pages
            }

        /** One comic page image file, cached. Pages larger than [MAX_PAGE] bytes are refused. */
        suspend fun comicPage(
            serverBookId: Long,
            page: Int,
        ): File =
            withContext(io) {
                val dir = File(bookDir(serverBookId), "pages").apply { mkdirs() }
                val target = File(dir, page.toString())
                if (!target.exists()) {
                    val base = session.baseUrl() ?: throw IOException("No server")
                    val part = File(dir, "$page.part")
                    fetch(GrimmoryUrls.cbxPage(base, serverBookId, page), part, MAX_PAGE) {}
                    if (!part.renameTo(target) && !target.exists()) throw IOException("Could not store the page")
                }
                target
            }

        private fun bookDir(serverBookId: Long): File {
            val serverRowId = session.server.value?.id ?: throw IOException("No server")
            return File(files.streamDir, "$serverRowId/$serverBookId").apply { mkdirs() }
        }

        private suspend fun fetch(
            url: HttpUrl,
            part: File,
            limit: Long?,
            onProgress: (Float?) -> Unit,
        ) {
            try {
                client().newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val total = response.body.contentLength().takeIf { it > 0 }
                    checkLimit(total ?: 0, limit)
                    response.body.byteStream().use { copy(it, part, total, limit, onProgress) }
                }
            } catch (e: IOException) {
                part.delete()
                throw e
            }
        }

        private suspend fun copy(
            input: InputStream,
            part: File,
            total: Long?,
            limit: Long?,
            onProgress: (Float?) -> Unit,
        ) {
            val buffer = ByteArray(BUFFER)
            var written = 0L
            var reported = 0L
            part.outputStream().use { out ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    written += n
                    checkLimit(written, limit)
                    out.write(buffer, 0, n)
                    if (written - reported > REPORT_EVERY) {
                        reported = written
                        onProgress(total?.let { written.toFloat() / it })
                    }
                }
            }
            if (total != null && written != total) throw IOException("Incomplete")
        }

        private fun checkLimit(
            size: Long,
            limit: Long?,
        ) {
            if (limit != null && size > limit) throw IOException("Too large")
        }

        private fun touch(dir: File) {
            dir.setLastModified(System.currentTimeMillis())
        }

        /** Deletes the least recently opened books until the cache fits [MAX_CACHE]; [keep] is never deleted. */
        private fun trim(keep: File?) {
            val books =
                files.streamDir
                    .listFiles()
                    .orEmpty()
                    .flatMap { it.listFiles().orEmpty().toList() }
            val sizes = books.associateWith { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
            var total = sizes.values.sum()
            for (dir in books.sortedBy { it.lastModified() }) {
                if (total <= MAX_CACHE) break
                if (dir == keep) continue
                dir.deleteRecursively()
                total -= sizes.getValue(dir)
            }
        }

        companion object {
            const val MAX_CACHE = 1L shl 30
            const val MAX_PAGE = 64L shl 20
            private const val BUFFER = 64 * 1024
            private const val REPORT_EVERY = 256L * 1024
        }
    }
