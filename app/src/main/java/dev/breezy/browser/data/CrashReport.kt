package dev.breezy.browser.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Remembers why Breezy last died so the user can send a report. Java/Kotlin exceptions are
 * written by an uncaught-exception handler; native crashes, ANRs and renderer-related kills come
 * from Android's exit records (API 30+).
 */
object CrashReport {
    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(app.filesDir, FILE).writeText(header(app) + "Thread: ${thread.name}\n\n" + e.stackTraceToString())
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The report from the previous run, if it ended in a crash; consumed on read. */
    fun takePending(context: Context): String? {
        val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val file = File(context.filesDir, FILE)
        var report = file.takeIf { it.exists() }?.readText()
        file.delete()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val am = context.getSystemService(ActivityManager::class.java)
            val info = runCatching { am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() }.getOrNull()
            if (info != null && info.timestamp > prefs.getLong("seen", 0)) {
                prefs.edit().putLong("seen", info.timestamp).apply()
                val reason = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "crash"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                    ApplicationExitInfo.REASON_ANR -> "not responding"
                    else -> null
                }
                if (reason != null && report == null) {
                    val trace = runCatching {
                        info.traceInputStream?.use { it.readBytes().decodeToString().take(60_000) }
                    }.getOrNull()
                    report = header(context) + "Exit: $reason (${info.description})\n\n" + (trace ?: "(no trace)")
                }
            }
        }
        return report
    }

    private fun header(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val webView = runCatching { androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull()
        return "Breezy $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}\n" +
            "WebView $webView\n"
    }
}
