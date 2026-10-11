package com.c0mpile.grimmreader.core.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** An utterance waiting to be spoken, by the id narration gave it. */
internal class Pending(
    val id: String,
    val text: String,
)

/**
 * Speaks queued text without the pauses an engine leaves between utterances: it has the engine render the passages to
 * audio files well ahead of the one being played and plays the files back to back through one [AudioTrack]. The
 * [events] are the same ones the engine would send (started when a passage becomes audible, done when it has been
 * heard). When the engine cannot render a file the session ends and [onFallback] gets what has not been spoken yet.
 */
internal class BufferedSpeech(
    private val dir: File,
    private val synthesize: suspend (text: String, synthId: String, file: File) -> Boolean,
    private val emit: (SpeechEvent) -> Unit,
    private val onFallback: (List<Pending>) -> Unit,
) {
    private val synthIds = AtomicInteger()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var session: Session? = null

    /** True for the ids of the files being rendered, which [SystemSpeech]'s listener hands to [onSynth]. */
    fun isSynth(id: String) = id.startsWith(SYNTH_PREFIX)

    fun onSynth(
        id: String,
        ok: Boolean,
    ) {
        waiting[id]?.complete(ok)
    }

    @Synchronized
    fun enqueue(item: Pending) {
        val s = session ?: Session().also { session = it }
        s.add(item)
    }

    @Synchronized
    fun stop() {
        session?.close()
        session = null
    }

    @Synchronized
    private fun ended(s: Session) {
        if (session === s) session = null
    }

    private class Mark(
        val id: String,
        val start: Long,
        val trackNo: Int,
    ) {
        @Volatile var end = Long.MAX_VALUE
        var started = false
    }

    private class Clip(
        val item: Pending,
        val file: File,
        val wav: Wav,
    )

    private inner class Session {
        private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val inbox = Channel<Pending>(Channel.UNLIMITED)
        private val clips = Channel<Clip>(Channel.UNLIMITED)
        private val marks = ConcurrentLinkedQueue<Mark>()
        private val unfinished = ConcurrentLinkedQueue<Pending>()
        private val aheadMs = AtomicLong()
        private val closed = AtomicBoolean()

        @Volatile private var track: AudioTrack? = null

        @Volatile private var trackNo = 0

        init {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            work.launch { renderLoop() }
            work.launch { playLoop() }
            work.launch { watchLoop() }
        }

        fun add(item: Pending) {
            unfinished.add(item)
            inbox.trySend(item)
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            work.cancel()
            inbox.close()
            clips.close()
            track?.let {
                runCatching {
                    it.pause()
                    it.flush()
                    it.release()
                }
            }
            track = null
            waiting.values.forEach { it.complete(false) }
            dir.listFiles()?.forEach { it.delete() }
        }

        /** The engine cannot do this: end the session and hand what is left to the fallback. */
        private fun fail() {
            val rest = unfinished.toList()
            close()
            ended(this)
            onFallback(rest)
        }

        private suspend fun renderLoop() {
            for (item in inbox) {
                while (aheadMs.get() > AHEAD_MS) delay(POLL_MS)
                val id = "$SYNTH_PREFIX${synthIds.incrementAndGet()}"
                val file = File(dir, "$id.wav")
                val done = CompletableDeferred<Boolean>().also { waiting[id] = it }
                val started =
                    try {
                        synthesize(item.text, id, file)
                    } catch (_: SpeechUnavailableException) {
                        false
                    }
                val ok = started && withTimeoutOrNull(RENDER_TIMEOUT_MS) { done.await() } == true
                waiting.remove(id)
                currentCoroutineContext().ensureActive()
                val wav = if (ok) readWav(file) else null
                if (wav == null) {
                    file.delete()
                    fail()
                    return
                }
                aheadMs.addAndGet(wav.durationMs)
                clips.trySend(Clip(item, file, wav))
            }
        }

        private fun newTrack(wav: Wav): AudioTrack {
            val mask = if (wav.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val buffer = maxOf(AudioTrack.getMinBufferSize(wav.sampleRate, mask, encoding), wav.sampleRate * wav.frameSize)
            return AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(encoding)
                        .setSampleRate(wav.sampleRate)
                        .setChannelMask(mask)
                        .build(),
                ).setBufferSizeInBytes(buffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        }

        private suspend fun playLoop() {
            var format: Pair<Int, Int>? = null
            var written = 0L
            for (clip in clips) {
                aheadMs.addAndGet(-clip.wav.durationMs)
                val now = clip.wav.sampleRate to clip.wav.channels
                var t = track
                if (t == null || format != now) {
                    // Another format (a voice change): let what is queued finish, then start a new track.
                    if (t != null) {
                        while (work.isActive && (t.playbackHeadPosition.toLong() and UINT) < written) delay(POLL_MS)
                        trackNo++
                        t.release()
                    }
                    written = 0
                    t = runCatching { newTrack(clip.wav) }.getOrNull()
                    track = t
                    format = now
                    if (t == null) {
                        fail()
                        return
                    }
                }
                val mark = Mark(clip.item.id, written, trackNo)
                marks.add(mark)
                val bytes = writeClip(t, clip)
                if (bytes < 0) {
                    fail()
                    return
                }
                written += bytes / clip.wav.frameSize
                mark.end = written
                clip.file.delete()
            }
        }

        /** Plays the file's samples into [t] (blocks while its buffer is full); the bytes written, or -1 on an error. */
        private fun writeClip(
            t: AudioTrack,
            clip: Clip,
        ): Long {
            val buf = ByteArray(CHUNK)
            var left = clip.wav.dataLength
            var total = 0L
            RandomAccessFile(clip.file, "r").use { f ->
                f.seek(clip.wav.dataOffset)
                while (left > 0 && work.isActive) {
                    val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    val whole = n - n % clip.wav.frameSize
                    if (whole > 0 && t.write(buf, 0, whole) < 0) return -1
                    left -= n
                    total += whole
                }
            }
            return total
        }

        /** Tells when each passage became audible and when it has been heard, by the track's playback position. */
        private suspend fun watchLoop() {
            while (work.isActive) {
                delay(POLL_MS)
                val t = track ?: continue
                report(t.playbackHeadPosition.toLong() and UINT)
            }
        }

        private fun report(head: Long) {
            while (true) {
                val m = marks.peek() ?: return
                val past = m.trackNo < trackNo
                if (!m.started && (past || audible(m, head))) {
                    m.started = true
                    emit(SpeechEvent.Started(m.id))
                }
                if (!m.started || !(past || head >= m.end)) return
                marks.poll()
                unfinished.removeIf { it.id == m.id }
                emit(SpeechEvent.Done(m.id))
            }
        }

        /** The track has played into the passage (the first one only once something has been heard). */
        private fun audible(
            m: Mark,
            head: Long,
        ) = if (m.start == 0L) head > 0 else head >= m.start
    }

    companion object {
        const val SYNTH_PREFIX = "syn-"
        private const val AHEAD_MS = 90_000L
        private const val RENDER_TIMEOUT_MS = 90_000L
        private const val POLL_MS = 40L
        private const val CHUNK = 16_384
        private const val UINT = 0xFFFFFFFFL
    }
}
