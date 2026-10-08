package com.c0mpile.grimmreader.core.data.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.c0mpile.grimmreader.api.grimmory.GrimmoryUrls
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.BookFileDao
import com.c0mpile.grimmreader.core.database.dao.DownloadDao
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import com.c0mpile.grimmreader.core.database.entity.DownloadState
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.files.DocumentStore
import com.c0mpile.grimmreader.core.files.FormatSniffer
import com.c0mpile.grimmreader.core.files.LocalFileStore
import com.c0mpile.grimmreader.core.model.BookFormat
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * Downloads one book file to app storage: `<file>.part` with Range resume (`If-Range` on the stored
 * Last-Modified), then sniff, hash and rename. Uses the authenticated guarded client.
 */
@HiltWorker
class DownloadWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val session: ServerSession,
        private val bookDao: BookDao,
        private val fileDao: BookFileDao,
        private val downloads: DownloadDao,
        private val store: LocalFileStore,
        private val documents: DocumentStore,
        private val prefs: AppPreferences,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val fileId = inputData.getLong(KEY_FILE_ID, -1)
            val file = fileDao.get(fileId) ?: return Result.failure()
            val book = bookDao.get(file.bookId)?.book ?: return Result.failure()
            val base = session.baseUrl() ?: return Result.failure()
            val serverRowId = book.serverRowId ?: return Result.failure()
            val serverBookId = book.serverBookId ?: return Result.failure()
            val target = store.downloadTarget(serverRowId, serverBookId, fileId)
            val part = File(target.path + ".part")
            val meta = File(target.path + ".part.meta")
            update(fileId, DownloadState.RUNNING, part.length(), null)
            return try {
                fetch(GrimmoryUrls.content(base, serverBookId), part, meta, fileId)
                val format = FormatSniffer.sniff(part) ?: file.format
                val final = File(target.parentFile, "${target.nameWithoutExtension}.${format.extensions.first()}")
                check(part.renameTo(final)) { "rename failed" }
                meta.delete()
                val size = final.length()
                val md5 = LocalFileStore.partialMd5(final)
                val localUri = moveToFolder(final, book, format) ?: final.absolutePath
                fileDao.upsert(file.copy(format = format, localUri = localUri, sizeBytes = size, partialMd5 = md5))
                update(fileId, DownloadState.DONE, size, size)
                Result.success()
            } catch (e: IOException) {
                update(fileId, DownloadState.FAILED, part.length(), null, e.javaClass.simpleName)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        }

        /**
         * With a download folder set, moves the finished file there as "Title - Author.ext" and returns its URI.
         * Null keeps it in app storage (no folder set). Throws when the folder is set but not writable, so the
         * download is retried rather than silently landing somewhere else.
         */
        private suspend fun moveToFolder(
            downloaded: File,
            book: BookEntity,
            format: BookFormat,
        ): String? {
            val folder = prefs.downloadFolder.first() ?: return null
            if (!documents.hasAccess(folder)) throw IOException("Download folder is not accessible")
            val uri = downloaded.inputStream().use { documents.create(folder, fileName(book, format), it) }
            downloaded.delete()
            return uri
        }

        private suspend fun fetch(
            url: okhttp3.HttpUrl,
            part: File,
            meta: File,
            fileId: Long,
        ) {
            val offset = part.length()
            val validator = meta.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
            val request = Request.Builder().url(url)
            if (offset > 0 && validator != null) request.header("Range", "bytes=$offset-").header("If-Range", validator)
            session.authedClient().newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val resumed = response.code == HTTP_PARTIAL
                response.header("Last-Modified")?.let { meta.writeText(it) }
                val start = if (resumed) offset else 0L
                val total =
                    response.body
                        .contentLength()
                        .takeIf { it >= 0 }
                        ?.plus(start)
                val written = copy(response.body.byteStream(), part, start, total, fileId)
                if (total != null && written != total) throw IOException("Incomplete download")
            }
        }

        /** Streams [input] into [part] from [start], reporting progress; returns the final length. */
        private suspend fun copy(
            input: java.io.InputStream,
            part: File,
            start: Long,
            total: Long?,
            fileId: Long,
        ): Long {
            val out = RandomAccessFile(part, "rw")
            try {
                out.setLength(start)
                out.seek(start)
                return pump(input, out, start, total, fileId)
            } finally {
                out.close()
                input.close()
            }
        }

        private suspend fun pump(
            input: java.io.InputStream,
            out: RandomAccessFile,
            start: Long,
            total: Long?,
            fileId: Long,
        ): Long {
            val buffer = ByteArray(BUFFER)
            var written = start
            var lastReport = start
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) return written
                out.write(buffer, 0, n)
                written += n
                if (written - lastReport > REPORT_EVERY) lastReport = report(fileId, written, total)
            }
        }

        private suspend fun report(
            fileId: Long,
            written: Long,
            total: Long?,
        ): Long {
            update(fileId, DownloadState.RUNNING, written, total)
            setProgress(workDataOf(KEY_DONE to written, KEY_TOTAL to (total ?: -1L)))
            return written
        }

        private suspend fun update(
            fileId: Long,
            state: DownloadState,
            done: Long,
            total: Long?,
            error: String? = null,
        ) {
            val existing = downloads.forFile(fileId)
            downloads.upsert(
                DownloadEntity(
                    id = existing?.id ?: 0,
                    bookFileId = fileId,
                    state = state,
                    bytesDone = done,
                    bytesTotal = total ?: existing?.bytesTotal,
                    error = error,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

        companion object {
            private const val MAX_NAME = 120

            /** "Title - First Author.epub", without characters that file systems reject. */
            internal fun fileName(
                book: BookEntity,
                format: BookFormat,
            ): String {
                val author = book.authors.split(BookEntity.AUTHOR_SEPARATOR).firstOrNull { it.isNotBlank() }
                val base =
                    listOfNotNull(book.title, author)
                        .joinToString(" - ")
                        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
                        .trim()
                        .trimEnd('.')
                        .take(MAX_NAME)
                        .ifEmpty { "book" }
                return "$base.${format.extensions.first()}"
            }

            const val KEY_FILE_ID = "fileId"
            const val KEY_DONE = "done"
            const val KEY_TOTAL = "total"
            private const val HTTP_PARTIAL = 206
            private const val BUFFER = 64 * 1024
            private const val REPORT_EVERY = 512L * 1024
            private const val MAX_ATTEMPTS = 5
        }
    }
