package com.c0mpile.grimmreader.core.model

/** Which books a library screen shows (picked in the sidebar). */
sealed interface LibraryScope {
    data object All : LibraryScope

    data object OnDevice : LibraryScope

    /** Server books on no (user) shelf. */
    data object Unshelved : LibraryScope

    data class Server(
        val libraryId: Long,
    ) : LibraryScope

    data class Shelf(
        val shelfId: Long,
    ) : LibraryScope

    data class MagicShelf(
        val shelfId: Long,
    ) : LibraryScope

    /** Stable text form (navigation keys). */
    fun encode(): String =
        when (this) {
            All -> "all"
            OnDevice -> "device"
            Unshelved -> "unshelved"
            is Server -> "library:$libraryId"
            is Shelf -> "shelf:$shelfId"
            is MagicShelf -> "magic:$shelfId"
        }

    companion object {
        fun decode(value: String?): LibraryScope {
            val id = value?.substringAfter(':', "")?.toLongOrNull()
            return when {
                value == "device" -> OnDevice
                value == "unshelved" -> Unshelved
                id != null && value.startsWith("library:") -> Server(id)
                id != null && value.startsWith("shelf:") -> Shelf(id)
                id != null && value.startsWith("magic:") -> MagicShelf(id)
                else -> All
            }
        }
    }
}

enum class BrowseMode { BOOKS, AUTHORS, SERIES }

enum class BookSort { TITLE, AUTHOR, ADDED, RECENT }

/** Cover grid, or a list with a thumbnail and the full title. */
enum class BookLayout { GRID, LIST }

/** The library screens' remembered choices. */
data class LibraryView(
    val sort: BookSort = BookSort.TITLE,
    val layout: BookLayout = BookLayout.GRID,
)
