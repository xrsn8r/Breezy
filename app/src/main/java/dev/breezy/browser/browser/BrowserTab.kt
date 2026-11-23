package dev.breezy.browser.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ContextThemeWrapper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.breezy.browser.AppSettings
import dev.breezy.browser.R
import dev.breezy.browser.adblock.NativeShield
import dev.breezy.browser.adblock.Shield
import java.util.concurrent.atomic.AtomicInteger

/** Callbacks a tab needs from the hosting activity. */
interface TabHost {
    fun openInNewTab(tab: BrowserTab)
    fun closeTab(tab: BrowserTab)
    fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun enterFullscreen(view: View, onExit: () -> Unit)
    fun exitFullscreen()
}

/**
 * One browser tab: a WebView plus the observable state the Compose UI renders.
 * Network requests are routed through Brave's adblock engine in [shouldInterceptRequest].
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserTab(context: Context, private val host: TabHost, val id: Long = nextId++) {

    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var favicon by mutableStateOf<Bitmap?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var loading by mutableStateOf(false)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var blockedCount by mutableIntStateOf(0)
        private set
    var desktopMode by mutableStateOf(false)
        private set

    /** Top-level page URL, read from WebView's IO threads as the request "source". */
    @Volatile private var pageUrl: String = ""
    @Volatile private var pageHost: String? = null
    private val pendingBlocked = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    private val mobileUserAgent: String

    /**
     * WebView decides dark rendering from its context's theme (`isLightTheme`): with a dark
     * theme, pages see `prefers-color-scheme: dark` and those without a dark style are
     * algorithmically darkened. Wrap the activity in a light or dark theme per the setting.
     */
    private val dark = AppSettings.darkWebPages
    private val webContext = ContextThemeWrapper(
        context, if (dark) R.style.Theme_Breezy_Web_Dark else R.style.Theme_Breezy_Web_Light,
    )

    val webView: WebView = WebView(webContext).apply {
        // Avoid a white flash before the first paint in dark mode.
        setBackgroundColor(if (dark) 0xFF121212.toInt() else android.graphics.Color.WHITE)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        isScrollbarFadingEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

        addJavascriptInterface(CosmeticBridge(), "BreezyShield")
        installCosmeticScript(this)

        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(context, url, userAgent, contentDisposition, mimeType)
        }
    }

    init {
        mobileUserAgent = webView.settings.userAgentString
    }

    fun load(input: String) {
        webView.loadUrl(input)
    }

    fun reload() = webView.reload()
    fun stop() = webView.stopLoading()
    fun goBack() = webView.goBack()
    fun goForward() = webView.goForward()

    fun toggleDesktopMode() {
        desktopMode = !desktopMode
        webView.settings.userAgentString = if (desktopMode) {
            mobileUserAgent.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
                .replace(" Mobile", "")
        } else mobileUserAgent
        webView.settings.useWideViewPort = true
        webView.reload()
    }

    private var deferredUrl: String? = null

    /** Remember a URL but only load it once the tab is first shown (restored tabs). */
    fun deferLoad(target: String) {
        deferredUrl = target
        url = target
    }

    fun onPause() = webView.onPause()
    fun onResume() {
        webView.onResume()
        deferredUrl?.let { deferredUrl = null; load(it) }
    }

    fun destroy() {
        webView.stopLoading()
        webView.removeJavascriptInterface("BreezyShield")
        webView.destroy()
    }

    private fun updateNav() {
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    private fun setPage(newUrl: String) {
        pageUrl = newUrl
        pageHost = runCatching { newUrl.toUri().host }.getOrNull()
    }

    private fun onBlocked() {
        Shield.recordBlocked()
        // Coalesce UI updates from many IO threads into one main-thread post.
        if (pendingBlocked.getAndIncrement() == 0) {
            main.post { blockedCount += pendingBlocked.getAndSet(0) }
        }
    }

    // --- Adblocking ------------------------------------------------------------------------------

    private fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val reqUrl = request.url
        val scheme = reqUrl.scheme
        if (scheme != "http" && scheme != "https") return null
        val urlString = reqUrl.toString()
        if (request.isForMainFrame) {
            // Record the navigation target first so subresources get the right source URL.
            setPage(urlString)
            pendingBlocked.set(0)
            main.post { blockedCount = 0 }
            return null // never block top-level navigations
        }
        if (!Shield.activeFor(pageHost)) return null
        val verdict = NativeShield.check(
            urlString, pageUrl, request.requestHeaders["Accept"], false, request.method,
        ) ?: return null
        onBlocked()
        return if (verdict.isEmpty()) Shield.blockedResponse() else Shield.redirectResponse(verdict)
    }

    /** JS interface used by assets/adblock/cosmetic.js. Called on a WebView binder thread. */
    private inner class CosmeticBridge {
        @JavascriptInterface
        fun resources(frameUrl: String): String {
            if (!Shield.activeFor(pageHost)) return "{}"
            return NativeShield.cosmeticResources(frameUrl)
        }

        @JavascriptInterface
        fun hiddenSelectors(classes: String, ids: String, exceptions: String): String {
            if (!Shield.activeFor(pageHost)) return "[]"
            return NativeShield.hiddenSelectors(classes, ids, exceptions)
        }

        @JavascriptInterface
        fun injectScriptlets(frameUrl: String) {
            if (!Shield.activeFor(pageHost)) return
            // Only for the top page's own site: evaluateJavascript targets the main frame, so a
            // third-party iframe must not be able to trigger injection there.
            val requested = runCatching { frameUrl.toUri().host }.getOrNull()
            if (requested == null || requested != pageHost) return
            val script = runCatching {
                org.json.JSONObject(NativeShield.cosmeticResources(frameUrl)).optString("injected_script")
            }.getOrNull()
            if (!script.isNullOrEmpty()) main.post { webView.evaluateJavascript(script, null) }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = intercept(request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme ?: return false
            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") return false
            // intent:, mailto:, tel:, market: ... hand off to the system.
            return runCatching {
                val intent = if (scheme == "intent") {
                    android.content.Intent.parseUri(request.url.toString(), android.content.Intent.URI_INTENT_SCHEME).apply {
                        addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                        component = null
                        selector = null
                    }
                } else android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                view.context.startActivity(intent)
                true
            }.getOrDefault(true)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            this@BrowserTab.url = url
            if (pageUrl != url) setPage(url)
            this@BrowserTab.favicon = favicon
            loading = true
            updateNav()
            if (!documentStartScriptSupported) {
                view.evaluateJavascript(asset(view.context, SCROLL_REPAIR), null)
                view.evaluateJavascript(asset(view.context, COSMETIC), null)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            loading = false
            progress = 1f
            updateNav()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            this@BrowserTab.url = url
            setPage(url)
            updateNav()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress / 100f
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            this@BrowserTab.title = title.orEmpty()
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            favicon = icon
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false // block pop-unders
            val tab = BrowserTab(view.context, host)
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = tab.webView
            resultMsg.sendToTarget()
            host.openInNewTab(tab)
            return true
        }

        override fun onCloseWindow(window: WebView) = host.closeTab(this@BrowserTab)

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ) = host.showFileChooser(filePathCallback, fileChooserParams)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            host.enterFullscreen(view) { callback.onCustomViewHidden() }
        }

        override fun onHideCustomView() = host.exitFullscreen()
    }

    private fun download(context: Context, url: String, userAgent: String, disposition: String?, mime: String?) {
        runCatching {
            // Strip path separators/control chars so a server can't steer the file location.
            val name = URLUtil.guessFileName(url, disposition, mime)
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .trimStart('.')
                .ifEmpty { "download" }
            if (!url.startsWith("http://") && !url.startsWith("https://")) error("unsupported scheme")
            val request = DownloadManager.Request(url.toUri())
                .setMimeType(mime)
                .addRequestHeader("User-Agent", userAgent)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            context.getSystemService(DownloadManager::class.java).enqueue(request)
            Toast.makeText(context, "Downloading $name", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Page tools --------------------------------------------------------------------------------

    /** Force-release scroll locks and make large invisible overlays click-through. */
    fun unlockScrolling(onDone: (Int) -> Unit) {
        webView.evaluateJavascript(asset(webView.context, FORCE_UNLOCK)) { result ->
            onDone(result?.toIntOrNull() ?: 0)
        }
    }

    /** Collect page + WebView metrics to diagnose rendering/scrolling problems. */
    fun diagnostics(onResult: (String) -> Unit) {
        val wv = webView
        val native = buildString {
            appendLine("WebView ${wv.width}x${wv.height}px, scrollY=${wv.scrollY}")
            @Suppress("DEPRECATION")
            appendLine("contentHeight=${wv.contentHeight}css  scale=${"%.2f".format(wv.scale)}")
            appendLine("canScrollDown=${wv.canScrollVertically(1)} canScrollUp=${wv.canScrollVertically(-1)}")
            appendLine("darkTheme=$dark shields=${Shield.activeFor(pageHost)}")
            appendLine("UA: ${wv.settings.userAgentString}")
        }
        wv.evaluateJavascript(asset(wv.context, DIAGNOSTICS)) { json ->
            // evaluateJavascript returns a JSON-encoded string literal.
            val page = runCatching { org.json.JSONTokener(json).nextValue().toString() }.getOrDefault(json.orEmpty())
            onResult(native + "\n" + page)
        }
    }

    companion object {
        private var nextId = 1L
        private const val COSMETIC = "adblock/cosmetic.js"
        private const val SCROLL_REPAIR = "breezy/scroll-repair.js"
        private const val FORCE_UNLOCK = "breezy/force-unlock.js"
        private const val DIAGNOSTICS = "breezy/diagnostics.js"
        private val scripts = HashMap<String, String>()

        val documentStartScriptSupported: Boolean by lazy {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }

        fun asset(context: Context, path: String): String = synchronized(scripts) {
            scripts.getOrPut(path) { context.assets.open(path).bufferedReader().use { it.readText() } }
        }

        private fun installCosmeticScript(webView: WebView) {
            if (documentStartScriptSupported) {
                WebViewCompat.addDocumentStartJavaScript(webView, asset(webView.context, SCROLL_REPAIR), setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(webView, asset(webView.context, COSMETIC), setOf("*"))
            }
        }
    }
}
