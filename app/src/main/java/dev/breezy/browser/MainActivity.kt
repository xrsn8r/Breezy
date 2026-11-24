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
        dev.breezy.browser.data.CrashReport.note(
            "activity onCreate #${System.identityHashCode(this).toString(16)} restored=${savedInstanceState != null} action=${intent?.action}",
        )

        tabs = TabManager(this, this)
        // Once per process: after a crash, reopening from Recents restores state, so don't key
        // this on savedInstanceState.
        dev.breezy.browser.data.CrashReport.takePending(this)?.let(::offerCrashReport)
        if (intent.getBooleanExtra(dev.breezy.browser.downloads.DownloadService.EXTRA_OPEN_DOWNLOADS, false)) tabs.showDownloads = true
        if (savedInstanceState == null) openFileFrom(intent)
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
                val dark = dev.breezy.browser.ui.theme.breezyDark()
                val black = dark && AppSettings.pureBlack
                androidx.compose.runtime.LaunchedEffect(dark, black) {
                    val bars = if (dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
                        when { black -> android.graphics.Color.BLACK; dark -> 0xFF101418.toInt(); else -> 0xFFF8F9FF.toInt() }))
                }
                BreezyTheme(dark) {
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
            lifecycleScope.launch { dev.breezy.browser.data.Updater.checkIfDue(applicationContext) }
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
        dev.breezy.browser.data.CrashReport.note("activity onNewIntent action=${intent.action}")
        if (intent.getBooleanExtra(dev.breezy.browser.downloads.DownloadService.EXTRA_OPEN_DOWNLOADS, false)) tabs.showDownloads = true
        openFileFrom(intent)
        intentUrl(intent)?.let { tabs.newTab(it) }
    }

    /** Download notification tapped: open (or install) that file. */
    private fun openFileFrom(intent: Intent?) {
        val id = intent?.getLongExtra(dev.breezy.browser.downloads.DownloadService.EXTRA_OPEN_FILE, -1L) ?: -1L
        if (id < 0) return
        intent?.removeExtra(dev.breezy.browser.downloads.DownloadService.EXTRA_OPEN_FILE)
        dev.breezy.browser.downloads.Downloads.get(id)?.let { dev.breezy.browser.downloads.FileOpener.open(this, it) }
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
        dev.breezy.browser.data.CrashReport.note("activity onPause #${System.identityHashCode(this).toString(16)} finishing=$isFinishing")
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
        dev.breezy.browser.data.CrashReport.note("activity onStop finishing=$isFinishing")
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        dev.breezy.browser.data.CrashReport.note("activity focus=$hasFocus")
    }

    override fun onResume() {
        super.onResume()
        dev.breezy.browser.data.CrashReport.note("activity onResume #${System.identityHashCode(this).toString(16)}")
        tabs.current?.webView?.keepVisible = false
        tabs.current?.onResume()
        dev.breezy.browser.downloads.FileOpener.resumePending(this)
        dev.breezy.browser.media.MediaPlaybackService.stop(this)
        dev.breezy.browser.media.MediaHub.tab = null
    }

    override fun onDestroy() {
        dev.breezy.browser.data.CrashReport.note("activity onDestroy #${System.identityHashCode(this).toString(16)} finishing=$isFinishing")
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

    private fun ensureNotificationPermission(force: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            (force || !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("asked_notifications", false))
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

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val inPip = isInPictureInPictureMode
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

    override fun openLinkInNewTab(url: String, private: Boolean) {
        tabs.newTab(url, private = private)
    }

    private fun offerCrashReport(report: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Breezy closed unexpectedly")
            .setMessage("Share the crash report so it can be fixed? It contains technical details only, no pages or history.")
            .setPositiveButton("Share") { _, _ ->
                startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Breezy crash report")
                        .putExtra(android.content.Intent.EXTRA_TEXT, report),
                    "Share crash report"))
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    override fun onRendererGone(tab: BrowserTab) {
        val reloaded = tabs.replaceCrashed(tab)
        android.widget.Toast.makeText(this,
            if (reloaded) "The page crashed and was reloaded" else "The page keeps crashing",
            android.widget.Toast.LENGTH_SHORT).show()
    }

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

    override fun onDownloadStarted(name: String) {
        ensureNotificationPermission(force = true)
        android.widget.Toast.makeText(this, "Downloading $name", android.widget.Toast.LENGTH_SHORT).show()
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
