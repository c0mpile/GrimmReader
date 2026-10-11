package com.c0mpile.grimmreader.core.tts

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The narration sleep timer: stop after some minutes, or at the end of the chapter being read. It reports through
 * [onChange] (for the state shown) and [onExpired] (to pause); the chapter case is checked by [chapterEnded].
 */
class SleepTimerControls internal constructor(
    private val scope: CoroutineScope,
    private val onChange: (SleepTimer?) -> Unit,
    private val onExpired: (String) -> Unit,
    private val currentSection: () -> Int?,
    private val onArmed: () -> Unit,
) {
    private var job: Job? = null
    internal var chapter: Int? = null
        private set

    fun setMinutes(minutes: Int) {
        job?.cancel()
        chapter = null
        val end = SystemClock.elapsedRealtime() + minutes * MINUTE_MS
        job =
            scope.launch {
                while (true) {
                    val left = end - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    onChange(SleepTimer(remainingMs = left))
                    delay(minOf(TICK_MS, left))
                }
                onChange(null)
                onExpired("Sleep timer ended.")
            }
    }

    /** Stops once the section being spoken is finished. */
    fun setEndOfChapter() {
        job?.cancel()
        chapter = currentSection()
        onChange(SleepTimer(endOfChapter = true))
        onArmed()
    }

    fun cancel() {
        job?.cancel()
        chapter = null
        onChange(null)
        onArmed()
    }

    /** The next passage to speak is in [nextSection]: true (and the timer is spent) when that is past the chapter. */
    internal fun chapterEnded(nextSection: Int): Boolean {
        val end = chapter ?: return false
        if (nextSection == end) return false
        chapter = null
        onChange(null)
        onExpired("Sleep timer ended at the end of the chapter.")
        return true
    }

    internal fun clear() {
        job?.cancel()
        chapter = null
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val TICK_MS = 1_000L
    }
}
