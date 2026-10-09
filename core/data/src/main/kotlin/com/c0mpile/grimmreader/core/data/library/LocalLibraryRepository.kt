package com.c0mpile.grimmreader.core.data.library

import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.LocalLibraryDao
import com.c0mpile.grimmreader.core.database.entity.LibraryEntity
import com.c0mpile.grimmreader.core.database.entity.LocalLibraryEntity
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.files.DocumentStore
import com.c0mpile.grimmreader.core.model.LocalLibrary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The libraries kept on this device. Each server library has one here (made when the server is connected and on
 * every refresh), so books downloaded from it land in it; the user can add others and give any of them a watch
 * folder (see [FolderLibrary]). Folder-related changes go through [FolderLibrary], which also handles the books.
 */
@Singleton
class LocalLibraryRepository
    @Inject
    constructor(
        private val dao: LocalLibraryDao,
        private val bookDao: BookDao,
        private val prefs: AppPreferences,
        private val documents: DocumentStore,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        private val adoptLock = Mutex()
        private var adopted = false

        /** Every library, in order. */
        val libraries: Flow<List<LocalLibrary>> =
            flow {
                adoptLegacyFolders()
                emitAll(dao.observe().map { rows -> rows.map { it.toDomain() } })
            }.flowOn(io)

        suspend fun all(): List<LocalLibraryEntity> {
            adoptLegacyFolders()
            return dao.all()
        }

        /** Whether any library mirrors a server library yet (not until the first refresh after an update). */
        suspend fun hasLinked(): Boolean = withContext(io) { dao.all().any { it.serverLibraryId != null } }

        suspend fun get(id: Long): LocalLibraryEntity? = withContext(io) { dao.get(id) }

        /** Only [FolderLibrary] sets folders, as it files the books too. */
        internal suspend fun setFolderUri(
            id: Long,
            folderUri: String?,
        ) = withContext(io) { dao.setFolder(id, folderUri) }

        internal suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }

        suspend fun setComics(
            id: Long,
            isComics: Boolean,
        ) = withContext(io) { dao.setComics(id, isComics) }

        suspend fun create(name: String): Long =
            withContext(io) {
                adoptLegacyFolders()
                dao.insert(LocalLibraryEntity(name = name.trim(), position = dao.nextPosition()))
            }

        /** Libraries mirroring a server library follow its name, so only the others can be renamed. */
        suspend fun rename(
            id: Long,
            name: String,
        ) = withContext(io) {
            if (dao.get(id)?.serverLibraryId == null && name.isNotBlank()) dao.rename(id, name.trim())
        }

        /** Puts a local book into [libraryId] (null = none). Server books follow their server library instead. */
        suspend fun assign(
            bookId: Long,
            libraryId: Long?,
        ) = withContext(io) {
            val row = bookDao.get(bookId)?.book ?: return@withContext
            if (row.serverRowId == null) bookDao.setLocalLibrary(bookId, libraryId)
        }

        /**
         * Mirrors the server's libraries: one local library each (an unlinked one of the same name is linked
         * instead of adding a twin), renamed with the server. A library the server no longer lists is unlinked
         * and kept with its folder and books. Server books follow the local library of their server library.
         * Returns the local library id per server library id.
         */
        suspend fun syncWithServer(
            serverRowId: Long,
            libraries: List<LibraryEntity>,
        ): Map<Long, Long> {
            adoptLegacyFolders()
            val existing = dao.all().toMutableList()
            val ids = mutableMapOf<Long, Long>()
            for (lib in libraries) {
                val linked = existing.firstOrNull { it.serverLibraryId == lib.serverLibraryId }
                val id =
                    when {
                        linked != null -> {
                            if (linked.name != lib.name) dao.rename(linked.id, lib.name)
                            linked.id
                        }
                        else -> {
                            val twin = existing.firstOrNull { it.serverLibraryId == null && it.name.equals(lib.name, ignoreCase = true) }
                            if (twin != null) {
                                dao.link(twin.id, lib.serverLibraryId)
                                dao.rename(twin.id, lib.name)
                                twin.id
                            } else {
                                dao.insert(
                                    LocalLibraryEntity(
                                        name = lib.name,
                                        serverLibraryId = lib.serverLibraryId,
                                        position = dao.nextPosition(),
                                        isComics = lib.toDomain().isComics,
                                    ),
                                )
                            }
                        }
                    }
                ids[lib.serverLibraryId] = id
                bookDao.setLocalLibraryOfServerLibrary(serverRowId, lib.serverLibraryId, id)
            }
            existing
                .mapNotNull { it.serverLibraryId }
                .filter { it !in ids }
                .forEach { dao.unlink(it) }
            return ids
        }

        /** The server is gone: its libraries stay on the device, no longer mirroring anything. */
        suspend fun unlinkServer() = withContext(io) { dao.unlinkAll() }

        /**
         * Book folders saved by earlier builds (one list, no libraries) become libraries named after their
         * folder; books found in them are filed into them by the next scan. Once per process.
         */
        private suspend fun adoptLegacyFolders() {
            if (adopted) return
            adoptLock.withLock {
                if (adopted) return
                withContext(io) {
                    val legacy = prefs.bookFolders.first()
                    if (legacy.isNotEmpty()) {
                        val known = dao.all().mapNotNull { it.folderUri }.toSet()
                        legacy.filter { it !in known }.forEach { tree ->
                            dao.insert(
                                LocalLibraryEntity(name = documents.folderName(tree), folderUri = tree, position = dao.nextPosition()),
                            )
                        }
                        prefs.setBookFolders(emptySet())
                    }
                }
                adopted = true
            }
        }

        private fun LocalLibraryEntity.toDomain() =
            LocalLibrary(
                id = id,
                name = name,
                serverLibraryId = serverLibraryId,
                folderName = folderUri?.let(documents::folderName),
                folderUri = folderUri,
                folderAccessible = folderUri?.let(documents::hasAccess) ?: true,
                isComics = isComics,
            )
    }
