package com.c0mpile.grimmreader.feature.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.download.DownloadRepository
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.Permissions
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
    val loaded: Boolean = false,
)

@HiltViewModel(assistedFactory = BookDetailViewModel.Factory::class)
class BookDetailViewModel
    @AssistedInject
    constructor(
        @Assisted private val bookId: Long,
        private val library: LibraryRepository,
        private val downloads: DownloadRepository,
        session: ServerSession,
    ) : ViewModel() {
        val state: StateFlow<BookDetailUiState> =
            combine(library.observeBook(bookId), downloads.observe(), session.permissions) { book, all, permissions ->
                val file = book?.primaryFile
                BookDetailUiState(
                    book = book,
                    download = all.firstOrNull { it.bookFileId == file?.id },
                    // Without canDownload the server still allows reading, but nothing is kept (PLAN §5).
                    canDownload = book?.source == BookSource.SERVER && permissions.has(Permissions.CAN_DOWNLOAD),
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BookDetailUiState())

        fun download() {
            state.value.book
                ?.primaryFile
                ?.let { downloads.enqueue(it.id) }
        }

        fun cancelDownload() {
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

        @AssistedFactory
        interface Factory {
            fun create(bookId: Long): BookDetailViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
