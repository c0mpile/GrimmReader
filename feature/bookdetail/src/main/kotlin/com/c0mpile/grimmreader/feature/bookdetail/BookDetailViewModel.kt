package com.c0mpile.grimmreader.feature.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.download.DownloadRepository
import com.c0mpile.grimmreader.core.data.library.BookDetails
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.library.LocalLibraryRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.data.shelf.ShelfRepository
import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.LocalLibrary
import com.c0mpile.grimmreader.core.model.Permissions
import com.c0mpile.grimmreader.core.model.Shelf
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookDetailUiState(
    val book: Book? = null,
    val download: DownloadEntity? = null,
    val canDownload: Boolean = false,
    val canReadOnline: Boolean = false,
    val loaded: Boolean = false,
    /** User shelves (not magic); empty for local books, which cannot be shelved. */
    val shelves: List<Shelf> = emptyList(),
    /** The on-device libraries a local book can be put in; empty for server books, which follow their server library. */
    val localLibraries: List<LocalLibrary> = emptyList(),
) {
    val favorites: Shelf? get() = shelves.firstOrNull { it.isFavorites }
    val isFavorite: Boolean get() = favorites?.let { book?.shelves?.contains(it.id) } == true
}

@HiltViewModel(assistedFactory = BookDetailViewModel.Factory::class)
class BookDetailViewModel
    @AssistedInject
    constructor(
        @Assisted private val bookId: Long,
        private val library: LibraryRepository,
        private val downloads: DownloadRepository,
        private val shelfRepo: ShelfRepository,
        private val localLibraries: LocalLibraryRepository,
        session: ServerSession,
        details: BookDetails,
    ) : ViewModel() {
        init {
            // Description, publisher and date are not in the library list; Room updates [state] when they arrive.
            viewModelScope.launch { details.load(bookId) }
        }

        val state: StateFlow<BookDetailUiState> =
            combine(
                library.observeBook(bookId),
                downloads.observe(),
                session.permissions,
                shelfRepo.observe(),
                localLibraries.libraries,
            ) { book, all, permissions, shelves, libraries ->
                val file = book?.primaryFile
                BookDetailUiState(
                    book = book,
                    download = all.firstOrNull { it.bookFileId == file?.id },
                    // Without canDownload the server still allows reading, but nothing is kept (PLAN §5).
                    canDownload = book?.source == BookSource.SERVER && permissions.has(Permissions.CAN_DOWNLOAD),
                    // Ebooks and PDFs are fetched whole into the cache; comics are streamed page by page.
                    canReadOnline = book?.source == BookSource.SERVER && book.serverId != null && file != null,
                    loaded = true,
                    shelves = if (book?.source == BookSource.SERVER) shelves.filter { !it.magic } else emptyList(),
                    localLibraries = if (book?.source == BookSource.LOCAL) libraries else emptyList(),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BookDetailUiState())

        fun download() =
            viewModelScope.launch {
                state.value.book
                    ?.primaryFile
                    ?.let { downloads.enqueue(it.id) }
            }

        fun cancelDownload() =
            viewModelScope.launch {
                state.value.book
                    ?.primaryFile
                    ?.let { downloads.cancel(it.id) }
            }

        fun removeLocalCopy(onRemoved: () -> Unit) =
            viewModelScope.launch {
                val book = state.value.book ?: return@launch
                library.deleteLocal(book.id)
                if (book.source != BookSource.SERVER) onRemoved()
            }

        /** Puts a local book into [libraryId] (null = none). */
        fun moveToLibrary(libraryId: Long?) = viewModelScope.launch { localLibraries.assign(bookId, libraryId) }

        fun toggleFavorite() {
            val shelf = state.value.favorites ?: return
            setShelved(shelf.id, !state.value.isFavorite)
        }

        fun setShelved(
            shelfId: Long,
            on: Boolean,
        ) = viewModelScope.launch { shelfRepo.setShelved(bookId, shelfId, on) }

        /** Creates a shelf and puts the book on it; the error message on failure. */
        suspend fun createShelfWithBook(name: String): String? =
            shelfRepo.create(name).fold(
                onSuccess = { shelf ->
                    shelfRepo.setShelved(bookId, shelf.id, true)
                    null
                },
                onFailure = { "Could not create the shelf. Check the connection." },
            )

        @AssistedFactory
        interface Factory {
            fun create(bookId: Long): BookDetailViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
