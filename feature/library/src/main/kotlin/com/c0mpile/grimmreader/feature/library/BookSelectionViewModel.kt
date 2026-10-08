package com.c0mpile.grimmreader.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.download.DownloadRepository
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.progress.ProgressRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.Permissions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the selection actions would do to the selected [books]. Delete only removes files on this device
 * (imports, downloads, book-folder files); nothing is ever deleted from a Grimmory server.
 */
internal data class SelectionSummary(
    val books: List<Book>,
    val canDownload: Boolean,
) {
    val hasServerBooks: Boolean = books.any { it.source == BookSource.SERVER }

    /** Server books without a file here, when the account may download. */
    val toDownload: List<Book> =
        if (canDownload) books.filter { it.source == BookSource.SERVER && it.primaryFile?.isAvailableOffline == false } else emptyList()

    val toDelete: List<Book> = books.filter { it.primaryFile?.isAvailableOffline == true }

    /** Files in a folder the user picked (book folder or download folder): deleting removes the user's own file. */
    val inFolders: Int = toDelete.count { it.primaryFile?.localUri?.startsWith("content://") == true }

    /** Server books keep their library entry when their file is deleted; local books leave the library. */
    val serverCopies: Int = toDelete.count { it.source == BookSource.SERVER }
}

/** Download, delete and reset progress for the books selected on a library screen. */
@HiltViewModel
class BookSelectionViewModel
    @Inject
    constructor(
        private val library: LibraryRepository,
        private val progress: ProgressRepository,
        private val downloads: DownloadRepository,
        session: ServerSession,
    ) : ViewModel() {
        val canDownload: StateFlow<Boolean> =
            session.permissions
                .map { it.has(Permissions.CAN_DOWNLOAD) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        private val messages = MutableStateFlow<String?>(null)
        val message: StateFlow<String?> = messages.asStateFlow()

        internal fun download(summary: SelectionSummary) {
            summary.toDownload.mapNotNull { it.primaryFile }.forEach { downloads.enqueue(it.id) }
            messages.value = "${countLabel(summary.toDownload.size)} queued for download"
        }

        internal fun delete(summary: SelectionSummary) =
            viewModelScope.launch {
                summary.toDelete.forEach { library.deleteLocal(it.id) }
                messages.value = "Deleted ${fileLabel(summary.toDelete.size)} from this device"
            }

        internal fun resetProgress(summary: SelectionSummary) =
            viewModelScope.launch {
                progress.reset(summary.books.map { it.id })
                messages.value = "Reading progress reset for ${countLabel(summary.books.size)}"
            }

        fun messageShown() {
            messages.value = null
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

internal fun fileLabel(n: Int) = if (n == 1) "1 file" else "$n files"
