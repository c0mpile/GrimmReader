package com.c0mpile.grimmreader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.designsystem.component.SidebarButton
import com.c0mpile.grimmreader.core.designsystem.component.StatusChip
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.model.Appearance
import com.c0mpile.grimmreader.core.model.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    versionName: String,
    onConnectServer: () -> Unit,
    onOpenDictionaries: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(navigationIcon = { SidebarButton() }, title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Appearance")
            AppearanceSettings(state.appearance, viewModel::setAppearance)
            HorizontalDivider()
            Section("Storage")
            StorageSettings(
                state,
                onDownloadFolder = viewModel::setDownloadFolder,
                onAddLibrary = viewModel::addLibrary,
                onLibraryFolder = viewModel::setLibraryFolder,
                onDeleteLibrary = viewModel::deleteLibrary,
                onRescan = viewModel::rescan,
                onForgetRemoved = viewModel::forgetRemovedBooks,
            )
            HorizontalDivider()
            Section("Dictionaries")
            Text(
                "Press and hold a word while reading to look it up. Download dictionaries or add your own StarDict files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenDictionaries) { Text("Manage dictionaries") }
            HorizontalDivider()
            Section("Server")
            val server = state.server
            if (server == null) {
                Text("No server. GrimmReader works as a local reader.")
                Button(onClick = onConnectServer) { Text("Connect to a Grimmory server") }
            } else {
                Text(server.baseUrl, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(server.username, server.serverVersion).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (server.allowCleartext) StatusChip("Unencrypted HTTP", LucideIcons.CircleAlert, warning = true)
                if (server.pinnedSpkiSha256 != null) StatusChip("Self-signed certificate trusted by fingerprint", LucideIcons.CircleAlert)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::signOut) { Text("Sign out") }
                    OutlinedButton(onClick = { confirmRemove = true }) { Text("Remove server") }
                }
                Button(onClick = onConnectServer) { Text("Sign in again or change server") }
            }
            HorizontalDivider()
            Section("About")
            Text("GrimmReader $versionName — AGPL-3.0. Uses foliate-js, fflate, zip.js, Inter and Lucide; see NOTICE.")
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove server?") },
            text = { Text("Keep the books you downloaded on this device? They stay readable as local books.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    viewModel.removeServer(keepDownloads = true)
                }) { Text("Keep downloads") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    viewModel.removeServer(keepDownloads = false)
                }) { Text("Delete them") }
            },
        )
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun AppearanceSettings(
    appearance: Appearance,
    onChange: (Appearance) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            FilterChip(
                selected = appearance.mode == mode,
                onClick = { onChange(appearance.copy(mode = mode)) },
                label = { Text(mode.label()) },
            )
        }
    }
    Text(
        "AMOLED uses true black. Page colours for reading (E-ink, light, sepia, dark, night, AMOLED) are in " +
            "the reading settings while a book is open.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun ThemeMode.label() =
    when (this) {
        ThemeMode.DARK -> "Dark"
        ThemeMode.AMOLED -> "AMOLED"
    }
