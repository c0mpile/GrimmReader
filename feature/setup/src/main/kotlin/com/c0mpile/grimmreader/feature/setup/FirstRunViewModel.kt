package com.c0mpile.grimmreader.feature.setup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.library.BookFolder
import com.c0mpile.grimmreader.core.data.library.FolderLibrary
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FirstRunUiState(
    val appFolder: BookFolder? = null,
    val contentFolders: List<BookFolder> = emptyList(),
)

@HiltViewModel
class FirstRunViewModel
    @Inject
    constructor(
        private val folders: FolderLibrary,
        private val prefs: AppPreferences,
    ) : ViewModel() {
        val state: StateFlow<FirstRunUiState> =
            combine(folders.downloadFolder, folders.folders, ::FirstRunUiState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FirstRunUiState())

        fun setAppFolder(tree: Uri?) = viewModelScope.launch { folders.setDownloadFolder(tree) }

        fun addContentFolder(tree: Uri) = viewModelScope.launch { folders.add(tree) }

        fun removeContentFolder(tree: String) = viewModelScope.launch { folders.remove(tree) }

        fun finish() = viewModelScope.launch { prefs.setFirstRunDone() }
    }
