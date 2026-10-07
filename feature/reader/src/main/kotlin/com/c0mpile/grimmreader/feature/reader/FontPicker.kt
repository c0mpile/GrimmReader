package com.c0mpile.grimmreader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.dp
import com.c0mpile.grimmreader.reader.ebook.FontCategory
import com.c0mpile.grimmreader.reader.ebook.ReaderFonts

private const val REGULAR = 400

/** Bundled reader fonts by group, each chip set in its own font, plus the book's own fonts. */
@Composable
internal fun FontPicker(
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = ReaderFonts.resolve(selectedId)
    val previews = rememberFontPreviews()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Font", style = MaterialTheme.typography.titleSmall)
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(ReaderFonts.PUBLISHER) },
            label = { Text("Publisher") },
        )
        FontCategory.entries.forEach { category ->
            Text(category.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFonts.all.filter { it.category == category }.forEach { font ->
                    FilterChip(
                        selected = selected == font,
                        onClick = { onSelect(font.id) },
                        label = { Text(font.id, fontFamily = previews[font.id]) },
                    )
                }
            }
        }
    }
}

/** Regular weight: some variable fonts (Source Sans 3) default to a light instance. */
@Composable
private fun rememberFontPreviews(): Map<String, FontFamily> {
    val assets = LocalContext.current.assets
    return remember(assets) {
        ReaderFonts.all.associate { font ->
            font.id to
                FontFamily(
                    Font(
                        path = "fonts/${font.previewFile}",
                        assetManager = assets,
                        variationSettings = FontVariation.Settings(FontVariation.weight(REGULAR)),
                    ),
                )
        }
    }
}
