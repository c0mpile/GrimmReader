package com.c0mpile.grimmreader.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.model.BrowseMode
import com.c0mpile.grimmreader.core.model.LibraryView

/**
 * Left: previous, "page / pages" (tap to type a page) and next. Right: "shown / total" (tap to type how many to
 * show per page). Hidden while everything fits on one default-sized page.
 */
@Composable
internal fun PaginationBar(
    state: LibraryUiState,
    onPage: (Int) -> Unit,
    onPageSize: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.pageCount <= 1 && state.total <= LibraryView.DEFAULT_PAGE_SIZE) return
    val shown = if (state.mode == BrowseMode.BOOKS) state.books.size else state.groups.size
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = DIVIDER_ALPHA))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onPage(state.page - 1) }, enabled = state.page > 0) {
                    Icon(LucideIcons.ChevronLeft, contentDescription = "Previous page")
                }
                PageNumber(state, onPage)
                IconButton(onClick = { onPage(state.page + 1) }, enabled = state.page < state.pageCount - 1) {
                    Icon(LucideIcons.ChevronRight, contentDescription = "Next page")
                }
            }
            PageSizeButton(shown, state.total, state.view.pageSize, onPageSize)
        }
    }
}

@Composable
private fun PageNumber(
    state: LibraryUiState,
    onPage: (Int) -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    TextButton(onClick = { asking = true }) { Text("${state.page + 1}/${state.pageCount}") }
    if (asking) {
        NumberDialog(
            title = "Go to page",
            label = "1 to ${state.pageCount}",
            initial = state.page + 1,
            range = 1..state.pageCount,
            onDismiss = { asking = false },
            onDone = { onPage(it - 1) },
        )
    }
}

@Composable
private fun PageSizeButton(
    shown: Int,
    total: Int,
    size: Int,
    onSize: (Int) -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    TextButton(onClick = { asking = true }) { Text("$shown/$total") }
    if (asking) {
        NumberDialog(
            title = "Books per page",
            label = "1 to ${LibraryView.MAX_PAGE_SIZE}",
            initial = size,
            range = 1..LibraryView.MAX_PAGE_SIZE,
            onDismiss = { asking = false },
            onDone = onSize,
        )
    }
}

/** A box to type a whole number in [range]; Go stays off until the text is one. */
@Composable
private fun NumberDialog(
    title: String,
    label: String,
    initial: Int,
    range: IntRange,
    onDismiss: () -> Unit,
    onDone: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in range }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(MAX_DIGITS) },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                enabled = value != null,
                onClick = {
                    onDismiss()
                    value?.let(onDone)
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAX_DIGITS = 6
private const val DIVIDER_ALPHA = 0.4f
