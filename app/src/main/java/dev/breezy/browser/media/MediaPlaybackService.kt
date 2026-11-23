package dev.breezy.browser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import dev.breezy.browser.MainActivity
import dev.breezy.browser.R
import dev.breezy.browser.browser.BrowserTab

/**
 * Keeps web audio/video playing while Breezy is in the background (screen off, other app) and shows
 * a media notification with play/pause. The page itself does the playing; this service only keeps
 * the process in the foreground and relays controls to [MediaHub.tab].
 */
class MediaPlaybackService : Service() {
    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "Breezy").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = MediaHub.play()
                override fun onPause() = MediaHub.pause()
                override fun onStop() { MediaHub.pause(); stopSelf() }
            })
            isActive = true
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Media playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for audio and video playing in Breezy"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> MediaHub.play()
            ACTION_PAUSE -> MediaHub.pause()
            ACTION_STOP -> { MediaHub.pause(); stopSelf(); return START_NOT_STICKY }
        }
        val tab = MediaHub.tab
        if (tab == null) { stopSelf(); return START_NOT_STICKY }
        val playing = tab.media.playing
        val title = tab.media.title.ifBlank { tab.title.ifBlank { "Playing in Breezy" } }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, dev.breezy.browser.browser.UrlInput.display(tab.url))
                .build(),
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
                .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        val notification = buildNotification(title, tab, playing)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        return START_NOT_STICKY
    }

    private fun action(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, MediaPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(title: String, tab: BrowserTab, playing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = if (playing) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", action(ACTION_PAUSE, 1))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", action(ACTION_PLAY, 2))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(dev.breezy.browser.browser.UrlInput.display(tab.url))
            .setContentIntent(open)
            .setDeleteIntent(action(ACTION_STOP, 3))
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .addAction(toggle)
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    override fun onDestroy() {
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "media"
        private const val NOTIFICATION_ID = 42
        const val ACTION_PLAY = "dev.breezy.browser.media.PLAY"
        const val ACTION_PAUSE = "dev.breezy.browser.media.PAUSE"
        const val ACTION_STOP = "dev.breezy.browser.media.STOP"

        fun update(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MediaPlaybackService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaPlaybackService::class.java))
        }
    }
}

/** The tab whose media the notification controls. Main-thread only. */
object MediaHub {
    var tab: BrowserTab? = null
    fun play() { tab?.mediaPlay() }
    fun pause() { tab?.mediaPause() }
}
