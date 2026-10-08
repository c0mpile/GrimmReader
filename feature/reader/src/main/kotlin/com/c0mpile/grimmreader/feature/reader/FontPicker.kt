package com.c0mpile.grimmreader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c0mpile.grimmreader.reader.ebook.FontCategory
import com.c0mpile.grimmreader.reader.ebook.ReaderFonts

private const val REGULAR = 400
private val FONT_CARD_HEIGHT = 84.dp

/** Web-style font cards ("Aa" set in the font, name under it): the book's own fonts, then bundled ones by group. */
@Composable
internal fun FontPicker(
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = ReaderFonts.resolve(selectedId)
    val previews = rememberFontPreviews()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardGrid(listOf(null)) { _, cardModifier ->
            FontCard("Publisher's", null, selected == null, cardModifier) { onSelect(ReaderFonts.PUBLISHER) }
        }
        FontCategory.entries.forEach { category ->
            Text(
                category.label,
                style = MaterialTheme.typography.labelLarge,
                color = mutedText(),
                modifier = Modifier.padding(top = 8.dp),
            )
            CardGrid(ReaderFonts.all.filter { it.category == category }) { font, cardModifier ->
                FontCard(font.id, previews[font.id], selected == font, cardModifier) { onSelect(font.id) }
            }
        }
    }
}

@Composable
private fun FontCard(
    name: String,
    family: FontFamily?,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OptionCard(selected, onClick, modifier.height(FONT_CARD_HEIGHT)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Aa", fontFamily = family, fontSize = 26.sp)
            Text(
                name,
                fontFamily = family,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
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
