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

    private var permissionCallback: ((Map<String, Boolean>) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionCallback?.invoke(result)
        permissionCallback = null
    }
    private var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var uiView: View? = null
    private var pipWasActive = false

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        tabs = TabManager(this, this)
        val incoming = intentUrl(intent)
        if (AppSettings.clearOnExit) {
            // Covers the case where the process was killed before onDestroy could clear.
            getSharedPreferences("tabs", MODE_PRIVATE).edit().clear().apply()
            (application as BreezyApp).scope.launch { dev.breezy.browser.data.SiteData.clearAll(applicationContext) }
        } else {
            tabs.restore()
        }
        if (incoming != null) tabs.newTab(incoming)

        val ui = ComposeView(this).also { uiView = it }.apply {
            setContent {
                BreezyTheme {
                    BrowserScreen(
                        tabs = tabs,
                        onNewTab = { url, private -> tabs.newTab(url, private = private) },
                        onCloseTab = ::closeTab,
                        onShare = ::share,
                        onExit = ::finish,
                    )
                }
            }
        }

        run {
            // The current tab's WebView lives directly in the window, beneath a transparent Compose
            // overlay, so it composites like in any other browser (not through Compose layers).
            val root = FrameLayout(this)
            // Pull-to-refresh wraps a fixed container (the WebView inside it changes with the tab).
            val refresh = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
                addView(webHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                setOnChildScrollUpCallback { _, _ ->
                    !AppSettings.pullToRefresh || tabs.current?.webView?.canScrollVertically(-1) != false
                }
                setOnRefreshListener { tabs.current?.reload() ?: run { isRefreshing = false } }
            }
            swipeRefresh = refresh
            root.addView(refresh, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            root.addView(ui, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setContentView(root)
            ViewCompat.setOnApplyWindowInsetsListener(refresh) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                insets
            }
            lifecycleScope.launch {
                snapshotFlow { tabs.current }.collect { attachToHost(it); updatePip() }
            }
            lifecycleScope.launch {
                snapshotFlow { tabs.current?.loading == true }.collect { loading -> if (!loading) refresh.isRefreshing = false }
            }
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

    private val keepPlaying: Boolean
        get() = AppSettings.backgroundPlay && tabs.current?.media?.playing == true

    override fun onPause() {
        super.onPause()
        if (keepPlaying || isInPictureInPictureMode) {
            // Keep the page "visible" to Chromium so video isn't suspended in the background.
            tabs.current?.webView?.keepVisible = true
        } else {
            tabs.current?.onPause()
        }
        tabs.save()
        Shield.flush()
        dev.breezy.browser.data.Stats.flush()
    }

    override fun onStop() {
        super.onStop()
        if (pipWasActive && !isInPictureInPictureMode) {
            // The picture-in-picture window was dismissed: stop playback.
            pipWasActive = false
            tabs.current?.let { it.mediaPause(); it.setVideoFocus(false); it.webView.keepVisible = false; it.onPause() }
            return
        }
        if (keepPlaying) {
            dev.breezy.browser.media.MediaHub.tab = tabs.current
            ensureNotificationPermission()
            dev.breezy.browser.media.MediaPlaybackService.update(this)
        }
    }

    override fun onResume() {
        super.onResume()
        tabs.current?.webView?.keepVisible = false
        tabs.current?.onResume()
        dev.breezy.browser.media.MediaPlaybackService.stop(this)
        dev.breezy.browser.media.MediaHub.tab = null
    }

    override fun onDestroy() {
        dev.breezy.browser.media.MediaPlaybackService.stop(this)
        if (isFinishing) {
            if (AppSettings.clearOnExit) {
                getSharedPreferences("tabs", MODE_PRIVATE).edit().clear().apply()
                (application as BreezyApp).scope.launch { dev.breezy.browser.data.SiteData.clearAll(applicationContext) }
            } else {
                tabs.save()
            }
        }
        tabs.closeAll()
        super.onDestroy()
    }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("asked_notifications", false)
        ) {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply()
            // Asked once; the media notification needs it on Android 13+.
            requestAndroidPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) {}
        }
    }

    // --- Picture-in-picture -----------------------------------------------------------------------

    /** PiP is offered for fullscreen video, or whenever a video is playing in the current tab. */
    private val pipEligible: Boolean
        get() = fullscreenView != null || tabs.current?.media?.let { it.playing && it.video } == true

    private fun pipParams(enabled: Boolean): android.app.PictureInPictureParams {
        // Android allows aspect ratios between 1:2.39 and 2.39:1.
        val aspect = (tabs.current?.media?.aspect ?: (16f / 9f)).coerceIn(0.42f, 2.38f)
        val b = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational((aspect * 1000).toInt(), 1000))
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(enabled).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePip() {
        runCatching { setPictureInPictureParams(pipParams(pipEligible)) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12 there's no auto-enter: do it when the user leaves (home / recents).
        if (pipEligible && android.os.Build.VERSION.SDK_INT < 31) {
            runCatching { enterPictureInPictureMode(pipParams(true)) }
        }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        val tab = tabs.current
        // Only the page shows in the small window: hide the browser UI.
        uiView?.visibility = if (inPip) View.GONE else View.VISIBLE
        tab?.webView?.keepVisible = inPip
        if (inPip) {
            pipWasActive = true
            if (fullscreenView == null) tab?.setVideoFocus(true)
        } else if (!pipWasActive || lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            // Expanded back to full screen.
            pipWasActive = false
            tab?.setVideoFocus(false)
        }
    }

    private fun share(url: String) {
        if (url.isEmpty()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
    }

    // --- TabHost -------------------------------------------------------------------------------

    override fun openInNewTab(tab: BrowserTab) = tabs.add(tab)

    override fun closeTab(tab: BrowserTab) {
        // Closing the last tab returns to the home page rather than leaving the app. The tab is
        // kept briefly so the "Undo" snackbar can bring it back.
        tabs.closeWithUndo(tab)
    }

    override fun askSitePermission(host: String, kinds: List<String>, onResult: (Boolean, Boolean) -> Unit) {
        tabs.permissionPrompt = TabManager.PermissionPrompt(host, kinds) { allow, remember ->
            tabs.permissionPrompt = null
            onResult(allow, remember)
        }
    }

    override fun requestAndroidPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) {
        val missing = permissions.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { onResult(true); return }
        permissionCallback?.invoke(emptyMap())
        permissionCallback = { _ ->
            val granted = permissions.map { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            // Location is granted if either coarse or fine was; everything else needs all.
            onResult(if (permissions.all { "LOCATION" in it }) granted.any { it } else granted.all { it })
        }
        permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onMediaStateChanged(tab: BrowserTab) {
        if (tab === tabs.current) updatePip()
        if (dev.breezy.browser.media.MediaHub.tab === tab && !hasWindowFocus()) {
            dev.breezy.browser.media.MediaPlaybackService.update(this)
        }
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
        // Leaving the app during fullscreen video drops into picture-in-picture.
        updatePip()
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
        updatePip()
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        onFullscreenExit?.invoke()
        onFullscreenExit = null
    }
}
