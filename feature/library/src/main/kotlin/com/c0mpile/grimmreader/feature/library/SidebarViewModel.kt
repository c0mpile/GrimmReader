package com.c0mpile.grimmreader.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.download.DownloadRepository
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.library.LocalLibraryRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.data.shelf.ShelfRepository
import com.c0mpile.grimmreader.core.database.entity.DownloadState
import com.c0mpile.grimmreader.core.model.BrowseMode
import com.c0mpile.grimmreader.core.model.LibraryFilter
import com.c0mpile.grimmreader.core.model.LibraryScope
import com.c0mpile.grimmreader.core.model.LocalLibrary
import com.c0mpile.grimmreader.core.model.Shelf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Where the sidebar can take the user. */
sealed interface SidebarDestination {
    data object Dashboard : SidebarDestination

    data object Search : SidebarDestination

    data class Browse(
        val scope: LibraryScope,
        val mode: BrowseMode = BrowseMode.BOOKS,
    ) : SidebarDestination

    data object Notebook : SidebarDestination

    data object Downloads : SidebarDestination

    data object Settings : SidebarDestination
}

data class SidebarState(
    val hasServer: Boolean = false,
    val userName: String? = null,
    val allBooks: Int = 0,
    val series: Int = 0,
    val comicSeries: Int = 0,
    val authors: Int = 0,
    /** The libraries (one per server library, plus the user's own) with the books in each, as its All view shows them. */
    val localLibraries: List<Pair<LocalLibrary, Int>> = emptyList(),
    /** On-device books in no library. */
    val unsorted: Int = 0,
    val unshelved: Int = 0,
    val shelves: List<Shelf> = emptyList(),
    /** Downloads running or waiting; the Downloads entry shows while there is a server or any download. */
    val activeDownloads: Int = 0,
    val hasDownloads: Boolean = false,
)

@HiltViewModel
class SidebarViewModel
    @Inject
    constructor(
        library: LibraryRepository,
        session: ServerSession,
        downloads: DownloadRepository,
        private val shelfRepo: ShelfRepository,
        private val localLibraries: LocalLibraryRepository,
    ) : ViewModel() {
        val state: StateFlow<SidebarState> =
            combine(
                library.observeLibrary(),
                combine(shelfRepo.observe(), localLibraries.libraries, ::Pair),
                session.server,
                downloads.observe(),
            ) { books, (shelves, local), server, queue ->
                val comics = local.filter { it.isComics }.map { it.id }.toSet()
                SidebarState(
                    hasServer = server != null,
                    userName = server?.username,
                    allBooks = books.size,
                    series = groupBySeries(books, comics).size,
                    comicSeries = groupByComicSeries(books, comics).size,
                    authors = groupByAuthor(books).size,
                    localLibraries =
                        local.map { lib ->
                            lib to books.inLibrary(lib, LibraryFilter.ALL, server != null, local.mapNotNull { it.folderUri }).size
                        },
                    unsorted = books.inScope(LibraryScope.Unsorted).size,
                    unshelved = books.inScope(LibraryScope.Unshelved).size,
                    shelves = shelves,
                    activeDownloads = queue.count { it.state in ACTIVE },
                    hasDownloads = queue.isNotEmpty(),
                )
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SidebarState())

        /** Adds an on-device library of the user's own. */
        suspend fun createLibrary(name: String) {
            localLibraries.create(name)
        }

        /** Creates a shelf on the server; the error message on failure. */
        suspend fun createShelf(name: String): String? =
            shelfRepo.create(name).fold(onSuccess = { null }, onFailure = { "Could not create the shelf. Check the connection." })

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            val ACTIVE = setOf(DownloadState.QUEUED, DownloadState.RUNNING, DownloadState.PAUSED)
        }
    }
