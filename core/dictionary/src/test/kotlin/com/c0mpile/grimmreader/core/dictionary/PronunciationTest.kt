package com.c0mpile.grimmreader.core.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class PronunciationTest {
    @Test
    fun usesTheTurboModel() {
        assertEquals("eleven_v4_turbo", Pronunciation.MODEL_ID)
    }
}
