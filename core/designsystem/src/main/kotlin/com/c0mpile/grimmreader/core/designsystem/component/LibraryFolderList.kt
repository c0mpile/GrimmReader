package com.c0mpile.grimmreader.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.c0mpile.grimmreader.core.model.LocalLibrary

/**
 * The on-device libraries with their optional watch folder (first run and Settings → Storage). A library that
 * mirrors a server library follows the server's name and cannot be deleted; the user's own can be.
 * [onChooseFolder] opens the folder picker for the library; [onSetComics] sets its media type (comics or books).
 */
@Composable
fun LibraryFolderList(
    libraries: List<LocalLibrary>,
    onChooseFolder: (LocalLibrary) -> Unit,
    onClearFolder: (LocalLibrary) -> Unit,
    onDelete: (LocalLibrary) -> Unit,
    onAdd: (String) -> Unit,
    onSetComics: (LocalLibrary, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        libraries.forEach { library ->
            Column {
                Text(library.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        !library.folderAccessible -> "${library.folderName}: no access any more. Choose the folder again."
                        library.folderName != null -> "Folder: ${library.folderName}"
                        library.serverLibraryId != null -> "From the server. No folder; downloads only."
                        else -> "No folder."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (library.folderAccessible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Comics library", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = library.isComics, onCheckedChange = { onSetComics(library, it) })
                }
                Row(Modifier.offset(x = (-12).dp)) {
                    TextButton(onClick = { onChooseFolder(library) }) {
                        Text(if (library.folderUri == null) "Choose folder" else "Change folder")
                    }
                    if (library.folderUri != null) TextButton(onClick = { onClearFolder(library) }) { Text("Remove folder") }
                    if (library.serverLibraryId == null) TextButton(onClick = { onDelete(library) }) { Text("Delete library") }
                }
            }
        }
        AddLibraryRow(onAdd)
    }
}

@Composable
private fun AddLibraryRow(onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    fun add() {
        if (name.isBlank()) return
        onAdd(name)
        name = ""
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("New library") },
            placeholder = { Text("Comics") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = ::add, enabled = name.isNotBlank()) { Text("Add") }
    }
}
