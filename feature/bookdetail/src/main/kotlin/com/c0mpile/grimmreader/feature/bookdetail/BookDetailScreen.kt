package com.c0mpile.grimmreader.feature.bookdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.designsystem.component.BookCover
import com.c0mpile.grimmreader.core.designsystem.icon.FilledIcons
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: Long,
    onBack: () -> Unit,
    onRead: (Long) -> Unit,
    viewModel: BookDetailViewModel = hiltViewModel<BookDetailViewModel, BookDetailViewModel.Factory> { it.create(bookId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(LucideIcons.ArrowLeft, contentDescription = "Back") } },
                actions = { if (state.favorites != null) FavoriteButton(state.isFavorite, viewModel::toggleFavorite) },
            )
        },
    ) { padding ->
        if (picking) ShelfPicker(state, viewModel::setShelved, viewModel::createShelfWithBook) { picking = false }
        if (moving) LibraryPicker(state, viewModel::moveToLibrary) { moving = false }
        val book = state.book
        if (book == null) {
            if (state.loaded) Text("This book is no longer in the library.", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookCover(book.title, book.coverUri, Modifier.width(140.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge)
                    if (book.authors.isNotEmpty()) Text(book.authors.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BookFacts(book)
                }
            }
            if (state.shelves.isNotEmpty()) ShelvesRow(state, onPick = { picking = true })
            if (state.localLibraries.isNotEmpty()) LibraryRow(state, onPick = { moving = true })
            BookActions(state, book, onRead, onRemoved = onBack, viewModel)
        }
    }
}

/** The book's shelves and a button to change them. */
@Composable
private fun ShelvesRow(
    state: BookDetailUiState,
    onPick: () -> Unit,
) {
    val on = state.shelves.filter { state.book?.shelves?.contains(it.id) == true }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (on.isEmpty()) "On no shelf" else on.joinToString(", ") { it.name },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onPick) {
            Icon(LucideIcons.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Shelves", Modifier.padding(start = 8.dp))
        }
    }
}

/** The on-device library a local book is in and a button to change it. */
@Composable
private fun LibraryRow(
    state: BookDetailUiState,
    onPick: () -> Unit,
) {
    val current = state.localLibraries.firstOrNull { it.id == state.book?.localLibraryId }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            current?.let { "In ${it.name}" } ?: "In no library",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onPick) {
            Icon(LucideIcons.Library, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Library", Modifier.padding(start = 8.dp))
        }
    }
}

/** One choice among the on-device libraries (or none) for a local book. */
@Composable
internal fun LibraryPicker(
    state: BookDetailUiState,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = state.book?.localLibraryId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Library") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (listOf<Long?>(null) + state.localLibraries.map { it.id }).forEach { id ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(id)
                                onDismiss()
                            }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = id == current, onClick = null)
                        Text(
                            state.localLibraries.firstOrNull { it.id == id }?.name ?: "None",
                            Modifier.weight(1f).padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Checkboxes for the user's shelves, plus a field to create one with this book on it. */
@Composable
internal fun ShelfPicker(
    state: BookDetailUiState,
    onToggle: (Long, Boolean) -> Unit,
    onCreate: suspend (String) -> String?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shelves") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.shelves.forEach { shelf ->
                    val checked = state.book?.shelves?.contains(shelf.id) == true
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(shelf.id, !checked) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { onToggle(shelf.id, it) })
                        Text(shelf.name, Modifier.weight(1f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("New shelf") },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = name.isNotBlank() && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                error = onCreate(name)
                                busy = false
                                if (error == null) name = ""
                            }
                        },
                    ) { Icon(LucideIcons.Plus, contentDescription = "Create shelf") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
internal fun FavoriteButton(
    on: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle) {
        Icon(
            if (on) FilledIcons.Heart else LucideIcons.Heart,
            contentDescription = if (on) "Remove from Favorites" else "Add to Favorites",
            tint = if (on) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}
