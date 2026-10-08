package com.c0mpile.grimmreader.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.designsystem.component.SidebarButton
import com.c0mpile.grimmreader.core.designsystem.component.StatusChip
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.model.BookSort
import com.c0mpile.grimmreader.core.model.BrowseMode
import com.c0mpile.grimmreader.core.model.LibraryScope
import com.c0mpile.grimmreader.core.model.ServerStatus

private const val EMPTY_SERVER = "No books yet. Pull to refresh or open a file."
private const val EMPTY_LOCAL = "Open an EPUB or comic file to start reading."
private const val NO_MATCH = "Nothing matches your search."

internal val READABLE_TYPES =
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
    scope: LibraryScope,
    mode: BrowseMode,
    onRead: (Long) -> Unit,
    onOpenGroup: (GroupKind, String) -> Unit,
    startSearching: Boolean = false,
    viewModel: LibraryViewModel =
        hiltViewModel<LibraryViewModel, LibraryViewModel.Factory>(key = "${scope.encode()}/$mode") { it.create(scope, mode) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.import(it) }
    var searching by rememberSaveable { mutableStateOf(startSearching) }
    val selection = rememberBookSelection()
    SelectionBackHandler(selection)
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    Scaffold(
        topBar = {
            if (selection.active) {
                SelectionTopBar(selection, state.books)
            } else {
                LibraryTopBar(state, searching, { searching = it }, viewModel)
            }
        },
        bottomBar = { SelectionActionBar(selection, state.books, snackbar) },
        floatingActionButton = {
            if (!selection.active) {
                FloatingActionButton(
                    onClick = { picker.launch(READABLE_TYPES) },
                ) { Icon(LucideIcons.FolderOpen, contentDescription = "Open a file") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusRow(state.hasServer, state.status)
            // Background refresh: a thin bar, the list stays usable. The pull indicator is only for a user pull.
            if (state.syncing && !state.refreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            } else {
                Spacer(Modifier.height(2.dp))
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                val empty = if (state.mode == BrowseMode.BOOKS) state.books.isEmpty() else state.groups.isEmpty()
                when {
                    empty && !state.syncing -> EmptyMessage(state)
                    state.mode == BrowseMode.BOOKS -> BookCollection(state.books, state.view.layout, onRead, selection)
                    state.mode == BrowseMode.AUTHORS -> AuthorList(state.groups) { onOpenGroup(GroupKind.AUTHOR, it) }
                    else -> SeriesCollection(state.groups, state.view.layout) { onOpenGroup(GroupKind.SERIES, it) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    state: LibraryUiState,
    searching: Boolean,
    onSearching: (Boolean) -> Unit,
    viewModel: LibraryViewModel,
) {
    TopAppBar(
        navigationIcon = { SidebarButton() },
        title = {
            if (searching) {
                SearchField(state.query, viewModel::setQuery)
            } else {
                Text(scopeTitle(state))
            }
        },
        actions = {
            IconButton(onClick = {
                if (searching) viewModel.setQuery("")
                onSearching(!searching)
            }) {
                Icon(
                    if (searching) LucideIcons.X else LucideIcons.Search,
                    contentDescription = if (searching) "Close search" else "Search",
                )
            }
            if (state.mode != BrowseMode.AUTHORS) LayoutToggle(state.view.layout, viewModel::setLayout)
            if (state.mode == BrowseMode.BOOKS) SortMenu(state.view.sort, viewModel::setSort)
        },
    )
}

private fun scopeTitle(state: LibraryUiState): String =
    when (val scope = state.scope) {
        LibraryScope.All ->
            when (state.mode) {
                BrowseMode.BOOKS -> "All Books"
                BrowseMode.AUTHORS -> "Authors"
                BrowseMode.SERIES -> "Series"
            }
        LibraryScope.OnDevice -> "On this device"
        LibraryScope.Unshelved -> "Unshelved"
        is LibraryScope.Server -> state.libraries.firstOrNull { it.id == scope.libraryId }?.name ?: "Library"
        is LibraryScope.Shelf -> state.shelves.firstOrNull { !it.magic && it.id == scope.shelfId }?.name ?: "Shelf"
        is LibraryScope.MagicShelf -> state.shelves.firstOrNull { it.magic && it.id == scope.shelfId }?.name ?: "Magic shelf"
    }

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The field keeps its own text: the view model's query comes back only after the whole library has been
    // filtered off the main thread, and a field fed that late value drops and reorders keystrokes.
    var text by rememberSaveable { mutableStateOf(query) }
    TextField(
        value = text,
        onValueChange = {
            text = it
            onQuery(it)
        },
        placeholder = { Text("Title, author or series") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

@Composable
private fun SortMenu(
    sort: BookSort,
    onSort: (BookSort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(LucideIcons.ArrowUpDown, contentDescription = "Sort") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BookSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    trailingIcon = { if (option == sort) Icon(LucideIcons.Check, contentDescription = null) },
                    onClick = {
                        onSort(option)
                        open = false
                    },
                )
            }
        }
    }
}

private fun BookSort.label() =
    when (this) {
        BookSort.TITLE -> "Title"
        BookSort.AUTHOR -> "Author"
        BookSort.ADDED -> "Recently added"
        BookSort.RECENT -> "Recently read"
    }

/** All, each server library (book libraries first as the server lists them), and books on this device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusRow(
    hasServer: Boolean,
    status: ServerStatus,
) {
    if (!hasServer) return
    when (status) {
        ServerStatus.OFFLINE ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { StatusChip("Offline — changes will sync", LucideIcons.CloudOff) }
        ServerStatus.AUTH_EXPIRED ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                StatusChip("Signed out — sign in again in Settings", LucideIcons.CircleAlert, warning = true)
            }
        else -> Unit
    }
}

@Composable
private fun EmptyMessage(state: LibraryUiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            when {
                state.query.isNotBlank() -> NO_MATCH
                state.mode == BrowseMode.SERIES -> "No series here."
                state.hasServer -> EMPTY_SERVER
                else -> EMPTY_LOCAL
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(32.dp),
        )
    }
}
