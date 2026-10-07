package com.c0mpile.grimmreader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.designsystem.theme.LocalMotionEnabled
import com.c0mpile.grimmreader.core.model.ReaderPrefs
import com.c0mpile.grimmreader.reader.ebook.EbookController
import com.c0mpile.grimmreader.reader.ebook.EbookCss
import com.c0mpile.grimmreader.reader.ebook.EbookEvent
import com.c0mpile.grimmreader.reader.ebook.EbookReader
import com.c0mpile.grimmreader.reader.ebook.PageColors
import com.c0mpile.grimmreader.reader.paged.PagedReader

private const val MIN_FONT = 12
private const val MAX_FONT = 36

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel<ReaderViewModel, ReaderViewModel.Factory> { it.create(bookId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.readerPrefs.collectAsStateWithLifecycle()
    var chrome by rememberSaveable { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    val motion = LocalMotionEnabled.current
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.flush() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Content(state.content, prefs, viewModel, onToggleChrome = { chrome = !chrome })
        AnimatedVisibility(chrome, enter = if (motion) fadeIn() else fadeIn(snap()), exit = if (motion) fadeOut() else fadeOut(snap())) {
            Chrome(state, onBack = onBack, onSettings = { settings = true })
        }
    }
    if (settings) {
        ModalBottomSheet(onDismissRequest = { settings = false }) { ReaderSettings(prefs, viewModel::setReaderPrefs) }
    }
    state.offer?.let { offer ->
        AlertDialog(
            onDismissRequest = viewModel::dismissOffer,
            title = { Text("Continue where you left off?") },
            text = { Text("This book was read further on another device or the web (%.0f %%).".format(offer.locator.percent)) },
            confirmButton = { TextButton(onClick = viewModel::acceptOffer) { Text("Go there") } },
            dismissButton = { TextButton(onClick = viewModel::dismissOffer) { Text("Stay here") } },
        )
    }
}

private fun <T> snap() =
    androidx.compose.animation.core
        .snap<T>()

@Composable
private fun Content(
    content: ReaderContent,
    prefs: ReaderPrefs,
    viewModel: ReaderViewModel,
    onToggleChrome: () -> Unit,
) {
    when (content) {
        ReaderContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ReaderContent.Ebook -> {
            val colors = MaterialTheme.colorScheme
            val css = EbookCss.build(prefs, PageColors(colors.background.hex(), colors.onBackground.hex(), colors.primary.hex()))
            val controller = remember(content.restoreKey) { EbookController() }
            key(content.file, content.restoreKey) {
                EbookReader(
                    file = content.file,
                    initialCfi = content.cfi,
                    css = css,
                    animated = LocalMotionEnabled.current,
                    controller = controller,
                    onEvent = { event ->
                        if (event is EbookEvent.Relocated) viewModel.onEbookPosition(event.locator, event.tocLabel, event.hasPosition)
                    },
                    onToggleChrome = onToggleChrome,
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }
        is ReaderContent.Paged ->
            key(content.restoreKey) {
                PagedReader(content.source, content.page, onPage = viewModel::onPage, onToggleChrome = onToggleChrome)
            }
        is ReaderContent.Unsupported -> Message("Reading ${content.format.name} files arrives in the next milestone (M1).")
        is ReaderContent.Fetching ->
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Loading from the server…", style = MaterialTheme.typography.bodyLarge)
                val p = content.progress
                if (p == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            }
        is ReaderContent.Failed -> Message(content.message)
        ReaderContent.NotDownloaded -> Message("This book's file is not on this device.")
    }
}

@Composable
private fun Message(text: String) {
    Box(
        Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

/** Reader chrome is dark in every theme, like the web reader. */
@Composable
private fun Chrome(
    state: ReaderUiState,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xE6171717))
                .safeDrawingPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(LucideIcons.ArrowLeft, contentDescription = "Back", tint = Color.White) }
            Text(state.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(LucideIcons.Settings, contentDescription = "Reading settings", tint = Color.White) }
        }
        Box(Modifier.weight(1f))
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xE6171717))
                .safeDrawingPadding()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(state.location.orEmpty(), color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            state.percent?.let { Text("%.0f %%".format(it), color = Color.White.copy(alpha = 0.6f)) }
        }
    }
}

@Composable
private fun ReaderSettings(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Font size", Modifier.weight(1f))
            TextButton(onClick = { onChange(prefs.copy(fontSize = (prefs.fontSize - 1).coerceAtLeast(MIN_FONT))) }) { Text("A−") }
            Text("${prefs.fontSize}")
            TextButton(onClick = { onChange(prefs.copy(fontSize = (prefs.fontSize + 1).coerceAtMost(MAX_FONT))) }) { Text("A+") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Serif", "Sans", "Publisher").forEach { family ->
                FilterChip(
                    selected = prefs.fontFamily.equals(family, ignoreCase = true) || (family == "Serif" && prefs.fontFamily == "Literata"),
                    onClick = { onChange(prefs.copy(fontFamily = family)) },
                    label = { Text(family) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Justify text", Modifier.weight(1f))
            Switch(checked = prefs.justify, onCheckedChange = { onChange(prefs.copy(justify = it)) })
        }
    }
}

private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)
