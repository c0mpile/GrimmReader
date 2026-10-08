package com.c0mpile.grimmreader.core.data.library

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Fills Coil's disk cache with the server thumbnail of every book in the library, so scrolling a large library
 * reads covers from disk instead of waiting for the server cell by cell. Covers already cached are skipped
 * without a request, so a run after the first only fetches new or changed covers (the URL carries the cover's
 * version). One request about every [PACE_MS] (a background job must not crowd the server; covers on screen
 * still load at once), through the app's image loader and so the single guarded client; only on an unmetered
 * network. Stops and retries later after [MAX_FAILURES] failures in a row.
 */
@HiltWorker
class CoverPrefetchWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val library: LibraryRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val loader = SingletonImageLoader.get(applicationContext)
            val disk = loader.diskCache ?: return Result.success()
            var failures = 0
            for (url in library.serverCoverUrls()) {
                if (isStopped) break
                if (disk.openSnapshot(url)?.use { true } == true) continue
                val request =
                    ImageRequest
                        .Builder(applicationContext)
                        .data(url)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        // Only the download is wanted; decoding at a tiny size keeps the work cheap.
                        .size(DECODE_SIZE_PX)
                        .build()
                val started = System.currentTimeMillis()
                val result = loader.execute(request)
                delay(PACE_MS - (System.currentTimeMillis() - started))
                if (result is ErrorResult) {
                    if (++failures >= MAX_FAILURES) return Result.retry()
                } else {
                    failures = 0
                }
            }
            return Result.success()
        }

        companion object {
            const val NAME = "grimm-cover-prefetch"
            private const val MAX_FAILURES = 5
            private const val DECODE_SIZE_PX = 8
            private const val PACE_MS = 1_000L
            private const val BACKOFF_MINUTES = 15L

            /** Starts a run unless one is already queued or running; a queued run reads the library when it starts. */
            fun enqueue(context: Context) {
                val request =
                    OneTimeWorkRequestBuilder<CoverPrefetchWorker>()
                        .setConstraints(
                            Constraints
                                .Builder()
                                .setRequiredNetworkType(NetworkType.UNMETERED)
                                .setRequiresBatteryNotLow(true)
                                .build(),
                        ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                        .build()
                WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
            }

            fun cancel(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(NAME)
            }
        }
    }
