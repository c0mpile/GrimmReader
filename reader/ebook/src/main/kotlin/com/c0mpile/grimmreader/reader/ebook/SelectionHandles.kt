package com.c0mpile.grimmreader.reader.ebook

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A point of the reader view in CSS px (dp at the default zoom). */
data class HandlePoint(
    val x: Float,
    val y: Float,
)

/** Where the selection's handles hang: under the start of its first line and the end of its last. */
data class HandlePoints(
    val start: HandlePoint,
    val end: HandlePoint,
)

/** The selection's two drag handles: where they are (null while nothing is selected) and the move command. */
class EbookHandles internal constructor(
    private val webView: () -> WebView?,
) {
    var points by mutableStateOf<HandlePoints?>(null)
        internal set

    /** Moves the selection's start ([end] false) or end to the word at [x], [y]; the page answers with the new points. */
    fun move(
        end: Boolean,
        x: Float,
        y: Float,
    ) {
        webView()?.evaluateJavascript("window.grimm_api && grimm_api.moveHandle($end, $x, $y)", null)
    }

    /** The finger lifted off a handle: the page reports the selection again. */
    fun finish() {
        webView()?.evaluateJavascript("window.grimm_api && grimm_api.endSelection()", null)
    }
}

private const val ROUND = 50
private const val SHARP = 0
private val START_DROP = RoundedCornerShape(ROUND, SHARP, ROUND, ROUND)
private val END_DROP = RoundedCornerShape(SHARP, ROUND, ROUND, ROUND)
private val HANDLE_COLOR = Color(0xFF60A5FA)
private val HANDLE_SIZE = 22.dp
private val TOUCH_SIZE = 48.dp
private val LIFT = 10.dp

/** The start and end handles of the selection; dragging one extends (or trims) the selection by whole words. */
@Composable
internal fun SelectionHandles(handles: EbookHandles) {
    val points = handles.points ?: return
    Handle(handles, end = false, at = points.start)
    Handle(handles, end = true, at = points.end)
}

@Composable
private fun Handle(
    handles: EbookHandles,
    end: Boolean,
    at: HandlePoint,
) {
    val density = LocalDensity.current
    val scale = density.density
    val current by rememberUpdatedState(at)
    val touch = with(density) { TOUCH_SIZE.roundToPx() }
    val lift = with(density) { LIFT.toPx() }
    // The start handle hangs to the left of its point, the end handle to the right; both just under the line.
    val x = (at.x * scale).roundToInt() - if (end) 0 else touch
    val y = (at.y * scale).roundToInt()
    Box(
        Modifier
            .offset { IntOffset(x, y) }
            .size(TOUCH_SIZE)
            .pointerInput(end) {
                var finger = Offset.Zero
                detectDragGestures(
                    onDragStart = { finger = Offset(current.x * scale, current.y * scale) },
                    onDragEnd = handles::finish,
                    onDragCancel = handles::finish,
                ) { change, drag ->
                    change.consume()
                    finger += drag
                    handles.move(end, finger.x / scale, (finger.y - lift) / scale)
                }
            },
        contentAlignment = if (end) Alignment.TopStart else Alignment.TopEnd,
    ) {
        // A drop: round except the corner that points at the text.
        Box(
            Modifier
                .size(HANDLE_SIZE)
                .background(
                    HANDLE_COLOR,
                    if (end) END_DROP else START_DROP,
                ),
        )
    }
}
