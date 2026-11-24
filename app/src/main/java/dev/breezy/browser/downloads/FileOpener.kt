package dev.breezy.browser.downloads

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri

/**
 * Opens a finished download. APKs go straight to the system installer; the first time, Android
 * asks the user to allow Breezy to install apps, and the install continues when they come back.
 */
object FileOpener {
    private var pendingApk: Long? = null

    fun open(activity: Activity, job: DownloadJob) {
        if (Downloads.isApk(job) &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingApk = job.id
            Toast.makeText(activity, "Allow Breezy to install apps, then go back", Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()),
                )
            }
            return
        }
        launch(activity, job)
    }

    /** Call from onResume: finishes an install that was waiting for the permission. */
    fun resumePending(activity: Activity) {
        val id = pendingApk ?: return
        pendingApk = null
        if (!activity.packageManager.canRequestPackageInstalls()) return
        Downloads.get(id)?.let { launch(activity, it) }
    }

    private fun launch(activity: Activity, job: DownloadJob) {
        val intent = Downloads.openIntent(job) ?: return
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(activity, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }
}
