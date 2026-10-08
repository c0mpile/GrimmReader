package com.c0mpile.grimmreader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.designsystem.theme.palette
import com.c0mpile.grimmreader.core.model.EinkTint
import com.c0mpile.grimmreader.core.model.PageTheme
import com.c0mpile.grimmreader.core.model.ReaderPrefs
import kotlin.math.roundToInt

private const val MIN_FONT = 12
private const val MAX_FONT = 36
private const val MIN_LINE = 1.0f
private const val MAX_LINE = 2.5f
private const val LINE_STEP = 0.1f
private const val MAX_COLUMNS = 4
private const val MAX_GAP = 0.2f
private const val WIDTH_STEP = 40
private const val MIN_WIDTH = 320
private const val MAX_WIDTH = 1600
private const val HEIGHT_STEP = 80
private const val MIN_HEIGHT = 480
private const val MAX_HEIGHT = 3200
private const val PERCENT = 100

/**
 * The popover under the settings button (web: dark mode, font size, line spacing, "More Settings"). Page
 * themes take the place of the web's dark mode switch; comics and PDFs only have those, comics also the
 * guided view switch.
 */
@Composable
internal fun QuickSettings(
    prefs: ReaderPrefs,
    textSettings: Boolean,
    comicSettings: Boolean,
    onChange: (ReaderPrefs) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier.width(320.dp).border(1.dp, MaterialTheme.colorScheme.outline, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Page", style = MaterialTheme.typography.bodyLarge)
            ThemeDots(prefs, onChange, Modifier.padding(top = 10.dp, bottom = 6.dp))
            if (textSettings) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                FontSizeRow(prefs, onChange)
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                LineHeightRow(prefs, onChange, label = "Line Spacing")
            }
            if (comicSettings) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                SettingSwitch("Guided view", prefs.guidedView) { onChange(prefs.copy(guidedView = it)) }
            }
            HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            val button = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(button)
                    .background(raised())
                    .border(1.dp, MaterialTheme.colorScheme.outline, button)
                    .clickable(role = Role.Button, onClick = onMore)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LucideIcons.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("More Settings", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** One dot per page theme, in the page colour with a text-coloured centre. */
@Composable
private fun ThemeDots(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        PageTheme.entries.forEach { theme ->
            val swatch = theme.palette(prefs.einkTint)
            val selected = prefs.pageTheme == theme
            val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.RadioButton) { onChange(prefs.copy(pageTheme = theme)) }
                    .semantics {
                        this.selected = selected
                        contentDescription = theme.label()
                    }.padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier
                        .size(36.dp)
                        .border(if (selected) 2.dp else 1.dp, ring, CircleShape)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(swatch.background),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) { Spacer(Modifier.size(10.dp).clip(CircleShape).background(swatch.text)) }
                Text(
                    theme.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else mutedText(),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FontSizeRow(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
) {
    SettingRow("Font Size") {
        Stepper(
            "${prefs.fontSize}",
            onMinus = { onChange(prefs.copy(fontSize = prefs.fontSize - 1)) }.takeIf { prefs.fontSize > MIN_FONT },
            onPlus = { onChange(prefs.copy(fontSize = prefs.fontSize + 1)) }.takeIf { prefs.fontSize < MAX_FONT },
            description = "font size",
        )
    }
}

@Composable
private fun LineHeightRow(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
    label: String,
) {
    // Tenths as integers, so repeated steps don't drift (1.5000001).
    val tenths = (prefs.lineHeight / LINE_STEP).roundToInt()
    val set = { t: Int -> onChange(prefs.copy(lineHeight = t * LINE_STEP)) }
    SettingRow(label) {
        Stepper(
            "%.1f".format(tenths * LINE_STEP),
            onMinus = { set(tenths - 1) }.takeIf { tenths * LINE_STEP > MIN_LINE + LINE_STEP / 2 },
            onPlus = { set(tenths + 1) }.takeIf { tenths * LINE_STEP < MAX_LINE - LINE_STEP / 2 },
            description = "line spacing",
        )
    }
}

private enum class SettingsTab(
    val label: String,
) {
    THEME("Theme"),
    TYPOGRAPHY("Typography"),
    LAYOUT("Layout"),
    CONTROLS("Controls"),
}

/**
 * "More Settings": tabs Theme / Typography / Layout like the web dialog, plus Controls (page turning).
 * Comics and PDFs get Theme and Controls (comics with guided view).
 */
@Composable
internal fun SettingsDialog(
    prefs: ReaderPrefs,
    textSettings: Boolean,
    comicSettings: Boolean,
    onChange: (ReaderPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    val tabs = if (textSettings) SettingsTab.entries else listOf(SettingsTab.THEME, SettingsTab.CONTROLS)
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tab = tabs.getOrElse(selected) { SettingsTab.THEME }
    // One height for every tab, like the web dialog.
    val maxHeight = min((LocalConfiguration.current.screenHeightDp * DIALOG_HEIGHT).dp, DIALOG_MAX_HEIGHT)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(12.dp)
        Surface(
            Modifier
                .padding(16.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .height(maxHeight)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape),
            shape = shape,
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PanelTabs(
                        tabs.map { PanelTab(it.label) },
                        selected = tabs.indexOf(tab),
                        onSelect = { selected = it },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) { Icon(LucideIcons.X, contentDescription = "Close", tint = mutedText()) }
                }
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (tab) {
                        SettingsTab.THEME -> ThemeTab(prefs, onChange)
                        SettingsTab.TYPOGRAPHY -> TypographyTab(prefs, onChange)
                        SettingsTab.LAYOUT -> LayoutTab(prefs, onChange)
                        SettingsTab.CONTROLS -> ControlsTab(prefs, comicSettings, onChange)
                    }
                }
            }
        }
    }
}

private const val DIALOG_HEIGHT = 0.85f
private val DIALOG_MAX_HEIGHT = 760.dp

@Composable
private fun ThemeTab(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
) {
    SectionHeader("Page theme")
    CardGrid(PageTheme.entries) { theme, modifier ->
        val swatch = theme.palette(prefs.einkTint)
        OptionCard(prefs.pageTheme == theme, { onChange(prefs.copy(pageTheme = theme)) }, modifier) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Swatch(swatch.background)
                    Swatch(swatch.text)
                }
                Text(
                    theme.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    if (prefs.pageTheme == PageTheme.EINK) EinkOptions(prefs, onChange)
}

/** Shown only for E-ink pages. */
@Composable
private fun EinkOptions(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
) {
    SectionHeader("E-ink", Modifier.padding(top = 8.dp))
    CardGrid(EinkTint.entries, columns = 2) { tint, modifier ->
        OptionCard(prefs.einkTint == tint, { onChange(prefs.copy(einkTint = tint)) }, modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(PageTheme.EINK.palette(tint).background)
                Text(
                    if (tint == EinkTint.WARM) "Warm paper" else "Cool paper",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
    SettingSwitch("Paper grain", prefs.einkGrain) { onChange(prefs.copy(einkGrain = it)) }
    Text("Refresh flash", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
    CardGrid(FLASH_CHOICES, columns = FLASH_CHOICES.size) { n, modifier ->
        OptionCard(prefs.einkFlashEvery == n, { onChange(prefs.copy(einkFlashEvery = n)) }, modifier) {
            Text(if (n == 0) "Off" else "Every $n", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val FLASH_CHOICES = listOf(0, 5, 10, 20)

@Composable
private fun TypographyTab(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
) {
    SectionHeader("Font settings")
    FontSizeRow(prefs, onChange)
    LineHeightRow(prefs, onChange, label = "Line Height")
    SectionHeader("Font family", Modifier.padding(top = 8.dp))
    FontPicker(prefs.fontFamily, onSelect = { onChange(prefs.copy(fontFamily = it)) })
}

@Composable
private fun LayoutTab(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
) {
    SectionHeader("Layout")
    SettingRow("Max Columns") {
        val n = prefs.maxColumnCount
        Stepper(
            "$n",
            onMinus = { onChange(prefs.copy(maxColumnCount = n - 1)) }.takeIf { n > 1 },
            onPlus = { onChange(prefs.copy(maxColumnCount = n + 1)) }.takeIf { n < MAX_COLUMNS },
            description = "columns",
        )
    }
    SettingRow("Column Gap") {
        val accent = MaterialTheme.colorScheme.primary
        ThinSlider(
            value = prefs.gap / MAX_GAP,
            description = "Column gap",
            colors = SliderColors(MaterialTheme.colorScheme.outline, Color.Transparent, accent),
            onDrag = { onChange(prefs.copy(gap = (it * MAX_GAP * PERCENT).roundToInt() / PERCENT.toFloat())) },
            onRelease = {},
            modifier = Modifier.width(140.dp),
        )
        Text(
            "${(prefs.gap * PERCENT).roundToInt()}%",
            color = mutedText(),
            modifier = Modifier.widthIn(min = 44.dp).padding(start = 8.dp),
        )
    }
    SettingRow("Max Width") {
        val w = prefs.maxInlineSize
        Stepper(
            "$w",
            onMinus = { onChange(prefs.copy(maxInlineSize = (w - WIDTH_STEP).coerceAtLeast(MIN_WIDTH))) }.takeIf { w > MIN_WIDTH },
            onPlus = { onChange(prefs.copy(maxInlineSize = (w + WIDTH_STEP).coerceAtMost(MAX_WIDTH))) }.takeIf { w < MAX_WIDTH },
            description = "width",
        )
    }
    SettingRow("Max Height") {
        val h = prefs.maxBlockSize
        Stepper(
            "$h",
            onMinus = { onChange(prefs.copy(maxBlockSize = (h - HEIGHT_STEP).coerceAtLeast(MIN_HEIGHT))) }.takeIf { h > MIN_HEIGHT },
            onPlus = { onChange(prefs.copy(maxBlockSize = (h + HEIGHT_STEP).coerceAtMost(MAX_HEIGHT))) }.takeIf { h < MAX_HEIGHT },
            description = "height",
        )
    }
    SectionHeader("Text options", Modifier.padding(top = 8.dp))
    SettingSwitch("Justify Text", prefs.justify) { onChange(prefs.copy(justify = it)) }
    SettingSwitch("Hyphenate", prefs.hyphenate) { onChange(prefs.copy(hyphenate = it)) }
}

@Composable
private fun ControlsTab(
    prefs: ReaderPrefs,
    comicSettings: Boolean,
    onChange: (ReaderPrefs) -> Unit,
) {
    SectionHeader("Page turning")
    SettingSwitch("Tap to turn", prefs.tapToTurn) { onChange(prefs.copy(tapToTurn = it)) }
    SettingSwitch("Swipe to turn", prefs.swipeToTurn) { onChange(prefs.copy(swipeToTurn = it)) }
    Text("Turn zone width", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
    CardGrid(TURN_ZONES, columns = TURN_ZONES.size) { zone, modifier ->
        OptionCard(prefs.turnZone == zone, { onChange(prefs.copy(turnZone = zone)) }, modifier) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ZoneSketch(zone)
                Text("$zone%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    Text(
        "Taps and swipes turn the page only in the left and right zones. A tap in the middle shows or hides " +
            "the bars; while they are shown, a tap on the page hides them.",
        style = MaterialTheme.typography.bodyMedium,
        color = mutedText(),
        modifier = Modifier.padding(top = 4.dp),
    )
    if (comicSettings) GuidedOptions(prefs, onChange)
}

@Composable
private fun GuidedOptions(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
) {
    SectionHeader("Guided view", Modifier.padding(top = 12.dp))
    SettingSwitch("Guided view", prefs.guidedView) { onChange(prefs.copy(guidedView = it)) }
    if (prefs.guidedView) {
        SettingSwitch("Show full page first", prefs.guidedFullPage) { onChange(prefs.copy(guidedFullPage = it)) }
    }
    Text(
        "Moves through each page panel by panel, left to right, with the same taps and swipes that turn " +
            "pages. Pages without clear panels are shown whole.",
        style = MaterialTheme.typography.bodyMedium,
        color = mutedText(),
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** A page with its two turn zones shaded. */
@Composable
private fun ZoneSketch(zone: Int) {
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    Row(
        Modifier
            .width(40.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)),
    ) {
        val edge = zone / PERCENT.toFloat()
        Spacer(Modifier.weight(edge).height(28.dp).background(accent))
        Spacer(Modifier.weight(1 - 2 * edge))
        Spacer(Modifier.weight(edge).height(28.dp).background(accent))
    }
}

private val TURN_ZONES = listOf(15, 20, 25, 33)

/** Equal-width cards in rows; 3 per row on wide dialogs, 2 on narrow ones, unless [columns] is given. */
@Composable
internal fun <T> CardGrid(
    items: List<T>,
    columns: Int? = null,
    card: @Composable (T, Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        val perRow = columns ?: if (maxWidth >= WIDE_GRID) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { card(it, Modifier.weight(1f)) }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val WIDE_GRID = 420.dp

internal fun PageTheme.label() =
    when (this) {
        PageTheme.EINK -> "E-ink"
        PageTheme.LIGHT -> "Light"
        PageTheme.SEPIA -> "Sepia"
        PageTheme.DARK -> "Dark"
        PageTheme.NIGHT -> "Night"
        PageTheme.AMOLED -> "AMOLED"
    }
