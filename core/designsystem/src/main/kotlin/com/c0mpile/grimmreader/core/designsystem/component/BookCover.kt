package com.c0mpile.grimmreader.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.absoluteValue

const val COVER_ASPECT = 5f / 7f

/** Cover image (5:7) with a generated placeholder while loading, on error, or when there is no cover. */
@Composable
fun BookCover(
    title: String,
    model: Any?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    val boxModifier = modifier.aspectRatio(COVER_ASPECT).clip(shape)
    if (model == null) {
        CoverPlaceholder(title, boxModifier)
        return
    }
    // Plain AsyncImage (SubcomposeAsyncImage subcomposes every cell and makes a long grid stutter). The loading
    // placeholder is a painter, so a cell that comes back from the memory cache never flips a state to hide a
    // placeholder composable; the titled placeholder is only composed when the load fails.
    var failed by remember(model) { mutableStateOf(false) }
    val placeholder = remember(title) { CoverGradientPainter(title) }
    Box(boxModifier) {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            placeholder = placeholder,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
        )
        if (failed) CoverPlaceholder(title, Modifier.fillMaxSize())
    }
}

private class CoverGradientPainter(
    title: String,
) : Painter() {
    private val brush = coverBrush(title)

    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() = drawRect(brush)
}

private fun coverBrush(title: String): Brush {
    val hue = (title.hashCode().absoluteValue % 360).toFloat()
    return Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.55f), Color.hsv((hue + 40f) % 360f, 0.65f, 0.30f)))
}

/** Gradient whose hue is derived from the title, with the title set in a serif face. */
@Composable
fun CoverPlaceholder(
    title: String,
    modifier: Modifier = Modifier,
) {
    val brush = remember(title) { coverBrush(title) }
    val textColor = Color.White
    Box(modifier.background(brush), contentAlignment = Alignment.Center) {
        Text(
            text = title,
            modifier = Modifier.padding(10.dp),
            color = textColor,
            fontFamily = FontFamily.Serif,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
