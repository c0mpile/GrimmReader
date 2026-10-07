package com.c0mpile.grimmreader.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.files.UnsupportedFormatException
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.ServerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

enum class SourceFilter { ALL, SERVER, LOCAL }

data class LibraryUiState(
    val books: List<Book> = emptyList(),
    val filter: SourceFilter = SourceFilter.ALL,
    val hasServer: Boolean = false,
    val status: ServerStatus = ServerStatus.ONLINE,
    val refreshing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val library: LibraryRepository,
        private val session: ServerSession,
    ) : ViewModel() {
        private val local = MutableStateFlow(LibraryUiState())

        val state: StateFlow<LibraryUiState> =
            combine(library.observeLibrary(), session.server, session.status, local) { books, server, status, ui ->
                ui.copy(
                    books =
                        books.filter {
                            when (ui.filter) {
                                SourceFilter.ALL -> true
                                SourceFilter.SERVER -> it.source == BookSource.SERVER
                                SourceFilter.LOCAL -> it.source != BookSource.SERVER
                            }
                        },
                    hasServer = server != null,
                    status = status,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

        init {
            refresh()
        }

        fun setFilter(filter: SourceFilter) = local.update { it.copy(filter = filter) }

        fun refresh() {
            if (session.server.value == null && local.value.refreshing) return
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
