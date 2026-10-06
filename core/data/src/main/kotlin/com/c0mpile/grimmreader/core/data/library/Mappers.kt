package com.c0mpile.grimmreader.core.data.library

import com.c0mpile.grimmreader.api.grimmory.BookSummaryDto
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.BookWithFiles
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookFile
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.ReadStatus
import java.time.Instant

/** Grimmory file types the app can read. Audiobook types map to null and are filtered from the library. */
internal fun formatOf(
    primaryFileType: String?,
    fileName: String?,
): BookFormat? {
    val fromName = fileName?.substringAfterLast('.', "")?.let(BookFormat::fromExtension)
    return when (primaryFileType?.uppercase()) {
        "EPUB" -> BookFormat.EPUB
        "PDF" -> BookFormat.PDF
        "MOBI", "AZW", "AZW3" -> BookFormat.MOBI
        "FB2" -> BookFormat.FB2
        "CBX", "CBZ", "CBR", "CB7" -> fromName?.takeIf { it.isComic } ?: BookFormat.CBZ
        null -> fromName
        else -> null
    }
}

internal fun readStatusOf(value: String?): ReadStatus =
    when (value?.uppercase()) {
        "READ" -> ReadStatus.READ
        "READING", "RE_READING", "PARTIALLY_READ", "PAUSED" -> ReadStatus.READING
        else -> ReadStatus.UNREAD
    }

internal fun parseInstant(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

internal fun BookSummaryDto.toEntity(
    serverRowId: Long,
    existing: BookEntity?,
): BookEntity =
    BookEntity(
        id = existing?.id ?: 0,
        source = BookSource.SERVER,
        serverRowId = serverRowId,
        serverBookId = id,
        serverLibraryId = libraryId,
        title = title?.ifBlank { null } ?: primaryFileName ?: "#$id",
        authors = authors.joinToString(BookEntity.AUTHOR_SEPARATOR),
        seriesName = seriesName,
        seriesNumber = seriesNumber,
        coverUpdatedOn = coverUpdatedOn,
        readStatus = readStatusOf(readStatus),
        progressPercent = readProgress,
        readingDirection = existing?.readingDirection,
        addedAt = parseInstant(addedOn) ?: existing?.addedAt ?: 0,
        lastReadAt = parseInstant(lastReadTime) ?: existing?.lastReadAt,
    )

fun BookWithFiles.toDomain(coverModel: (BookEntity) -> String?): Book =
    Book(
        id = book.id,
        source = book.source,
        title = book.title,
        authors = book.authors.split(BookEntity.AUTHOR_SEPARATOR).filter { it.isNotBlank() },
        serverId = book.serverBookId,
        seriesName = book.seriesName,
        seriesNumber = book.seriesNumber,
        coverUri = coverModel(book),
        readStatus = book.readStatus,
        progressPercent = position?.percent ?: book.progressPercent,
        files = files.map { it.toDomain() },
    )

fun BookFileEntity.toDomain() =
    BookFile(
        id = id,
        bookId = bookId,
        format = format,
        isPrimary = isPrimary,
        serverFileId = serverFileId,
        localUri = localUri,
        sizeBytes = sizeBytes,
        partialMd5 = partialMd5,
    )
