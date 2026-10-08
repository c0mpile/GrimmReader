package com.c0mpile.grimmreader.core.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverThumbnailTest {
    /** A noisy PNG, so it is large on disk like a scanned comic page. */
    private fun bigCover(
        width: Int = 1200,
        height: Int = 1800,
    ): ByteArray {
        val random = Random(1)
        val pixels = IntArray(width * height) { random.nextInt() or (0xFF shl 24) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun bounds(bytes: ByteArray) =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        }

    @Test fun largeCoverIsScaledToTheMaximumSideKeepingItsShape() {
        val big = bigCover()
        assertTrue(big.size > CoverThumbnail.SMALL_BYTES)

        val small = CoverThumbnail.shrink(big)

        assertTrue(small.size < big.size)
        val b = bounds(small)
        assertEquals(CoverThumbnail.MAX_SIDE, b.outHeight)
        assertEquals(480, b.outWidth)
    }

    @Test fun smallOrUndecodableCoversAreKept() {
        val small = ByteArray(1000) { it.toByte() }
        assertArrayEquals(small, CoverThumbnail.shrink(small))
        val junk = ByteArray(CoverThumbnail.SMALL_BYTES + 1) { 7 }
        assertTrue(junk === CoverThumbnail.shrink(junk))
    }

    @Test fun saveCoverStoresAThumbnailAndOldFullSizeCoversAreShrunk() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = LocalFileStore(context, DocumentStore(context))
        val big = bigCover()

        assertEquals(CoverThumbnail.MAX_SIDE, bounds(store.saveCover(1, big).readBytes()).outHeight)

        // A cover written at full size by an earlier build.
        File(context.filesDir, "covers/2.img").writeBytes(big)
        assertEquals(1, store.shrinkLargeCovers())
        assertEquals(CoverThumbnail.MAX_SIDE, bounds(store.coverFile(2).readBytes()).outHeight)
        assertEquals(0, store.shrinkLargeCovers())
        assertEquals(setOf(1L, 2L), store.coverIds())
    }
}
