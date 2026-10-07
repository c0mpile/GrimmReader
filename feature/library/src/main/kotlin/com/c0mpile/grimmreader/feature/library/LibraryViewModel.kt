package com.c0mpile.grimmreader.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.files.UnsupportedFormatException
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookLayout
import com.c0mpile.grimmreader.core.model.BookSort
import com.c0mpile.grimmreader.core.model.BrowseMode
import com.c0mpile.grimmreader.core.model.Library
import com.c0mpile.grimmreader.core.model.LibraryScope
import com.c0mpile.grimmreader.core.model.LibraryView
import com.c0mpile.grimmreader.core.model.ServerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class LibraryUiState(
    val view: LibraryView = LibraryView(),
    val libraries: List<Library> = emptyList(),
    val books: List<Book> = emptyList(),
    val groups: List<BookGroup> = emptyList(),
    val query: String = "",
    val hasServer: Boolean = false,
    val status: ServerStatus = ServerStatus.ONLINE,
    val refreshing: Boolean = false,
    val message: String? = null,
)

private data class LocalState(
    val query: String = "",
    val refreshing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val library: LibraryRepository,
        private val session: ServerSession,
        private val prefs: AppPreferences,
    ) : ViewModel() {
        private val local = MutableStateFlow(LocalState())

        val state: StateFlow<LibraryUiState> =
            combine(
                library.observeLibrary(),
                library.observeLibraries(),
                prefs.libraryView,
                combine(session.server, session.status) { server, status -> (server != null) to status },
                local,
            ) { all, libraries, view, (hasServer, status), ui ->
                // A library that disappeared (or a server that was removed) falls back to everything.
                val scope =
                    view.scope.takeUnless { s -> s is LibraryScope.Server && (!hasServer || libraries.none { it.id == s.libraryId }) }
                        ?: LibraryScope.All
                val inScope = all.inScope(scope)
                LibraryUiState(
                    view = view.copy(scope = scope),
                    libraries = libraries,
                    books = if (view.mode == BrowseMode.BOOKS) inScope.matching(ui.query).sortedFor(view.sort) else emptyList(),
                    groups =
                        when (view.mode) {
                            BrowseMode.BOOKS -> emptyList()
                            BrowseMode.AUTHORS -> groupByAuthor(inScope).matchingName(ui.query)
                            BrowseMode.SERIES -> groupBySeries(inScope).matchingName(ui.query)
                        },
                    query = ui.query,
                    hasServer = hasServer,
                    status = status,
                    refreshing = ui.refreshing,
                    message = ui.message,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

        init {
            refresh()
        }

        fun setScope(scope: LibraryScope) = updateView { it.copy(scope = scope) }

        fun setMode(mode: BrowseMode) = updateView { it.copy(mode = mode) }

        fun setSort(sort: BookSort) = updateView { it.copy(sort = sort) }

        fun setLayout(layout: BookLayout) = updateView { it.copy(layout = layout) }

        fun setQuery(query: String) = local.update { it.copy(query = query) }

        private fun updateView(change: (LibraryView) -> LibraryView) =
            viewModelScope.launch { prefs.setLibraryView(change(prefs.libraryView.first())) }

        fun refresh() {
            if (local.value.refreshing) return
            if (session.server.value == null) return
            local.update { it.copy(refreshing = true) }
            viewModelScope.launch {
                library.refresh().onFailure { local.update { s -> s.copy(message = "Could not refresh the library.") } }
                local.update { it.copy(refreshing = false) }
            }
        }

        fun import(uris: List<Uri>) =
            viewModelScope.launch {
                var failed = 0
                for (uri in uris) {
                    try {
                        library.importLocal(uri)
                    } catch (_: UnsupportedFormatException) {
                        failed++
                    } catch (_: IOException) {
                        failed++
                    }
                }
                if (failed > 0) local.update { it.copy(message = "$failed file(s) could not be imported.") }
            }

        fun messageShown() = local.update { it.copy(message = null) }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
