package com.c0mpile.grimmreader.core.data.progress

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.c0mpile.grimmreader.api.grimmory.GrimmoryApi
import com.c0mpile.grimmreader.core.data.bookmark.BookmarkRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.data.shelf.ShelfRepository
import com.c0mpile.grimmreader.core.database.dao.OutboxDao
import com.c0mpile.grimmreader.core.database.dao.ReadingPositionDao
import com.c0mpile.grimmreader.core.database.entity.OutboxOpEntity
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
        private val bookmarks: BookmarkRepository,
        private val shelves: ShelfRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val api = session.api() ?: return Result.success()
            val ops = outbox.due(System.currentTimeMillis())
            val sent = HashSet<Long>()
            for (op in ops) {
                if (op.id in sent) continue
                // Every queued reset goes together (in bulk when allowed). Moving them ahead is safe: a reset drops
                // its book's older progress op, so any progress op for the same book comes after it anyway.
                val batch =
                    if (op.kind == ProgressRepository.KIND_RESET) ops.filter { it.kind == op.kind && it.id !in sent } else listOf(op)
                val done =
                    try {
                        when (op.kind) {
                            ProgressRepository.KIND_PROGRESS -> batch.takeIf { pushProgress(op, api) }.orEmpty()
                            ProgressRepository.KIND_RESET ->
                                ProgressRepository.pushResets(batch, api, session.permissions.value.canBulkResetProgress)
                            ShelfRepository.KIND_ASSIGN -> batch.takeIf { shelves.push(op, api) }.orEmpty()
                            else -> batch.takeIf { bookmarks.push(op, api) }.orEmpty()
                        }
                    } catch (_: IOException) {
                        session.reportOffline(true)
                        return Result.retry()
                    } catch (_: HttpException) {
                        return Result.retry()
                    }
                done.forEach {
                    outbox.delete(it.id)
                    sent += it.id
                }
                if (done.size < batch.size) return Result.retry()
            }
            session.reportOffline(false)
            return Result.success()
        }

        /** True when the op is done: sent, or a client error that would fail the same way again. */
        private suspend fun pushProgress(
            op: OutboxOpEntity,
            api: GrimmoryApi,
        ): Boolean {
            val pending =
                runCatching {
                    ProgressRepository.json.decodeFromString(ProgressRepository.Pending.serializer(), op.payload)
                }.getOrNull() ?: return true
            val response = api.updateProgress(pending.serverBookId, pending.body)
            return when {
                response.isSuccessful -> {
                    positions.markSynced(op.entityId, System.currentTimeMillis(), pending.localUpdatedAt)
                    true
                }
                response.code() == HTTP_UNAUTHORIZED -> false
                else -> response.code() in CLIENT_ERRORS
            }
        }

        companion object {
            const val NAME = "grimm-sync"
            private const val HTTP_UNAUTHORIZED = 401
            private val CLIENT_ERRORS = 400..499

            fun enqueue(context: Context) = enqueueSync(context)
        }
    }
