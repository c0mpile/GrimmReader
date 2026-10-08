package com.c0mpile.grimmreader.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.dictionary.CatalogEntry
import com.c0mpile.grimmreader.core.dictionary.DictionaryDownload
import com.c0mpile.grimmreader.core.dictionary.DictionarySource
import com.c0mpile.grimmreader.core.dictionary.InstalledDictionary
import com.c0mpile.grimmreader.core.dictionary.language
import java.util.Locale

/** Installed dictionaries (add your own, remove) and the download list (Wiktionary, FreeDict). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionariesScreen(
    onBack: () -> Unit,
    viewModel: DictionariesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Always opens on Installed: the download list is fetched only when the user opens its tab.
    var tab by rememberSaveable { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(LucideIcons.ArrowLeft, contentDescription = "Back") } },
                title = { Text("Dictionaries") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed (${state.installed.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Download") })
            }
            when (tab) {
                0 -> InstalledTab(state, viewModel)
                else -> DownloadTab(state, viewModel)
            }
        }
    }
}

@Composable
private fun InstalledTab(
    state: DictionariesUiState,
    viewModel: DictionariesViewModel,
) {
    var confirmRemove by remember { mutableStateOf<InstalledDictionary?>(null) }
    val pickFiles =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) viewModel.importFiles(uris)
        }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(viewModel::importFolder) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Press and hold a word while reading to look it up in every dictionary below. Add StarDict dictionaries " +
                    "(.ifo, .idx and .dict or .dict.dz files, loose or in a .zip, .tar.gz, .tar.bz2, .tar.xz or .tar.zst archive), " +
                    "or download one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, enabled = !state.importing) { Text("Add files") }
                OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = !state.importing) { Text("Add folder") }
                if (state.importing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (state.installed.isEmpty()) {
            item { Text("No dictionary installed yet.", style = MaterialTheme.typography.bodyLarge) }
        }
        items(state.installed, key = { it.id }) { dictionary ->
            Column {
                Text(dictionary.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "%,d words · %s".format(dictionary.words, megabytes(dictionary.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { confirmRemove = dictionary }, modifier = Modifier.padding(start = 0.dp)) { Text("Remove") }
                HorizontalDivider()
            }
        }
    }
    confirmRemove?.let { dictionary ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${dictionary.name}?") },
            text = { Text("Its files are deleted from this device. You can add or download it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    viewModel.remove(dictionary)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DownloadTab(
    state: DictionariesUiState,
    viewModel: DictionariesViewModel,
) {
    // Opening this tab is the user's request for the list; nothing is fetched before.
    LaunchedEffect(Unit) { if (state.catalog == CatalogState.NotLoaded) viewModel.loadCatalog() }
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<DictionarySource?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Language") },
            leadingIcon = { Icon(LucideIcons.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = source == null, onClick = { source = null }, label = { Text("All") })
            DictionarySource.entries.forEach { s ->
                FilterChip(selected = source == s, onClick = { source = s }, label = { Text(s.label) })
            }
        }
        when (val catalog = state.catalog) {
            CatalogState.NotLoaded, CatalogState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            CatalogState.Failed ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Could not load the dictionary list. Check the connection.")
                    OutlinedButton(onClick = { viewModel.loadCatalog(refresh = true) }) { Text("Try again") }
                }
            is CatalogState.Loaded -> {
                val shown =
                    remember(catalog, query, source) {
                        catalog.entries
                            .filter { source == null || it.source == source }
                            .filter { query.isBlank() || it.matches(query.trim()) }
                            .sortedWith(compareBy({ it.rank() }, { it.title }))
                    }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown, key = { it.id }) { entry ->
                        CatalogRow(
                            entry,
                            download = state.downloads[entry.id],
                            installed = state.installed.any { it.catalogId == entry.id },
                            viewModel = viewModel,
                        )
                    }
                    item {
                        Text(
                            DictionarySource.entries.joinToString("\n") { "${it.label}: ${it.licence}." },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    download: DictionaryDownload?,
    installed: Boolean,
    viewModel: DictionariesViewModel,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(entry.source.label, megabytes(entry.sizeBytes), entry.words?.let { "%,d words".format(it) })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                download is DictionaryDownload.Running -> TextButton(onClick = { viewModel.cancel(entry) }) { Text("Cancel") }
                download is DictionaryDownload.Installing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                installed -> Icon(LucideIcons.Check, contentDescription = "Installed", tint = MaterialTheme.colorScheme.primary)
                else ->
                    TextButton(
                        onClick = { viewModel.download(entry) },
                    ) { Text(if (download is DictionaryDownload.Failed) "Retry" else "Download") }
            }
        }
        when (download) {
            is DictionaryDownload.Running ->
                if (download.total > 0) {
                    LinearProgressIndicator(
                        progress = { (download.bytes.toFloat() / download.total).coerceIn(0f, 1f) },
                        Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            is DictionaryDownload.Installing -> Text("Installing…", style = MaterialTheme.typography.bodySmall)
            is DictionaryDownload.Failed ->
                Text(
                    download.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            null -> Unit
        }
    }
}

/** Language names or codes, either side: "german", "deu", "de". */
private fun CatalogEntry.matches(query: String): Boolean =
    listOf(from, to, language(from), language(to), title).any { it.contains(query, ignoreCase = true) }

/**
 * Sort rank for the device's language: its own definitions (English → English) first, then dictionaries from
 * it, then everything else.
 */
private fun CatalogEntry.rank(): Int {
    val own = Locale.getDefault()

    fun isOwn(code: String) = Locale.forLanguageTag(code).language.let { it == own.language || it == own.isO3Language }
    return when {
        isOwn(from) && isOwn(to) -> 0
        isOwn(from) -> 1
        else -> 2
    }
}

private fun megabytes(bytes: Long) =
    if (bytes <
        BYTES_PER_MB
    ) {
        "%.1f MB".format(bytes / BYTES_PER_MB)
    } else {
        "%.0f MB".format(bytes / BYTES_PER_MB)
    }

private const val BYTES_PER_MB = 1_048_576f
