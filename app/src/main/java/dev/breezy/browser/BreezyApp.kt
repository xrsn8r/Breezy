package dev.breezy.browser

import android.app.Application
import android.webkit.WebView
import dev.breezy.browser.adblock.Shield
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
        AppSettings.init(this)
        Shield.init(this)
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
