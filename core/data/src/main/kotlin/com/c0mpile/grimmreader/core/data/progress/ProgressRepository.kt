package com.c0mpile.grimmreader.core.data.progress

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.c0mpile.grimmreader.api.grimmory.EpubProgressDto
import com.c0mpile.grimmreader.api.grimmory.FileProgressDto
import com.c0mpile.grimmreader.api.grimmory.GrimmoryApi
import com.c0mpile.grimmreader.api.grimmory.PageProgressDto
import com.c0mpile.grimmreader.api.grimmory.ProgressDto
import com.c0mpile.grimmreader.api.grimmory.UpdateProgressDto
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.data.library.parseInstant
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.OutboxDao
import com.c0mpile.grimmreader.core.database.dao.ReadingPositionDao
import com.c0mpile.grimmreader.core.database.entity.OutboxOpEntity
import com.c0mpile.grimmreader.core.database.entity.ReadingPositionEntity
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.Locator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A newer position from another device or the web, offered when a book is opened. */
data class RemotePosition(
    val locator: Locator,
    val lastReadAt: Long,
)

/**
 * Reading positions. Every save lands in Room and, for server books, in the outbox (coalesced, last write
 * wins) in one transaction; [SyncWorker] pushes it when online. Local books never touch the network.
 */
@Singleton
class ProgressRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: GrimmDatabase,
        private val positions: ReadingPositionDao,
        private val outbox: OutboxDao,
        private val bookDao: BookDao,
        private val session: ServerSession,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        suspend fun local(bookId: Long): Locator? = withContext(io) { positions.get(bookId)?.locator?.let(LocatorCodec::decode) }

        suspend fun save(
            bookId: Long,
            locator: Locator,
        ) = withContext(io) {
            val now = System.currentTimeMillis()
            val row = bookDao.get(bookId) ?: return@withContext
            val serverBook = row.book.source == BookSource.SERVER && row.book.serverBookId != null
            db.withTransaction {
                val previous = positions.get(bookId)
                positions.upsert(
                    ReadingPositionEntity(
                        bookId,
                        LocatorCodec.encode(locator),
                        locator.percent,
                        now,
                        previous?.serverSeenAt,
                        dirty = serverBook,
                    ),
                )
                bookDao.updateProgress(bookId, locator.percent, now)
                if (serverBook) {
                    val file = row.files.firstOrNull { it.isPrimary } ?: row.files.firstOrNull()
                    val payload = payload(locator, file?.serverFileId, file?.format)
                    outbox.replace(
                        OutboxOpEntity(
                            kind = KIND_PROGRESS,
                            entityId = bookId,
                            payload = json.encodeToString(Pending.serializer(), Pending(row.book.serverBookId!!, now, payload)),
                            createdAt = now,
                        ),
                    )
                }
            }
            if (serverBook) SyncWorker.enqueue(context)
        }

        /**
         * Resets reading progress: the book is unread again, without a position, and leaves Continue Reading.
         * Bookmarks, highlights and notes are kept. Server books also queue a server reset (sent by [pushResets]);
         * a pending progress upload for the book is dropped first, and a later read is queued after the reset.
         */
        suspend fun reset(bookIds: Collection<Long>) =
            withContext(io) {
                val now = System.currentTimeMillis()
                var queued = false
                db.withTransaction {
                    for (bookId in bookIds) {
                        val book = bookDao.get(bookId)?.book ?: continue
                        positions.delete(bookId)
                        bookDao.resetProgress(bookId)
                        outbox.deleteFor(KIND_PROGRESS, bookId)
                        val serverBookId = book.serverBookId?.takeIf { book.source == BookSource.SERVER } ?: continue
                        outbox.replace(OutboxOpEntity(kind = KIND_RESET, entityId = bookId, payload = "$serverBookId", createdAt = now))
                        queued = true
                    }
                }
                if (queued) SyncWorker.enqueue(context)
            }

        /**
         * Server position if it should be offered on open: the server moved since we last reconciled, our
         * own position is not pending upload, and the two positions differ (CFI or page; percentages differ
         * slightly between screen sizes, so they are not compared).
         */
        suspend fun remoteToOffer(bookId: Long): RemotePosition? =
            withContext(io) {
                val row = bookDao.get(bookId) ?: return@withContext null
                val serverBookId = row.book.serverBookId?.takeIf { row.book.source == BookSource.SERVER } ?: return@withContext null
                // Until the server has the reset, it still holds the old position.
                if (outbox.ofKind(KIND_RESET).any { it.entityId == bookId }) return@withContext null
                val dto = runCatching { session.api()?.progress(serverBookId) }.getOrNull() ?: return@withContext null
                val format = row.files.firstOrNull { it.isPrimary }?.format ?: return@withContext null
                val remote = dto.toLocator(format) ?: return@withContext null
                val lastReadAt = parseInstant(dto.lastReadTime) ?: return@withContext null
                val local = positions.get(bookId)
                decide(local, remote, lastReadAt)
            }

        /** Accepting a remote position makes it the local, already-synced state. */
        suspend fun acceptRemote(
            bookId: Long,
            remote: RemotePosition,
        ) = withContext(io) {
            positions.upsert(
                ReadingPositionEntity(
                    bookId,
                    LocatorCodec.encode(remote.locator),
                    remote.locator.percent,
                    remote.lastReadAt,
                    remote.lastReadAt,
                ),
            )
            bookDao.updateProgress(bookId, remote.locator.percent, remote.lastReadAt)
        }

        @kotlinx.serialization.Serializable
        internal data class Pending(
            val serverBookId: Long,
            val localUpdatedAt: Long,
            val body: UpdateProgressDto,
        )

        companion object {
            const val KIND_PROGRESS = "progress"
            const val KIND_RESET = "progress_reset"
            internal val json = Json { ignoreUnknownKeys = true }

            /** The server takes at most this many ids per reset request. */
            internal const val RESET_CHUNK = 500

            /**
             * Sends queued resets: in one request per [RESET_CHUNK] books when [bulk] (the user may bulk-reset),
             * else one request per book. A rejected bulk request (a stale permission, a book gone from the server)
             * falls back to single requests; a single one rejected with a client error is dropped. Returns the ops
             * that are done; fewer than given means the rest must be retried (auth or server error).
             */
            internal suspend fun pushResets(
                ops: List<OutboxOpEntity>,
                api: GrimmoryApi,
                bulk: Boolean,
            ): List<OutboxOpEntity> {
                val (valid, broken) = ops.partition { it.payload.toLongOrNull() != null }
                val done = broken.toMutableList()
                for (chunk in if (bulk) valid.chunked(RESET_CHUNK) else valid.map(::listOf)) {
                    if (chunk.size > 1) {
                        val response = api.resetProgress(chunk.map { it.payload.toLong() })
                        if (response.isSuccessful) {
                            done += chunk
                            continue
                        }
                        if (!response.isClientError()) return done
                    }
                    for (op in chunk) {
                        val response = api.resetProgress(listOf(op.payload.toLong()))
                        if (!response.isSuccessful && !response.isClientError()) return done
                        done += op
                    }
                }
                return done
            }

            /** A 4xx that would fail the same way again; 401 is not one (the token is refreshed and it is retried). */
            private fun retrofit2.Response<*>.isClientError() = code() != HTTP_UNAUTHORIZED && code() in HTTP_CLIENT_ERRORS

            private const val HTTP_UNAUTHORIZED = 401
            private val HTTP_CLIENT_ERRORS = 400..499

            internal fun decide(
                local: ReadingPositionEntity?,
                remote: Locator,
                remoteLastReadAt: Long,
            ): RemotePosition? {
                if (local == null) return RemotePosition(remote, remoteLastReadAt)
                if (local.dirty) return null
                val seenAt = local.serverSeenAt
                if (seenAt != null && remoteLastReadAt <= seenAt) return null
                val current = LocatorCodec.decode(local.locator) ?: return RemotePosition(remote, remoteLastReadAt)
                return if (samePosition(current, remote)) null else RemotePosition(remote, remoteLastReadAt)
            }

            internal fun samePosition(
                a: Locator,
                b: Locator,
            ): Boolean =
                when {
                    a is Locator.Epub && b is Locator.Epub -> a.cfi == b.cfi
                    a is Locator.Page && b is Locator.Page -> a.page == b.page
                    else -> false
                }

            /** The web sends the per-format object and `fileProgress` together (Spike b). */
            internal fun payload(
                locator: Locator,
                serverFileId: Long?,
                format: BookFormat?,
            ): UpdateProgressDto =
                when (locator) {
                    is Locator.Epub ->
                        UpdateProgressDto(
                            fileProgress =
                                serverFileId?.let {
                                    FileProgressDto(it, locator.cfi, locator.href, locator.percent, locator.contentSourcePercent)
                                },
                            epubProgress = EpubProgressDto(locator.cfi, locator.href, locator.percent, locator.contentSourcePercent),
                        )
                    is Locator.Page -> {
                        val page = PageProgressDto(locator.page, locator.percent)
                        UpdateProgressDto(
                            fileProgress = serverFileId?.let { FileProgressDto(it, locator.page.toString(), null, locator.percent) },
                            pdfProgress = page.takeIf { format == BookFormat.PDF },
                            cbxProgress = page.takeIf { format != BookFormat.PDF },
                        )
                    }
                }

            internal fun ProgressDto.toLocator(format: BookFormat): Locator? =
                when {
                    format.isReflowable -> epubProgress?.cfi?.let { Locator.Epub(it, epubProgress?.href, epubProgress?.percentage ?: 0f) }
                    format == BookFormat.PDF -> pdfProgress?.page?.let { Locator.Page(it, pageCount(it, pdfProgress?.percentage)) }
                    else -> cbxProgress?.page?.let { Locator.Page(it, pageCount(it, cbxProgress?.percentage)) }
                }

            /** The server stores page + percentage only; recover the page count for the [Locator]. */
            private fun pageCount(
                page: Int,
                percent: Float?,
            ): Int = if (percent == null || percent <= 0f) page else Math.round(page * PERCENT / percent).coerceAtLeast(page)

            private const val PERCENT = 100f
        }
    }

/** Builds the WorkManager request for [SyncWorker]: unique, network-constrained, exponential backoff. */
internal fun syncRequest() =
    OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
        .build()

internal fun enqueueSync(context: Context) =
    WorkManager.getInstance(context).enqueueUniqueWork(SyncWorker.NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, syncRequest())

private const val BACKOFF_SECONDS = 30L
