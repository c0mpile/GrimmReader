package com.c0mpile.grimmreader.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.designsystem.component.BookCard
import com.c0mpile.grimmreader.core.designsystem.component.SidebarButton
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.model.Book

/** The start screen: Continue Reading, Recently Added and Discover Something New, as rows of covers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenBook: (Long) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.import(it) }
    LaunchedEffect(state.sync.message) {
        state.sync.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    Scaffold(
        topBar = { TopAppBar(navigationIcon = { SidebarButton() }, title = { Text("Dashboard") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { picker.launch(READABLE_TYPES) }) {
                Icon(LucideIcons.FolderOpen, contentDescription = "Open a file")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusRow(state.hasServer, state.status)
            if (state.sync.syncing && !state.sync.refreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            } else {
                Spacer(Modifier.height(2.dp))
            }
            PullToRefreshBox(isRefreshing = state.sync.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                if (state.empty && !state.sync.syncing) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.hasServer) EMPTY_SERVER else EMPTY_LOCAL,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        shelfRow("Continue Reading", state.continueReading, onOpenBook)
                        shelfRow("Recently Added", state.recentlyAdded, onOpenBook)
                        shelfRow("Discover Something New", state.discover, onOpenBook)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.shelfRow(
    title: String,
    books: List<Book>,
    onOpenBook: (Long) -> Unit,
) {
    if (books.isEmpty()) return
    item(key = title) { BookRow(title, books, onOpenBook) }
}

/** A titled card with an accent underline and a horizontal row of covers, after the web dashboard. */
@Composable
private fun BookRow(
    title: String,
    books: List<Book>,
    onOpenBook: (Long) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(vertical = 16.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Box(
            Modifier
                .padding(start = 16.dp, top = 4.dp, bottom = 12.dp)
                .size(width = 40.dp, height = 3.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = { it.id }) { book ->
                BookCard(
                    title = book.title,
                    author = book.authors.joinToString(", "),
                    coverModel = book.coverUri,
                    onClick = { onOpenBook(book.id) },
                    modifier = Modifier.width(COVER_WIDTH.dp),
                    progressPercent = book.progressPercent,
                    badge = book.primaryFile?.format?.name,
                )
            }
        }
    }
}

private const val COVER_WIDTH = 116
private const val EMPTY_SERVER = "No books yet. Pull to refresh or open a file."
private const val EMPTY_LOCAL = "Open an EPUB, PDF or comic file to start reading."
