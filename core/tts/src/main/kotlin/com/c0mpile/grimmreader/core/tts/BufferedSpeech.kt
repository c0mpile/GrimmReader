package com.c0mpile.grimmreader.core.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CancellationException
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

/** The first sentence of [text] (at least ~60 characters, at most ~200) and the rest; no rest when it is short or has no break. */
internal fun splitHead(text: String): Pair<String, String?> {
    val end =
        Regex("""[.!?…]["'”’)]*\s""")
            .findAll(text)
            .map { it.range.last }
            .firstOrNull { it + 1 in HEAD_MIN..HEAD_MAX }
    if (end == null) return text to null
    val tail = text.substring(end + 1).trim()
    return if (tail.isEmpty()) text to null else text.substring(0, end + 1).trim() to tail
}

private const val HEAD_MIN = 60
private const val HEAD_MAX = 200

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
    fun heard(id: String): Int? = session?.heard(id)

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

        /** The pieces of the passage that reached the track: their characters and their length in frames. */
        val pieces = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Long>>()
    }

    /** A rendered piece of a passage; the passage is heard once its [last] piece has been played. */
    private class Clip(
        val item: Pending,
        val file: File,
        val wav: Wav,
        val last: Boolean,
        val chars: Int,
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
            work.launch { guarded { renderLoop() } }
            work.launch { guarded { playLoop() } }
            work.launch { watchLoop() }
        }

        /** Characters of the passage [id] heard so far, from how much of its audio the track has played. */
        fun heard(id: String): Int? {
            val t = track ?: return null
            val m = marks.firstOrNull { it.id == id && it.started && it.trackNo == trackNo } ?: return null
            var rel = (t.playbackHeadPosition.toLong() and UINT) - m.start
            var chars = 0.0
            for ((n, frames) in m.pieces) {
                if (rel >= frames) {
                    chars += n
                    rel -= frames
                } else {
                    chars += n * rel.coerceAtLeast(0) / frames.coerceAtLeast(1).toDouble()
                    break
                }
            }
            return chars.toInt()
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

        /** Runs a loop; an error that is not a stop (the files are gone, the track broke) ends the session like any other failure. */
        private suspend fun guarded(loop: suspend () -> Unit) {
            try {
                loop()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail()
            }
        }

        /** The engine cannot do this: end the session and hand what is left to the fallback (nothing when it was closed on purpose). */
        private fun fail() {
            if (closed.get()) return
            Log.w(TAG, "buffered narration failed: ${unfinished.size} passages go to direct speech")
            val rest = unfinished.toList()
            close()
            ended(this)
            onFallback(rest)
        }

        private suspend fun renderLoop() {
            var first = true
            for (item in inbox) {
                // The first passage is rendered in two pieces so the sound starts after a short sentence, not a whole passage.
                val (head, tail) = if (first) splitHead(item.text) else item.text to null
                first = false
                if (!render(item, head, tail == null)) return
                if (tail != null && !render(item, tail, true)) return
            }
        }

        /** Renders [text] to a file and queues it for playing; false when the engine cannot (the fallback has been called). */
        private suspend fun render(
            item: Pending,
            text: String,
            last: Boolean,
        ): Boolean {
            while (aheadMs.get() > AHEAD_MS) delay(POLL_MS)
            val id = "$SYNTH_PREFIX${synthIds.incrementAndGet()}"
            val file = File(dir, "$id.wav")
            val done = CompletableDeferred<Boolean>().also { waiting[id] = it }
            val started =
                try {
                    synthesize(text, id, file)
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
                return false
            }
            Log.d(TAG, "rendered ${item.id} chars=${text.length} ms=${wav.durationMs} last=$last")
            aheadMs.addAndGet(wav.durationMs)
            clips.trySend(Clip(item, file, wav, last, text.length))
            return true
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
            var mark: Mark? = null
            for (clip in clips) {
                if (closed.get()) return
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
                // A passage rendered in pieces has one mark: it starts with the first piece and ends with the last.
                val m = mark?.takeIf { it.id == clip.item.id } ?: Mark(clip.item.id, written, trackNo).also { marks.add(it) }
                mark = m
                Log.d(TAG, "play ${clip.item.id} chars=${clip.chars} frames=${clip.wav.dataLength / clip.wav.frameSize}")
                m.pieces.add(clip.chars to clip.wav.dataLength / clip.wav.frameSize)
                val bytes = writeClip(t, clip)
                if (bytes < 0) {
                    fail()
                    return
                }
                written += bytes / clip.wav.frameSize
                if (clip.last) {
                    m.end = written
                    mark = null
                }
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
        private const val TAG = "GrimmNarration"
        const val SYNTH_PREFIX = "syn-"
        private const val AHEAD_MS = 90_000L
        private const val RENDER_TIMEOUT_MS = 90_000L
        private const val POLL_MS = 40L
        private const val CHUNK = 16_384
        private const val UINT = 0xFFFFFFFFL
    }
}
