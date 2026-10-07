package com.c0mpile.grimmreader.core.data.bookmark

import android.content.Context
import androidx.room.withTransaction
import com.c0mpile.grimmreader.api.grimmory.BookmarkDto
import com.c0mpile.grimmreader.api.grimmory.CreateBookmarkDto
import com.c0mpile.grimmreader.api.grimmory.GrimmoryApi
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.data.progress.SyncWorker
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.BookmarkDao
import com.c0mpile.grimmreader.core.database.dao.OutboxDao
import com.c0mpile.grimmreader.core.database.entity.BookmarkEntity
import com.c0mpile.grimmreader.core.database.entity.OutboxOpEntity
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.Bookmark
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bookmarks, stored like the web does so both see the same ones: ebooks by CFI, comics by page number as text
 * in `cfi`, PDFs by `pageNumber`. Every change lands in Room (and, for server books, in the outbox) first;
 * [SyncWorker] pushes it. Opening a server book pulls its list ([pull]), so bookmarks made elsewhere appear.
 */
@Singleton
class BookmarkRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: GrimmDatabase,
        private val bookmarks: BookmarkDao,
        private val outbox: OutboxDao,
        private val bookDao: BookDao,
        private val session: ServerSession,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /** In reading order: by page, else by position, else oldest first. */
        fun observe(bookId: Long): Flow<List<Bookmark>> =
            bookmarks.observe(bookId).map { rows ->
                rows
                    .sortedWith(
                        compareBy<BookmarkEntity, Int?>(nullsLast()) { it.page }.thenBy(nullsLast()) { it.percent }.thenBy { it.createdAt },
                    ).map { Bookmark(it.id, it.cfi, it.page, it.title, it.percent) }
            }

        /** Adds a bookmark at [cfi] (ebooks) or [page] (1-based). */
        suspend fun add(
            bookId: Long,
            cfi: String?,
            page: Int?,
            title: String,
            percent: Float?,
        ): Unit =
            withContext(io) {
                val target = serverTarget(bookId)
                db.withTransaction {
                    val id =
                        bookmarks.insert(
                            BookmarkEntity(bookId = bookId, cfi = cfi, page = page, title = title, percent = percent, createdAt = now()),
                        )
                    if (target != null) {
                        val body = createBody(target.serverBookId, target.format, cfi, page, title)
                        outbox.insert(op(KIND_CREATE, id, json.encodeToString(Create.serializer(), Create(target.serverBookId, body))))
                    }
                }
                if (target != null) SyncWorker.enqueue(context)
            }

        suspend fun remove(id: Long): Unit =
            withContext(io) {
                val row = bookmarks.get(id) ?: return@withContext
                val serverId = row.serverId
                db.withTransaction {
                    if (serverId == null) {
                        // Never reached the server: drop it and its pending upload.
                        outbox.deleteFor(KIND_CREATE, id)
                        bookmarks.delete(id)
                    } else {
                        bookmarks.markDeleted(id)
                        outbox.insert(op(KIND_DELETE, id, json.encodeToString(Delete.serializer(), Delete(serverId))))
                    }
                }
                if (serverId != null) SyncWorker.enqueue(context)
            }

        /** Merges the server's list into Room. Network errors are ignored (the local list stays). */
        suspend fun pull(bookId: Long): Unit =
            withContext(io) {
                val target = serverTarget(bookId) ?: return@withContext
                val api = session.api() ?: return@withContext
                val remote = runCatching { api.bookmarks(target.serverBookId) }.getOrNull() ?: return@withContext
                merge(bookId, target.format, remote)
            }

        /** Applies the server's [remote] list for [bookId] in one transaction. */
        internal suspend fun merge(
            bookId: Long,
            format: BookFormat,
            remote: List<BookmarkDto>,
        ) = db.withTransaction { mergeRows(bookId, format, remote) }

        private suspend fun mergeRows(
            bookId: Long,
            format: BookFormat,
            remote: List<BookmarkDto>,
        ) {
            val local = bookmarks.forBook(bookId)
            val known = local.mapNotNull { it.serverId }.toSet()
            remote.filter { it.id !in known }.forEach { dto -> spotOf(dto, format)?.let { importRemote(bookId, dto, it, local) } }
            val onServer = remote.map { it.id }.toSet()
            local.filter { it.serverId != null && it.serverId !in onServer && !it.deleted }.forEach { bookmarks.delete(it.id) }
        }

        private suspend fun importRemote(
            bookId: Long,
            dto: BookmarkDto,
            spot: Spot,
            local: List<BookmarkEntity>,
        ) {
            // Our own pending upload of the same spot (the POST may have gone through): adopt it.
            val twin = local.firstOrNull { it.serverId == null && !it.deleted && it.cfi == spot.cfi && it.page == spot.page }
            if (twin != null) {
                bookmarks.setServerId(twin.id, dto.id)
                outbox.deleteFor(KIND_CREATE, twin.id)
                return
            }
            val title = dto.title?.ifBlank { null } ?: spot.page?.let { "Page $it" } ?: "Bookmark"
            bookmarks.insert(
                BookmarkEntity(bookId = bookId, serverId = dto.id, cfi = spot.cfi, page = spot.page, title = title, createdAt = now()),
            )
        }

        /** Pushes one outbox op; true when it is done (sent, or pointless to retry). Called by [SyncWorker]. */
        suspend fun push(
            op: OutboxOpEntity,
            api: GrimmoryApi,
        ): Boolean =
            when (op.kind) {
                KIND_CREATE -> pushCreate(op, api)
                KIND_DELETE -> pushDelete(op, api)
                else -> true
            }

        private suspend fun pushCreate(
            op: OutboxOpEntity,
            api: GrimmoryApi,
        ): Boolean {
            val create = runCatching { json.decodeFromString(Create.serializer(), op.payload) }.getOrNull() ?: return true
            val response = api.createBookmark(create.body)
            val serverId =
                when {
                    response.isSuccessful -> response.body()?.id
                    // Already there (made elsewhere, or our earlier attempt went through): use that one.
                    response.code() == HTTP_CONFLICT -> api.bookmarks(create.serverBookId).firstOrNull { it.sameSpot(create.body) }?.id
                    else -> return !retryable(response.code())
                }
            if (serverId == null) return true
            if (bookmarks.get(op.entityId) == null) {
                // Removed locally while the upload was in flight.
                api.deleteBookmark(serverId)
            } else {
                bookmarks.setServerId(op.entityId, serverId)
            }
            return true
        }

        private suspend fun pushDelete(
            op: OutboxOpEntity,
            api: GrimmoryApi,
        ): Boolean {
            val delete = runCatching { json.decodeFromString(Delete.serializer(), op.payload) }.getOrNull()
            val code = delete?.let { api.deleteBookmark(it.serverId).code() }
            if (code != null && code != HTTP_NOT_FOUND && retryable(code)) return false
            bookmarks.delete(op.entityId)
            return true
        }

        private class Target(
            val serverBookId: Long,
            val format: BookFormat,
        )

        private suspend fun serverTarget(bookId: Long): Target? {
            val row = bookDao.get(bookId) ?: return null
            val serverBookId = row.book.serverBookId?.takeIf { row.book.source == BookSource.SERVER } ?: return null
            val format = (row.files.firstOrNull { it.isPrimary } ?: row.files.firstOrNull())?.format ?: return null
            return Target(serverBookId, format)
        }

        @Serializable
        internal data class Create(
            val serverBookId: Long,
            val body: CreateBookmarkDto,
        )

        @Serializable
        internal data class Delete(
            val serverId: Long,
        )

        internal data class Spot(
            val cfi: String?,
            val page: Int?,
        )

        companion object {
            const val KIND_CREATE = "bookmark_create"
            const val KIND_DELETE = "bookmark_delete"
            private const val HTTP_UNAUTHORIZED = 401
            private const val HTTP_NOT_FOUND = 404
            private const val HTTP_CONFLICT = 409
            private const val HTTP_SERVER_ERRORS = 500
            private val json = Json { ignoreUnknownKeys = true }

            private fun now() = System.currentTimeMillis()

            private fun op(
                kind: String,
                entityId: Long,
                payload: String,
            ) = OutboxOpEntity(kind = kind, entityId = entityId, payload = payload, createdAt = now())

            /** Auth problems and server errors are retried; anything else would get the same answer again. */
            private fun retryable(code: Int) = code == HTTP_UNAUTHORIZED || code >= HTTP_SERVER_ERRORS

            /** The request the web reader sends for this format. */
            internal fun createBody(
                serverBookId: Long,
                format: BookFormat,
                cfi: String?,
                page: Int?,
                title: String,
            ): CreateBookmarkDto =
                when {
                    format.isReflowable -> CreateBookmarkDto(bookId = serverBookId, cfi = cfi, title = title)
                    format == BookFormat.PDF ->
                        CreateBookmarkDto(
                            bookId = serverBookId,
                            pageNumber = page,
                            title = title,
                            pdfBookmark = true,
                        )
                    else -> CreateBookmarkDto(bookId = serverBookId, cfi = page?.toString(), title = title)
                }

            /** Where a server bookmark points for this format; null for ones the app cannot show (audiobook, other format). */
            internal fun spotOf(
                dto: BookmarkDto,
                format: BookFormat,
            ): Spot? =
                when {
                    dto.positionMs != null -> null
                    format.isReflowable -> dto.cfi?.takeIf { it.startsWith("epubcfi(") }?.let { Spot(it, null) }
                    format == BookFormat.PDF -> (dto.pageNumber ?: dto.cfi?.toIntOrNull())?.let { Spot(null, it) }
                    else -> (dto.cfi?.toIntOrNull() ?: dto.pageNumber)?.let { Spot(null, it) }
                }?.takeIf { it.page == null || it.page >= 1 }

            private fun BookmarkDto.sameSpot(body: CreateBookmarkDto) =
                if (body.pageNumber != null) pageNumber == body.pageNumber else cfi == body.cfi
        }
    }
