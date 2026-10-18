package com.c0mpile.grimmreader.core.data.library

import com.c0mpile.grimmreader.api.grimmory.BookSummaryDto
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.BookFileDao
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity

private const val BYTES_PER_KB = 1024L
private const val DELETE_CHUNK = 500

/** Writes batches of the server's book list into Room (a refresh calls it inside a transaction). */
internal class LibraryMirror(
    private val bookDao: BookDao,
    private val fileDao: BookFileDao,
) {
    suspend fun books(
        serverRowId: Long,
        localIds: Map<Long, Long>,
        resetting: Set<Long>,
        books: List<BookSummaryDto>,
        seen: MutableSet<Long>,
    ) {
        for (dto in books) {
            val format = formatOf(dto.primaryFileType, dto.primaryFileName) ?: continue
            val existing = bookDao.byServerId(serverRowId, dto.id)
            val entity = dto.toEntity(serverRowId, existing, localIds[dto.libraryId])
            val bookId =
                bookDao
                    .upsert(if (existing?.id in resetting) entity.withoutProgress() else entity)
                    .takeIf { it > 0 } ?: existing!!.id
            val file = fileDao.forBook(bookId).firstOrNull { it.isPrimary }
            fileDao.upsert(
                (file ?: BookFileEntity(bookId = bookId, format = format)).copy(
                    serverFileId = dto.primaryFileId,
                    // The server's size (in KB) until the file is here; then the exact size of the copy.
                    sizeBytes = if (file?.localUri != null) file.sizeBytes else dto.fileSizeKb?.let { it * BYTES_PER_KB },
                    format = if (file?.localUri != null) file.format else format,
                ),
            )
            seen += dto.id
        }
    }

    /** Drops server rows the server no longer lists (downloaded ones stay). */
    suspend fun removeGone(
        serverRowId: Long,
        seen: Set<Long>,
    ) {
        // One NOT IN (...) with a variable per book fails past SQLite's 32766 variables (a 40,000-book library).
        bookDao.serverBookIds(serverRowId).filterNot { it in seen }.chunked(DELETE_CHUNK).forEach {
            bookDao.deleteServerBooksIn(serverRowId, it)
        }
    }
}
