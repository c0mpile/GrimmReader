package com.c0mpile.grimmreader.reader.ebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity

/** A page turn being shown: [image] is the page as it was before the turn, [forward] is toward the next page. */
class PageTurn internal constructor(
    internal val image: ImageBitmap,
    internal val forward: Boolean,
)

/** The paper page turn: a picture of the old page is turned over (or back) while the page script moves the real one. */
class EbookTurns internal constructor(
    private val webView: () -> WebView?,
) {
    var current by mutableStateOf<PageTurn?>(null)
        internal set

    /** Takes a picture of the page as it is now and starts the turn; the caller moves the page right after. */
    internal fun begin(forward: Boolean) {
        val view = webView()?.takeIf { it.width > 0 && it.height > 0 } ?: return
        val picture = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(picture))
        current = PageTurn(picture.asImageBitmap(), forward)
    }

    internal fun finish() {
        current = null
    }

    /** The page script is turning for narration (request [id]): show the turn, then let it move the page. */
    internal fun forNarration(id: Int) {
        begin(forward = true)
        webView()?.evaluateJavascript("window.grimm_api && grimm_api.turnNext($id)", null)
    }
}

private const val TURN_MS = 420
private const val TURN_DEGREES = 90f
private const val SHADOW = 0.4f
private const val CAMERA = 24f
private const val MIDDLE = 0.5f
private val LEFT_EDGE = TransformOrigin(0f, MIDDLE)

/** How far the turn has gone, 0..1, while there is one. */
@Composable
internal fun rememberTurnProgress(turns: EbookTurns): Animatable<Float, *> {
    val progress = remember { Animatable(0f) }
    val turn = turns.current
    LaunchedEffect(turn) {
        if (turn == null) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(TURN_MS, easing = FastOutSlowInEasing))
        turns.finish()
    }
    return progress
}

/** The live page's own turn: when going back, the previous page swings in from the left edge. */
internal fun Modifier.liveTurn(
    turn: PageTurn?,
    progress: Animatable<Float, *>,
    density: Float,
): Modifier =
    graphicsLayer {
        if (turn != null && !turn.forward) {
            transformOrigin = LEFT_EDGE
            rotationY = -TURN_DEGREES * (1f - progress.value)
            cameraDistance = CAMERA * density
        }
    }

/** The picture of the old page: swinging away to the left when going forward, lying under the new page when going back. */
@Composable
internal fun PageTurnPicture(
    turn: PageTurn,
    progress: Animatable<Float, *>,
) {
    val density = LocalDensity.current.density
    // The page that is not moving gets a shadow from the one that is.
    val under = if (turn.forward) 1f - progress.value else progress.value
    Image(
        bitmap = turn.image,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (turn.forward) {
                        transformOrigin = LEFT_EDGE
                        rotationY = -TURN_DEGREES * progress.value
                        cameraDistance = CAMERA * density
                    }
                }.drawWithContent {
                    drawContent()
                    // The turning page darkens as it stands up; the page under it is shaded where it lies.
                    val shade = if (turn.forward) progress.value * SHADOW else under * SHADOW
                    drawRect(Color.Black.copy(alpha = shade.coerceIn(0f, 1f)))
                },
    )
}
