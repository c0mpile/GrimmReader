package com.c0mpile.grimmreader.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun wav(
        samples: Int,
        declared: Int = samples * 2,
        bits: Int = 16,
        tag: Int = 1,
        extra: Boolean = false,
    ): File {
        fun le(n: Int) =
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(n)
                .array()

        fun le16(n: Int) =
            ByteBuffer
                .allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(n.toShort())
                .array()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(le(36 + samples * 2))
        out.write("WAVE".toByteArray())
        if (extra) {
            out.write("LIST".toByteArray())
            out.write(le(3))
            out.write(byteArrayOf(1, 2, 3, 0))
        }
        out.write("fmt ".toByteArray())
        out.write(le(16))
        out.write(le16(tag))
        out.write(le16(1))
        out.write(le(22050))
        out.write(le(44100))
        out.write(le16(2))
        out.write(le16(bits))
        out.write("data".toByteArray())
        out.write(le(declared))
        out.write(ByteArray(samples * 2))
        return tmp.newFile().also { it.writeBytes(out.toByteArray()) }
    }

    @Test fun readsMonoPcm() {
        val w = readWav(wav(22050))!!
        assertEquals(22050, w.sampleRate)
        assertEquals(1, w.channels)
        assertEquals(44100L, w.dataLength)
        assertEquals(1000L, w.durationMs)
    }

    @Test fun streamedSizeRunsToTheEndOfTheFile() {
        assertEquals(2000L, readWav(wav(1000, declared = -1))!!.dataLength)
        assertEquals(2000L, readWav(wav(1000, declared = 0))!!.dataLength)
    }

    @Test fun skipsOtherChunks() = assertNotNull(readWav(wav(100, extra = true)))

    @Test fun refusesWhatItCannotPlay() {
        assertNull(readWav(wav(100, bits = 8)))
        assertNull(readWav(wav(100, tag = 3)))
        assertNull(readWav(wav(0)))
        assertNull(readWav(tmp.newFile().also { it.writeText("not audio at all") }))
    }
}

class SplitHeadTest {
    private val sentence = "It was a bright cold day in April, and the clocks were striking thirteen."

    @Test fun splitsAfterTheFirstSentenceOfUsableLength() {
        val (head, tail) = splitHead("$sentence Winston Smith slipped quickly through the glass doors.")
        assertEquals(sentence, head)
        assertEquals("Winston Smith slipped quickly through the glass doors.", tail)
    }

    @Test fun keepsShortOrBreaklessTextWhole() {
        assertNull(splitHead("Short one. Two.").second)
        assertNull(splitHead("x".repeat(500)).second)
        assertNull(splitHead(sentence).second)
    }

    @Test fun skipsATooShortFirstSentence() {
        val text = "Yes. $sentence Then more text follows here."
        assertEquals("Yes. $sentence", splitHead(text).first)
    }
}
