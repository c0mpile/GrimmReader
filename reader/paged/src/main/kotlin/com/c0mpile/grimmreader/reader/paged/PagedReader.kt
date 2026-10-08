package com.c0mpile.grimmreader.reader.paged

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.c0mpile.grimmreader.core.designsystem.component.PageTurns
import com.c0mpile.grimmreader.core.designsystem.component.PageZone
import com.c0mpile.grimmreader.core.designsystem.theme.LocalMotionEnabled
import com.c0mpile.grimmreader.core.model.ReadingDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.abs

private const val ZOOM_HEADROOM = 2

/**
 * Page-by-page reader for comics and PDF: one page per screen, pinch/double-tap zoom, taps in the edge zones
 * of [turns] (mirrored for right-to-left). A drag that starts in an edge zone moves the page with the finger
 * (see [edgeDrag]); one in the middle turns nothing, and a zoomed page pans everywhere. [imageFilter] and
 * [instantTurns] come from the page theme; turns are also instant when system animations are off. [onPage]
 * gets 0-based indices.
 */
@Composable
fun PagedReader(
    source: PageSource,
    initialPage: Int,
    onPage: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
    turns: PageTurns = PageTurns(),
    imageFilter: ColorFilter? = null,
    instantTurns: Boolean = false,
) {
    val rtl = source.readingDirection == ReadingDirection.RTL
    val pager = rememberPagerState(initialPage.coerceIn(0, (source.pageCount - 1).coerceAtLeast(0))) { source.pageCount }
    val scope = rememberCoroutineScope()
    val motion by rememberUpdatedState(LocalMotionEnabled.current && !instantTurns)
    val toggle by rememberUpdatedState(onToggleChrome)
    // Forward is towards the right edge, or the left one for right-to-left books.
    val step = { by: Int -> scope.turn(pager, pager.currentPage + by, motion) }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect(onPage) }
    HorizontalPager(
        state = pager,
        modifier = modifier.fillMaxSize().edgeDrag(turns, pager, rtl) { target -> scope.turn(pager, target, motion) },
        reverseLayout = rtl,
        beyondViewportPageCount = 1,
        userScrollEnabled = false,
    ) { index ->
        Page(source, index, imageFilter) { offset, width ->
            val zone = if (turns.taps) turns.zone(offset.x, width) else PageZone.MIDDLE
            when (zone) {
                PageZone.LEFT -> step(if (rtl) 1 else -1)
                PageZone.RIGHT -> step(if (rtl) -1 else 1)
                PageZone.MIDDLE -> toggle()
            }
        }
    }
}

/**
 * The pager's own scrolling is off; this drags it instead, but only for touches that start in an edge zone.
 * The page follows the finger and on release settles on the next/previous page when dragged past
 * [SETTLE_FRACTION] of a page or flung, else springs back ([onSettle] gets the target page). Drags the zoomed
 * image consumed (panning) and multi-touch (pinch) are left alone.
 */
private fun Modifier.edgeDrag(
    turns: PageTurns,
    pager: PagerState,
    rtl: Boolean,
    onSettle: (Int) -> Unit,
): Modifier =
    pointerInput(turns, pager, rtl) {
        val fling = FLING_DP.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!turns.swipes || turns.zone(down.position.x, size.width.toFloat()) == PageZone.MIDDLE) {
                swallowDrag(down)
                return@awaitEachGesture
            }
            val start = pager.currentPage
            // Positive = towards the next page.
            val forward = dragPager(down, pager, rtl) ?: return@awaitEachGesture
            val travelled = pager.currentPage - start + pager.currentPageOffsetFraction
            val target =
                when {
                    forward > fling || travelled > SETTLE_FRACTION -> start + 1
                    forward < -fling || travelled < -SETTLE_FRACTION -> start - 1
                    else -> start
                }
            onSettle(target.coerceIn(0, (pager.pageCount - 1).coerceAtLeast(0)))
        }
    }

/**
 * Moves [pager] with the finger once [down] passes the touch slop horizontally, until it lifts. Returns the
 * release velocity towards the next page, or null when it never became a page drag.
 */
private suspend fun AwaitPointerEventScope.dragPager(
    down: PointerInputChange,
    pager: PagerState,
    rtl: Boolean,
): Float? {
    val velocity = VelocityTracker()
    var dragging = false
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!dragging && event.isPinchOrPan(change)) return null
        velocity.addPosition(change.uptimeMillis, change.position)
        if (!dragging) dragging = isPageDrag(change.position - down.position, viewConfiguration.touchSlop)
        if (dragging) {
            val dx = change.position.x - change.previousPosition.x
            pager.dispatchRawDelta(if (rtl) dx else -dx)
            change.consume()
        }
        if (!change.pressed) break
    }
    if (!dragging) return null
    return velocity.calculateVelocity().x.let { if (rtl) it else -it }
}

/**
 * A one-finger drag that turns nothing (middle zone, or swipes off) is taken here, so the image does not see
 * it as a tap that toggles the bars. Pans of a zoomed page and pinches are left to the image.
 */
private suspend fun AwaitPointerEventScope.swallowDrag(down: PointerInputChange) {
    var dragged = false
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return
        if (event.isPinchOrPan(change)) return
        if (!dragged) dragged = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
        if (dragged) change.consume()
        if (!change.pressed) return
    }
}

/** Two fingers, or a movement the zoomed image already used to pan. */
private fun PointerEvent.isPinchOrPan(change: PointerInputChange) =
    changes.count { it.pressed } > 1 || (change.pressed && change.isConsumed)

private fun isPageDrag(
    moved: Offset,
    slop: Float,
) = abs(moved.x) > slop && abs(moved.x) > abs(moved.y)

private const val SETTLE_FRACTION = 0.25f
private const val FLING_DP = 400

private fun CoroutineScope.turn(
    pager: PagerState,
    target: Int,
    animate: Boolean,
) {
    if (target !in 0 until pager.pageCount) return
    launch { if (animate) pager.animateScrollToPage(target) else pager.scrollToPage(target) }
}

@Composable
private fun Page(
    source: PageSource,
    index: Int,
    imageFilter: ColorFilter?,
    onTap: (Offset, Float) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val target =
        with(density) {
            IntSize(configuration.screenWidthDp.dp.roundToPx() * ZOOM_HEADROOM, configuration.screenHeightDp.dp.roundToPx() * ZOOM_HEADROOM)
        }
    val bitmap by produceState<ImageBitmap?>(null, source, index) { value = source.decode(index, target) }
    val zoom = rememberZoomableState()
    var width by remember { mutableIntStateOf(1) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) {
            CircularProgressIndicator()
        } else {
            LaunchedEffect(image) {
                zoom.setContentLocation(
                    ZoomableContentLocation.scaledInsideAndCenterAligned(
                        androidx.compose.ui.geometry
                            .Size(image.width.toFloat(), image.height.toFloat()),
                    ),
                )
            }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Inside,
                colorFilter = imageFilter,
                modifier =
                    Modifier.fillMaxSize().zoomable(
                        zoom,
                        onClick = { onTap(it, with(density) { configuration.screenWidthDp.dp.toPx() }) },
                    ),
            )
        }
    }
}
