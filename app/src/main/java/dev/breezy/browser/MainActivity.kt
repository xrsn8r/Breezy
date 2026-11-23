package dev.breezy.browser

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import kotlinx.coroutines.launch
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.breezy.browser.adblock.Shield
import dev.breezy.browser.browser.BrowserTab
import dev.breezy.browser.browser.TabHost
import dev.breezy.browser.browser.TabManager
import dev.breezy.browser.browser.UrlInput
import dev.breezy.browser.ui.BrowserScreen
import dev.breezy.browser.ui.theme.BreezyTheme

class MainActivity : ComponentActivity(), TabHost {

    private lateinit var tabs: TabManager
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var onFullscreenExit: (() -> Unit)? = null
    private val fullscreenBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitFullscreen()
    }

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        tabs = TabManager(this, this)
        val incoming = intentUrl(intent)
        tabs.restore()
        if (incoming != null) tabs.newTab(incoming)

        val ui = ComposeView(this).apply {
            setContent {
                BreezyTheme {
                    BrowserScreen(
                        tabs = tabs,
                        onNewTab = { url -> tabs.newTab(url) },
                        onCloseTab = ::closeTab,
                        onShare = ::share,
                        onExit = ::finish,
                    )
                }
            }
        }

        if (AppSettings.nativePageHost) {
            // The current tab's WebView lives directly in the window, beneath a transparent Compose
            // overlay, so it composites like in any other browser (not through Compose layers).
            val root = FrameLayout(this)
            root.addView(webHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            root.addView(ui, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setContentView(root)
            ViewCompat.setOnApplyWindowInsetsListener(webHost) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                insets
            }
            lifecycleScope.launch {
                snapshotFlow { tabs.current }.collect { attachToHost(it) }
            }
        } else {
            setContentView(ui)
        }
    }

    private val webHost by lazy { FrameLayout(this) }

    private fun attachToHost(tab: BrowserTab?) {
        val view = tab?.webView
        if (view != null && view.parent === webHost && webHost.childCount == 1) return
        webHost.removeAllViews()
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            webHost.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    /** Switch page hosting mode (Settings → Troubleshooting); tabs are saved and restored. */
    fun restartForHostChange() {
        tabs.save()
        recreate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentUrl(intent)?.let { tabs.newTab(it) }
    }

    private fun intentUrl(intent: Intent?): String? = when (intent?.action) {
        // Only web links from other apps: never javascript:, file:, content: or intent: URIs.
        Intent.ACTION_VIEW -> intent.dataString?.takeIf {
            it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
        }
        Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH ->
            intent.getStringExtra("query")?.let(UrlInput::resolve)
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(UrlInput::resolve)
        else -> null
    }

    override fun onPause() {
        super.onPause()
        tabs.current?.onPause()
        tabs.save()
        Shield.flush()
    }

    override fun onResume() {
        super.onResume()
        tabs.current?.onResume()
    }

    override fun onDestroy() {
        if (isFinishing) tabs.save()
        tabs.closeAll()
        super.onDestroy()
    }

    private fun share(url: String) {
        if (url.isEmpty()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
    }

    // --- TabHost -------------------------------------------------------------------------------

    override fun openInNewTab(tab: BrowserTab) = tabs.add(tab)

    override fun closeTab(tab: BrowserTab) {
        // Closing the last tab returns to the home page rather than leaving the app.
        tabs.close(tab)
    }

    override fun showFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return runCatching { fileChooser.launch(params.createIntent()); true }.getOrElse {
            fileCallback = null
            false
        }
    }

    override fun enterFullscreen(view: View, onExit: () -> Unit) {
        exitFullscreen()
        fullscreenView = view
        onFullscreenExit = onExit
        (window.decorView as ViewGroup).addView(
            view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        // Videos are almost always landscape: rotate like Chrome does for fullscreen video.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Re-add so it sits on top of Compose's back handlers.
        fullscreenBack.remove()
        onBackPressedDispatcher.addCallback(this, fullscreenBack)
        fullscreenBack.isEnabled = true
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun exitFullscreen() {
        val view = fullscreenView ?: return
        (window.decorView as ViewGroup).removeView(view)
        fullscreenView = null
        fullscreenBack.isEnabled = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        onFullscreenExit?.invoke()
        onFullscreenExit = null
    }
}
