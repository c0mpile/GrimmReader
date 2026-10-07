package com.c0mpile.grimmreader.core.data.library

import android.net.Uri
import androidx.room.withTransaction
import com.c0mpile.grimmreader.api.grimmory.GrimmoryUrls
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.BookFileDao
import com.c0mpile.grimmreader.core.database.dao.LibraryDao
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.LibraryEntity
import com.c0mpile.grimmreader.core.files.BookMetadataReader
import com.c0mpile.grimmreader.core.files.LocalFileStore
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.Library
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The unified library: server books cached in Room plus local books. Room is the source of truth. */
@Singleton
class LibraryRepository
    @Inject
    constructor(
        private val db: GrimmDatabase,
        private val bookDao: BookDao,
        private val fileDao: BookFileDao,
        private val libraryDao: LibraryDao,
        private val session: ServerSession,
        private val files: LocalFileStore,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        fun observeLibrary(libraryId: Long? = null): Flow<List<Book>> =
            bookDao.observeLibrary(libraryId).map { rows -> rows.filter { it.files.isNotEmpty() }.map { it.toDomain(::coverModel) } }

        fun observeBook(id: Long): Flow<Book?> = bookDao.observe(id).map { it?.toDomain(::coverModel) }

        /** The signed-in server's libraries, in server order; empty in local mode. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun observeLibraries(): Flow<List<Library>> =
            session.server.flatMapLatest { server ->
                if (server == null) flowOf(emptyList()) else libraryDao.observe(server.id).map { rows -> rows.map { it.toDomain() } }
            }

        /** Cover for Coil: the local extracted cover, else the server thumbnail (fetched with the authed client). */
        private fun coverModel(book: BookEntity): String? {
            val local = files.coverFile(book.id)
            if (local.exists()) return local.absolutePath
            val base = session.baseUrl() ?: return null
            val serverId = book.serverBookId ?: return null
            if (book.serverRowId == null) return null
            return GrimmoryUrls.thumbnail(base, serverId, book.coverUpdatedOn).toString()
        }

        /**
         * Pulls every page of `/api/v1/app/books` (sequential, [PAGE_SIZE] per request) and mirrors it into Room.
         * Books that disappeared are removed unless downloaded. Audiobook-only items are skipped.
         */
        suspend fun refresh(): Result<Int> =
            withContext(io) {
                val server = session.server.value ?: return@withContext Result.success(0)
                val api = session.api() ?: return@withContext Result.success(0)
                runCatching {
                    val libraries = api.libraries()
                    libraryDao.replaceAll(
                        server.id,
                        libraries.mapIndexed { i, l -> LibraryEntity(server.id, l.id, l.name, l.allowedFormats.joinToString(","), i) },
                    )
                    val seen = mutableListOf<Long>()
                    var page = 0
                    do {
                        val result = api.books(page = page, size = PAGE_SIZE, sort = "title", dir = "asc")
                        db.withTransaction {
                            for (dto in result.content) {
                                val format = formatOf(dto.primaryFileType, dto.primaryFileName) ?: continue
                                val existing = bookDao.byServerId(server.id, dto.id)
                                val bookId = bookDao.upsert(dto.toEntity(server.id, existing)).takeIf { it > 0 } ?: existing!!.id
                                val file = fileDao.forBook(bookId).firstOrNull { it.isPrimary }
                                fileDao.upsert(
                                    (file ?: BookFileEntity(bookId = bookId, format = format)).copy(
                                        serverFileId = dto.primaryFileId,
                                        format = if (file?.localUri != null) file.format else format,
                                    ),
                                )
                                seen += dto.id
                            }
                        }
                        page++
                    } while (result.hasNext)
                    bookDao.deleteServerBooksNotIn(server.id, seen)
                    session.reportOffline(false)
                    seen.size
                }.onFailure { if (it is IOException) session.reportOffline(true) }
            }

        /** Copies a user-picked file into app storage and adds it as a local book. Returns the new book id. */
        suspend fun importLocal(uri: Uri): Long =
            withContext(io) {
                val imported = files.importFrom(uri)
                val meta = BookMetadataReader.read(imported.file, imported.format)
                val title = meta.title ?: imported.displayName.substringBeforeLast('.')
                val bookId =
                    db.withTransaction {
                        val id =
                            bookDao.insert(
                                BookEntity(
                                    source = BookSource.LOCAL,
                                    title = title,
                                    authors = meta.authors.joinToString(BookEntity.AUTHOR_SEPARATOR),
                                    seriesName = meta.series,
                                    seriesNumber = meta.seriesNumber,
                                    readingDirection = meta.readingDirection,
                                    addedAt = System.currentTimeMillis(),
                                ),
                            )
                        fileDao.upsert(
                            BookFileEntity(
                                bookId = id,
                                format = imported.format,
                                localUri = imported.file.absolutePath,
                                sizeBytes = imported.sizeBytes,
                                partialMd5 = imported.partialMd5,
                            ),
                        )
                        id
                    }
                meta.cover?.let { files.saveCover(bookId, it) }
                bookId
            }

        /** Deletes a local book and its file; server books only lose their downloaded copy. */
        suspend fun deleteLocal(bookId: Long) =
            withContext(io) {
                val row = bookDao.get(bookId) ?: return@withContext
                row.files.mapNotNull { it.localUri }.forEach { java.io.File(it).delete() }
                if (row.book.source == BookSource.SERVER) {
                    row.files.forEach { fileDao.setLocal(it.id, null, it.sizeBytes, it.partialMd5) }
                } else {
                    files.coverFile(bookId).delete()
                    bookDao.delete(bookId)
                }
            }

        private companion object {
            const val PAGE_SIZE = 100
        }
    }
