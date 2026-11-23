package dev.breezy.browser

import android.app.Application
import android.webkit.WebView
import dev.breezy.browser.adblock.Shield
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BreezyApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        Shield.init(this)
        // Deserializing the precompiled engine takes a few ms; do it off the main thread while
        // the first WebView spins up.
        scope.launch { Shield.load(this@BreezyApp) }
    }
}
