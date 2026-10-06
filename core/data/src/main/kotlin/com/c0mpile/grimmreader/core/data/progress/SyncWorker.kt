package com.c0mpile.grimmreader.core.data.progress

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.dao.OutboxDao
import com.c0mpile.grimmreader.core.database.dao.ReadingPositionDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException
import java.io.IOException

/** Drains the outbox in order. Never scheduled without a server; 4xx other than auth drops the op. */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val outbox: OutboxDao,
        private val positions: ReadingPositionDao,
        private val session: ServerSession,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val api = session.api() ?: return Result.success()
            for (op in outbox.due(System.currentTimeMillis())) {
                if (op.kind != ProgressRepository.KIND_PROGRESS) continue
                val pending =
                    runCatching {
                        ProgressRepository.json.decodeFromString(
                            ProgressRepository.Pending.serializer(),
                            op.payload,
                        )
                    }.getOrNull()
                if (pending == null) {
                    outbox.delete(op.id)
                    continue
                }
                try {
                    val response = api.updateProgress(pending.serverBookId, pending.body)
                    when {
                        response.isSuccessful -> {
                            outbox.delete(op.id)
                            positions.markSynced(op.entityId, System.currentTimeMillis(), pending.localUpdatedAt)
                        }
                        response.code() == HTTP_UNAUTHORIZED -> return Result.retry()
                        response.code() in CLIENT_ERRORS -> outbox.delete(op.id)
                        else -> return Result.retry()
                    }
                } catch (_: IOException) {
                    session.reportOffline(true)
                    return Result.retry()
                } catch (_: HttpException) {
                    return Result.retry()
                }
            }
            session.reportOffline(false)
            return Result.success()
        }

        companion object {
            const val NAME = "grimm-sync"
            private const val HTTP_UNAUTHORIZED = 401
            private val CLIENT_ERRORS = 400..499

            fun enqueue(context: Context) = enqueueSync(context)
        }
    }
