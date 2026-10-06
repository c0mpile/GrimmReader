package com.c0mpile.grimmreader.spike.panels.app

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Debug
import android.util.Log
import com.c0mpile.grimmreader.spike.panels.GutterDetector
import com.c0mpile.grimmreader.spike.panels.Raster
import java.io.File
import kotlin.concurrent.thread

/**
 * Times the panel detector on pages copied into `filesDir/pages` (`*.img`). Logs to tag PANELS:
 * one line per page (decode ms, first-run ms, median of 5 warm runs, panel count), then a summary.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread(name = "panels") { run() }
    }

    private fun run() {
        val pages = File(filesDir, "pages").listFiles { f -> f.name.endsWith(".img") }.orEmpty().sortedBy { it.name }
        val firsts = ArrayList<Double>()
        val warms = ArrayList<Double>()
        val decodes = ArrayList<Double>()
        var peakJava = 0L
        for (f in pages) {
            val t0 = System.nanoTime()
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val raster = Raster(bmp.width, bmp.height, px)
            bmp.recycle()
            val decode = ms(t0)
            val t1 = System.nanoTime()
            val d = GutterDetector.detect(raster)
            val first = ms(t1)
            val runs = (1..5).map {
                val t = System.nanoTime()
                GutterDetector.detect(raster)
                ms(t)
            }.sorted()
            val rt = Runtime.getRuntime()
            peakJava = maxOf(peakJava, rt.totalMemory() - rt.freeMemory())
            decodes += decode
            firsts += first
            warms += runs[2]
            Log.i(TAG, "page ${f.name.removeSuffix(".img")} decode %.1f first %.1f warm %.1f panels %d fallback %b".format(decode, first, runs[2], d.panels.size, d.fallback))
        }
        Log.i(
            TAG,
            "summary pages ${pages.size} decode p50 %.1f p90 %.1f | first p50 %.1f p90 %.1f max %.1f | warm p50 %.1f p90 %.1f max %.1f | peak java heap %d KB, native heap %d KB".format(
                pct(decodes, 50), pct(decodes, 90), pct(firsts, 50), pct(firsts, 90), firsts.maxOrNull() ?: 0.0,
                pct(warms, 50), pct(warms, 90), warms.maxOrNull() ?: 0.0, peakJava / 1024, Debug.getNativeHeapAllocatedSize() / 1024,
            ),
        )
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    private fun pct(v: List<Double>, p: Int): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return s[((s.size - 1) * p / 100.0).toInt()]
    }

    private companion object {
        const val TAG = "PANELS"
    }
}
