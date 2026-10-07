package com.c0mpile.grimmreader.core.data.library

import com.c0mpile.grimmreader.api.grimmory.BookSummaryDto
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.BookWithFiles
import com.c0mpile.grimmreader.core.database.entity.LibraryEntity
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookFile
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.Library
import com.c0mpile.grimmreader.core.model.ReadStatus
import java.time.Instant

/**
 * Grimmory file types the app can read. Audiobooks and CBR/CB7 comics map to null and are filtered from the
 * library: CBZ is the only comic format. The server reports every comic as CBX, so the file name decides.
 */
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
        "CBX", "CBZ" -> if (fileName?.substringAfterLast('.', "")?.lowercase() in UNSUPPORTED_COMICS) null else BookFormat.CBZ
        null -> fromName
        else -> null
    }
}

private val UNSUPPORTED_COMICS = setOf("cbr", "rar", "cb7", "7z")

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
        libraryId = book.serverLibraryId,
        addedAt = book.addedAt,
        lastReadAt = maxOf(book.lastReadAt ?: 0L, position?.localUpdatedAt ?: 0L).takeIf { it > 0L },
        shelves = shelves.filter { !it.magic }.map { it.shelfId }.toSet(),
        magicShelves = shelves.filter { it.magic }.map { it.shelfId }.toSet(),
    )

private val EBOOK_FORMATS = setOf("EPUB", "MOBI", "AZW3", "FB2")

/** Comic libraries allow CBX and no reflowable ebook format; PDF alone could be either, so it counts as books. */
internal fun isComicLibrary(formats: Collection<String>): Boolean {
    val upper = formats.map { it.uppercase() }
    return "CBX" in upper && upper.none { it in EBOOK_FORMATS }
}

fun LibraryEntity.toDomain() = Library(serverLibraryId, name, isComicLibrary(formats.split(',').filter { it.isNotBlank() }))

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
