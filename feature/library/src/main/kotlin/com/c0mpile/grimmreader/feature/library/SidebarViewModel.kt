package com.c0mpile.grimmreader.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.data.shelf.ShelfRepository
import com.c0mpile.grimmreader.core.model.BrowseMode
import com.c0mpile.grimmreader.core.model.Library
import com.c0mpile.grimmreader.core.model.LibraryScope
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

    data object Settings : SidebarDestination
}

data class SidebarState(
    val hasServer: Boolean = false,
    val userName: String? = null,
    val allBooks: Int = 0,
    val series: Int = 0,
    val authors: Int = 0,
    val libraries: List<Pair<Library, Int>> = emptyList(),
    val onDevice: Int = 0,
    val unshelved: Int = 0,
    val shelves: List<Shelf> = emptyList(),
)

@HiltViewModel
class SidebarViewModel
    @Inject
    constructor(
        library: LibraryRepository,
        session: ServerSession,
        private val shelfRepo: ShelfRepository,
    ) : ViewModel() {
        val state: StateFlow<SidebarState> =
            combine(
                library.observeLibrary(),
                library.observeLibraries(),
                shelfRepo.observe(),
                session.server,
            ) { books, libraries, shelves, server ->
                SidebarState(
                    hasServer = server != null,
                    userName = server?.username,
                    allBooks = books.size,
                    series = groupBySeries(books).size,
                    authors = groupByAuthor(books).size,
                    libraries = libraries.map { it to books.inScope(LibraryScope.Server(it.id)).size },
                    onDevice = books.inScope(LibraryScope.OnDevice).size,
                    unshelved = books.inScope(LibraryScope.Unshelved).size,
                    shelves = shelves,
                )
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SidebarState())

        /** Creates a shelf on the server; the error message on failure. */
        suspend fun createShelf(name: String): String? =
            shelfRepo.create(name).fold(onSuccess = { null }, onFailure = { "Could not create the shelf. Check the connection." })

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
