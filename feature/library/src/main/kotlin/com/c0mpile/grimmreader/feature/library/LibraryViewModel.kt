package com.c0mpile.grimmreader.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.data.shelf.ShelfRepository
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookLayout
import com.c0mpile.grimmreader.core.model.BookSort
import com.c0mpile.grimmreader.core.model.BrowseMode
import com.c0mpile.grimmreader.core.model.Library
import com.c0mpile.grimmreader.core.model.LibraryScope
import com.c0mpile.grimmreader.core.model.LibraryView
import com.c0mpile.grimmreader.core.model.ServerStatus
import com.c0mpile.grimmreader.core.model.Shelf
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val scope: LibraryScope = LibraryScope.All,
    val mode: BrowseMode = BrowseMode.BOOKS,
    val view: LibraryView = LibraryView(),
    val libraries: List<Library> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    val books: List<Book> = emptyList(),
    val groups: List<BookGroup> = emptyList(),
    val query: String = "",
    val hasServer: Boolean = false,
    val status: ServerStatus = ServerStatus.ONLINE,
    /** A refresh the user asked for (pull to refresh): shows the pull indicator. */
    val refreshing: Boolean = false,
    /** Any refresh in progress, including the automatic one: a thin bar that never blocks the list. */
    val syncing: Boolean = false,
    val message: String? = null,
)

/** One library screen: [scope] (all, a library, a shelf, on this device…) shown as books, authors or series. */
@HiltViewModel(assistedFactory = LibraryViewModel.Factory::class)
class LibraryViewModel
    @AssistedInject
    constructor(
        @Assisted private val scope: LibraryScope,
        @Assisted private val mode: BrowseMode,
        private val library: LibraryRepository,
        private val session: ServerSession,
        shelves: ShelfRepository,
        private val prefs: AppPreferences,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val sync = LibrarySync(viewModelScope, library, session)

        val state: StateFlow<LibraryUiState> =
            combine(
                library.observeLibrary(),
                combine(library.observeLibraries(), shelves.observe()) { libraries, shelfList -> libraries to shelfList },
                prefs.libraryView,
                combine(session.server, session.status) { server, status -> (server != null) to status },
                combine(query, sync.flags) { q, flags -> q to flags },
            ) { all, (libraries, shelfList), view, (hasServer, status), (q, ui) ->
                val inScope = all.inScope(scope)
                LibraryUiState(
                    scope = scope,
                    mode = mode,
                    view = view,
                    libraries = libraries,
                    shelves = shelfList,
                    books = if (mode == BrowseMode.BOOKS) inScope.matching(q).sortedFor(view.sort) else emptyList(),
                    groups =
                        when (mode) {
                            BrowseMode.BOOKS -> emptyList()
                            BrowseMode.AUTHORS -> groupByAuthor(inScope).matchingName(q)
                            BrowseMode.SERIES -> groupBySeries(inScope).matchingName(q)
                        },
                    query = q,
                    hasServer = hasServer,
                    status = status,
                    refreshing = ui.refreshing,
                    syncing = ui.syncing,
                    message = ui.message,
                )
            }.flowOn(Dispatchers.Default) // Mapping, sorting and grouping the whole library stays off the main thread.
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState(scope = scope, mode = mode))

        fun setSort(sort: BookSort) = updateView { it.copy(sort = sort) }

        fun setLayout(layout: BookLayout) = updateView { it.copy(layout = layout) }

        fun setQuery(text: String) {
            query.value = text
        }

        private fun updateView(change: (LibraryView) -> LibraryView) =
            viewModelScope.launch { prefs.setLibraryView(change(prefs.libraryView.first())) }

        fun refresh() = sync.refresh()

        fun import(uris: List<Uri>) = sync.import(uris)

        fun messageShown() = sync.messageShown()

        @AssistedFactory
        interface Factory {
            fun create(
                scope: LibraryScope,
                mode: BrowseMode,
            ): LibraryViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
