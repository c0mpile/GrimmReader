package com.c0mpile.grimmreader.feature.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.designsystem.component.BookCover
import com.c0mpile.grimmreader.core.designsystem.component.SidebarButton
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.model.NotebookBook
import com.c0mpile.grimmreader.core.model.NotebookEntry
import com.c0mpile.grimmreader.core.model.NotebookEntryType
import java.text.DateFormat
import java.util.Date

/** Books with highlights, notes or bookmarks (from the server), like the web Notebook. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(
    onOpen: (NotebookBook) -> Unit,
    viewModel: NotebookViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(navigationIcon = { SidebarButton() }, title = { Text("Notebook") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                query,
                viewModel::setQuery,
                placeholder = { Text("Search notes and highlights") },
                leadingIcon = { Icon(LucideIcons.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Loaded(books, empty = "No highlights, notes or bookmarks yet.", onRetry = { viewModel.reload() }) { list ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(list, key = { it.serverBookId }) { book -> BookRow(book) { onOpen(book) } }
                }
            }
        }
    }
}

/** One book's highlights, notes and bookmarks, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookBookScreen(
    serverBookId: Long,
    title: String,
    localBookId: Long?,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    viewModel: NotebookBookViewModel =
        hiltViewModel<NotebookBookViewModel, NotebookBookViewModel.Factory>(key = "notebook/$serverBookId") { it.create(serverBookId) },
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(LucideIcons.ArrowLeft, contentDescription = "Back") } },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { localBookId?.let { id -> TextButton(onClick = { onOpenBook(id) }) { Text("Open book") } } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Loaded(entries, empty = "Nothing in the notebook for this book.", onRetry = { viewModel.reload() }) { list ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { items(list, key = { "${it.type}/${it.id}" }) { EntryCard(it) } }
            }
        }
    }
}

@Composable
private fun <T : List<*>> Loaded(
    state: Loadable<T>,
    empty: String,
    onRetry: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    val value = state.value
    when {
        value == null && state.loading -> Centered { CircularProgressIndicator() }
        value == null ->
            Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The notebook is on the server. Check the connection.", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
        value.isEmpty() -> Centered { Text(empty, style = MaterialTheme.typography.bodyLarge) }
        else -> content(value)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { content() }

@Composable
private fun BookRow(
    book: NotebookBook,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BookCover(book.title, book.coverUri, Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.authors.isNotEmpty()) {
                Text(
                    book.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("${book.entries}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A colour bar (the highlight colour), the kind and chapter, the passage in italics, the note, the date. */
@Composable
private fun EntryCard(entry: NotebookEntry) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(colors.surface, RoundedCornerShape(8.dp)),
    ) {
        Box(
            Modifier
                .width(
                    4.dp,
                ).fillMaxHeight()
                .background(entry.color?.toColor() ?: colors.primary, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)),
        )
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                listOfNotNull(entry.type.label(), entry.chapter).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            entry.text?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic) }
            entry.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            entry.createdAt?.let {
                Text(
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

private fun NotebookEntryType.label() =
    when (this) {
        NotebookEntryType.HIGHLIGHT -> "Highlight"
        NotebookEntryType.NOTE -> "Note"
        NotebookEntryType.BOOKMARK -> "Bookmark"
        NotebookEntryType.OTHER -> "Entry"
    }

private fun String.toColor(): Color? = removePrefix("#").takeIf { it.length == HEX_RGB }?.toLongOrNull(HEX)?.let { Color(OPAQUE or it) }

private const val HEX_RGB = 6
private const val HEX = 16
private const val OPAQUE = 0xFF000000
