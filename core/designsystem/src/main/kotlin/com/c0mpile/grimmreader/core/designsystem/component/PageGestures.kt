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
 * The lambdas are captured once per [turns]; pass ones that read current state.
 */
fun Modifier.pageGestures(
    turns: PageTurns,
    onTap: ((PageZone) -> Unit)?,
    onSwipe: (toLeft: Boolean) -> Unit,
): Modifier =
    pointerInput(turns, onTap != null) {
        val minSwipe = SWIPE_DP.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val touch = followTouch(down) ?: return@awaitEachGesture
            val zone = turns.zone(down.position.x, size.width.toFloat())
            val move: Offset = touch.up.position - down.position
            val held = touch.up.uptimeMillis - down.uptimeMillis
            when {
                !touch.dragged && onTap != null && held < viewConfiguration.longPressTimeoutMillis -> {
                    touch.up.consume()
                    onTap(if (turns.taps) zone else PageZone.MIDDLE)
                }
                touch.dragged && turns.swipes && zone != PageZone.MIDDLE && isSwipe(move, minSwipe) -> onSwipe(move.x < 0)
            }
        }
    }

private class Touch(
    val up: PointerInputChange,
    val dragged: Boolean,
)

/** Follows [down] until it lifts; null for multi-touch, a movement a child consumed, or a cancelled touch. */
private suspend fun AwaitPointerEventScope.followTouch(down: PointerInputChange): Touch? {
    var dragged = false
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (event.changes.count { it.pressed } > 1 || (change.pressed && change.isConsumed)) return null
        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
        if (!change.pressed) return Touch(change, dragged)
    }
}

internal fun isSwipe(
    move: Offset,
    minDistance: Float,
): Boolean = abs(move.x) >= minDistance && abs(move.x) > abs(move.y) * SWIPE_SLOPE

private const val SWIPE_DP = 48

/** Horizontal travel must be at least twice the vertical one. */
private const val SWIPE_SLOPE = 2f
