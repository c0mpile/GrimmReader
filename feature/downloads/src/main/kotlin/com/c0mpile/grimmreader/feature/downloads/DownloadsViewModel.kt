package com.c0mpile.grimmreader.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.download.DownloadRepository
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.database.entity.DownloadState
import com.c0mpile.grimmreader.core.database.entity.DownloadWithBook
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A download as listed: the row plus the cover model the library uses for that book. */
data class DownloadItem(
    val row: DownloadWithBook,
    val cover: String? = null,
) {
    val download get() = row.download
    val bookId get() = row.bookId
    val title get() = row.title
    val format get() = row.format
}

/**
 * The download queue in three groups: [active] (running first, then waiting, in the order they were queued),
 * [failed], and [finished] (newest first). [loaded] is false until the database has answered.
 */
data class DownloadQueue(
    val active: List<DownloadItem> = emptyList(),
    val failed: List<DownloadItem> = emptyList(),
    val finished: List<DownloadItem> = emptyList(),
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = active.isEmpty() && failed.isEmpty() && finished.isEmpty()

    companion object {
        fun of(rows: List<DownloadItem>): DownloadQueue {
            val byState = rows.groupBy { it.download.state }
            return DownloadQueue(
                active =
                    byState[DownloadState.RUNNING].orEmpty() +
                        byState[DownloadState.QUEUED].orEmpty() +
                        byState[DownloadState.PAUSED].orEmpty(),
                failed = byState[DownloadState.FAILED].orEmpty(),
                finished = byState[DownloadState.DONE].orEmpty().sortedByDescending { it.download.updatedAt },
                loaded = true,
            )
        }
    }
}

@HiltViewModel
class DownloadsViewModel
    @Inject
    constructor(
        private val downloads: DownloadRepository,
        library: LibraryRepository,
    ) : ViewModel() {
        val queue: StateFlow<DownloadQueue> =
            downloads
                .observeQueue()
                .map { rows ->
                    DownloadQueue.of(
                        rows.map { DownloadItem(it, library.coverModel(it.bookId, it.serverRowId, it.serverBookId, it.coverUpdatedOn)) },
                    )
                }.flowOn(Dispatchers.IO)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DownloadQueue())

        fun cancel(bookFileId: Long) = viewModelScope.launch { downloads.cancel(bookFileId) }

        fun retry(bookFileId: Long) = viewModelScope.launch { downloads.retry(bookFileId) }

        fun clearFinished() = viewModelScope.launch { downloads.clearFinished() }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
