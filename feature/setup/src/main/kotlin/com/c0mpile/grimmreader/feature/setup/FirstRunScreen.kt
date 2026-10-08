package com.c0mpile.grimmreader.feature.setup

import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.data.server.LoginResult
import com.c0mpile.grimmreader.core.designsystem.component.LibraryFolderList

/**
 * First run, in two steps: connect a Grimmory server (optional; its libraries become libraries on the device),
 * then the app folder for saved books and a folder for each library. Everything is in Settings too.
 */
@Composable
fun FirstRunScreen(
    devServerUrl: String,
    viewModel: FirstRunViewModel = hiltViewModel(),
    serverViewModel: SetupViewModel = hiltViewModel(),
) {
    var step by rememberSaveable { mutableStateOf(STEP_SERVER) }
    val server by serverViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(devServerUrl) { serverViewModel.prefill(devServerUrl) }
    LaunchedEffect(server.loginResult) { if (server.loginResult is LoginResult.Ok) step = STEP_FOLDERS }
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
            if (step == STEP_SERVER) {
                Text("Connect a server", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Optional. Connect your Grimmory server and its libraries are set up on this device too. " +
                        "Without one, GrimmReader is a local reader.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ServerForm(server, serverViewModel)
                OutlinedButton(
                    onClick = { step = STEP_FOLDERS },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Skip, use it as a local reader") }
            } else {
                FoldersStep(viewModel)
            }
        }
    }
    CertificateDialog(server, serverViewModel)
}

@Composable
private fun FoldersStep(viewModel: FirstRunViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pickApp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(viewModel::setAppFolder) }
    var pickingFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val pickLibrary =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            val library = pickingFor
            if (tree != null && library != null) viewModel.setLibraryFolder(library, tree)
            pickingFor = null
        }
    Text("App folder", style = MaterialTheme.typography.titleSmall)
    Text(
        "Where the app keeps books it downloads" +
            (if (state.hasServer) ", in a subfolder for each library" else "") +
            ". Without one they stay in private app storage, which is removed when the app is uninstalled.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(state.appFolder?.name ?: "App storage", style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickApp.launch(null) }) { Text("Choose folder") }
        if (state.appFolder != null) TextButton(onClick = { viewModel.setAppFolder(null) }) { Text("Use app storage") }
    }
    Text("Libraries", style = MaterialTheme.typography.titleSmall)
    Text(
        (if (state.hasServer) "These are your server's libraries. " else "") +
            "Give a library a folder and every book you put in it (and its subfolders) shows up there, read in place. " +
            "Folders are optional." +
            if (state.libraries.isEmpty()) " Add one library for each kind of content, say Books, Comics or Magazines." else "",
        style = MaterialTheme.typography.bodySmall,
    )
    LibraryFolderList(
        libraries = state.libraries,
        onChooseFolder = {
            pickingFor = it.id
            pickLibrary.launch(null)
        },
        onClearFolder = { viewModel.setLibraryFolder(it.id, null) },
        onDelete = { viewModel.deleteLibrary(it.id) },
        onAdd = viewModel::addLibrary,
    )
    Button(onClick = viewModel::finish, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    Text(
        "You can change all of this later in Settings.",
        style = MaterialTheme.typography.bodySmall,
    )
}

private const val STEP_SERVER = 0
private const val STEP_FOLDERS = 1
