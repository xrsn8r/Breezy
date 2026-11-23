package dev.breezy.browser.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.breezy.browser.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps downloads running in the background and shows their progress.
 * It stops itself as soon as nothing is downloading.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            started = true
            startForegroundCompat(progressNotification(Downloads.jobs.value))
            scope.launch {
                Downloads.jobs.collectLatest { jobs ->
                    if (jobs.none { it.active }) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(ONGOING_ID, progressNotification(jobs))
                        kotlinx.coroutines.delay(1_000) // at most one notification update per second
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(n: android.app.Notification) {
        ServiceCompat.startForeground(
            this, ONGOING_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun progressNotification(jobs: List<DownloadJob>): android.app.Notification {
        val active = jobs.filter { it.active }
        val totalKnown = active.all { it.total > 0 || (it.kind == DownloadKind.Hls && it.hlsTotal > 0) }
        val progress = if (active.isEmpty()) 0f else active.map { it.progress }.average().toFloat()
        val speed = active.sumOf { it.speed }
        val title = if (active.size == 1) active[0].fileName else "Downloading ${active.size} files"
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (speed > 0) Formatter.formatShortFileSize(this, speed) + "/s" else "Starting…")
            .setProgress(100, (progress * 100).toInt(), !totalKnown)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(this))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_PROGRESS = "downloads"
        private const val CHANNEL_DONE = "downloads_done"
        private const val ONGOING_ID = 7001
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
        const val EXTRA_OPEN_FILE = "dev.breezy.browser.OPEN_FILE"

        fun update(context: Context) {
            if (!Downloads.anyActive) return
            runCatching { context.startForegroundService(Intent(context, DownloadService::class.java)) }
        }

        private fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun openApp(context: Context) = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_DOWNLOADS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** "Downloaded <file>" notification; tapping opens the file. */
        fun notifyCompleted(context: Context, job: DownloadJob) {
            ensureChannels(context)
            // Route through Breezy so APKs can ask for install permission first.
            val open = PendingIntent.getActivity(
                context, job.id.toInt(),
                Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_FILE, job.id)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(job.fileName)
                .setContentText("Download complete · " + Formatter.formatShortFileSize(context, job.downloaded))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(job.id.toInt(), n) }
        }
    }
}
