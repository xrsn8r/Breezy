package dev.breezy.browser.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.breezy.browser.AppSettings
import dev.breezy.browser.BuildConfig
import dev.breezy.browser.downloads.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * In-app updates from the project's GitHub Releases.
 *
 * Checks the latest release (at most daily, or on demand), downloads the arm64 APK through the
 * download manager, checks it against the release's SHA256SUMS.txt, then opens the installer.
 * Android itself refuses an update signed with a different key, so a tampered APK can't replace
 * Breezy even if the checksum step were bypassed.
 */
object Updater {
    private const val REPO = "xrsn8r/Breezy"
    private const val DAY = 24 * 60 * 60 * 1000L

    data class Release(val version: String, val notesUrl: String, val apkUrl: String, val apkName: String, val sumsUrl: String?)

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object UpToDate : Status
        data class Available(val release: Release) : Status
        data class Downloading(val release: Release) : Status
        data object Verifying : Status
        data class Failed(val message: String) : Status
    }

    var status by mutableStateOf<Status>(Status.Idle)
        private set

    /** Download id of an update the user asked for; installed automatically when it completes. */
    private var pendingDownload: Long? = null
    private var pendingRelease: Release? = null

    /** Automatic check: only when enabled and not checked in the last day. */
    suspend fun checkIfDue(context: Context) {
        if (!AppSettings.autoCheckUpdates) return
        val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("last_check", 0) < DAY) {
            // Still offer a release found earlier that hasn't been installed.
            prefs.getString("known_version", null)?.let { v ->
                if (isNewer(v, BuildConfig.VERSION_NAME)) runCatching { check(context) }
            }
            return
        }
        runCatching { check(context) }
    }

    suspend fun check(context: Context): Status {
        status = Status.Checking
        val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
        status = try {
            val r = latest()
            prefs.edit { putLong("last_check", System.currentTimeMillis()); putString("known_version", r?.version) }
            if (r != null && isNewer(r.version, BuildConfig.VERSION_NAME)) Status.Available(r) else Status.UpToDate
        } catch (e: Exception) {
            Status.Failed("Couldn't check for updates (${e.message ?: "network error"})")
        }
        return status
    }

    /** True when the user dismissed the prompt for this version. */
    fun dismissed(context: Context, version: String) =
        context.getSharedPreferences("updater", Context.MODE_PRIVATE).getString("dismissed", null) == version

    fun dismiss(context: Context, version: String) =
        context.getSharedPreferences("updater", Context.MODE_PRIVATE).edit { putString("dismissed", version) }

    fun download(release: Release) {
        pendingRelease = release
        pendingDownload = Downloads.enqueue(
            release.apkUrl, release.apkName, Downloads.APK_MIME, mapOf("User-Agent" to "Breezy/${BuildConfig.VERSION_NAME}"),
        )
        status = Status.Downloading(release)
    }

    /**
     * If [jobId] is the update download, verify it. Returns the verified job's id to install, or
     * null when it isn't ours (or failed verification, reflected in [status]).
     */
    suspend fun onDownloadCompleted(context: Context, jobId: Long): Boolean {
        if (jobId != pendingDownload) return false
        val release = pendingRelease ?: return false
        pendingDownload = null
        status = Status.Verifying
        val job = Downloads.get(jobId) ?: return false
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val expected = release.sumsUrl?.let { url ->
                    fetch(url).lines().firstOrNull { it.trim().endsWith(release.apkName) }?.substringBefore(' ')?.trim()?.lowercase()
                } ?: return@runCatching true // no checksum file published: rely on Android's signature check
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(job.uri!!.toUri())!!.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
                }
                digest.digest().joinToString("") { "%02x".format(it) } == expected
            }.getOrDefault(false)
        }
        status = if (ok) Status.Available(release) else Status.Failed("The download didn't match the published checksum")
        if (!ok) Downloads.remove(jobId, deleteFile = true)
        return ok
    }

    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val json = JSONObject(fetch("https://api.github.com/repos/$REPO/releases/latest", github = true))
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        var apk: Pair<String, String>? = null
        var sums: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            val url = a.getString("browser_download_url")
            if (name.endsWith(".apk") && (apk == null || "arm64" in name)) apk = name to url
            if (name == "SHA256SUMS.txt") sums = url
        }
        apk?.let { Release(version, json.optString("html_url"), it.second, it.first, sums) }
    }

    private fun fetch(url: String, github: Boolean = false): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Breezy/${BuildConfig.VERSION_NAME}")
        if (github) conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    /** Numeric dotted-version comparison: 0.10.0 > 0.9.3. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
