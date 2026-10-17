package com.c0mpile.grimmreader.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.c0mpile.grimmreader.core.model.LibraryView

/**
 * Previous / next, the page number (tap to type one) and how many books to show per page. Hidden while
 * everything fits on the smallest page size.
 */
@Composable
internal fun PaginationBar(
    state: LibraryUiState,
    onPage: (Int) -> Unit,
    onPageSize: (Int) -> Unit,
) {
    if (state.total <= LibraryView.PAGE_SIZES.first()) return
    Column {
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
            PageSizeMenu(state.view.pageSize, onPageSize)
        }
    }
}

@Composable
private fun PageNumber(
    state: LibraryUiState,
    onPage: (Int) -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    TextButton(onClick = { asking = true }) { Text("${state.page + 1} / ${state.pageCount}") }
    if (asking) {
        var text by remember { mutableStateOf((state.page + 1).toString()) }
        val target = text.toIntOrNull()?.takeIf { it in 1..state.pageCount }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Go to page") },
            text = {
                TextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(MAX_DIGITS) },
                    label = { Text("1 to ${state.pageCount}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = target != null,
                    onClick = {
                        asking = false
                        target?.let { onPage(it - 1) }
                    },
                ) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PageSizeMenu(
    size: Int,
    onSize: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$size per page") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibraryView.PAGE_SIZES.forEach { option ->
                DropdownMenuItem(
                    text = { Text("$option per page") },
                    trailingIcon = { if (option == size) Icon(LucideIcons.Check, contentDescription = null) },
                    onClick = {
                        onSize(option)
                        open = false
                    },
                )
            }
        }
    }
}

private const val MAX_DIGITS = 6
private const val DIVIDER_ALPHA = 0.4f
