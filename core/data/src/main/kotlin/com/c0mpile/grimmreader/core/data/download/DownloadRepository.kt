package com.c0mpile.grimmreader.core.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.c0mpile.grimmreader.core.database.dao.DownloadDao
import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloads: DownloadDao,
    ) {
        fun observe(): Flow<List<DownloadEntity>> = downloads.observeAll()

        /** Queues a download; at most [MAX_PARALLEL] run at once (WorkManager unique work per file). */
        fun enqueue(
            bookFileId: Long,
            wifiOnly: Boolean = false,
        ) {
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
            WorkManager.getInstance(context).enqueueUniqueWork(name(bookFileId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(bookFileId: Long) = WorkManager.getInstance(context).cancelUniqueWork(name(bookFileId))

        private fun name(bookFileId: Long) = "download-$bookFileId"

        companion object {
            const val TAG = "grimm-download"
            const val MAX_PARALLEL = 2
            private const val BACKOFF_SECONDS = 20L
        }
    }
