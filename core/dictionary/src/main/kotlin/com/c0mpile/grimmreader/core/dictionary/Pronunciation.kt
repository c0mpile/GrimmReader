package com.c0mpile.grimmreader.core.dictionary

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.c0mpile.grimmreader.core.common.AppScope
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.datastore.SecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PronunciationState {
    data object Idle : PronunciationState

    /** [word] is being fetched or played. */
    data class Busy(
        val word: String,
    ) : PronunciationState

    data class Failed(
        val message: String,
    ) : PronunciationState
}

/**
 * Speaks a looked-up word with ElevenLabs text-to-speech. Runs only when the user taps the speaker icon and
 * has saved an API key and a voice ID (Keystore-encrypted in [SecretStore]); the audio is cached per word so a repeat costs
 * nothing. Uses the guarded client; nothing happens in the background.
 */
@Singleton
class Pronunciation
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val client: dagger.Lazy<OkHttpClient>,
        private val secrets: SecretStore,
        @IoDispatcher private val io: CoroutineDispatcher,
        @AppScope private val scope: CoroutineScope,
    ) {
        private val dir = File(context.cacheDir, "pronunciation")
        private val _state = MutableStateFlow<PronunciationState>(PronunciationState.Idle)
        val state: StateFlow<PronunciationState> = _state.asStateFlow()
        private val _hasKey = MutableStateFlow<Boolean?>(null)

        /** Whether an API key is saved; null until first read. */
        val hasKey: StateFlow<Boolean?> = _hasKey.asStateFlow()
        private var job: Job? = null
        private var player: MediaPlayer? = null

        private val _voiceId = MutableStateFlow<String?>(null)

        /** The voice the user chose in Settings; none is built in. Null until read or when unset. */
        val voiceId: StateFlow<String?> = _voiceId.asStateFlow()

        suspend fun refreshKey() {
            _hasKey.value = !secrets.get(KEY_NAME).isNullOrBlank()
            _voiceId.value = secrets.get(VOICE_NAME)?.takeIf { it.isNotBlank() }
        }

        suspend fun setVoiceId(id: String?) {
            secrets.put(VOICE_NAME, id?.trim()?.takeIf { it.isNotEmpty() })
            refreshKey()
        }

        suspend fun setApiKey(key: String?) {
            secrets.put(KEY_NAME, key?.trim()?.takeIf { it.isNotEmpty() })
            refreshKey()
        }

        @Synchronized
        fun speak(word: String) {
            val text = word.trim().take(MAX_CHARS)
            if (text.isEmpty()) return
            job?.cancel()
            stopPlayer()
            _state.value = PronunciationState.Busy(text)
            job =
                scope.launch {
                    try {
                        val key = secrets.get(KEY_NAME)
                        if (key.isNullOrBlank()) {
                            _state.value = PronunciationState.Failed("Add an ElevenLabs API key in Settings → Pronunciation.")
                            return@launch
                        }
                        val voice = secrets.get(VOICE_NAME)?.takeIf { it.isNotBlank() }
                        if (voice == null) {
                            _state.value = PronunciationState.Failed("Add an ElevenLabs voice ID in Settings → Pronunciation.")
                            return@launch
                        }
                        val file = withContext(io) { cached(text, voice) ?: fetch(text, key, voice) }
                        withContext(Dispatchers.Main) { play(file) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        _state.value = PronunciationState.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Pronunciation failed")
                    }
                }
        }

        fun dismissError() {
            if (_state.value is PronunciationState.Failed) _state.value = PronunciationState.Idle
        }

        private fun fileFor(
            text: String,
            voice: String,
        ): File {
            val hash = MessageDigest.getInstance("SHA-256").digest("$voice|$MODEL_ID|$text".toByteArray())
            return File(dir, hash.joinToString("") { "%02x".format(it) }.take(HASH_CHARS) + ".mp3")
        }

        private fun cached(
            text: String,
            voice: String,
        ): File? = fileFor(text, voice).takeIf { it.length() > 0 }

        private fun fetch(
            text: String,
            key: String,
            voice: String,
        ): File {
            val body =
                JSONObject()
                    .put("text", text)
                    .put("model_id", MODEL_ID)
                    .toString()
                    .toRequestBody(JSON)
            val request =
                Request
                    .Builder()
                    .url("$ENDPOINT/${Uri.encode(voice)}?output_format=$FORMAT")
                    .header("xi-api-key", key)
                    .header("Accept", "audio/mpeg")
                    .post(body)
                    .build()
            dir.mkdirs()
            val file = fileFor(text, voice)
            val part = File(dir, "${file.name}.part")
            try {
                client.get().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException(describe(response.code, errorDetail(response.body.source())))
                    part.writeBytes(readAudio(response.body.source()))
                }
                check(part.renameTo(file)) { "Could not save the audio." }
            } finally {
                part.delete()
            }
            return file
        }

        private fun readAudio(source: BufferedSource): ByteArray {
            // Look one byte past the limit so an oversized body is refused without reading all of it.
            source.request(MAX_BYTES + 1L)
            if (source.buffer.size == 0L || source.buffer.size > MAX_BYTES) throw IOException("Unexpected audio response.")
            return source.readByteArray()
        }

        /** The `detail.message` ElevenLabs sends with an error (never the request, so never the key), if any. */
        private fun errorDetail(source: BufferedSource): String? {
            source.request(MAX_ERROR_BYTES)
            val text = source.buffer.readUtf8(minOf(source.buffer.size, MAX_ERROR_BYTES))
            val detail = runCatching { JSONObject(text).opt("detail") }.getOrNull()
            return when (detail) {
                is JSONObject -> detail.optString("message").ifBlank { detail.optString("status") }
                is String -> detail
                else -> null
            }?.takeIf { it.isNotBlank() }?.take(MAX_DETAIL_CHARS)
        }

        private fun describe(
            code: Int,
            detail: String?,
        ): String {
            val base =
                when (code) {
                    HTTP_UNAUTHORIZED -> "ElevenLabs rejected the API key."
                    HTTP_PAYMENT -> "ElevenLabs needs a paid plan or credits for this request."
                    HTTP_QUOTA -> "ElevenLabs rate limit or quota reached."
                    else -> "ElevenLabs answered $code."
                }
            return if (detail == null) base else "$base ($detail)"
        }

        private fun play(file: File) {
            stopPlayer()
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                mp.setDataSource(file.path)
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener { finish(it) }
                mp.setOnErrorListener { p, _, _ ->
                    _state.value = PronunciationState.Failed("The audio could not be played.")
                    finish(p, keepState = true)
                    true
                }
                mp.prepareAsync()
            } catch (e: IOException) {
                playFailed(mp, e)
            } catch (e: IllegalStateException) {
                playFailed(mp, e)
            }
        }

        private fun playFailed(
            mp: MediaPlayer,
            cause: Exception,
        ) {
            finish(mp, keepState = true)
            _state.value = PronunciationState.Failed("The audio could not be played (${cause.javaClass.simpleName}).")
        }

        private fun finish(
            mp: MediaPlayer,
            keepState: Boolean = false,
        ) {
            mp.release()
            if (player === mp) player = null
            if (!keepState) _state.value = PronunciationState.Idle
        }

        private fun stopPlayer() {
            player?.release()
            player = null
        }

        companion object {
            const val MODEL_ID = "eleven_v4_turbo"
            private const val KEY_NAME = "elevenlabs_api_key"
            private const val VOICE_NAME = "elevenlabs_voice_id"
            private const val ENDPOINT = "https://api.elevenlabs.io/v1/text-to-speech"
            private const val FORMAT = "mp3_44100_64"
            private const val MAX_CHARS = 100
            private const val MAX_BYTES = 2 shl 20
            private const val HASH_CHARS = 32
            private const val HTTP_UNAUTHORIZED = 401
            private const val HTTP_PAYMENT = 402
            private const val HTTP_QUOTA = 429
            private const val MAX_ERROR_BYTES = 2048L
            private const val MAX_DETAIL_CHARS = 200
            private val JSON = "application/json".toMediaType()
        }
    }
