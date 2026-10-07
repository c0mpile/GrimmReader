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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.c0mpile.grimmreader.core.database.entity.DownloadState
import com.c0mpile.grimmreader.core.designsystem.component.BookCover
import com.c0mpile.grimmreader.core.designsystem.icon.FilledIcons
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookSource
import kotlinx.coroutines.launch

private const val BYTES_PER_MB = 1_048_576f

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
        val book = state.book
        if (book == null) {
            if (state.loaded) Text("This book is no longer in the library.", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val file = book.primaryFile
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookCover(book.title, book.coverUri, Modifier.width(140.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge)
                    if (book.authors.isNotEmpty()) Text(book.authors.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    book.seriesName?.let { s ->
                        Text(listOfNotNull(s, book.seriesNumber?.let { "#${it.toString().removeSuffix(".0")}" }).joinToString(" "))
                    }
                    file?.let { f ->
                        Text(
                            listOfNotNull(f.format.name, f.sizeBytes?.let { "%.1f MB".format(it / BYTES_PER_MB) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    book.progressPercent?.takeIf { it > 0 }?.let {
                        Text(
                            "%.0f %% read".format(it),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (state.shelves.isNotEmpty()) ShelvesRow(state, onPick = { picking = true })
            ReadActions(state, book, onRead, onBack, viewModel)
        }
    }
}

@Composable
private fun DownloadSection(
    state: BookDetailUiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val download = state.download
    when (download?.state) {
        DownloadState.QUEUED, DownloadState.RUNNING -> {
            val total = download.bytesTotal
            if (total != null && total > 0) {
                LinearProgressIndicator(progress = { download.bytesDone.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel download") }
        }
        DownloadState.FAILED -> {
            Text("Download failed; it will retry when possible.", color = MaterialTheme.colorScheme.error)
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Retry download") }
        }
        else ->
            OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Icon(LucideIcons.Download, contentDescription = null)
                Text("Download", Modifier.padding(start = 8.dp))
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

/** Checkboxes for the user's shelves, plus a field to create one with this book on it. */
@Composable
private fun ShelfPicker(
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
private fun FavoriteButton(
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

/** Read (or read online), and download or remove the local copy. */
@Composable
private fun ReadActions(
    state: BookDetailUiState,
    book: Book,
    onRead: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: BookDetailViewModel,
) {
    val file = book.primaryFile
    val available = file?.isAvailableOffline == true
    if (available) {
        Button(onClick = { onRead(book.id) }, modifier = Modifier.fillMaxWidth()) { Text("Read") }
        OutlinedButton(onClick = { viewModel.removeLocalCopy(onBack) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (book.source == BookSource.SERVER) "Remove download" else "Remove from this device")
        }
    } else if (book.source == BookSource.SERVER) {
        // Reading online needs no download permission; nothing is kept beyond an evictable cache.
        if (state.canReadOnline) {
            Button(onClick = { onRead(book.id) }, modifier = Modifier.fillMaxWidth()) {
                Icon(LucideIcons.Globe, contentDescription = null)
                Text("Read online", Modifier.padding(start = 8.dp))
            }
        }
        if (state.canDownload) {
            DownloadSection(state, onDownload = viewModel::download, onCancel = viewModel::cancelDownload)
        } else {
            Text("Your account cannot download this book.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
