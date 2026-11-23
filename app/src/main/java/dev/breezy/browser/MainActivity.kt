package dev.breezy.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
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
        if (!tabs.restore() || incoming != null) tabs.newTab(incoming)

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
        tabs.close(tab)
        if (tabs.tabs.isEmpty()) tabs.newTab()
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
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        onFullscreenExit?.invoke()
        onFullscreenExit = null
    }
}
