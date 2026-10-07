package com.c0mpile.grimmreader.reader.paged

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.c0mpile.grimmreader.core.designsystem.theme.EinkImageFilter
import com.c0mpile.grimmreader.core.designsystem.theme.LocalEinkLook
import com.c0mpile.grimmreader.core.designsystem.theme.LocalMotionEnabled
import com.c0mpile.grimmreader.core.model.ReadingDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

private const val TAP_EDGE = 0.3f
private const val ZOOM_HEADROOM = 2

/**
 * Page-by-page reader for comics and PDF: one page per screen, pinch/double-tap zoom, tap zones 30/40/30
 * (mirrored for right-to-left), instant page turns when motion is off (E-ink look). [onPage] gets 0-based
 * indices.
 */
@Composable
fun PagedReader(
    source: PageSource,
    initialPage: Int,
    onPage: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rtl = source.readingDirection == ReadingDirection.RTL
    val pager = rememberPagerState(initialPage.coerceIn(0, (source.pageCount - 1).coerceAtLeast(0))) { source.pageCount }
    val scope = rememberCoroutineScope()
    val motion = LocalMotionEnabled.current
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect(onPage) }
    HorizontalPager(state = pager, modifier = modifier.fillMaxSize(), reverseLayout = rtl, beyondViewportPageCount = 1) { index ->
        Page(source, index) { offset, width ->
            val forward = if (rtl) offset.x < width * TAP_EDGE else offset.x > width * (1 - TAP_EDGE)
            val backward = if (rtl) offset.x > width * (1 - TAP_EDGE) else offset.x < width * TAP_EDGE
            when {
                forward -> scope.turn(pager, pager.currentPage + 1, motion)
                backward -> scope.turn(pager, pager.currentPage - 1, motion)
                else -> onToggleChrome()
            }
        }
    }
}

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
    val eink = LocalEinkLook.current
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
                colorFilter = if (eink) EinkImageFilter else null,
                modifier =
                    Modifier.fillMaxSize().zoomable(
                        zoom,
                        onClick = { onTap(it, with(density) { configuration.screenWidthDp.dp.toPx() }) },
                    ),
            )
        }
    }
}
