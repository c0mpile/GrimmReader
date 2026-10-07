package com.c0mpile.grimmreader.core.data.shelf

import android.content.Context
import androidx.room.withTransaction
import com.c0mpile.grimmreader.api.grimmory.GrimmoryApi
import com.c0mpile.grimmreader.api.grimmory.ShelfCreateDto
import com.c0mpile.grimmreader.api.grimmory.ShelvesAssignmentDto
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.data.progress.SyncWorker
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.OutboxDao
import com.c0mpile.grimmreader.core.database.dao.ShelfDao
import com.c0mpile.grimmreader.core.database.entity.BookShelfEntity
import com.c0mpile.grimmreader.core.database.entity.OutboxOpEntity
import com.c0mpile.grimmreader.core.database.entity.ShelfEntity
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.Shelf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server shelves: the list and memberships are mirrored by the library refresh ([ShelfMirror]); adding or
 * removing a book changes Room first and goes to the server through the outbox, so it works offline. Only
 * server books can be shelved; magic shelves are read-only.
 */
@Singleton
class ShelfRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: GrimmDatabase,
        private val shelfDao: ShelfDao,
        private val bookDao: BookDao,
        private val outbox: OutboxDao,
        private val session: ServerSession,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /** User shelves first (server order), then magic shelves; empty without a server. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(): Flow<List<Shelf>> =
            session.server.flatMapLatest { server ->
                if (server == null) {
                    flowOf(emptyList())
                } else {
                    combine(shelfDao.observe(server.id), shelfDao.observeCounts()) { shelves, counts ->
                        val byShelf = counts.associate { (it.shelfId to it.magic) to it.books }
                        val favorites = shelves.firstOrNull { !it.magic && isFavorites(it) }
                        shelves.map { s ->
                            Shelf(s.shelfId, s.name, s.icon, s.magic, byShelf[s.shelfId to s.magic] ?: 0, isFavorites = s == favorites)
                        }
                    }
                }
            }

        /** Puts [bookId] on or off a user shelf. False when the book cannot be shelved (local book). */
        suspend fun setShelved(
            bookId: Long,
            shelfId: Long,
            on: Boolean,
        ): Boolean =
            withContext(io) {
                val serverBookId =
                    bookDao
                        .get(bookId)
                        ?.book
                        ?.takeIf { it.source == BookSource.SERVER }
                        ?.serverBookId
                if (serverBookId == null) return@withContext false
                db.withTransaction {
                    if (on) shelfDao.addMembers(listOf(BookShelfEntity(bookId, shelfId))) else shelfDao.removeMember(bookId, shelfId)
                    outbox.insert(
                        OutboxOpEntity(
                            kind = KIND_ASSIGN,
                            entityId = bookId,
                            payload = json.encodeToString(Change.serializer(), Change(serverBookId, shelfId, on)),
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                SyncWorker.enqueue(context)
                true
            }

        /** Creates a user shelf on the server (needs a connection). */
        suspend fun create(name: String): Result<Shelf> =
            withContext(io) {
                runCatching {
                    val server = session.server.value ?: throw IOException("No server")
                    val api = session.api() ?: throw IOException("No server")
                    val dto = api.createShelf(ShelfCreateDto(name = name.trim()))
                    val entity = ShelfEntity(server.id, dto.id, magic = false, dto.name, dto.icon, shelfDao.nextPosition(server.id))
                    shelfDao.insert(entity)
                    Shelf(dto.id, dto.name, dto.icon, magic = false, bookCount = 0, isFavorites = false)
                }
            }

        /** Pushes one outbox op; true when done (sent, or rejected for good, then undone locally). */
        suspend fun push(
            op: OutboxOpEntity,
            api: GrimmoryApi,
        ): Boolean {
            val change = decode(op.payload) ?: return true
            val body =
                ShelvesAssignmentDto(
                    bookIds = listOf(change.serverBookId),
                    shelvesToAssign = listOfNotNull(change.shelfId.takeIf { change.add }),
                    shelvesToUnassign = listOfNotNull(change.shelfId.takeIf { !change.add }),
                )
            val code = api.assignShelves(body).code()
            return when {
                code in HTTP_OK -> true
                code == HTTP_UNAUTHORIZED || code >= HTTP_SERVER_ERRORS -> false
                else -> {
                    // The server will not take it (shelf gone, not ours): show the server's truth again.
                    if (change.add) shelfDao.removeMember(op.entityId, change.shelfId)
                    true
                }
            }
        }

        @Serializable
        internal data class Change(
            val serverBookId: Long,
            val shelfId: Long,
            val add: Boolean,
        )

        companion object {
            const val KIND_ASSIGN = "shelf_assign"
            private const val HTTP_UNAUTHORIZED = 401
            private const val HTTP_SERVER_ERRORS = 500
            private val HTTP_OK = 200..299
            private val json = Json { ignoreUnknownKeys = true }

            internal fun decode(payload: String): Change? = runCatching { json.decodeFromString(Change.serializer(), payload) }.getOrNull()

            /** The server creates "Favorites" with the heart icon for every user. */
            fun isFavorites(shelf: ShelfEntity) = shelf.icon == "heart" || shelf.name.equals("Favorites", ignoreCase = true)
        }
    }
