package com.c0mpile.grimmreader.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.c0mpile.grimmreader.core.common.AppScope
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.datastore.SpeechPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An installed Android text-to-speech engine (an app such as Google's, Samsung's or VoxSherpa). */
data class SpeechEngine(
    val packageName: String,
    val label: String,
)

/** One voice of an engine, as the engine reports it. */
data class SpeechVoice(
    val name: String,
    val language: String,
    val needsNetwork: Boolean,
)

/** The engines on the device and the voices of the engine that is in use; [error] when it could not start. */
data class SpeechCatalog(
    val engines: List<SpeechEngine> = emptyList(),
    /** Label of the system's default engine, for the "System default" choice. */
    val defaultEngineLabel: String? = null,
    val voices: List<SpeechVoice> = emptyList(),
    val error: String? = null,
)

/**
 * The device's Android text-to-speech: any installed engine and any of its voices (also third-party engines such as
 * VoxSherpa), chosen in Settings. One [TextToSpeech] instance at a time, rebuilt when the chosen engine changes and
 * released a minute after its last use. The audio goes through the OS; the app makes no network call for it.
 */
@Singleton
class SystemSpeech
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val prefs: AppPreferences,
        @AppScope private val scope: CoroutineScope,
    ) : SpeechOutput {
        private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = EVENT_BUFFER)
        override val events: Flow<SpeechEvent> = _events.asSharedFlow()

        private val lock = Mutex()
        private var tts: TextToSpeech? = null
        private var engineOf: String? = null
        private var voiceOf: String? = null
        private var releaseJob: Job? = null
        private var counter = 0

        // Narration is rendered to audio files ahead of time and played gaplessly; an engine that cannot is spoken to directly.
        private val buffer =
            BufferedSpeech(
                File(context.cacheDir, "narration"),
                synthesize = ::renderToFile,
                emit = { _events.tryEmit(it) },
                onFallback = ::fallBack,
            )

        @Volatile private var bufferBroken = false

        @Volatile private var stops = 0

        override suspend fun enqueue(
            text: String,
            id: String,
        ) {
            if (bufferBroken) return speak(text, id)
            ensure()
            buffer.enqueue(Pending(id, text.take(TextToSpeech.getMaxSpeechInputLength())))
        }

        private suspend fun speak(
            text: String,
            id: String,
        ) {
            val t = ensure()
            val max = TextToSpeech.getMaxSpeechInputLength()
            val result = withContext(Dispatchers.Main) { t.speak(text.take(max), TextToSpeech.QUEUE_ADD, null, id) }
            if (result != TextToSpeech.SUCCESS) _events.tryEmit(SpeechEvent.Failed(id, "The speech engine refused the text."))
        }

        private suspend fun renderToFile(
            text: String,
            synthId: String,
            file: File,
        ): Boolean {
            val t = ensure()
            return withContext(Dispatchers.Main) { t.synthesizeToFile(text, null, file, synthId) } == TextToSpeech.SUCCESS
        }

        /** The engine cannot render files: from now on (for this engine) it speaks directly, starting with what was left. */
        private fun fallBack(rest: List<Pending>) {
            bufferBroken = true
            val mine = stops
            scope.launch {
                for (item in rest) {
                    if (mine != stops) return@launch
                    try {
                        speak(item.text, item.id)
                    } catch (e: SpeechUnavailableException) {
                        _events.tryEmit(SpeechEvent.Failed(item.id, e.message ?: "The speech engine is not available."))
                        return@launch
                    }
                }
            }
        }

        override fun heardChars(id: String): Int? = if (bufferBroken) null else buffer.heard(id)

        override fun stop() {
            stops++
            buffer.stop()
            tts?.stop()
        }

        override fun releaseLater() {
            releaseJob?.cancel()
            releaseJob =
                scope.launch {
                    delay(RELEASE_AFTER_MS)
                    release()
                }
        }

        /** Speaks [text] now (a word or a selection) and returns when it has been said. */
        suspend fun say(text: String) {
            val id = "say-${counter++}"
            try {
                coroutineScope {
                    val outcome =
                        async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            _events.asSharedFlow().first { it.id == id && it !is SpeechEvent.Started }
                        }
                    stop()
                    speak(text, id)
                    val end = outcome.await()
                    if (end is SpeechEvent.Failed) throw IOException(end.message)
                }
            } finally {
                stop()
                releaseLater()
            }
        }

        /** The installed engines and the voices of the chosen one. Starts that engine, which can take a moment. */
        suspend fun catalog(): SpeechCatalog {
            val t =
                try {
                    ensure()
                } catch (e: SpeechUnavailableException) {
                    return SpeechCatalog(error = e.message)
                }
            return withContext(Dispatchers.Main) {
                val engines = t.engines.map { SpeechEngine(it.name, it.label) }
                val voices =
                    runCatching { t.voices }
                        .getOrNull()
                        .orEmpty()
                        .filter { "notInstalled" !in it.features }
                        .map { it.toVoice() }
                        .sortedWith(compareBy({ it.language }, { it.name }))
                SpeechCatalog(
                    engines = engines,
                    defaultEngineLabel = engines.firstOrNull { it.packageName == t.defaultEngine }?.label,
                    voices = voices,
                )
            }.also { releaseLater() }
        }

        private fun Voice.toVoice() = SpeechVoice(name, locale.getDisplayName(Locale.getDefault()), isNetworkConnectionRequired)

        private suspend fun ensure(): TextToSpeech =
            lock.withLock {
                releaseJob?.cancel()
                val p = prefs.speechPrefs.first()
                withContext(Dispatchers.Main) {
                    var t = tts
                    if (t != null && engineOf != p.engine) {
                        t.shutdown()
                        tts = null
                        t = null
                    }
                    if (t == null) {
                        t = create(p.engine)
                        tts = t
                        engineOf = p.engine
                        voiceOf = null
                        bufferBroken = false
                    }
                    configure(t, p)
                    t
                }
            }

        private fun configure(
            t: TextToSpeech,
            p: SpeechPrefs,
        ) {
            t.setSpeechRate(p.rate)
            t.setPitch(p.pitch)
            if (voiceOf == p.voice) return
            voiceOf = p.voice
            val voice = p.voice?.let { name -> runCatching { t.voices }.getOrNull()?.firstOrNull { it.name == name } }
            if (voice != null) t.voice = voice
        }

        private suspend fun create(engine: String?): TextToSpeech {
            val label = engine ?: "the default engine"
            try {
                return withTimeout(INIT_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        var created: TextToSpeech? = null
                        val onInit =
                            TextToSpeech.OnInitListener { status ->
                                val t = created
                                if (status == TextToSpeech.SUCCESS && t != null) {
                                    cont.resume(t)
                                } else {
                                    t?.shutdown()
                                    cont.resumeWithException(SpeechUnavailableException("The speech engine ($label) could not start."))
                                }
                            }
                        created = TextToSpeech(context, onInit, engine)
                        cont.invokeOnCancellation { created.shutdown() }
                    }
                }.also(::prepare)
            } catch (e: TimeoutCancellationException) {
                throw SpeechUnavailableException("The speech engine ($label) did not start in time.").apply { initCause(e) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw SpeechUnavailableException("The speech engine ($label) is not available.").apply { initCause(e) }
            }
        }

        private fun prepare(t: TextToSpeech) {
            t.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            t.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        if (buffer.isSynth(utteranceId)) return
                        _events.tryEmit(SpeechEvent.Started(utteranceId))
                    }

                    override fun onDone(utteranceId: String) {
                        if (buffer.isSynth(utteranceId)) {
                            buffer.onSynth(utteranceId, true)
                            return
                        }
                        _events.tryEmit(SpeechEvent.Done(utteranceId))
                    }

                    @Deprecated("Replaced by the overload with an error code.")
                    override fun onError(utteranceId: String) {
                        if (buffer.isSynth(utteranceId)) return buffer.onSynth(utteranceId, false)
                        _events.tryEmit(SpeechEvent.Failed(utteranceId, "The speech engine failed."))
                    }

                    override fun onError(
                        utteranceId: String,
                        errorCode: Int,
                    ) {
                        if (buffer.isSynth(utteranceId)) return buffer.onSynth(utteranceId, false)
                        _events.tryEmit(SpeechEvent.Failed(utteranceId, "The speech engine failed (error $errorCode)."))
                    }

                    override fun onStop(
                        utteranceId: String,
                        interrupted: Boolean,
                    ) {
                        if (buffer.isSynth(utteranceId)) return buffer.onSynth(utteranceId, false)
                        _events.tryEmit(SpeechEvent.Stopped(utteranceId))
                    }
                },
            )
        }

        private suspend fun release() {
            lock.withLock {
                withContext(Dispatchers.Main) {
                    tts?.shutdown()
                    tts = null
                    engineOf = null
                    voiceOf = null
                }
            }
        }

        private companion object {
            const val EVENT_BUFFER = 128
            const val INIT_TIMEOUT_MS = 20_000L
            const val RELEASE_AFTER_MS = 60_000L
        }
    }
