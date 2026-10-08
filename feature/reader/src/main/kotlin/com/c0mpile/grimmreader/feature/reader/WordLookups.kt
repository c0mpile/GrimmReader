package com.c0mpile.grimmreader.feature.reader

import com.c0mpile.grimmreader.core.dictionary.Dictionaries
import com.c0mpile.grimmreader.core.dictionary.Lookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The dictionary sheet: [result] is null while [query] is being looked up. */
data class WordLookup(
    val query: String,
    val result: Lookup? = null,
)

/** Looks up long-pressed (or typed) words for the reader's dictionary sheet; lives as long as the reader. */
class WordLookups(
    private val scope: CoroutineScope,
    private val dictionaries: Dictionaries,
) {
    private val _current = MutableStateFlow<WordLookup?>(null)

    /** The word being looked up, or null when the sheet is closed. */
    val current: StateFlow<WordLookup?> = _current.asStateFlow()
    private var job: Job? = null

    fun lookUp(word: String) {
        val query = word.trim().takeIf { it.isNotEmpty() } ?: return
        job?.cancel()
        _current.value = WordLookup(query)
        job =
            scope.launch {
                val result = dictionaries.lookup(query)
                _current.update { if (it?.query == query) it.copy(result = result) else it }
            }
    }

    fun close() {
        job?.cancel()
        _current.value = null
    }
}
