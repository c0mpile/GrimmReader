package com.c0mpile.grimmreader.spike.engine

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.Activity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.net.URLEncoder

/**
 * Spike: foliate-js in a WebView. Serves app-local content only; every other request gets 403.
 * Driven with `adb shell am start -n <pkg>/.MainActivity --es book <file in filesDir/books> [--es goto <cfi>] [--es js <code>]`.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/book/", WebViewAssetLoader.InternalStoragePathHandler(this, File(filesDir, "books")))
            .build()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    loader.shouldInterceptRequest(request.url)
                        ?: WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), "".byteInputStream())
            }
        }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        WebViewCompat.addWebMessageListener(web, "spikeBridge", setOf(ORIGIN)) { _, message, _, _, _ ->
            Log.i(TAG, message.data.orEmpty())
        }
        setContentView(web)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        intent.getStringExtra("book")?.let { name ->
            val goto = intent.getStringExtra("goto")?.let { "&goto=" + URLEncoder.encode(it, "utf-8") }.orEmpty()
            Log.i(TAG, "{\"t\":\"load\",\"uptime\":${SystemClock.uptimeMillis()}}")
            web.loadUrl("$ORIGIN/assets/reader.html?book=/book/$name&name=$name$goto")
        }
        intent.getStringExtra("js")?.let { web.evaluateJavascript(it) { r -> Log.i(TAG, "{\"t\":\"js\",\"result\":$r}") } }
    }

    private companion object {
        const val TAG = "SPIKE"
        const val ORIGIN = "https://appassets.androidplatform.net"
    }
}
