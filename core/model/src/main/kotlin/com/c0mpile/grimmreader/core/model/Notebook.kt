package com.c0mpile.grimmreader.core.model

/** A server book with highlights, notes or bookmarks; [localBookId] when it is in the local library. */
data class NotebookBook(
    val serverBookId: Long,
    val localBookId: Long?,
    val title: String,
    val authors: List<String>,
    val entries: Int,
    val coverUri: String?,
)

enum class NotebookEntryType { HIGHLIGHT, NOTE, BOOKMARK, OTHER }

/** One highlight, note or bookmark as the Grimmory notebook shows it. [color] is a `#RRGGBB` string. */
data class NotebookEntry(
    val id: Long,
    val type: NotebookEntryType,
    val text: String?,
    val note: String?,
    val color: String?,
    val chapter: String?,
    val createdAt: Long?,
)
