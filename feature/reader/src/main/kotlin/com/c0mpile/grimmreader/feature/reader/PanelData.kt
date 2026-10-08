package com.c0mpile.grimmreader.feature.reader

import com.c0mpile.grimmreader.core.data.notebook.NotebookRepository
import com.c0mpile.grimmreader.core.model.NotebookEntry
import com.c0mpile.grimmreader.core.model.NotebookEntryType
import com.c0mpile.grimmreader.reader.ebook.EbookEvent
import com.c0mpile.grimmreader.reader.ebook.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Search in an ebook: hits grouped by chapter, in book order. [progress] is 0..1 while running, null when done. */
data class SearchState(
    val query: String = "",
    val id: Int = 0,
    val groups: List<SearchGroup> = emptyList(),
    val progress: Float? = null,
)

data class SearchGroup(
    val label: String,
    val hits: List<SearchHit>,
)

/** The server notebook's highlights and notes for this book (read-only). */
sealed interface Annotations {
    data object NotLoaded : Annotations

    data object Loading : Annotations

    /** [local]: a book without a server copy; else the server could not be reached. */
    data class Unavailable(
        val local: Boolean,
    ) : Annotations

    data class Loaded(
        val highlights: List<NotebookEntry>,
        val notes: List<NotebookEntry>,
    ) : Annotations
}

/** State of the reader's side panels: book search and the notebook's highlights and notes. */
class PanelData(
    private val scope: CoroutineScope,
    private val notebook: NotebookRepository,
) {
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _annotations = MutableStateFlow<Annotations>(Annotations.NotLoaded)
    val annotations: StateFlow<Annotations> = _annotations.asStateFlow()

    /** Set once the book is known; null for local books. */
    var serverBookId: Long? = null

    /** Starts a search and returns its id, to pass to the page with the query. */
    fun startSearch(query: String): Int {
        val id = _search.value.id + 1
        _search.value = SearchState(query = query, id = id, progress = 0f)
        return id
    }

    fun clearSearch() = _search.update { SearchState(id = it.id + 1) }

    /** Results of an older search are dropped. */
    fun onSearch(event: EbookEvent.Search) =
        _search.update { s ->
            if (event.id != s.id) return@update s
            val groups = if (event.hits.isEmpty()) s.groups else s.groups + SearchGroup(event.label.orEmpty(), event.hits)
            s.copy(groups = groups, progress = if (event.done) null else event.progress ?: s.progress)
        }

    /** Fetches the notebook entries of a server book once (a read); local books have none. */
    fun loadAnnotations() {
        if (_annotations.value != Annotations.NotLoaded) return
        val id = serverBookId
        if (id == null) {
            _annotations.value = Annotations.Unavailable(local = true)
            return
        }
        _annotations.value = Annotations.Loading
        scope.launch {
            _annotations.value =
                notebook.entries(id).fold(
                    onSuccess = { list ->
                        Annotations.Loaded(
                            highlights = list.filter { it.type == NotebookEntryType.HIGHLIGHT },
                            notes = list.filter { it.type == NotebookEntryType.NOTE },
                        )
                    },
                    onFailure = { Annotations.Unavailable(local = false) },
                )
        }
    }
}
