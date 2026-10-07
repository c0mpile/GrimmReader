package com.c0mpile.grimmreader.core.model

/** Which books the library screen shows. */
sealed interface LibraryScope {
    data object All : LibraryScope

    data object OnDevice : LibraryScope

    data class Server(
        val libraryId: Long,
    ) : LibraryScope

    /** Stable text form for preferences. */
    fun encode(): String =
        when (this) {
            All -> "all"
            OnDevice -> "device"
            is Server -> "library:$libraryId"
        }

    companion object {
        fun decode(value: String?): LibraryScope =
            when {
                value == "device" -> OnDevice
                value?.startsWith("library:") == true -> value.removePrefix("library:").toLongOrNull()?.let(::Server) ?: All
                else -> All
            }
    }
}

enum class BrowseMode { BOOKS, AUTHORS, SERIES }

enum class BookSort { TITLE, AUTHOR, ADDED, RECENT }

/** Cover grid, or a list with a thumbnail and the full title. */
enum class BookLayout { GRID, LIST }

/** The library screen's remembered choices. */
data class LibraryView(
    val scope: LibraryScope = LibraryScope.All,
    val mode: BrowseMode = BrowseMode.BOOKS,
    val sort: BookSort = BookSort.TITLE,
    val layout: BookLayout = BookLayout.GRID,
)
