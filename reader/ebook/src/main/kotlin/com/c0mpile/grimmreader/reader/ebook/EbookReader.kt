package com.c0mpile.grimmreader.reader.ebook

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.c0mpile.grimmreader.core.designsystem.component.PageTurns
import com.c0mpile.grimmreader.core.designsystem.component.PageZone
import com.c0mpile.grimmreader.core.designsystem.component.TapHistory
import com.c0mpile.grimmreader.core.designsystem.component.pageGestures
import com.c0mpile.grimmreader.core.files.BookHandle
import com.c0mpile.grimmreader.core.model.Locator
import com.c0mpile.grimmreader.core.model.PageAnimation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A chapter; [depth] is 0 for top-level entries, 1 for their sub-chapters and so on. */
data class TocEntry(
    val label: String,
    val href: String,
    val depth: Int = 0,
)

/** Paginator layout (the web's `gap`, `maxColumnCount`, `maxInlineSize`, `maxBlockSize`). */
@Serializable
data class EbookLayout(
    val gap: Float,
    val columns: Int,
    val maxInlineSize: Int,
    val maxBlockSize: Int,
)

/** A highlight to draw: [color] is `#RRGGBB`. */
@Serializable
data class HighlightMark(
    val id: Long,
    val cfi: String,
    val color: String,
)

/** A search stops after this many hits (MAX_HITS in reader.js). */
const val MAX_SEARCH_HITS = 500

/** One search hit: [match] with some text before and after it. */
data class SearchHit(
    val cfi: String,
    val pre: String,
    val match: String,
    val post: String,
)

/** A passage of the book to read aloud: [cfi] is its range, [section] the index of the section it is in. */
@Serializable
data class NarrationText(
    val text: String,
    val cfi: String,
    val section: Int,
)

sealed interface EbookEvent {
    /** [sections] are where the book's sections start, as fractions 0..1 of the whole book. */
    data class Ready(
        val toc: List<TocEntry>,
        val sections: List<Float> = emptyList(),
    ) : EbookEvent

    /** Search progress for search [id]: hits in one chapter ([label]), [progress] 0..1, or [done]. */
    data class Search(
        val id: Int,
        val label: String? = null,
        val hits: List<SearchHit> = emptyList(),
        val progress: Float? = null,
        val done: Boolean = false,
    ) : EbookEvent

    /**
     * [hasPosition] is false on cover/image-only pages (no fraction); progress must not be saved there.
     * [bookmark] is the CFI of a bookmark on the visible page (see [EbookController.setBookmarks]).
     */
    data class Relocated(
        val locator: Locator.Epub,
        val tocLabel: String?,
        val hasPosition: Boolean,
        val bookmark: String? = null,
    ) : EbookEvent

    /**
     * The user selected [text] (long press, optionally dragged), shown selected until
     * [EbookController.clearSelection]. [cfi] is its range; [over] are the ids of the highlights it touches.
     */
    data class Selection(
        val text: String,
        val cfi: String,
        val over: List<Long> = emptyList(),
        val handles: HandlePoints? = null,
    ) : EbookEvent

    /** The page script is about to turn the page for narration (request [id]) and wants the paper page turn shown. */
    data class Turn(
        val id: Int,
    ) : EbookEvent

    /** The selection's handles moved ([EbookHandles.move]). */
    data class Handles(
        val points: HandlePoints,
    ) : EbookEvent

    /** The page dropped the selection (it turned or changed). */
    data object SelectionCleared : EbookEvent

    /** The bookmark on the visible page changed after [EbookController.setBookmarks]. */
    data class BookmarkHere(
        val cfi: String?,
    ) : EbookEvent

    /** The page's answer to a narration request [req]; [chunks] is null when the request failed. */
    data class Narration(
        val req: Int,
        val ok: Boolean,
        val chunks: List<NarrationText>?,
    ) : EbookEvent

    data class Failed(
        val message: String,
    ) : EbookEvent
}

/** Commands from Compose into the page. */
class EbookController {
    internal var webView: WebView? = null

    /** Reading the book aloud: the page cuts it into passages and marks the one being spoken. */
    val narration = EbookNarration { webView }

    /** The selection's drag handles. */
    val handles = EbookHandles { webView }

    /** The paper page turn (see [PageAnimation.TURN]): a picture of the old page turns while the real page moves. */
    val turns = EbookTurns { webView }

    /** How page turns look; set by [EbookReader]. */
    var animation = PageAnimation.SLIDE

    fun next() = turn(forward = true)

    fun prev() = turn(forward = false)

    private fun turn(forward: Boolean) {
        if (animation == PageAnimation.TURN) turns.begin(forward)
        call(if (forward) "grimm_api.next()" else "grimm_api.prev()")
    }

    /**
     * Arguments are JSON-encoded, so book data can never break out of the string literal. With [readFromHere],
     * narration started on that page begins at [target] (a highlight's first word) rather than at the page's first word.
     */
    fun goTo(
        target: String,
        readFromHere: Boolean = false,
    ) = call("grimm_api.goTo(${Json.encodeToString(target)}, $readFromHere)")

    /** Jumps to a position 0..1 through the book (foliate's fraction, weighted like the progress). */
    fun goToFraction(fraction: Float) = call("grimm_api.goToFraction(${fraction.coerceIn(0f, 1f)})")

    fun setStyle(css: String) = call("grimm_api.setStyle(${Json.encodeToString(css)})")

    fun applyAnimation(mode: PageAnimation) {
        animation = mode
        call("grimm_api.setAnimation('${mode.name.lowercase()}')")
    }

    fun setLayout(layout: EbookLayout) = call("grimm_api.setLayout(${Json.encodeToString(layout)})")

    /** Searches the whole book; results arrive as [EbookEvent.Search] carrying [id]. */
    fun search(
        query: String,
        id: Int,
    ) = call("grimm_api.search(${Json.encodeToString(query)}, $id)")

    fun clearSearch() = call("grimm_api.clearSearch()")

    /** The page answers with [EbookEvent.BookmarkHere]. */
    fun setBookmarks(cfis: List<String>) = call("grimm_api.setBookmarks(${Json.encodeToString(cfis)})")

    /** Starts a selection on the word at [x], [y] (CSS px of the reader view), if there is one. */
    fun selectAt(
        x: Float,
        y: Float,
    ) {
        handles.points = null
        call("grimm_api.selectAt($x, $y)")
    }

    /** Extends the selection to the word at [x], [y]. */
    fun extendTo(
        x: Float,
        y: Float,
    ) = call("grimm_api.extendTo($x, $y)")

    /** The finger lifted: the page answers with [EbookEvent.Selection] if something is selected. */
    fun endSelection() = call("grimm_api.endSelection()")

    fun clearSelection() {
        handles.points = null
        call("grimm_api.clearSelection()")
    }

    /** Draws these highlights (`id`, `cfi`, `color` each) over the page, and no others. */
    fun setHighlights(highlights: List<HighlightMark>) = call("grimm_api.setHighlights(${Json.encodeToString(highlights)})")

    /** No-op until the page script defined its API (styles can change before the book is open). */
    private fun call(js: String) {
        webView?.evaluateJavascript("window.grimm_api && $js", null)
    }
}

/** Narration requests to the page script; answers arrive as [EbookEvent.Narration] and complete the request. */
class EbookNarration internal constructor(
    private val webView: () -> WebView?,
) {
    private val replies = ConcurrentHashMap<Int, CompletableDeferred<EbookEvent.Narration>>()
    private val requests = AtomicInteger()

    /**
     * Positions narration at the first word of the visible page, at [from], or just after it; the word at
     * [x], [y] (CSS px of the reader view) wins. False when the book has no text.
     */
    suspend fun begin(
        from: String?,
        after: Boolean,
        x: Float? = null,
        y: Float? = null,
    ): Boolean = request { req -> "grimm_api.narrationBegin($req, ${Json.encodeToString(from)}, $after, $x, $y)" }?.ok == true

    /** The next [count] passages in reading order; empty at the end of the book, null when the page did not answer. */
    suspend fun next(count: Int): List<NarrationText>? = request { req -> "grimm_api.narrationNext($req, $count)" }?.chunks

    /** Marks [passage] in the page and turns to it (when the page is visible). */
    fun spoken(passage: NarrationText) = call("grimm_api.narrationSpoken(${Json.encodeToString(passage)})")

    fun clear() = call("grimm_api.narrationClear()")

    private fun call(js: String) {
        webView()?.evaluateJavascript("window.grimm_api && $js", null)
    }

    private suspend fun request(js: (Int) -> String): EbookEvent.Narration? {
        if (webView() == null) return null
        val req = requests.incrementAndGet()
        val reply = CompletableDeferred<EbookEvent.Narration>()
        replies[req] = reply
        try {
            call(js(req))
            return withTimeoutOrNull(NARRATION_TIMEOUT_MS) { reply.await() }
        } finally {
            replies.remove(req)
        }
    }

    internal fun onReply(reply: EbookEvent.Narration) {
        replies[reply.req]?.complete(reply)
    }
}

private const val NARRATION_TIMEOUT_MS = 20_000L

internal const val ORIGIN = "https://appassets.androidplatform.net"

/**
 * foliate-js in a WebView that can only reach app-local content: the reader page, foliate itself and the
 * one [book] being read. Every other request gets a 403; network loads are blocked outright. [fileName] only
 * tells foliate the format by its extension.
 */
@Composable
fun EbookReader(
    book: BookHandle,
    fileName: String,
    initialCfi: String?,
    css: String,
    layout: EbookLayout,
    animation: PageAnimation,
    turns: PageTurns,
    controller: EbookController,
    selecting: Boolean,
    onEvent: (EbookEvent) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
    highlighting: Boolean = false,
    onWordDoubleTap: ((x: Float, y: Float) -> Unit)? = null,
) {
    val events by rememberUpdatedState(onEvent)
    val density = LocalDensity.current.density
    // Long press selects a word, dragging extends it; the WebView's CSS px are dp at the default zoom.
    val selection =
        Selection(
            start = { at -> controller.selectAt(at.x / density, at.y / density) },
            drag = { at -> controller.extendTo(at.x / density, at.y / density) },
            end = controller::endSelection,
        )
    val startUrl = remember(book) { startUrl(fileName, initialCfi, css, layout, animation) }
    val progress = rememberTurnProgress(controller.turns)
    val turn = controller.turns.current
    Box(modifier) {
        // Going back, the old page lies under the page that swings in.
        if (turn != null && !turn.forward) PageTurnPicture(turn, progress)
        AndroidView(
            modifier = Modifier.fillMaxSize().liveTurn(turn, progress, density),
            factory = { context -> readerWebView(context, book, controller) { events(it) }.apply { loadUrl(startUrl) } },
        )
        // Going forward, the old page swings away over the new one.
        if (turn != null && turn.forward) PageTurnPicture(turn, progress)
        TapZones(
            turns,
            controller,
            selecting,
            highlighting,
            onToggleChrome,
            selection,
            onWordDoubleTap?.let { tap ->
                { at: Offset ->
                    tap(
                        at.x / density,
                        at.y / density,
                    )
                }
            },
        )
        // Above the tap zones, so a handle takes its own touches.
        SelectionHandles(controller.handles)
    }
    LaunchedEffect(css) { controller.setStyle(css) }
    LaunchedEffect(animation) { controller.applyAnimation(animation) }
    LaunchedEffect(layout) { controller.setLayout(layout) }
    DisposableEffect(Unit) {
        onDispose {
            controller.webView?.destroy()
            controller.webView = null
        }
    }
}

private fun startUrl(
    fileName: String,
    initialCfi: String?,
    css: String,
    layout: EbookLayout,
    animation: PageAnimation,
): String =
    Uri
        .parse("$ORIGIN/assets/reader/reader.html")
        .buildUpon()
        .appendQueryParameter("name", fileName)
        .appendQueryParameter("anim", animation.name.lowercase())
        .apply { if (initialCfi != null) appendQueryParameter("cfi", initialCfi) }
        .appendQueryParameter("css", css)
        .appendQueryParameter("layout", Json.encodeToString(layout))
        .build()
        .toString()

@SuppressLint("SetJavaScriptEnabled")
private fun readerWebView(
    context: Context,
    book: BookHandle,
    controller: EbookController,
    onEvent: (EbookEvent) -> Unit,
): WebView {
    val loader =
        WebViewAssetLoader
            .Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/book/") { path ->
                if (path == "current") WebResourceResponse("application/octet-stream", null, book.inputStream()) else null
            }.build()
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.safeBrowsingEnabled = false
        settings.setSupportZoom(false)
        webViewClient = LocalOnlyClient(loader)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "grimm", setOf(ORIGIN)) { _, message, _, isMainFrame, _ ->
                // Book sections are same-origin frames; only our own page may talk to the app.
                if (isMainFrame) {
                    when (val event = message.data?.let { parse(it) }) {
                        null -> Unit
                        is EbookEvent.Narration -> controller.narration.onReply(event)
                        is EbookEvent.Handles -> controller.handles.points = event.points
                        is EbookEvent.Turn -> controller.turns.forNarration(event.id)
                        else -> {
                            if (event is EbookEvent.Selection) controller.handles.points = event.handles
                            if (event is EbookEvent.SelectionCleared) controller.handles.points = null
                            onEvent(event)
                        }
                    }
                }
            }
        }
        controller.webView = this
    }
}

private class Selection(
    val start: (Offset) -> Unit,
    val drag: (Offset) -> Unit,
    val end: () -> Unit,
)

/**
 * Takes every touch (the WebView gets none): taps and swipes in the edge zones turn, the middle toggles the bars,
 * a long press anywhere starts a selection, and in highlight mode a tap does. While [selecting], a tap only drops
 * the selection. A double tap in the middle (it first toggled the bars, which it takes back) goes to [onDoubleTap]
 * with the spot in px.
 */
@Composable
private fun TapZones(
    turns: PageTurns,
    controller: EbookController,
    selecting: Boolean,
    highlighting: Boolean,
    onToggleChrome: () -> Unit,
    selection: Selection,
    onDoubleTap: ((Offset) -> Unit)?,
) {
    val toggle by rememberUpdatedState(onToggleChrome)
    val isSelecting by rememberUpdatedState(selecting)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val history = remember { TapHistory() }
    val last =
        remember {
            object {
                var toggled = false
            }
        }
    Box(
        Modifier
            .fillMaxSize()
            .pageGestures(
                turns,
                onTap = { zone ->
                    last.toggled = false
                    when {
                        isSelecting -> controller.clearSelection()
                        zone == PageZone.LEFT -> controller.prev()
                        zone == PageZone.RIGHT -> controller.next()
                        else -> {
                            last.toggled = true
                            toggle()
                        }
                    }
                },
                onSwipe = { toLeft -> if (toLeft) controller.next() else controller.prev() },
                onLongPress = selection.start,
                onLongPressDrag = selection.drag,
                onLongPressEnd = selection.end,
                onDoubleTap =
                    if (onDoubleTap == null) {
                        null
                    } else {
                        { at ->
                            if (last.toggled) toggle()
                            last.toggled = false
                            doubleTap?.invoke(at)
                        }
                    },
                history = history,
                // Highlight mode: a tap picks the word under it (the handles then widen it).
                onPointTap =
                    if (highlighting) {
                        { at ->
                            selection.start(at)
                            selection.end()
                        }
                    } else {
                        null
                    },
            ),
    )
}

/**
 * foliate renders untrusted book content in same-origin frames with scripts allowed (WebKit bug 218086), so
 * the only safe setup is a CSP that blocks every script but our own (foliate README). Sent on every response
 * and repeated in reader.html; book sections (blob: frames) inherit it.
 */
internal val READER_CSP =
    listOf(
        "default-src 'self' blob: data:",
        "script-src 'self'",
        "object-src 'none'",
        "base-uri 'none'",
        "form-action 'none'",
        "connect-src 'self' blob:",
        "style-src 'self' 'unsafe-inline' blob:",
        "img-src 'self' blob: data:",
        "font-src 'self' blob: data:",
        "media-src 'self' blob:",
        "frame-src 'self' blob:",
    ).joinToString("; ")

internal fun WebResourceResponse.withSecurityHeaders(): WebResourceResponse =
    apply {
        responseHeaders =
            (responseHeaders.orEmpty() + mapOf("Content-Security-Policy" to READER_CSP, "X-Content-Type-Options" to "nosniff"))
    }

private const val HTTP_FORBIDDEN = 403

private class LocalOnlyClient(
    private val loader: WebViewAssetLoader,
) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse = (loader.shouldInterceptRequest(request.url) ?: forbidden()).withSecurityHeaders()

    private fun forbidden() = WebResourceResponse("text/plain", "utf-8", HTTP_FORBIDDEN, "Forbidden", emptyMap(), null)

    /**
     * Android also asks about sub-frame navigations to non-HTTP schemes: foliate loads every section into a
     * frame from a `blob:` URL of our own origin, which must be allowed. Everything else (a book's external
     * links, any navigation of the main frame away from the reader) is cancelled.
     */
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = !isAllowedNavigation(request.url.toString(), request.isForMainFrame)
}

internal fun isAllowedNavigation(
    url: String,
    mainFrame: Boolean,
): Boolean =
    if (mainFrame) {
        url.startsWith("$ORIGIN/assets/reader/")
    } else {
        url.startsWith("blob:$ORIGIN/") || url == "about:blank"
    }

internal fun parse(data: String): EbookEvent? {
    val o = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
    return when (o.str("t")) {
        "ready" ->
            EbookEvent.Ready(
                o["toc"]
                    ?.jsonArray
                    ?.mapNotNull { e ->
                        val item = e as? JsonObject ?: return@mapNotNull null
                        TocEntry(
                            item.str("label")?.trim() ?: return@mapNotNull null,
                            item.str("href") ?: return@mapNotNull null,
                            item["depth"]?.jsonPrimitive?.intOrNull ?: 0,
                        )
                    }.orEmpty(),
                o["sections"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.floatOrNull }
                    ?.filter { it > 0f && it < 1f }
                    .orEmpty(),
            )
        "relocate" -> {
            val cfi = o.str("cfi") ?: return null
            val fraction = o["fraction"]?.jsonPrimitive?.floatOrNull
            EbookEvent.Relocated(
                Locator.Epub(cfi, o.str("href"), (fraction ?: 0f) * PERCENT),
                o.str("toc"),
                hasPosition = fraction != null,
                bookmark = o.str("bookmark"),
            )
        }
        "bookmark" -> EbookEvent.BookmarkHere(o.str("cfi"))
        "selection" -> parseSelection(o)
        "handles" -> parseHandles(o)?.let { EbookEvent.Handles(it) }
        "turn" -> o["id"]?.jsonPrimitive?.intOrNull?.let { EbookEvent.Turn(it) }
        "search" -> parseSearch(o)
        "narration" -> parseNarration(o)
        "error" -> EbookEvent.Failed(o.str("message") ?: "error")
        else -> null
    }
}

private fun parseSelection(o: JsonObject): EbookEvent? {
    if (o["cleared"]?.jsonPrimitive?.booleanOrNull == true) return EbookEvent.SelectionCleared
    val text = o.str("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cfi = o.str("cfi")?.takeIf { it.startsWith("epubcfi(") } ?: return null
    val over = o["over"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty()
    return EbookEvent.Selection(text.take(MAX_SELECTION_CHARS), cfi, over, (o["handles"] as? JsonObject)?.let(::parseHandles))
}

private fun parseHandles(o: JsonObject): HandlePoints? {
    val sx = o["sx"]?.jsonPrimitive?.floatOrNull ?: return null
    val sy = o["sy"]?.jsonPrimitive?.floatOrNull ?: return null
    val ex = o["ex"]?.jsonPrimitive?.floatOrNull ?: return null
    val ey = o["ey"]?.jsonPrimitive?.floatOrNull ?: return null
    return HandlePoints(HandlePoint(sx, sy), HandlePoint(ex, ey))
}

private fun parseNarration(o: JsonObject): EbookEvent? {
    val req = o["req"]?.jsonPrimitive?.intOrNull ?: return null
    val chunks =
        o["chunks"]?.jsonArray?.mapNotNull { e ->
            val item = e as? JsonObject ?: return@mapNotNull null
            val cfi = item.str("cfi")?.takeIf { it.startsWith("epubcfi(") } ?: return@mapNotNull null
            val text = item.str("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NarrationText(text, cfi, item["section"]?.jsonPrimitive?.intOrNull ?: 0)
        }
    val ok = o["ok"]?.jsonPrimitive?.booleanOrNull ?: (o["error"] == null)
    return EbookEvent.Narration(req, ok, if (o["error"] != null) null else chunks.orEmpty())
}

private fun parseSearch(o: JsonObject): EbookEvent.Search? {
    val id = o["id"]?.jsonPrimitive?.intOrNull ?: return null
    val hits =
        o["items"]?.jsonArray?.mapNotNull { e ->
            val item = e as? JsonObject ?: return@mapNotNull null
            SearchHit(
                item.str("cfi") ?: return@mapNotNull null,
                item.str("pre").orEmpty(),
                item.str("match").orEmpty(),
                item.str("post").orEmpty(),
            )
        }
    return EbookEvent.Search(
        id = id,
        label = o.str("label"),
        hits = hits.orEmpty(),
        progress = o["progress"]?.jsonPrimitive?.floatOrNull,
        done = o["done"]?.jsonPrimitive?.booleanOrNull == true,
    )
}

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

private const val PERCENT = 100f
private const val MAX_SELECTION_CHARS = 5000
