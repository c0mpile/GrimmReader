package com.c0mpile.grimmreader.core.designsystem.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Where on the page a touch started: the edge zones turn pages, the middle shows or hides the reader's bars. */
enum class PageZone { LEFT, MIDDLE, RIGHT }

/**
 * How pages turn: taps and/or horizontal swipes, both only in the left and right edge zones. [edge] is each
 * zone's share of the width (0..0.5). Off ([taps] and [swipes] false) every tap is a [PageZone.MIDDLE] tap.
 */
data class PageTurns(
    val edge: Float = DEFAULT_EDGE,
    val taps: Boolean = true,
    val swipes: Boolean = true,
) {
    fun zone(
        x: Float,
        width: Float,
    ): PageZone =
        when {
            x < width * edge -> PageZone.LEFT
            x > width * (1 - edge) -> PageZone.RIGHT
            else -> PageZone.MIDDLE
        }

    companion object {
        const val DEFAULT_EDGE = 0.25f

        /** While the reader's bars are shown: a tap anywhere hides them, nothing turns the page. */
        val Off = PageTurns(taps = false, swipes = false)
    }
}

/**
 * One gesture is either a tap or a swipe, never both: a touch that moves beyond the touch slop is a drag and
 * never a tap. A swipe counts only when it starts in an edge zone, travels at least [SWIPE_DP] and is mostly
 * horizontal. Taps report the zone they landed in ([PageZone.MIDDLE] for edge taps when [PageTurns.taps] is
 * off); [onTap] null leaves taps to the content (comics: the zoomable image). [onSwipe] gets true for a swipe
 * to the left. Multi-touch (pinch) and movements a child consumed (panning a zoomed page) are ignored.
 *
 * A touch held still for the long-press timeout goes to [onLongPress] (with where it is, in px) as soon as the
 * timeout passes; the rest of that touch is then ignored. With [onLongPress] null a long hold does nothing.
 *
 * The lambdas are captured once per [turns]; pass ones that read current state.
 */
fun Modifier.pageGestures(
    turns: PageTurns,
    onTap: ((PageZone) -> Unit)?,
    onSwipe: (toLeft: Boolean) -> Unit,
    onLongPress: ((Offset) -> Unit)? = null,
): Modifier =
    pointerInput(turns, onTap != null, onLongPress != null) {
        val minSwipe = SWIPE_DP.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val touch = Touch()
            val lifted = if (onLongPress != null) followOrLongPress(down, touch, onLongPress) else follow(down, touch)
            val up = touch.up
            if (!lifted || up == null) return@awaitEachGesture
            val zone = turns.zone(down.position.x, size.width.toFloat())
            val move: Offset = up.position - down.position
            val held = up.uptimeMillis - down.uptimeMillis
            when {
                !touch.dragged && onTap != null && held < viewConfiguration.longPressTimeoutMillis -> {
                    up.consume()
                    onTap(if (turns.taps) zone else PageZone.MIDDLE)
                }
                touch.dragged && turns.swipes && zone != PageZone.MIDDLE && isSwipe(move, minSwipe) -> onSwipe(move.x < 0)
            }
        }
    }

private class Touch {
    var up: PointerInputChange? = null
    var dragged = false
}

/**
 * Like [follow], but a touch still held without moving when the long-press timeout passes goes to [onLongPress];
 * the rest of that touch is swallowed (false: neither a tap nor a swipe).
 */
private suspend fun AwaitPointerEventScope.followOrLongPress(
    down: PointerInputChange,
    touch: Touch,
    onLongPress: (Offset) -> Unit,
): Boolean {
    val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { follow(down, touch) }
    if (lifted != null) return lifted
    if (touch.dragged) return follow(down, touch)
    onLongPress(down.position)
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
    return false
}

/**
 * Follows [down] until it lifts (true, [Touch.up] set); false for multi-touch, a movement a child consumed, or a
 * cancelled touch. Updates [touch] as it goes, so a caller that times out still knows whether it moved.
 */
private suspend fun AwaitPointerEventScope.follow(
    down: PointerInputChange,
    touch: Touch,
): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return false
        if (event.changes.count { it.pressed } > 1 || (change.pressed && change.isConsumed)) return false
        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) touch.dragged = true
        if (!change.pressed) {
            touch.up = change
            return true
        }
    }
}

internal fun isSwipe(
    move: Offset,
    minDistance: Float,
): Boolean = abs(move.x) >= minDistance && abs(move.x) > abs(move.y) * SWIPE_SLOPE

private const val SWIPE_DP = 48

/** Horizontal travel must be at least twice the vertical one. */
private const val SWIPE_SLOPE = 2f
