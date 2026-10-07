package com.c0mpile.grimmreader.reader.ebook

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.c0mpile.grimmreader.core.model.Locator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream

data class TocEntry(
    val label: String,
    val href: String,
)

sealed interface EbookEvent {
    data class Ready(
        val toc: List<TocEntry>,
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

    /** The bookmark on the visible page changed after [EbookController.setBookmarks]. */
    data class BookmarkHere(
        val cfi: String?,
    ) : EbookEvent

    data class Failed(
        val message: String,
    ) : EbookEvent
}

/** Commands from Compose into the page. */
class EbookController {
    internal var webView: WebView? = null

    fun next() = call("grimm_api.next()")

    fun prev() = call("grimm_api.prev()")

    /** Arguments are JSON-encoded, so book data can never break out of the string literal. */
    fun goTo(target: String) = call("grimm_api.goTo(${Json.encodeToString(target)})")

    fun setStyle(css: String) = call("grimm_api.setStyle(${Json.encodeToString(css)})")

    /** The page answers with [EbookEvent.BookmarkHere]. */
    fun setBookmarks(cfis: List<String>) = call("grimm_api.setBookmarks(${Json.encodeToString(cfis)})")

    /** No-op until the page script defined its API (styles can change before the book is open). */
    private fun call(js: String) {
        webView?.evaluateJavascript("window.grimm_api && $js", null)
    }
}

private const val ORIGIN = "https://appassets.androidplatform.net"
private const val TAP_EDGE = 0.3f

/**
 * foliate-js in a WebView that can only reach app-local content: the reader page, foliate itself and the
 * one [file] being read. Every other request gets a 403; network loads are blocked outright.
 */
@Composable
fun EbookReader(
    file: File,
    initialCfi: String?,
    css: String,
    animated: Boolean,
    controller: EbookController,
    onEvent: (EbookEvent) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val events by rememberUpdatedState(onEvent)
    val startUrl = remember(file) { startUrl(file, initialCfi, css, animated) }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> readerWebView(context, file, controller) { events(it) }.apply { loadUrl(startUrl) } },
        )
        TapZones(controller, onToggleChrome)
    }
    LaunchedEffect(css) { controller.setStyle(css) }
    DisposableEffect(Unit) {
        onDispose {
            controller.webView?.destroy()
            controller.webView = null
        }
    }
}

private fun startUrl(
    file: File,
    initialCfi: String?,
    css: String,
    animated: Boolean,
): String =
    Uri
        .parse("$ORIGIN/assets/reader/reader.html")
        .buildUpon()
        .appendQueryParameter("name", file.name)
        .appendQueryParameter("animated", if (animated) "1" else "0")
        .apply { if (initialCfi != null) appendQueryParameter("cfi", initialCfi) }
        .appendQueryParameter("css", css)
        .build()
        .toString()

@SuppressLint("SetJavaScriptEnabled")
private fun readerWebView(
    context: Context,
    file: File,
    controller: EbookController,
    onEvent: (EbookEvent) -> Unit,
): WebView {
    val loader =
        WebViewAssetLoader
            .Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/book/") { path ->
                if (path == "current") WebResourceResponse("application/octet-stream", null, FileInputStream(file)) else null
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
                if (isMainFrame) message.data?.let { parse(it) }?.let(onEvent)
            }
        }
        controller.webView = this
    }
}

/** Tap zones 30/40/30 and horizontal swipes, like the web reader. */
@Composable
private fun TapZones(
    controller: EbookController,
    onToggleChrome: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    when {
                        offset.x < size.width * TAP_EDGE -> controller.prev()
                        offset.x > size.width * (1 - TAP_EDGE) -> controller.next()
                        else -> onToggleChrome()
                    }
                }
            }.pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        when {
                            total < -SWIPE_PX -> controller.next()
                            total > SWIPE_PX -> controller.prev()
                        }
                    },
                ) { _, amount -> total += amount }
            },
    )
}

private const val SWIPE_PX = 60f

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
                        TocEntry(item.str("label") ?: return@mapNotNull null, item.str("href") ?: return@mapNotNull null)
                    }.orEmpty(),
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
        "error" -> EbookEvent.Failed(o.str("message") ?: "error")
        else -> null
    }
}

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

private const val PERCENT = 100f
