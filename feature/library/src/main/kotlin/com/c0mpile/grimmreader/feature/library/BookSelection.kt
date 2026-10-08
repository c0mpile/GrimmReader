package com.c0mpile.grimmreader.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.model.Book

/**
 * Books picked for a bulk action. A long press on a book starts selecting; while any book is selected, a tap
 * checks or unchecks it instead of opening its drawer, and Back clears the selection.
 */
@Stable
internal class BookSelection(
    ids: Set<Long>,
) {
    var ids by mutableStateOf(ids)
        private set

    val active: Boolean get() = ids.isNotEmpty()

    fun isSelected(bookId: Long) = bookId in ids

    fun toggle(bookId: Long) {
        ids = if (bookId in ids) ids - bookId else ids + bookId
    }

    /** Selects every listed book, or clears the selection when all of them are selected already. */
    fun toggleAll(listed: Collection<Long>) {
        ids = if (ids.containsAll(listed)) emptySet() else listed.toSet()
    }

    fun clear() {
        ids = emptySet()
    }

    /** Drops books that are no longer listed (deleted, or hidden by the search). */
    fun retain(listed: Set<Long>) {
        if (!listed.containsAll(ids)) ids = ids intersect listed
    }

    companion object {
        val Saver = Saver<BookSelection, LongArray>(save = { it.ids.toLongArray() }, restore = { BookSelection(it.toSet()) })
    }
}

@Composable
internal fun rememberBookSelection() = rememberSaveable(saver = BookSelection.Saver) { BookSelection(emptySet()) }

/** Back clears the selection instead of leaving the screen. */
@Composable
internal fun SelectionBackHandler(selection: BookSelection) {
    val back = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(state = back, isBackEnabled = selection.active, onBackCompleted = selection::clear)
}

/** Replaces the screen's top bar while selecting: close, how many are selected, select or deselect all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    selection: BookSelection,
    books: List<Book>,
) {
    val all = books.isNotEmpty() && selection.ids.size == books.size
    TopAppBar(
        navigationIcon = { IconButton(onClick = selection::clear) { Icon(LucideIcons.X, contentDescription = "Clear selection") } },
        title = { Text("${selection.ids.size} selected", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            IconButton(onClick = { selection.toggleAll(books.map { it.id }) }) {
                Icon(
                    LucideIcons.CheckCheck,
                    contentDescription = if (all) "Deselect all" else "Select all",
                    tint = if (all) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )
}

/**
 * The actions for the selected books, sliding up from the bottom: Download (only when server books are
 * selected), Delete (files on this device only) and Reset progress, the last two after a confirmation.
 * Shows its results in [snackbar] and clears the selection once an action is started.
 */
@Composable
internal fun SelectionActionBar(
    selection: BookSelection,
    books: List<Book>,
    snackbar: SnackbarHostState,
    viewModel: BookSelectionViewModel = hiltViewModel(),
) {
    val canDownload by viewModel.canDownload.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    var confirm by rememberSaveable { mutableStateOf<SelectionAction?>(null) }
    val summary = SelectionSummary(books.filter { selection.isSelected(it.id) }, canDownload)
    AnimatedVisibility(
        selection.active,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (summary.hasServerBooks) {
                    SelectionButton("Download", LucideIcons.Download, enabled = summary.toDownload.isNotEmpty()) {
                        viewModel.download(summary)
                        selection.clear()
                    }
                }
                SelectionButton("Delete", LucideIcons.Trash, enabled = summary.toDelete.isNotEmpty()) { confirm = SelectionAction.DELETE }
                SelectionButton("Reset progress", LucideIcons.RotateCcw, enabled = summary.books.isNotEmpty()) {
                    confirm = SelectionAction.RESET
                }
            }
        }
    }
    when (confirm) {
        SelectionAction.DELETE ->
            ConfirmDialog(
                title = "Delete ${fileLabel(summary.toDelete.size)}?",
                text = deleteText(summary),
                confirm = "Delete",
                onDismiss = { confirm = null },
            ) {
                viewModel.delete(summary)
                selection.clear()
            }
        SelectionAction.RESET ->
            ConfirmDialog(
                title = "Reset reading progress?",
                text = resetText(summary),
                confirm = "Reset",
                onDismiss = { confirm = null },
            ) {
                viewModel.resetProgress(summary)
                selection.clear()
            }
        null -> Unit
    }
}

internal enum class SelectionAction { DELETE, RESET }

internal fun deleteText(summary: SelectionSummary): String =
    buildList {
        val files = if (summary.toDelete.size == 1) "The file is" else "The ${summary.toDelete.size} files are"
        add("$files deleted from this device. Nothing is deleted from the server.")
        if (summary.serverCopies > 0) add("Server books stay in the library and can be downloaded again.")
        if (summary.serverCopies < summary.toDelete.size) add("Books that are only on this device leave the library.")
        if (summary.inFolders > 0) add("${fileLabel(summary.inFolders)} in folders you added will be deleted from those folders.")
        val skipped = summary.books.size - summary.toDelete.size
        if (skipped > 0) add("${countLabel(skipped)} without a file on this device ${if (skipped == 1) "is" else "are"} left alone.")
    }.joinToString("\n\n")

internal fun resetText(summary: SelectionSummary): String =
    buildList {
        val books = if (summary.books.size == 1) "This book starts" else "These ${summary.books.size} books start"
        add("$books from the beginning again and leave Continue Reading.")
        if (summary.hasServerBooks) add("The progress is also reset on the server.")
        add("Bookmarks, highlights and notes are kept.")
    }.joinToString("\n\n")

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Icon over label, so three actions fit side by side on a phone. */
@Composable
private fun RowScope.SelectionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
