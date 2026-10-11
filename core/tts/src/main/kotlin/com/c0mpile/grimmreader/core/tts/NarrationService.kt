package com.c0mpile.grimmreader.core.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface NarrationEntryPoint {
    fun narration(): NarrationController
}

/**
 * Keeps the process alive while a book is narrated, so it goes on in the background and with the screen off, and
 * shows the media notification (and lock-screen controls through a [MediaSession]). The speaking itself is done by
 * [NarrationController]; this only mirrors its state and forwards the buttons.
 */
class NarrationService : Service() {
    private lateinit var narration: NarrationController
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class Shown(
        val status: NarrationStatus,
        val meta: NarrationMeta,
    )

    override fun onCreate() {
        super.onCreate()
        narration = EntryPointAccessors.fromApplication(applicationContext, NarrationEntryPoint::class.java).narration()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Narration", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        session =
            MediaSession(this, "GrimmReader narration").apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onPlay() = narration.resume()

                        override fun onPause() = narration.pause()

                        override fun onSkipToNext() = narration.skip(forward = true)

                        override fun onSkipToPrevious() = narration.skip(forward = false)

                        override fun onStop() = narration.stop()
                    },
                )
                isActive = true
            }
        scope.launch {
            narration.state
                .map { Shown(it.status, it.meta) }
                .distinctUntilChanged()
                .collect(::render)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // A foreground service must call startForeground soon after it is started.
        val now = narration.state.value
        startForeground(NOTIFICATION_ID, notification(Shown(now.status, now.meta)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            ACTION_TOGGLE -> narration.toggle()
            ACTION_NEXT -> narration.skip(forward = true)
            ACTION_PREVIOUS -> narration.skip(forward = false)
            ACTION_STOP -> narration.stop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private fun render(shown: Shown) {
        if (shown.status == NarrationStatus.IDLE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val playing = shown.status == NarrationStatus.PLAYING || shown.status == NarrationStatus.LOADING
        session.setPlaybackState(
            PlaybackState
                .Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                ).setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                ).build(),
        )
        session.setMetadata(
            android.media.MediaMetadata
                .Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, shown.meta.title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, shown.meta.subtitle.orEmpty())
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(shown))
    }

    private fun notification(shown: Shown): Notification {
        val playing = shown.status == NarrationStatus.PLAYING || shown.status == NarrationStatus.LOADING
        val open =
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_narration)
            .setContentTitle(shown.meta.title.ifBlank { "Reading aloud" })
            .setContentText(shown.meta.subtitle ?: if (playing) "Reading aloud" else "Paused")
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS))
            .addAction(
                if (playing) {
                    action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE)
                } else {
                    action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE)
                },
            ).addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(
        icon: Int,
        title: String,
        action: String,
    ): Notification.Action {
        val intent = Intent(this, NarrationService::class.java).setAction(action)
        val pending =
            PendingIntent.getService(
                this,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pending).build()
    }

    companion object {
        private const val CHANNEL = "narration"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_TOGGLE = "com.c0mpile.grimmreader.narration.TOGGLE"
        private const val ACTION_NEXT = "com.c0mpile.grimmreader.narration.NEXT"
        private const val ACTION_PREVIOUS = "com.c0mpile.grimmreader.narration.PREVIOUS"
        private const val ACTION_STOP = "com.c0mpile.grimmreader.narration.STOP"

        /** Starts the foreground service; call it while the app is in the foreground (the user tapped narrate). */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, NarrationService::class.java))
        }
    }
}
