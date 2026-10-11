package com.c0mpile.grimmreader.core.tts

import android.content.Context
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.datastore.SpeechProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the open book aloud with System text-to-speech: sentence by sentence, many sentences queued ahead of the
 * one being spoken so the voice never waits for the page, which marks the sentence and turns to it. Runs in a media
 * foreground service ([NarrationService]) with a wake lock and audio focus, so it goes on in the background and with
 * the screen off. Everything here runs on the main thread.
 */
@Singleton
class NarrationController internal constructor(
    @ApplicationContext private val context: Context,
    private val speech: SpeechOutput,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        speech: SystemSpeech,
        prefs: AppPreferences,
    ) : this(context, speech, prefs, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    private val _state = MutableStateFlow(NarrationState())
    val state: StateFlow<NarrationState> = _state.asStateFlow()

    private val queue = NarrationQueue()
    private var source: NarrationSource? = null
    private var owner: Any? = null
    private var fetching: Job? = null
    private var idleJob: Job? = null
    private var ticker: Job? = null
    private var fetchFailures = 0
    private val place = NarrationPlace(queue, speech, prefs, scope)
    private val submitLock = Mutex()
    private val session = PlaybackSession(context, onPause = ::pause, onResume = ::resume, isPlaying = { playing })

    /** The sleep timer: minutes, or the end of the chapter being read. */
    val sleepTimer =
        SleepTimerControls(
            scope,
            onChange = { timer -> _state.update { it.copy(sleep = timer) } },
            onExpired = { message ->
                if (_state.value.status == NarrationStatus.PLAYING) {
                    pause()
                    say(message)
                }
            },
            currentSection = { queue.head?.chunk?.section ?: queue.lastFetched?.section },
            onArmed = ::submitPending,
        )

    private val playing get() = _state.value.status == NarrationStatus.PLAYING

    init {
        scope.launch { speech.events.collect(::onSpeech) }
        scope.launch {
            prefs.speechPrefs.collect { p ->
                _state.update { it.copy(systemSpeechOn = p.provider == SpeechProvider.SYSTEM, rate = p.rate) }
                if (p.provider != SpeechProvider.SYSTEM && _state.value.status != NarrationStatus.IDLE) stop()
            }
        }
    }

    /** The reader that shows [source] takes over; a book already being narrated goes on from where it was queued. */
    fun attach(
        owner: Any,
        source: NarrationSource,
        meta: NarrationMeta,
    ) {
        // Another reader (another book) than the one being narrated: that narration ends.
        if (this.owner != owner && _state.value.status != NarrationStatus.IDLE) stop()
        this.owner = owner
        this.source = source
        _state.update { it.copy(meta = meta) }
        if (_state.value.status == NarrationStatus.IDLE) return
        // The page was rebuilt (rotation): continue after the last passage that was fetched.
        fetching?.cancel()
        queue.ended = false
        fetchFailures = 0
        scope.launch {
            val last = queue.lastFetched
            if (source.begin(last?.locator, after = last != null)) topUp() else queue.ended = true
        }
    }

    /** The reader's page went away; narration goes on from what is queued until the page comes back. */
    fun detach(owner: Any) {
        if (this.owner != owner) return
        source = null
        fetching?.cancel()
    }

    /** The reader is closed for good. */
    fun release(owner: Any) {
        if (this.owner != owner) return
        stop()
        this.owner = null
        source = null
    }

    fun updateMeta(
        owner: Any,
        meta: NarrationMeta,
    ) {
        if (this.owner == owner) _state.update { it.copy(meta = meta) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Starts reading at the first word of the visible page, or at the word [at] (also while reading: it starts over there). */
    fun start(at: NarrationPoint? = null) {
        val src = source ?: return
        if (_state.value.status != NarrationStatus.IDLE && at == null) return
        scope.launch {
            if (prefs.speechPrefs.first().provider != SpeechProvider.SYSTEM) {
                say("Turn on System text-to-speech in Settings to have books read aloud.")
                return@launch
            }
            queue.clear()
            flush()
            fetchFailures = 0
            _state.update { it.copy(status = NarrationStatus.LOADING, message = null) }
            NarrationService.start(context)
            val resume = if (at == null) place.saved(owner) else null
            if (src.begin(null, after = false, at = at, resume = resume)) {
                play()
            } else {
                stop("There is no text to read here.")
            }
        }
    }

    fun toggle() {
        when (_state.value.status) {
            NarrationStatus.PLAYING, NarrationStatus.LOADING -> pause()
            NarrationStatus.PAUSED -> play()
            NarrationStatus.IDLE -> start()
        }
    }

    fun pause() {
        if (_state.value.status == NarrationStatus.IDLE) return
        if (playing) place.remember(owner)
        ticker?.cancel()
        flush(releaseAudio = true)
        _state.update { it.copy(status = NarrationStatus.PAUSED) }
        idleJob?.cancel()
        idleJob =
            scope.launch {
                delay(PAUSED_LIMIT_MS)
                stop()
            }
    }

    fun resume() {
        if (_state.value.status == NarrationStatus.PAUSED) play()
    }

    fun stop(
        message: String? = null,
        finished: Boolean = false,
    ) {
        if (finished) {
            place.forget(owner)
        } else if (playing) {
            place.remember(owner)
        }
        fetching?.cancel()
        sleepTimer.clear()
        idleJob?.cancel()
        ticker?.cancel()
        flush(releaseAudio = true)
        source?.clearSpoken()
        queue.clear()
        _state.update { it.copy(status = NarrationStatus.IDLE, sleep = null, message = message ?: it.message) }
        speech.releaseLater()
    }

    /** Moves to the next ([forward]) or the previous passage; a passage being spoken is cut off. */
    fun skip(forward: Boolean) {
        if (!(if (forward) queue.skipForward() else queue.skipBack())) return
        flush()
        queue.head?.let { source?.spoken(it.chunk) }
        if (!playing) return
        if (queue.isEmpty && queue.ended) stop("End of the book.", finished = true) else topUp()
    }

    /** Speech speed (1 = normal); a passage being spoken starts over at the new speed. */
    fun setRate(rate: Float) {
        scope.launch {
            prefs.setSpeechRate(rate)
            if (playing) {
                flush()
                topUp()
            }
        }
    }

    private fun say(message: String) = _state.update { it.copy(message = message) }

    /** The engine's queue is dropped; what is still [queue]d is submitted again by [topUp]. */
    private fun flush(releaseAudio: Boolean = false) {
        queue.dropSubmitted()
        speech.stop()
        if (releaseAudio) session.stop()
    }

    private fun play() {
        idleJob?.cancel()
        if (!session.start()) {
            _state.update { it.copy(status = NarrationStatus.PAUSED, message = "Another app is using the audio right now.") }
            return
        }
        _state.update { it.copy(status = NarrationStatus.PLAYING) }
        NarrationService.start(context)
        queue.head?.let { source?.spoken(it.chunk) }
        ticker?.cancel()
        ticker =
            scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    source?.let(place::report)
                }
            }
        topUp()
    }

    /** Hands the engine what it lacks and, while the queue is short, fetches more from the page. */
    private fun topUp() {
        if (!playing) return
        submitPending()
        if (queue.ended || fetching?.isActive == true) return
        val src = source ?: return
        if (queue.queuedChars >= AHEAD_CHARS || queue.queuedCount >= AHEAD_MAX) return
        fetching =
            scope.launch {
                val got = withTimeoutOrNull(FETCH_TIMEOUT_MS) { src.next(BATCH) }
                if (got == null) {
                    // What is queued keeps playing; the page gets a few more tries before narration gives up.
                    if (++fetchFailures > MAX_FETCH_FAILURES) {
                        fetching = null
                        pause()
                        say("The page stopped responding.")
                        return@launch
                    }
                    delay(RETRY_MS * fetchFailures)
                    fetching = null
                    topUp()
                    return@launch
                }
                fetchFailures = 0
                if (got.isEmpty()) queue.ended = true else queue.add(got)
                fetching = null
                when {
                    queue.ended && queue.isEmpty -> stop("End of the book.", finished = true)
                    !chapterEnded() -> topUp()
                }
            }
    }

    /** Queues the fetched passages the engine does not have yet (not past the end of the chapter when that timer is set). */
    private fun submitPending() {
        if (!playing) return
        val mine = queue.epoch
        val todo = queue.takeUnsubmitted(sleepTimer.chapter)
        if (todo.isEmpty()) return
        scope.launch {
            submitLock.withLock {
                for (item in todo) {
                    if (mine != queue.epoch) return@withLock
                    try {
                        speech.enqueue(item.text, "n$mine:${item.seq}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SpeechUnavailableException) {
                        pause()
                        say(e.message ?: "The speech engine is not available.")
                        return@withLock
                    }
                }
            }
        }
    }

    private fun onSpeech(event: SpeechEvent) {
        if (!playing) return
        val (epoch, seq) = queue.parse(event.id) ?: return
        if (epoch != queue.epoch) return
        when (event) {
            is SpeechEvent.Started -> {
                if (queue.head?.seq == seq) {
                    source?.spoken(queue.head!!.chunk)
                    place.save(owner, queue.head!!)
                }
                topUp()
            }
            is SpeechEvent.Done -> {
                queue.finished(seq)
                when {
                    chapterEnded() -> Unit
                    queue.isEmpty && queue.ended -> stop("End of the book.", finished = true)
                    queue.isEmpty && source == null -> pause()
                    else -> topUp()
                }
            }
            is SpeechEvent.Failed -> {
                pause()
                say(event.message)
            }
            is SpeechEvent.Stopped -> Unit
        }
    }

    /** True (and paused) once everything of the chapter being read has been spoken. */
    private fun chapterEnded(): Boolean = queue.head?.let { sleepTimer.chapterEnded(it.chunk.section) } == true

    private companion object {
        const val BATCH = 4
        const val TICK_MS = 300L
        const val AHEAD_CHARS = 3_000
        const val AHEAD_MAX = 16
        const val FETCH_TIMEOUT_MS = 15_000L
        const val MAX_FETCH_FAILURES = 5
        const val RETRY_MS = 2_000L
        const val PAUSED_LIMIT_MS = 30 * 60_000L
    }
}
