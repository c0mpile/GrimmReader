package com.c0mpile.grimmreader.feature.reader

import com.c0mpile.grimmreader.core.data.highlight.HighlightRepository
import com.c0mpile.grimmreader.core.model.Highlight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Text the user selected in an ebook: [over] are the ids of the highlights it touches. */
data class TextSelection(
    val text: String,
    val cfi: String,
    val over: List<Long> = emptyList(),
)

/** The selection in the page, and turning it into (or removing) highlights; lives as long as the reader. */
class SelectionActions(
    private val scope: CoroutineScope,
    private val bookId: Long,
    private val highlights: HighlightRepository,
    private val say: (String) -> Unit,
) {
    private val _current = MutableStateFlow<TextSelection?>(null)

    /** The selected text, or null when nothing is selected. */
    val current: StateFlow<TextSelection?> = _current.asStateFlow()

    /** The highlights as last seen by the reader (replacing one of exactly the same passage needs them). */
    @Volatile
    var known: List<Highlight> = emptyList()

    fun select(selection: TextSelection) {
        _current.value = selection
    }

    fun clear() {
        _current.value = null
    }

    /** Highlights the selected text in [color] (replacing a highlight of exactly that passage), then drops the selection. */
    fun highlight(
        color: String,
        chapter: String?,
        percent: Float?,
    ) {
        val selection = _current.value ?: return
        _current.value = null
        scope.launch {
            known.filter { it.cfi == selection.cfi }.forEach { highlights.remove(it.id) }
            highlights.add(bookId, selection.cfi, selection.text, color, chapter?.trim(), percent)
            say("Highlight added")
        }
    }

    /** Removes the highlights the selection touches, then drops the selection. */
    fun removeSelected() {
        val ids = _current.value?.over.orEmpty()
        _current.value = null
        scope.launch {
            ids.forEach { highlights.remove(it) }
            if (ids.isNotEmpty()) say("Highlight removed")
        }
    }

    fun remove(highlight: Highlight) {
        scope.launch { highlights.remove(highlight.id) }
    }
}
