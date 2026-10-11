package com.c0mpile.grimmreader.core.tts

import com.c0mpile.grimmreader.core.datastore.NarrationResume

/** A passage to speak (a sentence or two), with where it is in the book. */
data class NarrationChunk(
    val text: String,
    /** The book's own locator for the passage (an EPUB CFI), handed back to [NarrationSource.spoken]. */
    val locator: String,
    /** Index of the book section (chapter file) the passage is in; the sleep timer's "end of chapter" uses it. */
    val section: Int,
)

/** A spot of the page the user pointed at, in the page's CSS px; narration starts at the word there. */
data class NarrationPoint(
    val x: Float,
    val y: Float,
)

/** The text of the open book, as the reader provides it. Calls come from the main thread. */
interface NarrationSource {
    /**
     * Positions the source: at the first word of the visible page when [from] is null, at [from] otherwise, or
     * just after [from] when [after]; the word at [at] wins over all of these.
     * With nothing else asked, [resume] (where the last narration of the book stopped) beats the page's first word.
     * Returns false when the book has no text to read.
     */
    suspend fun begin(
        from: String?,
        after: Boolean,
        at: NarrationPoint? = null,
        resume: NarrationResume? = null,
    ): Boolean

    /** The next passages in reading order; an empty list means the end of the book, null that the page failed to answer. */
    suspend fun next(count: Int): List<NarrationChunk>?

    /** [chunk] is being spoken now: mark it in the page and turn to it. */
    fun spoken(chunk: NarrationChunk)

    /**
     * [chars] non-space characters of [chunk], the passage being spoken, have been heard: a passage that runs onto the
     * next page turns the page where the voice gets there.
     */
    fun progress(
        chunk: NarrationChunk,
        chars: Int,
    ) = Unit

    /** Narration ended: remove the mark. */
    fun clearSpoken()
}

/** Shown in the notification and the narration sheet. */
data class NarrationMeta(
    val title: String = "",
    val subtitle: String? = null,
)

enum class NarrationStatus { IDLE, LOADING, PLAYING, PAUSED }

/** The sleep timer: [remainingMs] counts down; [endOfChapter] stops when the current section ends. */
data class SleepTimer(
    val remainingMs: Long? = null,
    val endOfChapter: Boolean = false,
)

data class NarrationState(
    val status: NarrationStatus = NarrationStatus.IDLE,
    val meta: NarrationMeta = NarrationMeta(),
    val sleep: SleepTimer? = null,
    /** An explanation for the user (engine missing, sleep timer ended, end of book); cleared by [NarrationController.messageShown]. */
    val message: String? = null,
    /** False when System text-to-speech is switched off in Settings, which narration needs. */
    val systemSpeechOn: Boolean = false,
    val rate: Float = 1f,
)
