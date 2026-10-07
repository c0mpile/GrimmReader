package com.c0mpile.grimmreader.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.c0mpile.grimmreader.core.designsystem.theme.GrimmTextStyles

/** Library grid card: cover with a 4 dp progress bar and optional badge, title and author underneath. */
@Composable
fun BookCard(
    title: String,
    author: String?,
    coverModel: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progressPercent: Float? = null,
    badge: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            BookCover(title, coverModel, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            if (progressPercent != null && progressPercent > 0f) {
                ProgressStrip(progressPercent, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp))
            }
            if (badge != null) FormatPill(badge, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(title, style = GrimmTextStyles.CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!author.isNullOrBlank()) {
                Text(
                    author,
                    style = GrimmTextStyles.CardAuthor,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ProgressStrip(
    percent: Float,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val bar = MaterialTheme.colorScheme.primary
    Box(
        modifier.drawBehind {
            drawRect(track)
            drawRect(bar, size = Size(size.width * (percent / 100f).coerceIn(0f, 1f), size.height))
        },
    )
}

@Composable
fun FormatPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier =
            modifier
                .drawBehind {
                    drawRoundRect(
                        color =
                            androidx.compose.ui.graphics.Color.Black
                                .copy(alpha = 0.6f),
                        cornerRadius =
                            androidx.compose.ui.geometry
                                .CornerRadius(8f),
                    )
                }.padding(horizontal = 6.dp, vertical = 2.dp),
        color = androidx.compose.ui.graphics.Color.White,
        style = MaterialTheme.typography.labelSmall,
    )
}
