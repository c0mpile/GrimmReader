package com.c0mpile.grimmreader.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookLayout
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The books of one author or series, within the library chosen on the library screen. */
@HiltViewModel(assistedFactory = BookGroupViewModel.Factory::class)
class BookGroupViewModel
    @AssistedInject
    constructor(
        @Assisted private val kind: GroupKind,
        @Assisted private val name: String,
        library: LibraryRepository,
        private val prefs: AppPreferences,
    ) : ViewModel() {
        val layout: StateFlow<BookLayout> =
            prefs.libraryView.map { it.layout }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BookLayout.GRID)

        fun setLayout(layout: BookLayout) = viewModelScope.launch { prefs.setLibraryView(prefs.libraryView.first().copy(layout = layout)) }

        val books: StateFlow<List<Book>?> =
            library
                .observeLibrary()
                .map { all ->
                    val groups = if (kind == GroupKind.AUTHOR) groupByAuthor(all) else groupBySeries(all)
                    groups.firstOrNull { it.name == name }?.books.orEmpty()
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        @AssistedFactory
        interface Factory {
            fun create(
                kind: GroupKind,
                name: String,
            ): BookGroupViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
