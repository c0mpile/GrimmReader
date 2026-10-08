package com.c0mpile.grimmreader.core.model

/** Where a book came from. Local and OPDS books never sync progress to a Grimmory server. */
enum class BookSource { SERVER, LOCAL, OPDS }

enum class ReadStatus { UNREAD, READING, READ }

enum class ReadingDirection { LTR, RTL }

data class Book(
    val id: Long,
    val source: BookSource,
    val title: String,
    val authors: List<String> = emptyList(),
    val serverId: Long? = null,
    val seriesName: String? = null,
    val seriesNumber: Float? = null,
    val coverUri: String? = null,
    val readStatus: ReadStatus = ReadStatus.UNREAD,
    val progressPercent: Float? = null,
    val files: List<BookFile> = emptyList(),
    val libraryId: Long? = null,
    /** The on-device library the book belongs to (see [LocalLibrary]); null = not sorted into one. */
    val localLibraryId: Long? = null,
    val addedAt: Long = 0,
    val lastReadAt: Long? = null,
    /** Server shelves (user shelves) and magic shelves the book is on, by server shelf id. */
    val shelves: Set<Long> = emptySet(),
    val magicShelves: Set<Long> = emptySet(),
    val subtitle: String? = null,
    /** Plain text summary. */
    val description: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
) {
    val primaryFile: BookFile? get() = files.firstOrNull { it.isPrimary } ?: files.firstOrNull()
}

data class BookFile(
    val id: Long,
    val bookId: Long,
    val format: BookFormat,
    val isPrimary: Boolean = true,
    val serverFileId: Long? = null,
    /** Local file path or content URI once the file is available on the device. */
    val localUri: String? = null,
    val sizeBytes: Long? = null,
    val partialMd5: String? = null,
) {
    val isAvailableOffline: Boolean get() = localUri != null
}

/** A server library. [isComics] when it only allows comic (and PDF) formats, like a typical comics library. */
data class Library(
    val id: Long,
    val name: String,
    val isComics: Boolean,
)

/**
 * A library kept on this device. One is made for every server library (linked by [serverLibraryId], named after
 * it) and others are the user's own. [folderName] is its optional watch folder: books in it are read in place.
 * Server books downloaded from the linked library show up in it too.
 */
data class LocalLibrary(
    val id: Long,
    val name: String,
    val serverLibraryId: Long? = null,
    val folderName: String? = null,
    val folderUri: String? = null,
    /** False once the grant on the watch folder was revoked or its volume is gone. */
    val folderAccessible: Boolean = true,
)

/**
 * A server shelf. [magic] shelves are rule-based (the server decides their books; read-only here).
 * [isFavorites] marks the shelf the server makes for every user ("Favorites", heart icon).
 */
data class Shelf(
    val id: Long,
    val name: String,
    val icon: String?,
    val magic: Boolean,
    val bookCount: Int,
    val isFavorites: Boolean,
)
