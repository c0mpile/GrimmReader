package com.c0mpile.grimmreader.reader.paged

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.c0mpile.grimmreader.core.designsystem.component.PageTurns
import com.c0mpile.grimmreader.core.designsystem.component.PageZone
import com.c0mpile.grimmreader.core.designsystem.component.pageGestures
import com.c0mpile.grimmreader.core.designsystem.theme.LocalMotionEnabled
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Guided view: steps through each page panel by panel ([panels]), left to right, the camera moving from one
 * panel to the next. Taps in the right edge zone or a swipe to the left go forward, the left zone or a swipe
 * to the right go back; past the last panel the next page opens on its first stop. With [fullPageFirst] the
 * whole page is shown before its first panel. A page without reliable panels is one full-page stop. A tap in
 * the middle shows or hides the reader's bars. Moves are instant with [instantTurns] (E-ink) or when system
 * animations are off. [onPage] gets 0-based indices.
 */
@Composable
fun GuidedReader(
    source: PageSource,
    panels: PanelProvider,
    initialPage: Int,
    onPage: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
    turns: PageTurns = PageTurns(),
    fullPageFirst: Boolean = true,
    imageFilter: ColorFilter? = null,
    instantTurns: Boolean = false,
) {
    val pageCount = source.pageCount
    var at by remember { mutableStateOf(GuidedStop(initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)), 0)) }
    // Stops per page once its panels are known; until then the page is one full-page stop.
    val known = remember { mutableStateMapOf<Int, List<Rect>>() }
    val stopsOf: suspend (Int) -> List<Rect> = { page ->
        known[page] ?: guidedStops(panels.panels(page), fullPageFirst).also { known[page] = it }
    }
    val scope = rememberCoroutineScope()
    val stepping = remember { Mutex() }
    val motion by rememberUpdatedState(LocalMotionEnabled.current && !instantTurns)
    val toggle by rememberUpdatedState(onToggleChrome)
    val move = { forward: Boolean ->
        scope.launch {
            stepping.withLock { nextStop(at, forward, pageCount) { stopsOf(it).size }?.let { at = it } }
        }
    }

    LaunchedEffect(at.page) {
        onPage(at.page)
        // Panels of this page first, then the neighbours', so a step to them does not wait.
        for (page in listOf(at.page, at.page + 1, at.page - 1)) if (page in 0 until pageCount) stopsOf(page)
    }

    val target = screenSize(ZOOM_HEADROOM)
    var shown by remember { mutableStateOf<ShownPage?>(null) }
    // The previous page stays on screen until the next one is decoded.
    LaunchedEffect(source, at.page) {
        val page = at.page
        shown = ShownPage(page, source.decode(page, target))
    }

    val camera = remember { Animatable(FULL_PAGE, Rect.VectorConverter) }
    var framed by remember { mutableIntStateOf(-1) }
    val page = shown
    val goal = page?.takeIf { it.index == at.page }?.let { known[it.index].stop(at.stop) }
    LaunchedEffect(page?.index, goal) {
        goal ?: return@LaunchedEffect
        // A new page starts framed; within a page the camera moves.
        if (!motion || framed != page.index) camera.snapTo(goal) else camera.animateTo(goal, tween(MOVE_MS))
        framed = page.index
    }

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .guidedGestures(turns, onMove = { move(it) }, onToggleChrome = { toggle() }),
        contentAlignment = Alignment.Center,
    ) {
        val image = page?.bitmap
        if (image == null) {
            CircularProgressIndicator()
        } else {
            // Until the camera has framed this page, draw it at its goal (no frame of the old position).
            Framed(image, imageFilter) { if (framed == page.index) camera.value else goal ?: FULL_PAGE }
        }
    }
}

/** Right zone or a swipe to the left: forward; left zone or a swipe to the right: back. */
private fun Modifier.guidedGestures(
    turns: PageTurns,
    onMove: (forward: Boolean) -> Unit,
    onToggleChrome: () -> Unit,
) = pageGestures(
    turns,
    onTap = { zone ->
        when (zone) {
            PageZone.LEFT -> onMove(false)
            PageZone.RIGHT -> onMove(true)
            PageZone.MIDDLE -> onToggleChrome()
        }
    },
    onSwipe = onMove,
)

private fun List<Rect>?.stop(index: Int) = this?.getOrNull(index) ?: FULL_PAGE

/** [image] drawn so that the page rectangle from [stop] (read while drawing) fills the middle. */
@Composable
private fun Framed(
    image: ImageBitmap,
    imageFilter: ColorFilter?,
    stop: () -> Rect,
) {
    Canvas(Modifier.fillMaxSize()) {
        val view = frame(stop(), Size(image.width.toFloat(), image.height.toFloat()), size)
        drawImage(
            image,
            dstOffset = IntOffset(view.offset.x.roundToInt(), view.offset.y.roundToInt()),
            dstSize = IntSize((image.width * view.scale).roundToInt(), (image.height * view.scale).roundToInt()),
            colorFilter = imageFilter,
            filterQuality = FilterQuality.Medium,
        )
    }
}

private class ShownPage(
    val index: Int,
    val bitmap: ImageBitmap?,
)

@Composable
private fun screenSize(factor: Int): IntSize {
    val configuration = LocalConfiguration.current
    return with(LocalDensity.current) {
        IntSize(configuration.screenWidthDp.dp.roundToPx() * factor, configuration.screenHeightDp.dp.roundToPx() * factor)
    }
}

/** A position in guided view: a page and one of its stops. */
internal data class GuidedStop(
    val page: Int,
    val stop: Int,
)

internal val FULL_PAGE = Rect(0f, 0f, 1f, 1f)

/** The stops of a page: its panels, after the whole page with [fullPageFirst] (not when it is one panel). */
internal fun guidedStops(
    panels: List<Rect>,
    fullPageFirst: Boolean,
): List<Rect> =
    when {
        panels.isEmpty() -> listOf(FULL_PAGE)
        fullPageFirst && panels.size > 1 -> listOf(FULL_PAGE) + panels
        else -> panels
    }

/** The stop after (or before) [at], across pages; null at either end of the book. */
internal suspend fun nextStop(
    at: GuidedStop,
    forward: Boolean,
    pageCount: Int,
    stopCount: suspend (Int) -> Int,
): GuidedStop? =
    if (forward) {
        when {
            at.stop + 1 < stopCount(at.page) -> at.copy(stop = at.stop + 1)
            at.page + 1 < pageCount -> GuidedStop(at.page + 1, 0)
            else -> null
        }
    } else {
        when {
            at.stop > 0 -> at.copy(stop = at.stop - 1)
            at.page > 0 -> GuidedStop(at.page - 1, stopCount(at.page - 1) - 1)
            else -> null
        }
    }

/** How the page image is drawn: its scale and the position of its top left corner. */
internal data class PageView(
    val scale: Float,
    val offset: Offset,
)

/**
 * Frames [stop] (page coordinates, 0..1) with a little padding in the middle of [viewport]: never smaller
 * than the whole page fitted, never more than [MAX_ZOOM] times that. Where the page is larger than the
 * viewport it is kept from showing empty space past its edges.
 */
internal fun frame(
    stop: Rect,
    image: Size,
    viewport: Size,
): PageView {
    val fit = min(viewport.width / image.width, viewport.height / image.height)
    val w = stop.width * image.width * (1 + 2 * PADDING)
    val h = stop.height * image.height * (1 + 2 * PADDING)
    val scale = min(viewport.width / w, viewport.height / h).coerceIn(fit, fit * MAX_ZOOM)
    val x = place(viewport.width, image.width * scale, stop.center.x * image.width * scale)
    val y = place(viewport.height, image.height * scale, stop.center.y * image.height * scale)
    return PageView(scale, Offset(x, y))
}

/** Offset along one axis that puts [center] (in the scaled page) in the middle of [view], clamped to the page. */
private fun place(
    view: Float,
    length: Float,
    center: Float,
) = if (length <= view) (view - length) / 2 else (view / 2 - center).coerceIn(view - length, 0f)

private const val ZOOM_HEADROOM = 2

/** Space around a panel, as a share of its size on each side. */
private const val PADDING = 0.03f
private const val MAX_ZOOM = 4f
private const val MOVE_MS = 350
