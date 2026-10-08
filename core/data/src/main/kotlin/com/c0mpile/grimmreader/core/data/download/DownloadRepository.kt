package com.c0mpile.grimmreader.core.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.BookFileDao
import com.c0mpile.grimmreader.core.database.dao.DownloadDao
import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import com.c0mpile.grimmreader.core.database.entity.DownloadState
import com.c0mpile.grimmreader.core.database.entity.DownloadWithBook
import com.c0mpile.grimmreader.core.files.LocalFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloads: DownloadDao,
        private val fileDao: BookFileDao,
        private val bookDao: BookDao,
        private val store: LocalFileStore,
    ) {
        fun observe(): Flow<List<DownloadEntity>> = downloads.observeAll()

        /** Every download with its book, in the order they were queued. */
        fun observeQueue(): Flow<List<DownloadWithBook>> = downloads.observeQueue()

        /**
         * Queues a download; at most [MAX_PARALLEL] run at once (WorkManager unique work per file). The row is
         * written first so the queue shows it while it waits for a slot or a network. [restart] replaces a
         * failed attempt that is waiting for its backoff.
         */
        suspend fun enqueue(
            bookFileId: Long,
            wifiOnly: Boolean = false,
            restart: Boolean = false,
        ) {
            val existing = downloads.forFile(bookFileId)
            downloads.upsert(
                DownloadEntity(
                    id = existing?.id ?: 0,
                    bookFileId = bookFileId,
                    state = DownloadState.QUEUED,
                    bytesDone = existing?.bytesDone?.takeIf { existing.state != DownloadState.DONE } ?: 0,
                    bytesTotal = existing?.bytesTotal,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            val request =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(workDataOf(DownloadWorker.KEY_FILE_ID to bookFileId))
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(
                                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                            ).build(),
                    ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .build()
            val policy = if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(name(bookFileId), policy, request)
        }

        /** Tries a failed download again now. */
        suspend fun retry(bookFileId: Long) = enqueue(bookFileId, restart = true)

        /** Stops a download and removes it from the queue together with its partial file. */
        suspend fun cancel(bookFileId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(name(bookFileId))
            downloads.delete(bookFileId)
            val file = fileDao.get(bookFileId) ?: return
            val book = bookDao.get(file.bookId)?.book ?: return
            val serverRowId = book.serverRowId ?: return
            val serverBookId = book.serverBookId ?: return
            val target = store.downloadTarget(serverRowId, serverBookId, bookFileId)
            withContext(Dispatchers.IO) {
                File(target.path + ".part").delete()
                File(target.path + ".part.meta").delete()
            }
        }

        /** Removes finished downloads from the queue; the books stay downloaded. */
        suspend fun clearFinished() = downloads.deleteFinished()

        private fun name(bookFileId: Long) = "download-$bookFileId"

        companion object {
            const val TAG = "grimm-download"
            const val MAX_PARALLEL = 2
            private const val BACKOFF_SECONDS = 20L
        }
    }
