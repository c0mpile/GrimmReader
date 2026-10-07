package com.c0mpile.grimmreader.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Lucide icons are 24×24 strokes of width 2 with round caps and joins; `Icon` tints them. */
internal fun lucide(
    name: String,
    block: LucideBuilder.() -> Unit,
): ImageVector {
    val builder = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    LucideBuilder(builder).block()
    return builder.build()
}

internal class LucideBuilder(
    private val builder: ImageVector.Builder,
) {
    fun stroke(pathData: String) {
        builder.addPath(
            pathData = addPathNodes(pathData),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    /** The same outline, filled (for "on" states such as a favorited heart). */
    fun filled(pathData: String) {
        builder.addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}
