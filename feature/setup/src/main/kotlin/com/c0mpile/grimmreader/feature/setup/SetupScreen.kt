package com.c0mpile.grimmreader.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.data.server.ConnectionResult
import com.c0mpile.grimmreader.core.data.server.LoginResult
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons

/** Connect to a Grimmory server; opened from Settings. */
@Composable
fun SetupScreen(
    devServerUrl: String,
    onDone: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(devServerUrl) { viewModel.prefill(devServerUrl) }
    LaunchedEffect(state.loginResult) { if (state.loginResult is LoginResult.Ok) onDone() }
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
            ServerForm(state, viewModel)
        }
    }
    CertificateDialog(state, viewModel)
}

/** Asks whether to trust the certificate the connection test found, if it did. */
@Composable
internal fun CertificateDialog(
    state: SetupUiState,
    viewModel: SetupViewModel,
) {
    state.certificate?.let { cert ->
        AlertDialog(
            onDismissRequest = viewModel::rejectCertificate,
            title = { Text("Untrusted certificate") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This server's certificate is not trusted by Android. Only continue if this fingerprint matches your server.")
                    Text("SHA-256 key fingerprint", style = MaterialTheme.typography.labelMedium)
                    Text(cert.spkiSha256, fontFamily = FontFamily.Monospace)
                    Text("Subject: ${cert.subject}", style = MaterialTheme.typography.bodySmall)
                    Text("Issuer: ${cert.issuer}", style = MaterialTheme.typography.bodySmall)
                    Text("Valid until: ${cert.validUntil}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::trustCertificate) { Text("Trust this key") } },
            dismissButton = { TextButton(onClick = viewModel::rejectCertificate) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun ServerForm(
    state: SetupUiState,
    viewModel: SetupViewModel,
) {
    OutlinedTextField(
        value = state.url,
        onValueChange = viewModel::onUrl,
        label = { Text("Server address") },
        placeholder = { Text("grimmory.example.com") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Allow unencrypted HTTP")
            Text("Only for a server on your own network.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = state.allowCleartext, onCheckedChange = viewModel::onAllowCleartext)
    }
    Button(onClick = viewModel::test, enabled = !state.testing && state.url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text("Test connection")
    }
    if (state.testing) CircularProgressIndicator()
    state.result?.let { ResultMessage(it) }
    if (state.result is ConnectionResult.Ok) {
        OutlinedTextField(
            value = state.username,
            onValueChange = viewModel::onUsername,
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPassword,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = viewModel::signIn,
            enabled = !state.signingIn && state.username.isNotBlank() && state.password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign in") }
        state.loginResult?.let { LoginMessage(it) }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ResultMessage(result: ConnectionResult) {
    val (ok, text) =
        when (result) {
            is ConnectionResult.Ok ->
                true to
                    buildString {
                        append("Grimmory ${result.version ?: "(unknown version)"} found.")
                        if (result.untestedVersion) append(" This version has not been tested with GrimmReader; the API may have changed.")
                    }
            ConnectionResult.InvalidUrl -> false to "That does not look like a server address."
            ConnectionResult.CleartextNotAllowed ->
                false to
                    "This address uses unencrypted HTTP. Turn on \"Allow unencrypted HTTP\" if it is on your network."
            ConnectionResult.Unreachable -> false to "The server could not be reached. Check the address and your connection."
            is ConnectionResult.UntrustedCertificate -> false to "The server's certificate is not trusted."
            ConnectionResult.CertificateChanged -> false to "The server's certificate changed since you trusted it. Connection refused."
            ConnectionResult.TlsError -> false to "A secure connection could not be established."
            ConnectionResult.GatewayLoginPage ->
                false to "A login page answered instead of Grimmory. A proxy or gateway in front of the server must let /api/ through."
            ConnectionResult.NotGrimmory -> false to "A server answered, but it is not Grimmory."
            is ConnectionResult.HttpError -> false to "The server answered with HTTP ${result.code}."
            is ConnectionResult.UnsupportedVersion -> false to "Grimmory ${result.version} is too old. GrimmReader needs 3.5.0 or newer."
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (ok) LucideIcons.Check else LucideIcons.CircleAlert, contentDescription = null)
        Text(text, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LoginMessage(result: LoginResult) {
    val text =
        when (result) {
            is LoginResult.Ok -> return
            LoginResult.WrongCredentials -> "Wrong username or password."
            LoginResult.Unreachable -> "The server could not be reached."
            is LoginResult.Failed -> "Sign-in failed (HTTP ${result.code})."
        }
    Text(text, color = MaterialTheme.colorScheme.error)
}
