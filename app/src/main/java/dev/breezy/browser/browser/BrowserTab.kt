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

    val webView: WebView = WebView(context).apply {
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
                view.evaluateJavascript(cosmeticScript(view.context), null)
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
            val name = URLUtil.guessFileName(url, disposition, mime)
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

    companion object {
        private var nextId = 1L
        private var script: String? = null

        val documentStartScriptSupported: Boolean by lazy {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }

        fun cosmeticScript(context: Context): String = script
            ?: context.assets.open("adblock/cosmetic.js").bufferedReader().use { it.readText() }.also { script = it }

        private fun installCosmeticScript(webView: WebView) {
            if (documentStartScriptSupported) {
                WebViewCompat.addDocumentStartJavaScript(webView, cosmeticScript(webView.context), setOf("*"))
            }
        }
    }
}
