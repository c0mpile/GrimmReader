package com.c0mpile.grimmreader.reader.ebook

import android.annotation.SuppressLint
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

    /** [hasPosition] is false on cover/image-only pages (no fraction); progress must not be saved there. */
    data class Relocated(
        val locator: Locator.Epub,
        val tocLabel: String?,
        val hasPosition: Boolean,
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

    private fun call(js: String) {
        webView?.evaluateJavascript(js, null)
    }
}

private const val ORIGIN = "https://appassets.androidplatform.net"
private const val TAP_EDGE = 0.3f

/**
 * foliate-js in a WebView that can only reach app-local content: the reader page, foliate itself and the
 * one [file] being read. Every other request gets a 403; network loads are blocked outright.
 */
@SuppressLint("SetJavaScriptEnabled")
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
    val startUrl =
        remember(file) {
            Uri
                .parse("$ORIGIN/assets/reader/reader.html")
                .buildUpon()
                .appendQueryParameter("name", file.name)
                .appendQueryParameter("animated", if (animated) "1" else "0")
                .apply { if (initialCfi != null) appendQueryParameter("cfi", initialCfi) }
                .appendQueryParameter("css", css)
                .build()
                .toString()
        }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val loader =
                    WebViewAssetLoader
                        .Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                        .addPathHandler("/book/") { path ->
                            if (path == "current") WebResourceResponse("application/octet-stream", null, FileInputStream(file)) else null
                        }.build()
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.safeBrowsingEnabled = false
                    settings.setSupportZoom(false)
                    webViewClient = LocalOnlyClient(loader)
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        WebViewCompat.addWebMessageListener(this, "grimm", setOf(ORIGIN)) { _, message, _, _, _ ->
                            message.data?.let { parse(it) }?.let { events(it) }
                        }
                    }
                    controller.webView = this
                    loadUrl(startUrl)
                }
            },
        )
        // Tap zones 30/40/30 and horizontal swipes, like the web reader.
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
                            if (total < -SWIPE_PX) {
                                controller.next()
                            } else if (total > SWIPE_PX) {
                                controller.prev()
                            }
                        },
                    ) { _, amount -> total += amount }
                },
        )
    }
    LaunchedEffect(css) { controller.setStyle(css) }
    DisposableEffect(Unit) {
        onDispose {
            controller.webView?.destroy()
            controller.webView = null
        }
    }
}

private const val SWIPE_PX = 60f
private const val HTTP_FORBIDDEN = 403

private class LocalOnlyClient(
    private val loader: WebViewAssetLoader,
) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse =
        loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "utf-8", HTTP_FORBIDDEN, "Forbidden", emptyMap(), null)

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = true
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
            EbookEvent.Relocated(Locator.Epub(cfi, o.str("href"), (fraction ?: 0f) * PERCENT), o.str("toc"), hasPosition = fraction != null)
        }
        "error" -> EbookEvent.Failed(o.str("message") ?: "error")
        else -> null
    }
}

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

private const val PERCENT = 100f
