package com.c0mpile.grimmreader.core.data.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.c0mpile.grimmreader.api.grimmory.GrimmoryUrls
import com.c0mpile.grimmreader.core.data.safeName
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.BookFileDao
import com.c0mpile.grimmreader.core.database.dao.DownloadDao
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import com.c0mpile.grimmreader.core.database.entity.DownloadState
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.files.DocumentStore
import com.c0mpile.grimmreader.core.files.FormatSniffer
import com.c0mpile.grimmreader.core.files.LocalFileStore
import com.c0mpile.grimmreader.core.model.BookFormat
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
            // Rows are created by DownloadRepository.enqueue; this covers work queued before that existed.
            downloads.insert(DownloadEntity(bookFileId = fileId, updatedAt = System.currentTimeMillis()))
            update(fileId, DownloadState.QUEUED, part.length(), null)
            return try {
                slots.withPermit { transfer(file, book, GrimmoryUrls.content(base, serverBookId), target) }
            } catch (e: CancellationException) {
                // Stopped by the system (network lost, constraints): it runs again later, so show it as waiting.
                // Cancelled by the user: DownloadRepository.cancel has removed the row already.
                if (stopReason != WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                    withContext(NonCancellable) { update(fileId, DownloadState.QUEUED, part.length(), null) }
                }
                throw e
            }
        }

        private suspend fun transfer(
            file: BookFileEntity,
            book: BookEntity,
            url: okhttp3.HttpUrl,
            target: File,
        ): Result {
            val fileId = file.id
            val part = File(target.path + ".part")
            val meta = File(target.path + ".part.meta")
            update(fileId, DownloadState.RUNNING, part.length(), null)
            return try {
                fetch(url, part, meta, fileId)
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
         * With a download folder set, moves the finished file there into a folder of its own, "Library/Title -
         * Author/Title - Author.ext" (one book per folder, so metadata files can sit next to it; "Library" is the
         * folder of the on-device library mirroring the book's server library), and returns its URI.
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
            val library = bookDao.localLibraryName(book.id)?.let { safeName(it, "library") }
            val uri =
                downloaded.inputStream().use {
                    documents.createInFolder(
                        folder,
                        library,
                        folderName(book),
                        fileName(book, format),
                        it,
                    )
                }
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
        ) = downloads.update(fileId, state, done, total, error, System.currentTimeMillis())

        companion object {
            /** Downloads queued together (a multi-book selection) run [DownloadRepository.MAX_PARALLEL] at a time. */
            private val slots = Semaphore(DownloadRepository.MAX_PARALLEL)

            /** "Title - First Author.epub", without characters that file systems reject. */
            internal fun fileName(
                book: BookEntity,
                format: BookFormat,
            ): String = "${folderName(book)}.${format.extensions.first()}"

            /** "Title - First Author", the name of the book's own folder in the download folder. */
            internal fun folderName(book: BookEntity): String {
                val author = book.authors.split(BookEntity.AUTHOR_SEPARATOR).firstOrNull { it.isNotBlank() }
                return safeName(listOfNotNull(book.title, author).joinToString(" - "), "book")
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
