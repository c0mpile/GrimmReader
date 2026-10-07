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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
    /** A refresh the user asked for (pull to refresh): shows the pull indicator. */
    val refreshing: Boolean = false,
    /** Any refresh in progress, including the automatic one: a thin bar that never blocks the list. */
    val syncing: Boolean = false,
    val message: String? = null,
)

private data class LocalState(
    val query: String = "",
    val refreshing: Boolean = false,
    val syncing: Boolean = false,
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
                    syncing = ui.syncing,
                    message = ui.message,
                )
            }.flowOn(Dispatchers.Default) // Mapping, sorting and grouping the whole library stays off the main thread.
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

        private var sync: Job? = null

        init {
            // The server row loads from Room asynchronously; check once it is there (never in local mode). The
            // repository skips the network when the library was mirrored recently, so returning here is free.
            viewModelScope.launch {
                session.server.filterNotNull().first()
                startSync { library.refreshIfStale() }
            }
        }

        fun setScope(scope: LibraryScope) = updateView { it.copy(scope = scope) }

        fun setMode(mode: BrowseMode) = updateView { it.copy(mode = mode) }

        fun setSort(sort: BookSort) = updateView { it.copy(sort = sort) }

        fun setLayout(layout: BookLayout) = updateView { it.copy(layout = layout) }

        fun setQuery(query: String) = local.update { it.copy(query = query) }

        private fun updateView(change: (LibraryView) -> LibraryView) =
            viewModelScope.launch { prefs.setLibraryView(change(prefs.libraryView.first())) }

        /** Pull to refresh: always asks the server, or joins the refresh already running. */
        fun refresh() {
            if (session.server.value == null || local.value.refreshing) return
            local.update { it.copy(refreshing = true) }
            val running = sync?.takeIf { it.isActive }
            if (running != null) {
                viewModelScope.launch {
                    running.join()
                    local.update { it.copy(refreshing = false) }
                }
            } else {
                startSync { library.refresh() }
            }
        }

        private fun startSync(run: suspend () -> Result<Int>?) {
            local.update { it.copy(syncing = true) }
            sync =
                viewModelScope.launch {
                    try {
                        run()?.onFailure { local.update { s -> s.copy(message = "Could not refresh the library.") } }
                    } finally {
                        local.update { it.copy(syncing = false, refreshing = false) }
                    }
                }
        }

        fun import(uris: List<Uri>) =
            viewModelScope.launch {
                var unsupported = 0
                var failed = 0
                for (uri in uris) {
                    try {
                        library.importLocal(uri)
                    } catch (_: UnsupportedFormatException) {
                        unsupported++
                    } catch (_: IOException) {
                        failed++
                    }
                }
                val message =
                    listOfNotNull(
                        "$unsupported file(s) not supported. Books: EPUB, MOBI/AZW3, FB2, PDF; comics: CBZ only."
                            .takeIf { unsupported > 0 },
                        "$failed file(s) could not be imported.".takeIf { failed > 0 },
                    ).joinToString(" ")
                if (message.isNotEmpty()) local.update { it.copy(message = message) }
            }

        fun messageShown() = local.update { it.copy(message = null) }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
