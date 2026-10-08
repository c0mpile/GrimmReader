package com.c0mpile.grimmreader.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.dictionary.CatalogEntry
import com.c0mpile.grimmreader.core.dictionary.Dictionaries
import com.c0mpile.grimmreader.core.dictionary.DictionaryCatalog
import com.c0mpile.grimmreader.core.dictionary.DictionaryDownload
import com.c0mpile.grimmreader.core.dictionary.DictionaryDownloads
import com.c0mpile.grimmreader.core.dictionary.ImportResult
import com.c0mpile.grimmreader.core.dictionary.InstalledDictionary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

sealed interface CatalogState {
    /** Not fetched yet: nothing goes over the network until the user opens the download list. */
    data object NotLoaded : CatalogState

    data object Loading : CatalogState

    data class Loaded(
        val entries: List<CatalogEntry>,
    ) : CatalogState

    data object Failed : CatalogState
}

data class DictionariesUiState(
    val installed: List<InstalledDictionary> = emptyList(),
    val downloads: Map<String, DictionaryDownload> = emptyMap(),
    val catalog: CatalogState = CatalogState.NotLoaded,
    val importing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class DictionariesViewModel
    @Inject
    constructor(
        private val dictionaries: Dictionaries,
        private val catalog: DictionaryCatalog,
        private val downloads: DictionaryDownloads,
    ) : ViewModel() {
        private val local = MutableStateFlow(Local())

        val state: StateFlow<DictionariesUiState> =
            combine(dictionaries.installed, downloads.state, local) { installed, running, l ->
                DictionariesUiState(installed, running, l.catalog, l.importing, l.message)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DictionariesUiState())

        fun loadCatalog(refresh: Boolean = false) {
            if (local.value.catalog == CatalogState.Loading) return
            local.update { it.copy(catalog = CatalogState.Loading) }
            viewModelScope.launch {
                val loaded =
                    try {
                        CatalogState.Loaded(catalog.entries(refresh))
                    } catch (_: IOException) {
                        CatalogState.Failed
                    }
                local.update { it.copy(catalog = loaded) }
            }
        }

        fun download(entry: CatalogEntry) = downloads.start(entry)

        fun cancel(entry: CatalogEntry) = downloads.cancel(entry.id)

        fun dismissFailure(entry: CatalogEntry) = downloads.dismiss(entry.id)

        fun importFiles(uris: List<Uri>) = importing { dictionaries.importDocuments(uris) }

        fun importFolder(tree: Uri) = importing { dictionaries.importFolder(tree) }

        fun remove(dictionary: InstalledDictionary) = viewModelScope.launch { dictionaries.remove(dictionary.id) }

        fun messageShown() = local.update { it.copy(message = null) }

        private fun importing(run: suspend () -> ImportResult) =
            viewModelScope.launch {
                local.update { it.copy(importing = true) }
                val result = run()
                local.update { it.copy(importing = false, message = result.summary()) }
            }

        private fun ImportResult.summary() =
            listOfNotNull(
                "Added ${added.joinToString(", ")}".takeIf { added.isNotEmpty() },
                "$duplicates already installed".takeIf { duplicates > 0 },
                "$failed could not be read (a StarDict dictionary needs its .ifo, .idx and .dict files)".takeIf { failed > 0 },
            ).joinToString(". ").ifEmpty { "No dictionary found" }

        private data class Local(
            val catalog: CatalogState = CatalogState.NotLoaded,
            val importing: Boolean = false,
            val message: String? = null,
        )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
