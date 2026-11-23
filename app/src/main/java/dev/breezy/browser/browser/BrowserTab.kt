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
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.breezy.browser.AppSettings
import dev.breezy.browser.R
import dev.breezy.browser.adblock.NativeShield
import dev.breezy.browser.adblock.Shield
import dev.breezy.browser.adblock.UserFilters
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import dev.breezy.browser.data.SitePermission

/** Callbacks a tab needs from the hosting activity. */
interface TabHost {
    fun openInNewTab(tab: BrowserTab)
    fun closeTab(tab: BrowserTab)
    fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun enterFullscreen(view: View, onExit: () -> Unit)
    fun exitFullscreen()
    /** Ask the user whether [host] may use [kinds] ("camera", "microphone", "location"). */
    fun askSitePermission(host: String, kinds: List<String>, onResult: (allow: Boolean, remember: Boolean) -> Unit)
    /** Request Android runtime permissions; result is true if all were granted. */
    fun requestAndroidPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit)
    fun onMediaStateChanged(tab: BrowserTab)
}

/**
 * One browser tab: a WebView plus the observable state the Compose UI renders.
 * Network requests are routed through Brave's adblock engine in [shouldInterceptRequest].
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserTab(
    context: Context,
    private val host: TabHost,
    /** Private tabs use their own WebView profile (cookies/storage wiped on close) and no history. */
    val isPrivate: Boolean = false,
    val id: Long = nextId++,
) {
    private val appContext = context.applicationContext
    private val profileName: String? = if (isPrivate && multiProfileSupported) "private-$id-${System.nanoTime()}" else null

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

    /** Element picker state while "Block element" is active; null otherwise. */
    /** Set when an HTTPS-only upgrade failed: the insecure URL the user may choose to continue to. */
    var httpsFailure by mutableStateOf<String?>(null)
        private set

    /** Find-in-page results: active match index (0-based) and total; null when not searching. */
    var findResult by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    data class MediaState(val playing: Boolean = false, val title: String = "", val hasMedia: Boolean = false)
    var media by mutableStateOf(MediaState())
        private set
    var playbackRate by mutableFloatStateOf(1f)
        private set

    /** Whether the toolbar should be expanded: collapses on scroll down, returns on scroll up. */
    var chromeVisible by mutableStateOf(true)

    var picker by mutableStateOf<PickerState?>(null)
        private set

    data class PickerState(
        val selector: String = "",
        val count: Int = 0,
        val label: String = "",
        val canParent: Boolean = false,
        val canChild: Boolean = false,
        val preview: Boolean = false,
    )

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
        // A private tab's profile must be set before the WebView is used for anything else.
        profileName?.let { name ->
            runCatching { WebViewCompat.setProfile(this, name) }
        }
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
        // With a dark context theme, pages always see prefers-color-scheme: dark (sites with a real
        // dark theme use it). Algorithmic recoloring of everything else is opt-in: it breaks
        // already-dark sites that don't declare color-scheme (invisible buttons and form text).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, dark && AppSettings.forceDarkAllSites)
        }
        cookieManager().setAcceptThirdPartyCookies(this, false)
        setFindListener { active, count, done -> if (done) findResult = if (count > 0) active to count else 0 to 0 }
        isScrollbarFadingEnabled = true
        setOnScrollChangeListener { _, _, y, _, oldY ->
            if (y > oldY + 12) chromeVisible = false
            else if (y < oldY - 12 || y == 0) chromeVisible = true
        }
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

        if (AppSettings.identifyAsChrome) presentAsChrome(settings)
        addJavascriptInterface(CosmeticBridge(), "BreezyShield")
        addJavascriptInterface(PickerBridge(), "BreezyPicker")
        addJavascriptInterface(PageBridge(), "BreezyPage")
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
        val target = rewriteNavigation(input) ?: input
        runCatching { prepareScriptlets(target.toUri()) }
        applySiteSettings(runCatching { target.toUri().host }.getOrNull())
        httpsFailure = null
        webView.loadUrl(target)
    }

    // --- Navigation rewriting: HTTPS-only + tracking parameters ---------------------------------

    /** Hosts the user chose to visit over plain HTTP this session (HTTPS-only fallback). */
    private val httpAllowed = HashSet<String>()
    private var upgradeHost: String? = null
    private var upgradeCount = 0
    private var upgradeAt = 0L
    private var upgradedFrom: String? = null

    /** The URL to load instead of [url] (upgraded and/or cleaned), or null to load it as is. */
    private fun rewriteNavigation(url: String): String? {
        var out = url
        if (AppSettings.stripTracking) out = dev.breezy.browser.data.Tracking.clean(out)
        if (AppSettings.httpsOnly && out.startsWith("http://", ignoreCase = true)) {
            val host = runCatching { out.toUri().host }.getOrNull()
            if (host != null && host !in httpAllowed && !isLocalHost(host)) {
                val now = System.currentTimeMillis()
                if (host == upgradeHost && now - upgradeAt < 5_000) upgradeCount++ else upgradeCount = 0
                upgradeHost = host; upgradeAt = now
                if (upgradeCount >= 3) {
                    // The site keeps redirecting HTTPS back to HTTP: it has no working HTTPS.
                    httpsFailure = out
                    return "about:blank"
                }
                upgradedFrom = out
                out = "https://" + out.substring(7)
            }
        }
        return out.takeIf { it != url }
    }

    private fun isLocalHost(host: String) = host == "localhost" || host.endsWith(".local") ||
        host.matches(Regex("^(127|10|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\..*")) || host.startsWith("[")

    /** User accepted the HTTPS-only warning: load the insecure URL and allow its host this session. */
    fun continueInsecure() {
        val url = httpsFailure ?: return
        runCatching { url.toUri().host }.getOrNull()?.let(httpAllowed::add)
        httpsFailure = null
        upgradedFrom = null
        webView.loadUrl(url)
    }

    fun dismissHttpsFailure() {
        httpsFailure = null
        if (webView.canGoBack()) webView.goBack() else host.closeTab(this)
    }

    // --- Per-site settings ------------------------------------------------------------------------

    private fun cookieManager(): CookieManager =
        profileName?.let { runCatching { androidx.webkit.ProfileStore.getInstance().getProfile(it)?.cookieManager }.getOrNull() }
            ?: CookieManager.getInstance()

    /** Apply JavaScript / cookie / text-size settings for [host] (call before navigating there). */
    fun applySiteSettings(host: String? = pageHost) {
        val site = dev.breezy.browser.data.SiteSettings.get(host)
        webView.settings.javaScriptEnabled = site.javascript
        webView.settings.textZoom = site.textZoom ?: AppSettings.textZoom
        // CookieManager's accept flag is global, so it follows the tab being shown.
        cookieManager().setAcceptCookie(site.cookies)
    }

    // --- Find in page -----------------------------------------------------------------------------

    fun find(query: String) {
        if (query.isEmpty()) { webView.clearMatches(); findResult = 0 to 0 } else webView.findAllAsync(query)
    }

    fun findNext(forward: Boolean) = webView.findNext(forward)

    fun stopFind() {
        webView.clearMatches()
        findResult = null
    }

    fun startFind() { findResult = 0 to 0 }

    // --- Reader mode --------------------------------------------------------------------------------

    data class Article(val title: String, val byline: String, val siteName: String, val html: String, val url: String)

    /** Run Mozilla Readability on a copy of the page; null if no article was found. */
    fun extractArticle(onResult: (Article?) -> Unit) {
        val lib = asset(webView.context, READABILITY)
        val js = "(function(){" + lib + "\n;try{var a=new Readability(document.cloneNode(true)).parse();" +
            "return a?JSON.stringify({t:a.title||'',b:a.byline||'',s:a.siteName||'',c:a.content||''}):null;}catch(e){return null;}})()"
        webView.evaluateJavascript(js) { raw ->
            val json = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
            val o = json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            onResult(o?.takeIf { it.optString("c").length > 200 }?.let {
                Article(it.optString("t").ifBlank { title }, it.optString("b"), it.optString("s"), it.optString("c"), url)
            })
        }
    }

    // --- Media --------------------------------------------------------------------------------------

    fun mediaPlay() = webView.evaluateJavascript("window.__breezyMedia&&__breezyMedia.play()", null)
    fun mediaPause() = webView.evaluateJavascript("window.__breezyMedia&&__breezyMedia.pause()", null)

    fun changePlaybackRate(rate: Float) {
        playbackRate = rate
        webView.evaluateJavascript("window.__breezyMedia&&__breezyMedia.setRate($rate)", null)
    }

    /** Receives media events and config requests from assets/breezy/page.js. */
    private inner class PageBridge {
        @JavascriptInterface
        fun config(): String = org.json.JSONObject()
            .put("bgPlay", AppSettings.backgroundPlay)
            .put("forceZoom", AppSettings.forceZoom)
            .put("rate", playbackRate.toDouble())
            .toString()

        @JavascriptInterface
        fun mediaState(playing: Boolean, title: String) {
            main.post {
                media = MediaState(playing = playing, title = title.ifBlank { this@BrowserTab.title }, hasMedia = true)
                host.onMediaStateChanged(this@BrowserTab)
            }
        }
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
        applySiteSettings()
        deferredUrl?.let { deferredUrl = null; load(it) }
    }

    fun destroy() {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.removeJavascriptInterface("BreezyShield")
        webView.removeJavascriptInterface("BreezyPicker")
        webView.removeJavascriptInterface("BreezyPage")
        scriptlets.values.forEach { it?.remove() }
        scriptlets.clear()
        webView.destroy()
        // Wipe the private profile (cookies, storage, cache) once its WebView is gone.
        profileName?.let { name ->
            main.postDelayed({ runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(name) } }, 500)
        }
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
        dev.breezy.browser.data.Stats.record(guessType(urlString, request.requestHeaders["Accept"]))
        return if (verdict.isEmpty()) Shield.blockedResponse() else Shield.redirectResponse(verdict)
    }

    /** Rough resource type for the data-saved estimate (the precise one is inferred natively). */
    private fun guessType(url: String, accept: String?): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".js") -> "script"
            path.endsWith(".css") -> "stylesheet"
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") -> "media"
            path.endsWith(".woff2") || path.endsWith(".woff") || path.endsWith(".ttf") -> "font"
            accept?.startsWith("image/") == true || path.endsWith(".png") || path.endsWith(".jpg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") -> "image"
            accept?.startsWith("text/html") == true -> "subdocument"
            else -> "xmlhttprequest"
        }
    }

    /** JS interface used by assets/adblock/cosmetic.js. Called on a WebView binder thread. */
    private inner class CosmeticBridge {
        @JavascriptInterface
        fun resources(frameUrl: String): String {
            if (!Shield.activeFor(pageHost)) return "{}"
            val json = NativeShield.cosmeticResources(frameUrl)
            val mine = UserFilters.selectorsFor(runCatching { frameUrl.toUri().host }.getOrNull())
            if (mine.isEmpty()) return json
            // Merge the user's own element-hiding rules ("My filters").
            return runCatching {
                val obj = org.json.JSONObject(json)
                val hides = obj.optJSONArray("hide_selectors") ?: org.json.JSONArray().also { obj.put("hide_selectors", it) }
                mine.forEach(hides::put)
                obj.toString()
            }.getOrDefault(json)
        }

        /** True when native already registered this origin's scriptlets as a document-start script. */
        @JavascriptInterface
        fun scriptletsHandled(origin: String): Boolean = synchronized(scriptletOrigins) { origin in scriptletOrigins }

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

    private val appScope get() = (appContext as dev.breezy.browser.BreezyApp).scope

    private fun maybeHttpsFailed(request: WebResourceRequest) {
        if (!request.isForMainFrame) return
        val from = upgradedFrom ?: return
        if (request.url.host == upgradeHost && request.url.scheme == "https") httpsFailure = from
    }

    private inner class Client : androidx.webkit.WebViewClientCompat() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = intercept(request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme ?: return false
            if (scheme == "http" || scheme == "https") {
                if (request.isForMainFrame) {
                    val original = request.url.toString()
                    val rewritten = if (request.method.equals("GET", true)) rewriteNavigation(original) else null
                    if (rewritten != null) {
                        load(rewritten)
                        return true
                    }
                    // Register this site's scriptlets before its document is created (also on redirects).
                    prepareScriptlets(request.url)
                    applySiteSettings(request.url.host)
                }
                return false
            }
            if (scheme == "about" || scheme == "data") return false
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
            media = MediaState()
            if (!documentStartScriptSupported) {
                view.evaluateJavascript(asset(view.context, SCROLL_REPAIR), null)
                view.evaluateJavascript(asset(view.context, PAGE), null)
                view.evaluateJavascript(asset(view.context, COSMETIC), null)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: androidx.webkit.WebResourceErrorCompat) {
            super.onReceivedError(view, request, error)
            maybeHttpsFailed(request)
        }

        override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            if (upgradedFrom != null && runCatching { error.url.toUri().host }.getOrNull() == upgradeHost) {
                httpsFailure = upgradedFrom
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
            if (!isPrivate && !isReload && url.startsWith("http")) {
                appScope.launch { dev.breezy.browser.data.History.record(url, title) }
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress / 100f
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            this@BrowserTab.title = title.orEmpty()
            val u = view.url
            if (!isPrivate && u != null && !title.isNullOrBlank()) {
                appScope.launch { dev.breezy.browser.data.History.updateTitle(u, title) }
            }
        }

        override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
            handlePermissionRequest(request)
        }

        override fun onPermissionRequestCanceled(request: android.webkit.PermissionRequest) = Unit

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
            handleGeolocation(origin, callback)
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

    /** The page's visible text (used to import a filter list opened in the tab). */
    fun pageText(onResult: (String) -> Unit) {
        webView.evaluateJavascript("document.body ? document.body.innerText : ''") { json ->
            onResult(runCatching { org.json.JSONTokener(json).nextValue() as String }.getOrDefault(""))
        }
    }

    // --- Site permissions ------------------------------------------------------------------------

    private fun handlePermissionRequest(request: android.webkit.PermissionRequest) {
        val site = runCatching { request.origin.host }.getOrNull()
        if (site == null) { request.deny(); return }
        val wanted = request.resources.toList()
        // DRM (Widevine) playback: allowed, like Chrome's default for protected content.
        val drm = android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        if (wanted == listOf(drm)) { request.grant(arrayOf(drm)); return }
        val kinds = wanted.mapNotNull {
            when (it) {
                android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
                android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
                else -> null
            }
        }
        if (kinds.isEmpty()) { request.deny(); return }
        val androidPerms = kinds.map {
            if (it == "camera") android.Manifest.permission.CAMERA else android.Manifest.permission.RECORD_AUDIO
        }.toTypedArray()
        decide(site, kinds) { allowed ->
            if (!allowed) { request.deny(); return@decide }
            host.requestAndroidPermissions(androidPerms) { granted ->
                if (granted) request.grant(wanted.toTypedArray()) else request.deny()
            }
        }
    }

    private fun handleGeolocation(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
        val site = runCatching { origin.toUri().host }.getOrNull()
        if (site == null) { callback.invoke(origin, false, false); return }
        decide(site, listOf("location")) { allowed ->
            if (!allowed) { callback.invoke(origin, false, false); return@decide }
            host.requestAndroidPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION)) { granted -> callback.invoke(origin, granted, false) }
        }
    }

    /** Resolve a permission from the site's saved decision, asking the user when it's "Ask". */
    private fun decide(site: String, kinds: List<String>, onDecision: (Boolean) -> Unit) {
        val cfg = dev.breezy.browser.data.SiteSettings.get(site)
        val decisions = kinds.map {
            when (it) {
                "camera" -> cfg.camera
                "microphone" -> cfg.microphone
                else -> cfg.location
            }
        }
        when {
            decisions.any { it == SitePermission.Block } -> onDecision(false)
            decisions.all { it == SitePermission.Allow } -> onDecision(true)
            else -> main.post {
                host.askSitePermission(site, kinds) { allow, remember ->
                    if (remember) dev.breezy.browser.data.SiteSettings.update(site) { c ->
                        val v = if (allow) SitePermission.Allow else SitePermission.Block
                        kinds.fold(c) { acc, k ->
                            when (k) {
                                "camera" -> acc.copy(camera = v)
                                "microphone" -> acc.copy(microphone = v)
                                else -> acc.copy(location = v)
                            }
                        }
                    }
                    onDecision(allow)
                }
            }
        }
    }

    // --- Element picker ------------------------------------------------------------------------------

    fun startPicker() {
        picker = PickerState()
        webView.evaluateJavascript(asset(webView.context, PICKER), null)
    }

    fun pickerParent() = webView.evaluateJavascript("window.__breezyPicker&&__breezyPicker.parent()", null)
    fun pickerChild() = webView.evaluateJavascript("window.__breezyPicker&&__breezyPicker.child()", null)

    fun pickerSetSelector(selector: String) {
        picker = picker?.copy(selector = selector)
        webView.evaluateJavascript("window.__breezyPicker&&__breezyPicker.setSelector(${org.json.JSONObject.quote(selector)})", null)
    }

    fun pickerPreview(on: Boolean) {
        picker = picker?.copy(preview = on)
        webView.evaluateJavascript("window.__breezyPicker&&__breezyPicker.preview($on)", null)
    }

    /** Save the current selection as a rule for this site and hide it right away. */
    fun pickerBlock(): Boolean {
        val state = picker ?: return false
        val host = pageHost ?: return false
        if (state.selector.isBlank() || state.count == 0) return false
        UserFilters.add(host, state.selector)
        webView.evaluateJavascript("window.__breezyPicker&&__breezyPicker.commit()", null)
        picker = null
        return true
    }

    fun stopPicker() {
        webView.evaluateJavascript("window.__breezyPicker&&__breezyPicker.stop()", null)
        picker = null
    }

    /** Receives selections from assets/breezy/picker.js (binder thread). */
    private inner class PickerBridge {
        @JavascriptInterface
        fun onSelect(json: String) {
            val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
            main.post {
                val current = picker ?: return@post // ignore calls when not picking
                picker = current.copy(
                    selector = o.optString("selector"),
                    count = o.optInt("count"),
                    label = o.optString("label"),
                    canParent = o.optBoolean("canParent"),
                    canChild = o.optBoolean("canChild"),
                )
            }
        }
    }

    // --- Scriptlets ---------------------------------------------------------------------------------

    /**
     * uBO scriptlets (e.g. YouTube's ad-removal json-prune) must run before page scripts, and sites
     * like YouTube forbid eval via CSP/Trusted Types. Document-start scripts registered natively are
     * neither late nor subject to the page's CSP, so register one per origin just before navigating.
     */
    private val scriptlets = HashMap<String, ScriptHandler?>()
    private val scriptletOrigins = HashSet<String>()
    private var scriptletGeneration = -1

    private fun prepareScriptlets(uri: android.net.Uri) {
        if (!documentStartScriptSupported) return
        val scheme = uri.scheme ?: return
        val host = uri.host ?: return
        if (scheme != "https" && scheme != "http") return
        // Engine reloaded / shields changed: drop registrations and rebuild lazily.
        if (scriptletGeneration != Shield.generation) {
            scriptlets.values.forEach { it?.remove() }
            scriptlets.clear()
            synchronized(scriptletOrigins) { scriptletOrigins.clear() }
            scriptletGeneration = Shield.generation
        }
        val origin = "$scheme://$host" + if (uri.port != -1) ":${uri.port}" else ""
        if (origin in scriptlets) return
        if (!Shield.activeFor(host)) { scriptlets[origin] = null; return }
        val code = runCatching {
            org.json.JSONObject(NativeShield.cosmeticResources(uri.toString())).optString("injected_script")
        }.getOrNull()
        if (code.isNullOrEmpty()) { scriptlets[origin] = null; return }
        val wrapped = "(function(){if(window.__breezyScriptlets)return;" +
            "Object.defineProperty(window,'__breezyScriptlets',{value:true});try{\n$code\n}catch(e){}})();"
        scriptlets[origin] = runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, wrapped, setOf(origin))
        }.getOrNull()
        if (scriptlets[origin] != null) synchronized(scriptletOrigins) { scriptletOrigins += origin }
    }

    companion object {
        private var nextId = 1L
        private const val PICKER = "breezy/picker.js"
        private const val PAGE = "breezy/page.js"
        private const val READABILITY = "reader/Readability.js"

        val multiProfileSupported: Boolean by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
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

        /**
         * Present as regular mobile Chrome instead of an embedded WebView: drop the "; wv" token and
         * "Version/4.0", use Chrome's reduced platform string, stop sending X-Requested-With, and
         * advertise Chromium client hints. Google sign-in and YouTube treat WebViews differently
         * (sign-in often refused or not persisted), which also kept YouTube Premium from applying.
         */
        private fun presentAsChrome(settings: WebSettings) {
            val default = settings.userAgentString
            val chromeVersion = Regex("Chrome/([\\d.]+)").find(default)?.groupValues?.get(1) ?: return
            val major = chromeVersion.substringBefore('.')
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                runCatching {
                    val brands = listOf(
                        UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium")
                            .setMajorVersion(major).setFullVersion(chromeVersion).build(),
                        UserAgentMetadata.BrandVersion.Builder().setBrand("Not_A Brand")
                            .setMajorVersion("24").setFullVersion("24.0.0.0").build(),
                    )
                    WebSettingsCompat.setUserAgentMetadata(
                        settings,
                        UserAgentMetadata.Builder()
                            .setBrandVersionList(brands)
                            .setFullVersion(chromeVersion)
                            .setPlatform("Android")
                            .setMobile(true)
                            .build(),
                    )
                }
            }
        }

        private fun installCosmeticScript(webView: WebView) {
            if (documentStartScriptSupported) {
                WebViewCompat.addDocumentStartJavaScript(webView, asset(webView.context, SCROLL_REPAIR), setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(webView, asset(webView.context, PAGE), setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(webView, asset(webView.context, COSMETIC), setOf("*"))
            }
        }
    }
}
