package dev.breezy.browser.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers why Breezy last died so the user can send a report.
 *
 * - Java/Kotlin exceptions: written by an uncaught-exception handler.
 * - Native crashes, ANRs, kills: Android's exit records (API 30+).
 * - Breadcrumbs: a short trail of recent events (tab opened, popup, renderer gone, hand-off to
 *   another app), kept on disk so it survives a native crash. Hosts only, never full URLs.
 */
object CrashReport {
    private const val FILE = "last-crash.txt"
    private const val TRAIL = "breadcrumbs.txt"
    private const val MAX_CRUMBS = 40

    private lateinit var dir: File
    private val crumbs = ArrayDeque<String>()
    private var checked = false
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun install(context: Context) {
        val app = context.applicationContext
        dir = app.filesDir
        // Last run's trail, for the report; this run starts a new one.
        File(dir, TRAIL).takeIf { it.exists() }?.renameTo(File(dir, "$TRAIL.prev"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(dir, FILE).writeText(header(app) + "Thread: ${thread.name}\n\n" + e.stackTraceToString())
            }
            previous?.uncaughtException(thread, e)
        }
        note("app start")
    }

    /** Record an event (main thread or not). Keep it short and free of personal data. */
    fun note(event: String) {
        if (!::dir.isInitialized) return
        synchronized(crumbs) {
            crumbs.addLast("${time.format(Date())} $event")
            while (crumbs.size > MAX_CRUMBS) crumbs.removeFirst()
            runCatching { File(dir, TRAIL).writeText(crumbs.joinToString("\n")) }
        }
    }

    /** The report from the previous run, if it ended abnormally. Checked once per process. */
    fun takePending(context: Context): String? {
        if (checked) return null
        checked = true
        val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val file = File(context.filesDir, FILE)
        var report = file.takeIf { it.exists() }?.readText()
        file.delete()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = lastExit(context)
            if (info != null && info.timestamp > prefs.getLong("seen", 0)) {
                prefs.edit().putLong("seen", info.timestamp).apply()
                if (report == null && abnormal(info.reason)) {
                    val trace = runCatching {
                        info.traceInputStream?.use { it.readBytes().decodeToString().take(60_000) }
                    }.getOrNull()
                    report = header(context) + "Exit: ${reasonName(info.reason)} (${info.description})\n\n" +
                        (trace ?: "(no trace)")
                }
            }
        }
        return report?.let { it + "\n\n--- Recent events ---\n" + previousTrail() }
    }

    /** Everything useful for a bug report, on demand (Settings → Advanced). */
    fun diagnostics(context: Context): String = buildString {
        append(header(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            append("\n--- Recent exits ---\n")
            val am = context.getSystemService(ActivityManager::class.java)
            runCatching { am.getHistoricalProcessExitReasons(null, 0, 5) }.getOrNull()?.forEach {
                append("${Date(it.timestamp)}: ${reasonName(it.reason)} · ${it.description ?: ""}\n")
            }
        }
        append("\n--- This run ---\n").append(synchronized(crumbs) { crumbs.joinToString("\n") })
        append("\n\n--- Previous run ---\n").append(previousTrail())
    }

    private fun previousTrail() = runCatching { File(dir, "$TRAIL.prev").readText() }.getOrDefault("(none)")

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun lastExit(context: Context): ApplicationExitInfo? = runCatching {
        context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
    }.getOrNull()

    private fun abnormal(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> true
        else -> false
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "force stopped"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "updated"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        else -> "reason $reason"
    }

    private fun header(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val webView = runCatching { androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull()
        return "Breezy $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}\n" +
            "WebView $webView\n"
    }
}
