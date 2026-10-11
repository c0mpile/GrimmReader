package com.c0mpile.grimmreader.core.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * What lets narration go on with the screen off and play well with other audio: audio focus (pause when another
 * app takes it, resume after a short interruption), a partial wake lock, and a pause when headphones are unplugged.
 */
internal class PlaybackSession(
    private val context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val isPlaying: () -> Boolean,
) {
    private var focus: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var noisy: BroadcastReceiver? = null
    private var resumeOnGain = false
    private var keepFocus = false

    /** Takes focus, the wake lock and the unplug listener; false when another app (a call) holds the audio. */
    fun start(): Boolean {
        resumeOnGain = false
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(::onFocus, Handler(Looper.getMainLooper()))
                .build()
        focus = request
        if (context.getSystemService(AudioManager::class.java).requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            return false
        }
        val lock =
            wakeLock ?: context
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GrimmReader:narration")
                .apply { setReferenceCounted(false) }
        wakeLock = lock
        if (!lock.isHeld) lock.acquire(WAKE_LOCK_MS)
        listenForUnplug()
        return true
    }

    /** Lets go of everything; after a short interruption (a notification sound) the focus is kept to hear when it ends. */
    fun stop() {
        noisy?.let { context.unregisterReceiver(it) }
        noisy = null
        wakeLock?.takeIf { it.isHeld }?.release()
        if (keepFocus) return
        resumeOnGain = false
        focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun onFocus(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> onPause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying()) {
                    keepFocus = true
                    resumeOnGain = true
                    onPause()
                    keepFocus = false
                }
            }
            AudioManager.AUDIOFOCUS_GAIN ->
                if (resumeOnGain) {
                    resumeOnGain = false
                    onResume()
                }
        }
    }

    private fun listenForUnplug() {
        if (noisy != null) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = onPause()
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisy = receiver
    }

    private companion object {
        const val WAKE_LOCK_MS = 6 * 60 * 60 * 1000L
    }
}
