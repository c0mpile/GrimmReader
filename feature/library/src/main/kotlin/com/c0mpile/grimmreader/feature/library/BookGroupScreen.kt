package com.c0mpile.grimmreader.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookGroupScreen(
    kind: GroupKind,
    name: String,
    onBack: () -> Unit,
    onRead: (Long) -> Unit,
    viewModel: BookGroupViewModel = hiltViewModel<BookGroupViewModel, BookGroupViewModel.Factory> { it.create(kind, name) },
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val selection = rememberBookSelection()
    val snackbar = remember { SnackbarHostState() }
    SelectionBackHandler(selection)
    Scaffold(
        topBar = {
            if (selection.active) {
                SelectionTopBar(selection, books.orEmpty())
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            books?.let { Text(countLabel(it.size), style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(LucideIcons.ArrowLeft, contentDescription = "Back") } },
                    actions = { LayoutToggle(layout) { viewModel.setLayout(it) } },
                )
            }
        },
        bottomBar = { SelectionActionBar(selection, books.orEmpty(), snackbar) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val list = books ?: return@Box
            if (list.isEmpty()) {
                Text("No books here any more.", Modifier.padding(24.dp))
            } else {
                BookCollection(list, layout, onRead, selection)
            }
        }
    }
}
