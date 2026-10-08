package com.c0mpile.grimmreader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons

/** Secondary text in the reader's panels and dialogs (web: neutral-500). */
@Composable
internal fun mutedText(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

/** Raised blocks on the panel background: cards, stepper buttons (web: white/4 %). */
@Composable
internal fun raised(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)

/** The selected option's fill (web: primary/20 %). */
@Composable
internal fun selectedFill(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)

internal class PanelTab(
    val label: String,
    val icon: ImageVector? = null,
    val badge: Int = 0,
)

/** Web-style tabs: equal widths, icon over label, the active one in the accent with an underline. */
@Composable
internal fun PanelTabs(
    tabs: List<PanelTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(role = Role.Tab) { onSelect(index) }
                        .padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    tab.icon?.let { icon ->
                        Box {
                            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                            if (tab.badge > 0) Badge(tab.badge, Modifier.align(Alignment.TopEnd).padding(start = 18.dp))
                        }
                    }
                    Text(
                        tab.label,
                        color = color,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.Medium else null,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    Box(
                        Modifier
                            .height(2.dp)
                            .fillMaxWidth(0.8f)
                            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    }
}

@Composable
private fun Badge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > MAX_BADGE) "$MAX_BADGE+" else "$count",
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private const val MAX_BADGE = 99

/** "FONT SETTINGS": small caps label over a hairline, like the web settings dialog. */
@Composable
internal fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 1.sp,
            color = mutedText(),
            fontWeight = FontWeight.SemiBold,
        )
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    }
}

/** A labelled setting row with its control at the end. */
@Composable
internal fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
internal fun SettingSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(label) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outline,
                    uncheckedBorderColor = Color.Transparent,
                ),
        )
    }
}

/** "− 18 +" in a bordered box; the buttons disable at the ends of the range. */
@Composable
internal fun Stepper(
    value: String,
    onMinus: (() -> Unit)?,
    onPlus: (() -> Unit)?,
    modifier: Modifier = Modifier,
    description: String = "",
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(LucideIcons.Minus, "Decrease $description".trim(), onMinus)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 56.dp).padding(horizontal = 4.dp),
        )
        StepButton(LucideIcons.Plus, "Increase $description".trim(), onPlus)
    }
}

@Composable
private fun StepButton(
    icon: ImageVector,
    description: String,
    onClick: (() -> Unit)?,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(raised())
            .clickable(enabled = onClick != null, role = Role.Button, onClickLabel = description) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (onClick != null) 0.8f else 0.3f)
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** Large outline icon, a title and a hint: an empty list in a panel. */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = mutedText(), modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = mutedText(), textAlign = TextAlign.Center)
    }
}

/** Selectable card used for page themes and fonts: accent border and fill when selected. */
@Composable
internal fun OptionCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) selectedFill() else raised())
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape,
            ).clickable(role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Small rounded colour sample. */
@Composable
internal fun Swatch(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .width(36.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(4.dp)),
    )
}

internal class SliderColors(
    val track: Color,
    val mark: Color,
    val thumb: Color,
)

/** Thin track with optional marks and a round thumb (web reader slider); tap or drag to pick 0..1. */
@Composable
internal fun ThinSlider(
    value: Float,
    description: String,
    colors: SliderColors,
    marks: List<Float> = emptyList(),
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    Box(
        modifier
            .height(40.dp)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                setProgress { target ->
                    drag(target.coerceIn(0f, 1f))
                    release()
                    true
                }
            }.pointerInput(Unit) {
                detectTapGestures { offset ->
                    drag((offset.x / size.width).coerceIn(0f, 1f))
                    release()
                }
            }.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { change, _ -> drag((change.position.x / size.width).coerceIn(0f, 1f)) }
            }.drawBehind {
                val y = size.height / 2
                val track = TRACK_DP.dp.toPx()
                drawRoundRect(
                    colors.track,
                    topLeft = Offset(0f, y - track / 2),
                    size = Size(size.width, track),
                    cornerRadius = CornerRadius(track / 2),
                )
                val mark = MARK_DP.dp.toPx()
                marks.forEach { m ->
                    drawLine(
                        colors.mark,
                        Offset(m * size.width, y - mark / 2),
                        Offset(m * size.width, y + mark / 2),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                drawCircle(colors.thumb, radius = THUMB_DP.dp.toPx(), center = Offset(value.coerceIn(0f, 1f) * size.width, y))
            },
    )
}

private const val TRACK_DP = 4
private const val MARK_DP = 12
private const val THUMB_DP = 9
