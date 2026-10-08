package com.c0mpile.grimmreader.feature.bookdetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import com.c0mpile.grimmreader.core.database.entity.DownloadState
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookSource
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val BYTES_PER_MB = 1_048_576f
private val ACTIONS_MAX_WIDTH = 560.dp

/**
 * A book's actions, opened in place under its cover or row in a library: full title, author, file facts,
 * publisher, publication date and description (each only when known), favorite and shelves, and [BookActions].
 * Calls [onClose] when the book is gone (a local copy removed).
 */
@Composable
fun BookDrawer(
    bookId: Long,
    onRead: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookDetailViewModel =
        hiltViewModel<BookDetailViewModel, BookDetailViewModel.Factory>(key = "book-drawer-$bookId") { it.create(bookId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    if (picking) ShelfPicker(state, viewModel::setShelved, viewModel::createShelfWithBook) { picking = false }
    if (moving) LibraryPicker(state, viewModel::moveToLibrary) { moving = false }
    val book = state.book
    if (book == null) {
        if (state.loaded) LaunchedEffect(Unit) { onClose() }
        return
    }
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium)
                    book.subtitle?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    if (book.authors.isNotEmpty()) {
                        Text(book.authors.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BookFacts(book)
                    book.publisher?.let { Fact("Publisher", it) }
                    book.publishedDate?.let { Fact("Published", publishedDateText(it)) }
                }
                state.favorites?.let { FavoriteButton(state.isFavorite, viewModel::toggleFavorite) }
                if (state.shelves.isNotEmpty()) {
                    IconButton(onClick = { picking = true }) { Icon(LucideIcons.Bookmark, contentDescription = "Shelves") }
                }
                if (state.localLibraries.isNotEmpty()) {
                    IconButton(onClick = { moving = true }) { Icon(LucideIcons.Library, contentDescription = "Library") }
                }
            }
            book.description?.let { Description(it) }
            BookActions(state, book, onRead, onRemoved = onClose, viewModel, Modifier.widthIn(max = ACTIONS_MAX_WIDTH))
        }
    }
}

/** "Label value" in body small, the label dimmed. */
@Composable
private fun Fact(
    label: String,
    value: String,
) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(label) }
            append("  ")
            append(value)
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

/** The description, cut to a few lines with More/Less when it is longer. */
@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "Less" else "More")
            }
        }
    }
}

/** "2010-05-01" as a localized date, "2010-05" as month and year; a bare year or free text as given. */
internal fun publishedDateText(raw: String): String {
    val date = raw.trim()
    runCatching { return LocalDate.parse(date.take(ISO_DATE_LENGTH)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
    runCatching { return YearMonth.parse(date).format(DateTimeFormatter.ofPattern("MMMM yyyy")) }
    return date
}

private const val DESCRIPTION_LINES = 6
private const val ISO_DATE_LENGTH = 10

/** Series, format, size and progress, one per line. */
@Composable
internal fun BookFacts(book: Book) {
    book.seriesName?.let { s ->
        Text(
            listOfNotNull(s, book.seriesNumber?.let { "#${it.toString().removeSuffix(".0")}" }).joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    book.primaryFile?.let { f ->
        Text(
            listOfNotNull(f.format.name, f.sizeBytes?.let { "%.1f MB".format(it / BYTES_PER_MB) }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    book.progressPercent?.takeIf { it > 0 }?.let {
        Text("%.0f %% read".format(it), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Either Read and Delete (the book has a file on this device: an import, a download, or a book-folder file,
 * also when that file was matched to a server book), or Read online and Download (a server book with no file here).
 */
@Composable
internal fun BookActions(
    state: BookDetailUiState,
    book: Book,
    onRead: (Long) -> Unit,
    onRemoved: () -> Unit,
    viewModel: BookDetailViewModel,
    modifier: Modifier = Modifier,
) {
    val file = book.primaryFile
    val available = file?.isAvailableOffline == true
    // A file in a folder the user picked (book folder or download folder): deleting it removes the user's own file.
    val inFolder = file?.localUri?.startsWith("content://") == true
    var confirmDelete by remember { mutableStateOf(false) }
    val download = state.download?.takeIf { !available }
    val downloading = download?.state == DownloadState.QUEUED || download?.state == DownloadState.RUNNING
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        download?.let { DownloadStatus(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (available) {
                ActionButton("Read", LucideIcons.BookOpen, primary = true) { onRead(book.id) }
                ActionButton("Delete", LucideIcons.Trash) {
                    if (inFolder) confirmDelete = true else viewModel.removeLocalCopy(onRemoved)
                }
            } else if (book.source == BookSource.SERVER) {
                // Reading online needs no download permission; nothing is kept beyond an evictable cache.
                if (state.canReadOnline) ActionButton("Read online", LucideIcons.Globe, primary = true) { onRead(book.id) }
                when {
                    !state.canDownload -> Unit
                    downloading -> ActionButton("Cancel", LucideIcons.X, onClick = viewModel::cancelDownload)
                    download?.state == DownloadState.FAILED -> ActionButton("Retry", LucideIcons.Download, onClick = viewModel::download)
                    else -> ActionButton("Download", LucideIcons.Download, onClick = viewModel::download)
                }
            }
        }
        if (!available && book.source == BookSource.SERVER && !state.canDownload) {
            Text("Your account cannot download this book.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete file?") },
            text = { Text("This file is in a folder you added. Deleting removes it from that folder for good.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.removeLocalCopy(onRemoved)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Progress of a running download, or a note that it failed. */
@Composable
private fun DownloadStatus(download: DownloadEntity) {
    when (download.state) {
        DownloadState.QUEUED, DownloadState.RUNNING -> {
            val total = download.bytesTotal
            if (total != null && total > 0) {
                LinearProgressIndicator(progress = { download.bytesDone.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        DownloadState.FAILED -> Text("Download failed; it will retry when possible.", color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}

/** One of the two side-by-side action buttons; [primary] is filled, the other outlined. */
@Composable
private fun RowScope.ActionButton(
    label: String,
    icon: ImageVector,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 8.dp), maxLines = 1)
    }
    if (primary) {
        Button(onClick = onClick, modifier = Modifier.weight(1f), content = content)
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f), content = content)
    }
}
