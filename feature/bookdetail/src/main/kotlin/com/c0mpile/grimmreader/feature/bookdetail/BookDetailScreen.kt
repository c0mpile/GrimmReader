package com.c0mpile.grimmreader.feature.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.database.entity.DownloadState
import com.c0mpile.grimmreader.core.designsystem.component.BookCover
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.model.BookSource

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
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(LucideIcons.ArrowLeft, contentDescription = "Back") } },
            )
        },
    ) { padding ->
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
