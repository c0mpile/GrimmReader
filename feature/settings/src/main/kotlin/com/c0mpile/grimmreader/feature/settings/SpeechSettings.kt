package com.c0mpile.grimmreader.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.datastore.SpeechProvider
import com.c0mpile.grimmreader.core.tts.SpeechCatalog
import java.util.Locale

private const val MIN_RATE = 0.5f
private const val MAX_RATE = 3f
private const val MIN_PITCH = 0.5f
private const val MAX_PITCH = 2f
private const val SAMPLE = "This is how this voice sounds when reading your book."

/**
 * Text to speech. Two switches, never both on: System (any installed Android engine and voice, used for the
 * speaker icon, Read aloud and reading the book aloud) or ElevenLabs (speaker icon and Read aloud only).
 */
@Composable
internal fun SpeechSettings(viewModel: SpeechSettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.speech.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val system = prefs.provider == SpeechProvider.SYSTEM
    LaunchedEffect(system, prefs.engine) { if (system) viewModel.loadCatalog() }
    SwitchRow(
        "System text-to-speech",
        "Uses the voices installed on this device through Android's text-to-speech, including other engines such as " +
            "VoxSherpa. Used for the speaker icon, Read aloud and reading a book aloud, which also works in the " +
            "background and with the screen off. Nothing is sent over the network by this app.",
        system,
        viewModel::setSystemSpeech,
    )
    if (system) {
        SystemOptions(viewModel, prefs.engine, prefs.voice, prefs.rate, prefs.pitch, catalog)
    }
    HorizontalDivider()
    val eleven = prefs.provider == SpeechProvider.ELEVENLABS
    SwitchRow(
        "ElevenLabs",
        "The speaker icon in the dictionary and Read aloud use ElevenLabs. Only the word or passage you tap is sent, " +
            "over HTTPS, and only when you tap. Reading a whole book aloud needs System text-to-speech.",
        eleven,
        viewModel::setElevenLabsSpeech,
    )
    if (eleven) {
        ElevenLabsFields(
            viewModel.hasElevenLabsKey.collectAsStateWithLifecycle().value == true,
            viewModel.elevenLabsVoice.collectAsStateWithLifecycle().value,
            viewModel::setElevenLabsKey,
            viewModel::setElevenLabsVoice,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SystemOptions(
    viewModel: SpeechSettingsViewModel,
    engine: String?,
    voice: String?,
    rate: Float,
    pitch: Float,
    catalog: SpeechCatalog?,
) {
    val context = LocalContext.current
    var pickEngine by remember { mutableStateOf(false) }
    var pickVoice by remember { mutableStateOf(false) }
    if (catalog == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
            Text("Starting the speech engine…", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    catalog.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    val engineLabel =
        catalog.engines.firstOrNull { it.packageName == engine }?.label
            ?: if (engine == null) "System default${catalog.defaultEngineLabel?.let { " ($it)" }.orEmpty()}" else engine
    val voiceLabel = catalog.voices.firstOrNull { it.name == voice }?.let { "${it.language} · ${it.name}" } ?: voice ?: "Engine default"
    ChoiceRow("Speech engine", engineLabel) { pickEngine = true }
    ChoiceRow("Voice", voiceLabel) { pickVoice = true }
    Text(
        "Voice models are installed in the engine's own app (for VoxSherpa, download them there). Pick the engine here, " +
            "then its voice.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LabeledSlider("Speed", rate, MIN_RATE..MAX_RATE, viewModel::setRate)
    LabeledSlider("Pitch", pitch, MIN_PITCH..MAX_PITCH, viewModel::setPitch)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.speakSample(SAMPLE) }) { Text("Test voice") }
        OutlinedButton(onClick = {
            try {
                context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }) { Text("Android speech settings") }
    }
    if (pickEngine) {
        val options =
            listOf<Pair<String?, String>>(null to "System default") + catalog.engines.map { it.packageName to it.label }
        ChoiceDialog("Speech engine", options, engine, searchable = false, onPick = viewModel::setEngine) { pickEngine = false }
    }
    if (pickVoice) {
        val options =
            listOf<Pair<String?, String>>(null to "Engine default") +
                catalog.voices.map { it.name to "${it.language} · ${it.name}${if (it.needsNetwork) " (online)" else ""}" }
        ChoiceDialog("Voice", options, voice, searchable = options.size > SEARCH_FROM, onPick = viewModel::setVoice) {
            pickVoice = false
        }
    }
}

private const val SEARCH_FROM = 12

@Composable
private fun ChoiceRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    var shown by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label ${String.format(Locale.US, "%.1f", shown)}×", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = shown,
            onValueChange = { shown = it },
            onValueChangeFinished = { onChange(Math.round(shown * 10) / 10f) },
            valueRange = range,
        )
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String?, String>>,
    selected: String?,
    searchable: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = options.filter { query.isBlank() || it.second.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (searchable) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        singleLine = true,
                        label = { Text("Search") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown) { (id, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(id)
                                    onDismiss()
                                }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = id == selected, onClick = null)
                            Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ElevenLabsFields(
    hasKey: Boolean,
    voice: String?,
    onSave: (String?) -> Unit,
    onSaveVoice: (String?) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var voiceText by remember(voice) { mutableStateOf(voice.orEmpty()) }
    Text(
        "Your ElevenLabs API key is stored encrypted on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (hasKey) "API key saved; enter a new one to replace" else "ElevenLabs API key") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                onSave(key)
                key = ""
            },
            enabled = key.isNotBlank(),
        ) { Text("Save key") }
        if (hasKey) OutlinedButton(onClick = { onSave(null) }) { Text("Remove key") }
    }
    OutlinedTextField(
        value = voiceText,
        onValueChange = { voiceText = it },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("ElevenLabs voice ID") },
        supportingText = { Text("A voice your plan can use through the API (free plans: default voices only).") },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
    Button(onClick = { onSaveVoice(voiceText) }, enabled = voiceText.trim() != voice.orEmpty()) { Text("Save voice") }
}
