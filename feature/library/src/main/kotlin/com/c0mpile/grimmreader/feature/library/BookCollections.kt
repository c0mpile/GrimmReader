package com.c0mpile.grimmreader.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.c0mpile.grimmreader.core.designsystem.component.BookCard
import com.c0mpile.grimmreader.core.designsystem.component.BookCover
import com.c0mpile.grimmreader.core.designsystem.component.ProgressStrip
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookLayout

private val GRID_CELL = 116.dp
private val LIST_COVER = 56.dp
private val AUTHOR_COVER = 40.dp

/** Switches between the cover grid and the list. */
@Composable
fun LayoutToggle(
    layout: BookLayout,
    onLayout: (BookLayout) -> Unit,
) {
    val list = layout == BookLayout.LIST
    IconButton(onClick = { onLayout(if (list) BookLayout.GRID else BookLayout.LIST) }) {
        Icon(
            if (list) LucideIcons.LayoutGrid else LucideIcons.List,
            contentDescription = if (list) "Show as grid" else "Show as list",
        )
    }
}

@Composable
fun BookCollection(
    books: List<Book>,
    layout: BookLayout,
    onOpenBook: (Long) -> Unit,
) {
    if (layout == BookLayout.GRID) {
        CoverGrid {
            items(books, key = { it.id }) { book ->
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
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(books, key = { it.id }) { book -> BookRow(book) { onOpenBook(book.id) } }
        }
    }
}

/** Cover thumbnail, the full title, authors, series and format, with reading progress underneath. */
@Composable
private fun BookRow(
    book: Book,
    onClick: () -> Unit,
) {
    ListRow(onClick, cover = { BookCover(book.title, book.coverUri, Modifier.width(LIST_COVER)) }) {
        Text(book.title, style = MaterialTheme.typography.titleMedium)
        if (book.authors.isNotEmpty()) {
            Text(
                book.authors.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val details =
            listOfNotNull(
                book.seriesName?.let { s ->
                    listOfNotNull(s, book.seriesNumber?.let { "#${it.toString().removeSuffix(".0")}" }).joinToString(" ")
                },
                book.primaryFile?.format?.name,
                book.progressPercent?.takeIf { it > 0 }?.let { "%.0f %%".format(it) },
            )
        if (details.isNotEmpty()) {
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        book.progressPercent?.takeIf { it > 0 }?.let {
            ProgressStrip(it, Modifier.padding(top = 4.dp).fillMaxWidth(PROGRESS_WIDTH).height(4.dp))
        }
    }
}

private const val PROGRESS_WIDTH = 0.5f

/** Authors are always a single-column list: cover of one of their books, name and book count. */
@Composable
fun AuthorList(
    groups: List<BookGroup>,
    onOpen: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(groups, key = { it.name }) { group -> GroupRow(group, AUTHOR_COVER) { onOpen(group.name) } }
    }
}

@Composable
fun SeriesCollection(
    groups: List<BookGroup>,
    layout: BookLayout,
    onOpen: (String) -> Unit,
) {
    if (layout == BookLayout.GRID) {
        CoverGrid {
            items(groups, key = { it.name }) { group ->
                BookCard(
                    title = group.name,
                    author = countLabel(group.books.size),
                    coverModel = group.coverUri,
                    onClick = { onOpen(group.name) },
                )
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(groups, key = { it.name }) { group -> GroupRow(group, LIST_COVER) { onOpen(group.name) } }
        }
    }
}

@Composable
private fun GroupRow(
    group: BookGroup,
    coverWidth: Dp,
    onClick: () -> Unit,
) {
    ListRow(
        onClick,
        cover = { BookCover(group.name, group.coverUri, Modifier.width(coverWidth)) },
        trailing = { Icon(LucideIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    ) {
        Text(group.name, style = MaterialTheme.typography.titleMedium)
        Text(countLabel(group.books.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ListRow(
    onClick: () -> Unit,
    cover: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            cover()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
            Box { trailing() }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = DIVIDER_ALPHA))
    }
}

private const val DIVIDER_ALPHA = 0.4f

@Composable
private fun CoverGrid(content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(GRID_CELL),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

internal fun countLabel(n: Int) = if (n == 1) "1 book" else "$n books"
