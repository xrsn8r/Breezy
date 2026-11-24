package dev.breezy.browser

import android.app.Application
import android.webkit.WebView
import dev.breezy.browser.adblock.Shield
import dev.breezy.browser.adblock.UserFilters
import dev.chrisbanes.haze.HazeFeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BreezyApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        dev.breezy.browser.data.CrashReport.install(this)
        AppSettings.init(this)
        dev.breezy.browser.data.Favicons.init(this)
        // Secure DNS must be in place before the first WebView makes a request.
        dev.breezy.browser.data.NetworkPrivacy.apply()
        Shield.init(this)
        UserFilters.init(this)
        dev.breezy.browser.data.SiteSettings.init(this)
        dev.breezy.browser.data.History.init(this)
        dev.breezy.browser.data.Stats.init(this)
        dev.breezy.browser.downloads.Downloads.init(this)
        dev.breezy.browser.downloads.Downloads.onCompleted = { job ->
            dev.breezy.browser.downloads.DownloadService.notifyCompleted(this, job)
        }
        // Real backdrop blur (Android 16+) for the frosted toolbar; older versions use a tint.
        HazeFeatureFlags.isPlatformBackdropEnabled = true
        // Deserializing the precompiled engine takes a few ms; do it off the main thread while
        // the first WebView spins up. Then refresh lists weekly when on Wi-Fi.
        scope.launch {
            Shield.load(this@BreezyApp)
            Shield.maybeAutoUpdate(this@BreezyApp, AppSettings.autoUpdateLists)
        }
    }
}
