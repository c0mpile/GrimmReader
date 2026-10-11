package com.c0mpile.grimmreader.core.tts

import kotlinx.coroutines.flow.Flow

/** What happens to a spoken utterance, by the id it was queued with. */
sealed interface SpeechEvent {
    val id: String

    data class Started(
        override val id: String,
    ) : SpeechEvent

    data class Done(
        override val id: String,
    ) : SpeechEvent

    data class Failed(
        override val id: String,
        val message: String,
    ) : SpeechEvent

    /** Cut off by [SpeechOutput.stop]. */
    data class Stopped(
        override val id: String,
    ) : SpeechEvent
}

/** The text-to-speech output narration speaks through; [SystemSpeech] is the real one. */
interface SpeechOutput {
    val events: Flow<SpeechEvent>

    /** Queues [text] behind what is already queued. Throws [SpeechUnavailableException] when no engine starts. */
    suspend fun enqueue(
        text: String,
        id: String,
    )

    /** How many characters of the passage [id], which is being heard now, have been heard; null when the output cannot tell. */
    fun heardChars(id: String): Int? = null

    /** Drops everything queued and silences the engine. */
    fun stop()

    /** Frees the engine a little while after the last use, so a large voice model does not stay in memory. */
    fun releaseLater()
}

class SpeechUnavailableException(
    message: String,
) : Exception(message)
