package com.c0mpile.grimmreader.core.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class PronunciationTest {
    @Test
    fun usesTheConfiguredVoiceAndModel() {
        assertEquals("QtY3JBOUKEB5xzrRfOKc", Pronunciation.VOICE_ID)
        assertEquals("eleven_v4_turbo", Pronunciation.MODEL_ID)
    }
}
