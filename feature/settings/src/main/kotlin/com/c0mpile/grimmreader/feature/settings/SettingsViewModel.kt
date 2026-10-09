package com.c0mpile.grimmreader.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.library.BookFolder
import com.c0mpile.grimmreader.core.data.library.FolderLibrary
import com.c0mpile.grimmreader.core.data.library.LocalLibraryRepository
import com.c0mpile.grimmreader.core.data.library.ScanResult
import com.c0mpile.grimmreader.core.data.server.ServerRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.model.Appearance
import com.c0mpile.grimmreader.core.model.LocalLibrary
import com.c0mpile.grimmreader.core.model.ServerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val appearance: Appearance = Appearance(),
    val server: ServerEntity? = null,
    val status: ServerStatus = ServerStatus.ONLINE,
    val libraries: List<LocalLibrary> = emptyList(),
    val downloadFolder: BookFolder? = null,
    /** Read books whose folder was removed, kept hidden so their position comes back with the folder. */
    val setAside: Int = 0,
    val scanning: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val prefs: AppPreferences,
        private val servers: ServerRepository,
        session: ServerSession,
        private val folders: FolderLibrary,
        private val localLibraries: LocalLibraryRepository,
    ) : ViewModel() {
        private val scan = MutableStateFlow(ScanState())

        val state: StateFlow<SettingsUiState> =
            combine(
                combine(prefs.appearance, session.server, session.status, ::Triple),
                localLibraries.libraries,
                combine(folders.downloadFolder, folders.setAsideCount, ::Pair),
                scan,
            ) { (appearance, server, status), libraries, (downloadFolder, setAside), scan ->
                SettingsUiState(appearance, server, status, libraries, downloadFolder, setAside, scan.running, scan.message)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

        fun addLibrary(name: String) = viewModelScope.launch { localLibraries.create(name) }

        fun setLibraryFolder(
            libraryId: Long,
            tree: Uri?,
        ) = scanning { folders.setLibraryFolder(libraryId, tree) }

        fun setLibraryComics(
            libraryId: Long,
            isComics: Boolean,
        ) = viewModelScope.launch { localLibraries.setComics(libraryId, isComics) }

        fun deleteLibrary(libraryId: Long) = viewModelScope.launch { folders.deleteLibrary(libraryId) }

        fun rescan() = scanning { folders.scan() }

        fun forgetRemovedBooks() = viewModelScope.launch { folders.forgetSetAside() }

        fun setDownloadFolder(tree: Uri?) = viewModelScope.launch { folders.setDownloadFolder(tree) }

        fun messageShown() = scan.update { it.copy(message = null) }

        private fun scanning(run: suspend () -> ScanResult) =
            viewModelScope.launch {
                scan.value = ScanState(running = true)
                val result = run()
                scan.value = ScanState(message = result.summary())
            }

        private fun ScanResult.summary() =
            listOfNotNull(
                "$added book(s) added".takeIf { added > 0 },
                "$removed removed".takeIf { removed > 0 },
                "$merged duplicate(s) merged".takeIf { merged > 0 },
                "$unreadableFolders folder(s) could not be read".takeIf { unreadableFolders > 0 },
            ).joinToString(", ").ifEmpty { "No changes" }

        private data class ScanState(
            val running: Boolean = false,
            val message: String? = null,
        )

        fun setAppearance(appearance: Appearance) = viewModelScope.launch { prefs.setAppearance(appearance) }

        fun signOut() = viewModelScope.launch { servers.signOut() }

        fun removeServer(keepDownloads: Boolean) = viewModelScope.launch { servers.remove(keepDownloads) }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
