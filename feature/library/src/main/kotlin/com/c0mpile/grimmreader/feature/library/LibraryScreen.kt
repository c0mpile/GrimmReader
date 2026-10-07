package com.c0mpile.grimmreader.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.designsystem.component.BookCard
import com.c0mpile.grimmreader.core.designsystem.component.StatusChip
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.model.ServerStatus

private const val EMPTY_SERVER = "No books yet. Pull to refresh or open a file."
private const val EMPTY_LOCAL = "Open an EPUB or comic file to start reading."

private val READABLE_TYPES =
    arrayOf(
        "application/epub+zip",
        "application/pdf",
        "application/x-mobipocket-ebook",
        "application/x-fictionbook+xml",
        "application/vnd.comicbook+zip",
        "application/x-cbz",
        "application/zip",
        "application/octet-stream",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (Long) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.import(it) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Library") }) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { picker.launch(READABLE_TYPES) },
            ) { Icon(LucideIcons.FolderOpen, contentDescription = "Open a file") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.hasServer) {
                    SourceFilter.entries.forEach { f ->
                        FilterChip(selected = state.filter == f, onClick = { viewModel.setFilter(f) }, label = { Text(f.label()) })
                    }
                }
                if (state.hasServer && state.status == ServerStatus.OFFLINE) StatusChip("Offline — changes will sync", LucideIcons.CloudOff)
                if (state.status ==
                    ServerStatus.AUTH_EXPIRED
                ) {
                    StatusChip("Signed out — sign in again in Settings", LucideIcons.CircleAlert, warning = true)
                }
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                if (state.books.isEmpty() && !state.refreshing) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.hasServer) EMPTY_SERVER else EMPTY_LOCAL,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(116.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.books, key = { it.id }) { book ->
                            BookCard(
                                title = book.title,
                                author = book.authors.joinToString(", "),
                                coverModel = book.coverUri,
                                onClick = { onOpenBook(book.id) },
                                progressPercent = book.progressPercent,
                                badge = book.primaryFile?.format?.name,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun SourceFilter.label() =
    when (this) {
        SourceFilter.ALL -> "All"
        SourceFilter.SERVER -> "Server"
        SourceFilter.LOCAL -> "On this device"
    }
