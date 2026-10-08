package com.c0mpile.grimmreader.feature.setup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.library.BookFolder
import com.c0mpile.grimmreader.core.data.library.FolderLibrary
import com.c0mpile.grimmreader.core.data.library.LocalLibraryRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.model.LocalLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FirstRunUiState(
    val appFolder: BookFolder? = null,
    /** The on-device libraries: one per library of the connected server, plus the ones added here. */
    val libraries: List<LocalLibrary> = emptyList(),
    val hasServer: Boolean = false,
)

@HiltViewModel
class FirstRunViewModel
    @Inject
    constructor(
        private val folders: FolderLibrary,
        private val localLibraries: LocalLibraryRepository,
        private val prefs: AppPreferences,
        session: ServerSession,
    ) : ViewModel() {
        val state: StateFlow<FirstRunUiState> =
            combine(folders.downloadFolder, localLibraries.libraries, session.server) { app, libraries, server ->
                FirstRunUiState(app, libraries, server != null)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FirstRunUiState())

        fun setAppFolder(tree: Uri?) = viewModelScope.launch { folders.setDownloadFolder(tree) }

        fun addLibrary(name: String) = viewModelScope.launch { localLibraries.create(name) }

        fun setLibraryFolder(
            libraryId: Long,
            tree: Uri?,
        ) = viewModelScope.launch { folders.setLibraryFolder(libraryId, tree) }

        fun deleteLibrary(libraryId: Long) = viewModelScope.launch { folders.deleteLibrary(libraryId) }

        fun finish() = viewModelScope.launch { prefs.setFirstRunDone() }
    }
