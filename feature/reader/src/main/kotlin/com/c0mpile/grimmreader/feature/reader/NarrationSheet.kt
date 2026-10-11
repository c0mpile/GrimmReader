package com.c0mpile.grimmreader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.designsystem.icon.MediaIcons
import com.c0mpile.grimmreader.core.tts.NarrationState
import com.c0mpile.grimmreader.core.tts.NarrationStatus
import java.util.Locale

/** What the sleep timer chips offer, in minutes. */
private val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60)

private const val MIN_RATE = 0.5f
private const val MAX_RATE = 2.5f
private const val RATE_STEPS = 19
private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60

internal class NarrationActions(
    val onToggle: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onStop: () -> Unit,
    val onRate: (Float) -> Unit,
    val onSleepMinutes: (Int) -> Unit,
    val onSleepChapter: () -> Unit,
    val onSleepOff: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/** Controls for reading the book aloud: play/pause, previous/next passage, speed and the sleep timer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NarrationSheet(
    state: NarrationState,
    actions: NarrationActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Read aloud", style = MaterialTheme.typography.titleMedium)
            if (!state.systemSpeechOn) {
                Text(
                    "Reading a book aloud uses System text-to-speech (any voice installed on this device, including " +
                        "third-party engines such as VoxSherpa). Turn it on in Settings → Text to speech.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = actions.onOpenSettings) { Text("Open settings") }
                return@Column
            }
            Transport(state, actions)
            Speed(state.rate, actions.onRate)
            SleepTimerChips(state, actions)
        }
    }
}

@Composable
private fun Transport(
    state: NarrationState,
    actions: NarrationActions,
) {
    val active = state.status != NarrationStatus.IDLE
    val playing = state.status == NarrationStatus.PLAYING || state.status == NarrationStatus.LOADING
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onPrevious, enabled = active) { Icon(MediaIcons.SkipBack, contentDescription = "Previous passage") }
        FilledIconButton(onClick = actions.onToggle, modifier = Modifier.padding(horizontal = 12.dp).size(56.dp)) {
            Icon(if (playing) MediaIcons.Pause else MediaIcons.Play, contentDescription = if (playing) "Pause" else "Read aloud")
        }
        IconButton(onClick = actions.onNext, enabled = active) { Icon(MediaIcons.SkipForward, contentDescription = "Next passage") }
        IconButton(onClick = actions.onStop, enabled = active) { Icon(MediaIcons.Square, contentDescription = "Stop") }
    }
    Text(
        when (state.status) {
            NarrationStatus.IDLE -> "Starts at the top of the page you are on."
            NarrationStatus.LOADING -> "Starting…"
            NarrationStatus.PLAYING -> "Reading aloud. It continues with the screen off."
            NarrationStatus.PAUSED -> "Paused."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Speed(
    rate: Float,
    onRate: (Float) -> Unit,
) {
    var value by remember(rate) { mutableFloatStateOf(rate) }
    Column {
        Text("Speed ${String.format(Locale.US, "%.1f", value)}×", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onRate(Math.round(value * 10) / 10f) },
            valueRange = MIN_RATE..MAX_RATE,
            steps = RATE_STEPS,
        )
    }
}

@Composable
private fun SleepTimerChips(
    state: NarrationState,
    actions: NarrationActions,
) {
    var chosen by remember { mutableStateOf<Int?>(null) }
    val sleep = state.sleep
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(LucideIcons.Moon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                when {
                    sleep == null -> "Sleep timer"
                    sleep.endOfChapter -> "Stops at the end of the chapter"
                    else -> "Stops in ${clock(sleep.remainingMs ?: 0)}"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sleep == null, onClick = {
                chosen = null
                actions.onSleepOff()
            }, label = { Text("Off") })
            SLEEP_MINUTES.forEach { minutes ->
                FilterChip(
                    selected = sleep?.endOfChapter == false && chosen == minutes,
                    onClick = {
                        chosen = minutes
                        actions.onSleepMinutes(minutes)
                    },
                    label = { Text("$minutes min") },
                )
            }
            FilterChip(selected = sleep?.endOfChapter == true, onClick = {
                chosen = null
                actions.onSleepChapter()
            }, label = { Text("End of chapter") })
        }
    }
}

private fun clock(ms: Long): String {
    val total = (ms / MS_PER_SECOND).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / SECONDS_PER_MINUTE, total % SECONDS_PER_MINUTE)
}
