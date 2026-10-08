package com.c0mpile.grimmreader.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.library.FolderLibrary
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.ReadStatus
import com.c0mpile.grimmreader.core.model.ServerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.random.Random

data class DashboardState(
    val loaded: Boolean = false,
    val continueReading: List<Book> = emptyList(),
    val recentlyAdded: List<Book> = emptyList(),
    val discover: List<Book> = emptyList(),
    val hasServer: Boolean = false,
    val status: ServerStatus = ServerStatus.ONLINE,
    val sync: SyncFlags = SyncFlags(),
) {
    val empty: Boolean get() = loaded && continueReading.isEmpty() && recentlyAdded.isEmpty()
}

/**
 * The start screen, like the web dashboard: Continue Reading, Recently Added, Discover Something New. Built
 * from the local library (server books mirrored in Room plus local ones), so it works offline and without a
 * server.
 */
@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        library: LibraryRepository,
        session: ServerSession,
        folders: FolderLibrary,
    ) : ViewModel() {
        private val sync = LibrarySync(viewModelScope, library, session, folders)

        /** Discover picks stay put while the screen lives, instead of reshuffling on every update. */
        private val seed = Random.nextLong()

        val state: StateFlow<DashboardState> =
            combine(library.observeLibrary(), session.server, session.status, sync.flags) { books, server, status, flags ->
                val reading =
                    books
                        .filter { (it.progressPercent ?: 0f) > 0f && it.readStatus != ReadStatus.READ && (it.progressPercent ?: 0f) < DONE }
                        .sortedByDescending { it.lastReadAt ?: 0L }
                        .take(ROW)
                val readingIds = reading.map { it.id }.toSet()
                DashboardState(
                    loaded = true,
                    continueReading = reading,
                    recentlyAdded = books.sortedByDescending { it.addedAt }.take(ROW),
                    discover =
                        books
                            .filter { it.id !in readingIds && it.readStatus == ReadStatus.UNREAD }
                            .sortedBy { it.id }
                            .shuffled(Random(seed))
                            .take(ROW),
                    hasServer = server != null,
                    status = status,
                    sync = flags,
                )
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DashboardState())

        fun refresh() = sync.refresh()

        fun import(uris: List<Uri>) = sync.import(uris)

        fun messageShown() = sync.messageShown()

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val ROW = 20
            const val DONE = 99.5f
        }
    }
