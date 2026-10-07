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
    val addedAt: Long = 0,
    val lastReadAt: Long? = null,
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
