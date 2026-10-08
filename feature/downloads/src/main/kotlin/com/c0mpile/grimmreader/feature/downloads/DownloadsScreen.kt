package com.c0mpile.grimmreader.feature.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.database.entity.DownloadState
import com.c0mpile.grimmreader.core.designsystem.component.BookCover
import com.c0mpile.grimmreader.core.designsystem.component.SidebarButton
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons

/** Downloads in progress, waiting, failed and finished, with their progress. Tapping one opens its book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onOpenBook: (Long) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { SidebarButton() },
                title = { Text("Downloads") },
                actions = {
                    if (queue.finished.isNotEmpty()) TextButton(onClick = { viewModel.clearFinished() }) { Text("Clear finished") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (queue.loaded && queue.isEmpty) {
                Text(
                    "No downloads. Books you download from a server library show up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    section("Downloading", queue.active) { row ->
                        DownloadRow(row, onOpenBook) {
                            IconButton(onClick = { viewModel.cancel(row.download.bookFileId) }) {
                                Icon(LucideIcons.X, contentDescription = "Cancel download")
                            }
                        }
                    }
                    section("Failed", queue.failed) { row ->
                        DownloadRow(row, onOpenBook) {
                            IconButton(onClick = { viewModel.retry(row.download.bookFileId) }) {
                                Icon(LucideIcons.RotateCcw, contentDescription = "Retry download")
                            }
                            IconButton(onClick = { viewModel.cancel(row.download.bookFileId) }) {
                                Icon(LucideIcons.X, contentDescription = "Remove from downloads")
                            }
                        }
                    }
                    section("Finished", queue.finished) { row -> DownloadRow(row, onOpenBook) {} }
                }
            }
        }
    }
}

private fun LazyListScope.section(
    title: String,
    rows: List<DownloadItem>,
    row: @Composable (DownloadItem) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "header/$title") {
        Text(
            "$title (${rows.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(rows, key = { it.download.bookFileId }) { row(it) }
}

@Composable
private fun DownloadRow(
    row: DownloadItem,
    onOpenBook: (Long) -> Unit,
    actions: @Composable () -> Unit,
) {
    val download = row.download
    Column(Modifier.fillMaxWidth().clickable { onOpenBook(row.bookId) }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BookCover(row.title, row.cover, Modifier.width(COVER))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    statusText(row),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (download.state ==
                            DownloadState.FAILED
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                DownloadProgress(row)
            }
            Row { actions() }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = DIVIDER_ALPHA))
    }
}

/** A bar while running or partly done; indeterminate while running when the server sent no size. */
@Composable
private fun DownloadProgress(row: DownloadItem) {
    val download = row.download
    val fraction = fraction(row)
    val modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
    when {
        download.state == DownloadState.DONE || download.state == DownloadState.FAILED -> Unit
        fraction != null && (download.state == DownloadState.RUNNING || fraction > 0f) ->
            LinearProgressIndicator(progress = { fraction }, modifier = modifier)
        download.state == DownloadState.RUNNING -> LinearProgressIndicator(modifier)
    }
}

internal fun fraction(row: DownloadItem): Float? {
    val total = row.download.bytesTotal?.takeIf { it > 0 } ?: return null
    return (row.download.bytesDone.toFloat() / total).coerceIn(0f, 1f)
}

/** "EPUB · Downloading · 12.3 MB of 40.0 MB · 31 %", "EPUB · Waiting", "EPUB · Failed (HTTP 503)", "EPUB · 40.0 MB". */
internal fun statusText(row: DownloadItem): String {
    val download = row.download
    val total = download.bytesTotal?.takeIf { it > 0 }
    val amount =
        when {
            total != null && download.bytesDone > 0 ->
                "${megabytes(download.bytesDone)} of ${megabytes(total)} · %.0f %%".format(download.bytesDone * PERCENT / total)
            download.bytesDone > 0 -> megabytes(download.bytesDone)
            else -> null
        }
    val parts =
        when (download.state) {
            DownloadState.RUNNING -> listOfNotNull("Downloading", amount)
            DownloadState.QUEUED -> listOfNotNull("Waiting", amount)
            DownloadState.PAUSED -> listOfNotNull("Paused", amount)
            DownloadState.FAILED -> listOf(download.error?.let { "Failed ($it)" } ?: "Failed")
            DownloadState.DONE -> listOfNotNull(total?.let(::megabytes) ?: "Downloaded")
        }
    return (listOf(row.format.name) + parts).joinToString(" · ")
}

private fun megabytes(bytes: Long) = "%.1f MB".format(bytes / BYTES_PER_MB)

private val COVER = 48.dp
private const val BYTES_PER_MB = 1_048_576f
private const val PERCENT = 100f
private const val DIVIDER_ALPHA = 0.4f
