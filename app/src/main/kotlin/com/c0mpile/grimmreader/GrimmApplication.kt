package com.c0mpile.grimmreader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.c0mpile.grimmreader.core.data.server.ServerSession
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import okio.Path.Companion.toOkioPath
import javax.inject.Inject

@HiltAndroidApp
class GrimmApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var session: Lazy<ServerSession>

    override fun onCreate() {
        super.onCreate()
        // Debug builds only: lets developers inspect the reader page with DevTools over adb.
        if (BuildConfig.DEBUG) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /**
     * Covers load through the single guarded client (security invariant). The call factory is only built on
     * the first network image, so local mode never creates an HTTP client. Server thumbnails are kept in a disk
     * cache that `CoverPrefetchWorker` fills for the whole library; their URLs carry the cover's version, so a
     * changed cover is a new entry.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { session.get().authedClient() })) }
            .diskCache {
                DiskCache
                    .Builder()
                    .directory(cacheDir.resolve("covers").toOkioPath())
                    .maxSizeBytes(COVER_CACHE_BYTES)
                    .build()
            }.build()

    private companion object {
        const val COVER_CACHE_BYTES = 512L * 1024 * 1024
    }
}
