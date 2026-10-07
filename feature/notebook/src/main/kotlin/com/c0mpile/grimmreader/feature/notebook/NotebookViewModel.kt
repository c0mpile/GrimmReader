package com.c0mpile.grimmreader.feature.notebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.notebook.NotebookRepository
import com.c0mpile.grimmreader.core.model.NotebookBook
import com.c0mpile.grimmreader.core.model.NotebookEntry
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Loading, loaded, or failed (offline or the server refused). */
data class Loadable<T>(
    val value: T? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** Books with highlights, notes or bookmarks; searchable on the server. */
@HiltViewModel
class NotebookViewModel
    @Inject
    constructor(
        private val notebook: NotebookRepository,
    ) : ViewModel() {
        private val _books = MutableStateFlow(Loadable<List<NotebookBook>>())
        val books: StateFlow<Loadable<List<NotebookBook>>> = _books.asStateFlow()
        val query = MutableStateFlow("")
        private var load: Job? = null

        init {
            reload()
        }

        fun setQuery(text: String) {
            query.value = text
            reload(debounce = true)
        }

        fun reload(debounce: Boolean = false) {
            load?.cancel()
            load =
                viewModelScope.launch {
                    if (debounce) delay(SEARCH_DEBOUNCE_MS)
                    _books.update { it.copy(loading = true) }
                    notebook
                        .books(query.value)
                        .onSuccess { list -> _books.value = Loadable(list, loading = false) }
                        .onFailure { _books.update { it.copy(loading = false, failed = true) } }
                }
        }

        private companion object {
            const val SEARCH_DEBOUNCE_MS = 400L
        }
    }

/** One book's notebook entries. */
@HiltViewModel(assistedFactory = NotebookBookViewModel.Factory::class)
class NotebookBookViewModel
    @AssistedInject
    constructor(
        @Assisted private val serverBookId: Long,
        private val notebook: NotebookRepository,
    ) : ViewModel() {
        private val _entries = MutableStateFlow(Loadable<List<NotebookEntry>>())
        val entries: StateFlow<Loadable<List<NotebookEntry>>> = _entries.asStateFlow()

        init {
            reload()
        }

        fun reload() =
            viewModelScope.launch {
                _entries.update { it.copy(loading = true) }
                notebook
                    .entries(serverBookId)
                    .onSuccess { list -> _entries.value = Loadable(list, loading = false) }
                    .onFailure { _entries.update { it.copy(loading = false, failed = true) } }
            }

        @AssistedFactory
        interface Factory {
            fun create(serverBookId: Long): NotebookBookViewModel
        }
    }
