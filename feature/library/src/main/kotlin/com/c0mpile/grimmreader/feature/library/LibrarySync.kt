package com.c0mpile.grimmreader.feature.library

import android.net.Uri
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.files.UnsupportedFormatException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class SyncFlags(
    /** A refresh the user asked for (pull to refresh): shows the pull indicator. */
    val refreshing: Boolean = false,
    /** Any refresh in progress, including the automatic one: a thin bar that never blocks the screen. */
    val syncing: Boolean = false,
    val message: String? = null,
)

/**
 * Refresh and import for the library screens (dashboard, books): the automatic refresh when the screen opens
 * (skipped by the repository when the library was mirrored recently), pull to refresh, and file import.
 */
class LibrarySync(
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val session: ServerSession,
) {
    private val _flags = MutableStateFlow(SyncFlags())
    val flags: StateFlow<SyncFlags> = _flags.asStateFlow()
    private var sync: Job? = null

    init {
        // The server row loads from Room asynchronously; check once it is there (never in local mode).
        scope.launch {
            session.server.filterNotNull().first()
            start { library.refreshIfStale() }
        }
    }

    /** Pull to refresh: always asks the server, or joins the refresh already running. */
    fun refresh() {
        if (session.server.value == null || _flags.value.refreshing) return
        _flags.update { it.copy(refreshing = true) }
        val running = sync?.takeIf { it.isActive }
        if (running != null) {
            scope.launch {
                running.join()
                _flags.update { it.copy(refreshing = false) }
            }
        } else {
            start { library.refresh() }
        }
    }

    private fun start(run: suspend () -> Result<Int>?) {
        _flags.update { it.copy(syncing = true) }
        sync =
            scope.launch {
                try {
                    run()?.onFailure { _flags.update { s -> s.copy(message = "Could not refresh the library.") } }
                } finally {
                    _flags.update { it.copy(syncing = false, refreshing = false) }
                }
            }
    }

    fun import(uris: List<Uri>) =
        scope.launch {
            var unsupported = 0
            var failed = 0
            for (uri in uris) {
                try {
                    library.importLocal(uri)
                } catch (_: UnsupportedFormatException) {
                    unsupported++
                } catch (_: IOException) {
                    failed++
                }
            }
            val message =
                listOfNotNull(
                    "$unsupported file(s) not supported. Books: EPUB, MOBI/AZW3, FB2, PDF; comics: CBZ only."
                        .takeIf { unsupported > 0 },
                    "$failed file(s) could not be imported.".takeIf { failed > 0 },
                ).joinToString(" ")
            if (message.isNotEmpty()) _flags.update { it.copy(message = message) }
        }

    fun messageShown() = _flags.update { it.copy(message = null) }
}
