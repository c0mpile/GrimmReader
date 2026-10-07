package com.c0mpile.grimmreader.reader.paged

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedDecoderTest {
    @Test fun fitsTheTarget() {
        assertEquals(1, BoundedDecoder.sampleSize(1249, 1920, IntSize(2160, 4848)))
        assertEquals(2, BoundedDecoder.sampleSize(4000, 6000, IntSize(1080, 2424)))
    }

    @Test fun hugeImagesAreCappedByPixels() {
        val sample = BoundedDecoder.sampleSize(60_000, 60_000, IntSize(100_000, 100_000))
        assertTrue((60_000L / sample) * (60_000L / sample) <= BoundedDecoder.MAX_PIXELS)
    }
}
