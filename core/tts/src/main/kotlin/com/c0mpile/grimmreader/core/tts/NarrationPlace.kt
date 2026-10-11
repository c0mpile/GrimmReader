package com.c0mpile.grimmreader.core.tts

import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.datastore.NarrationResume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Keeps the word narration is at: in the passage being spoken (so it goes on from there) and, saved, for the book. */
internal class NarrationPlace(
    private val queue: NarrationQueue,
    private val speech: SpeechOutput,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
) {
    /** Notes how far the passage being spoken has been heard, back to the start of the word, and saves it. */
    fun remember(book: Any?) {
        val head = queue.head ?: return
        val text = head.chunk.text
        val heard = speech.heardChars(queue.id(head)) ?: 0
        val at = (head.skip + heard).coerceIn(0, maxOf(text.length - 1, 0))
        head.skip = (text.lastIndexOf(' ', at - 1) + 1).coerceAtLeast(head.skip)
        save(book, head)
    }

    fun save(
        book: Any?,
        head: NarrationQueue.Item,
    ) {
        val id = book as? Long ?: return
        val heardWords =
            head.chunk.text
                .take(head.skip)
                .count { it != ' ' }
        scope.launch { prefs.setNarrationResume(id, NarrationResume(head.chunk.locator, heardWords)) }
    }

    /** Tells the page how far the passage being spoken has been heard (when the output can tell). */
    fun report(source: NarrationSource) {
        val head = queue.head ?: return
        val heard = speech.heardChars(queue.id(head)) ?: return
        source.progress(
            head.chunk,
            head.chunk.text
                .take(head.skip + heard)
                .count { it != ' ' },
        )
    }

    fun forget(book: Any?) {
        val id = book as? Long ?: return
        scope.launch { prefs.setNarrationResume(id, null) }
    }

    suspend fun saved(book: Any?): NarrationResume? = (book as? Long)?.let { prefs.narrationResume(it) }
}
