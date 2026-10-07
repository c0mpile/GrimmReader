package com.c0mpile.grimmreader.core.data.shelf

import com.c0mpile.grimmreader.api.grimmory.GrimmoryApi
import com.c0mpile.grimmreader.api.grimmory.MagicShelfDto
import com.c0mpile.grimmreader.api.grimmory.ShelfDto
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.OutboxDao
import com.c0mpile.grimmreader.core.database.dao.ShelfDao
import com.c0mpile.grimmreader.core.database.entity.BookShelfEntity
import com.c0mpile.grimmreader.core.database.entity.ShelfEntity
import javax.inject.Inject

/** The server's shelves and, per shelf, the server ids of its books (one request per shelf). */
class ShelfSnapshot(
    val shelves: List<ShelfDto>,
    val magic: List<MagicShelfDto>,
    val members: Map<Long, List<Long>>,
    val magicMembers: Map<Long, List<Long>>,
) {
    companion object {
        suspend fun fetch(api: GrimmoryApi): ShelfSnapshot {
            val shelves = api.shelves()
            val magic = api.magicShelves()
            return ShelfSnapshot(
                shelves,
                magic,
                shelves.associate { it.id to api.bookIds(shelfId = it.id) },
                magic.associate { it.id to api.bookIds(magicShelfId = it.id) },
            )
        }
    }
}

/**
 * Writes a [ShelfSnapshot] into Room (call inside the refresh transaction, after the books). Shelf changes
 * still waiting in the outbox are applied again on top, so a refresh never undoes them.
 */
class ShelfMirror
    @Inject
    constructor(
        private val shelfDao: ShelfDao,
        private val bookDao: BookDao,
        private val outbox: OutboxDao,
    ) {
        suspend fun apply(
            serverRowId: Long,
            snapshot: ShelfSnapshot,
        ) {
            shelfDao.deleteForServer(serverRowId)
            shelfDao.insertAll(
                snapshot.shelves.mapIndexed { i, s -> ShelfEntity(serverRowId, s.id, magic = false, s.name, s.icon, i) } +
                    snapshot.magic.mapIndexed { i, s -> ShelfEntity(serverRowId, s.id, magic = true, s.name, s.icon, i) },
            )
            shelfDao.deleteOrphanMembers()

            suspend fun local(serverIds: List<Long>) = serverIds.mapNotNull { bookDao.byServerId(serverRowId, it)?.id }
            snapshot.members.forEach { (shelf, ids) -> shelfDao.replaceMembers(shelf, magic = false, local(ids)) }
            snapshot.magicMembers.forEach { (shelf, ids) -> shelfDao.replaceMembers(shelf, magic = true, local(ids)) }
            for (op in outbox.ofKind(ShelfRepository.KIND_ASSIGN)) {
                val change = ShelfRepository.decode(op.payload) ?: continue
                if (change.add) {
                    shelfDao.addMembers(listOf(BookShelfEntity(op.entityId, change.shelfId)))
                } else {
                    shelfDao.removeMember(op.entityId, change.shelfId)
                }
            }
        }
    }
