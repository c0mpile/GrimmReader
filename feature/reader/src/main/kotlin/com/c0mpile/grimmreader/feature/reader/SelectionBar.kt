package com.c0mpile.grimmreader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.dictionary.PronunciationState
import com.c0mpile.grimmreader.core.model.HIGHLIGHT_COLORS

/**
 * What can be done with the selected text: highlight it in a colour, read it aloud (ElevenLabs, only when tapped),
 * look it up when it is one word, remove the highlights it touches. Shown while the page has a selection.
 */
@Composable
internal fun SelectionBar(
    selection: TextSelection,
    pronunciation: PronunciationState,
    onColor: (String) -> Unit,
    onRemove: () -> Unit,
    onSpeak: () -> Unit,
    onStop: () -> Unit,
    onDefine: () -> Unit,
    onDismissSpeakError: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HIGHLIGHT_COLORS.forEach { hex -> ColorDot(hex) { onColor(hex) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                val busy = pronunciation is PronunciationState.Busy
                TextButton(onClick = if (busy) onStop else onSpeak) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(LucideIcons.Volume2, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Text(if (busy) "Stop" else "Read aloud", Modifier.padding(start = 6.dp))
                }
                if (selection.text.none { it.isWhitespace() }) {
                    TextButton(onClick = onDefine) {
                        Icon(LucideIcons.BookOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Define", Modifier.padding(start = 6.dp))
                    }
                }
                if (selection.over.isNotEmpty()) {
                    TextButton(onClick = onRemove) {
                        Icon(LucideIcons.Trash, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Remove", Modifier.padding(start = 6.dp))
                    }
                }
                TextButton(onClick = onClose) { Icon(LucideIcons.X, contentDescription = "Close", modifier = Modifier.size(18.dp)) }
            }
            if (pronunciation is PronunciationState.Failed) {
                Text(
                    pronunciation.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onDismissSpeakError).padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ColorDot(
    hex: String,
    onClick: () -> Unit,
) {
    val color = remember(hex) { Color(0xFF000000 or hex.removePrefix("#").toLong(HEX_RADIX)) }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.size(34.dp).semantics { contentDescription = "Highlight in $hex" },
    ) {}
}

private const val HEX_RADIX = 16
