package com.c0mpile.grimmreader.feature.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** First run: the app folder for saved books and an optional folder of books read in place. Both are in Settings too. */
@Composable
fun FirstRunScreen(viewModel: FirstRunViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pickApp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(viewModel::setAppFolder) }
    val pickContent = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(viewModel::addContentFolder) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("GrimmReader", style = MaterialTheme.typography.headlineMedium)
            Text("App folder", style = MaterialTheme.typography.titleSmall)
            Text(
                "Where the app keeps books it saves. Without one they stay in private app storage, " +
                    "which is removed when the app is uninstalled.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(state.appFolder?.name ?: "App storage", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickApp.launch(null) }) { Text("Choose folder") }
                if (state.appFolder != null) TextButton(onClick = { viewModel.setAppFolder(null) }) { Text("Use app storage") }
            }
            Text("Local content folder (optional)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Books in this folder and its subfolders are read in place, without copying.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.contentFolders.forEach { folder ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(folder.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.removeContentFolder(folder.uri) }) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = { pickContent.launch(null) }) { Text("Add content folder") }
            Button(onClick = viewModel::finish, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            Text(
                "You can change these, or connect a Grimmory server, later in Settings.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
