package com.c0mpile.grimmreader.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.c0mpile.grimmreader.core.data.library.BookFolder

/** Download folder (one, or app storage) and the folders whose books are read in place. */
@Composable
internal fun StorageSettings(
    state: SettingsUiState,
    onDownloadFolder: (Uri?) -> Unit,
    onAddBookFolder: (Uri) -> Unit,
    onRemoveBookFolder: (String) -> Unit,
    onRescan: () -> Unit,
) {
    val pickDownload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(onDownloadFolder) }
    val pickBooks = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(onAddBookFolder) }

    Text("Downloads", style = MaterialTheme.typography.titleSmall)
    val download = state.downloadFolder
    FolderLine(
        name = download?.name ?: "App storage",
        detail =
            when {
                download == null -> "Private to the app; removed when the app is uninstalled."
                !download.accessible -> "No access any more. Downloads fail until you pick a folder again."
                else -> "New downloads are saved here. Earlier downloads stay where they are."
            },
        warning = download?.accessible == false,
    ) {
        TextButton(onClick = { pickDownload.launch(null) }) { Text("Choose") }
        if (download != null) TextButton(onClick = { onDownloadFolder(null) }) { Text("Use app storage") }
    }

    Text("Book folders", style = MaterialTheme.typography.titleSmall)
    Text(
        "Books in these folders and their subfolders are read in place, without copying.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.bookFolders.forEach { folder -> BookFolderLine(folder, onRemoveBookFolder) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { pickBooks.launch(null) }) { Text("Add folder") }
        if (state.bookFolders.isNotEmpty()) OutlinedButton(onClick = onRescan, enabled = !state.scanning) { Text("Scan now") }
        if (state.scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun BookFolderLine(
    folder: BookFolder,
    onRemove: (String) -> Unit,
) = FolderLine(
    name = folder.name,
    detail = if (folder.accessible) null else "No access any more. Remove it and add it again.",
    warning = !folder.accessible,
) {
    TextButton(onClick = { onRemove(folder.uri) }) { Text("Remove") }
}

/** Name and detail, actions on their own line under them so long texts keep the full width. */
@Composable
private fun FolderLine(
    name: String,
    detail: String?,
    warning: Boolean,
    actions: @Composable () -> Unit,
) {
    Column {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.offset(x = (-12).dp)) { actions() }
    }
}
