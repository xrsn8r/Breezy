package dev.breezy.browser.browser

import android.annotation.SuppressLint
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
    fun onDownloadStarted(name: String)
    /** Open [url] in a new tab next to the current one (target=_blank links). */
    fun openLinkInNewTab(url: String, private: Boolean)
    /** [tab]'s renderer died; its WebView is unusable and must be replaced. */
    fun onRendererGone(tab: BrowserTab)
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
    /** A popup opened from a private tab shares its opener's profile (WebView requires it). */
    sharedProfile: String? = null,
) {
    private val appContext = context.applicationContext
    private val profileName: String? =
        (sharedProfile ?: if (isPrivate && multiProfileSupported) "private-$id-${System.nanoTime()}" else null)
            ?.also { profileUsers.merge(it, 1, Int::plus) }

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

    /** A downloadable media file noticed on the page (network request or <video>/<audio> src). */
    data class DetectedMedia(
        val url: String, val kind: Kind, val pageTitle: String, val pageUrl: String,
        /** Headers the page sent for this request (Referer/Origin of the embedding player frame). */
        val requestHeaders: Map<String, String> = emptyMap(),
    ) {
        enum class Kind { Video, Audio, Hls }
    }

    // --- Translation ---------------------------------------------------------------------------
    /** Source language of the current translation ("…" while starting), or null when not translated. */
    var translatedFrom by mutableStateOf<String?>(null)
        private set
    var translateError by mutableStateOf<String?>(null)
    /** The page's declared language when it differs from the user's, to offer a translation. */
    var translateOffer by mutableStateOf<String?>(null)
        private set
    private val declinedTranslations = HashSet<String>()

    fun translate() {
        translateOffer = null
        translateError = null
        translatedFrom = "…"
        webView.evaluateJavascript(asset(webView.context, TRANSLATE), null)
    }

    fun showOriginal() {
        translatedFrom = null
        webView.evaluateJavascript("window.__breezyTranslate&&__breezyTranslate.restore()", null)
    }

    fun declineTranslate() {
        translateOffer?.let { declinedTranslations += it.substringBefore('-').lowercase() }
        translateOffer = null
    }

    private fun checkTranslateOffer() {
        if (!AppSettings.offerTranslate || translatedFrom != null) return
        if (!url.startsWith("http")) return
        webView.evaluateJavascript("(document.documentElement.lang||'')") { raw ->
            val lang = raw?.trim('"')?.trim().orEmpty()
            val target = dev.breezy.browser.data.Translator.target(AppSettings.translateTo)
            if (dev.breezy.browser.data.Translator.differs(lang, target) &&
                lang.substringBefore('-').lowercase() !in declinedTranslations && translatedFrom == null
            ) translateOffer = lang
        }
    }

    /** Media found on the current page, newest last. Reset on each top-level navigation. */
    val detectedMedia = androidx.compose.runtime.mutableStateListOf<DetectedMedia>()

    /** The page plays media but only through MediaSource streams (e.g. YouTube): not downloadable. */
    var streamOnlyMedia by mutableStateOf(false)
        private set

    data class MediaState(
        val playing: Boolean = false,
        val title: String = "",
        val hasMedia: Boolean = false,
        /** The playing element is a video (not audio only). */
        val video: Boolean = false,
        /** Video width / height, for the picture-in-picture window. */
        val aspect: Float = 16f / 9f,
    )
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

    val webView: BreezyWebView = BreezyWebView(webContext).apply {
        // A private tab's profile must be set before the WebView is used for anything else.
        profileName?.let { name ->
            runCatching { WebViewCompat.setProfile(this, name) }
        }
        // Avoid a white flash before the first paint in dark mode.
        setBackgroundColor(if (dark) (if (AppSettings.pureBlack) android.graphics.Color.BLACK else 0xFF121212.toInt()) else android.graphics.Color.WHITE)
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
        // Global Privacy Control as a header too, on navigations Breezy starts (WebView can't add
        // headers to the page's own requests; pages also see navigator.globalPrivacyControl).
        if (AppSettings.globalPrivacyControl) webView.loadUrl(target, mapOf("Sec-GPC" to "1"))
        else webView.loadUrl(target)
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

    /** Picture-in-picture: make the playing video fill the page (and undo it afterwards). */
    fun setVideoFocus(on: Boolean) =
        webView.evaluateJavascript("window.__breezyMedia&&__breezyMedia.focus($on)", null)
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

        /** Privacy signals and the farbling seed for a frame on [host] (null seed = no farbling). */
        @JavascriptInterface
        fun privacyConfig(host: String): String {
            val o = org.json.JSONObject()
                .put("gpc", AppSettings.globalPrivacyControl)
                .put("dnt", AppSettings.doNotTrack)
            // Shields down for the page also turns farbling off (it can break a site's canvas use).
            if (AppSettings.farbling && Shield.activeFor(pageHost) && host.isNotEmpty()) {
                val site = runCatching { NativeShield.registrableDomain(host) }.getOrNull() ?: host
                val digest = java.security.MessageDigest.getInstance("SHA-256").run { update(farbleKey); digest(site.toByteArray()) }
                o.put("seed", digest.take(4).joinToString("") { "%02x".format(it) })
            }
            return o.toString()
        }

        @JavascriptInterface
        fun sponsorEnabled(): Boolean = AppSettings.sponsorBlock && AppSettings.sponsorCategories.isNotEmpty()

        @JavascriptInterface
        fun sponsorSegments(videoId: String) {
            if (!Regex("[\\w-]{11}").matches(videoId)) return
            appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val json = runCatching { dev.breezy.browser.data.SponsorBlock.segments(videoId) }.getOrNull() ?: return@launch
                main.post {
                    webView.evaluateJavascript("window.__breezySB&&__breezySB.segments('$videoId',$json)", null)
                }
            }
        }

        @JavascriptInterface
        fun translateBatch(id: Int, json: String) {
            val texts = runCatching { org.json.JSONArray(json) }.getOrNull()?.let { a -> List(a.length()) { a.getString(it) } } ?: return
            val target = dev.breezy.browser.data.Translator.target(AppSettings.translateTo)
            appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val result = runCatching { dev.breezy.browser.data.Translator.translate(texts, target) }
                main.post {
                    result.onSuccess { r ->
                        r.sourceLang?.let { if (translatedFrom == null || translatedFrom == "…") translatedFrom = it }
                        val arr = org.json.JSONArray(r.texts).toString()
                        webView.evaluateJavascript("window.__breezyTranslate&&__breezyTranslate.apply($id,$arr)", null)
                    }.onFailure { translateError = it.message ?: "network error" }
                }
            }
        }

        /** `[{"src": "...", "tag": "video"|"audio"}, ...]` from page.js; "stream" = MediaSource only. */
        @JavascriptInterface
        fun mediaSources(json: String) {
            val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return
            main.post {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val src = o.optString("src")
                    val tag = o.optString("tag")
                    when {
                        src == "stream" -> streamOnlyMedia = true
                        src.startsWith("http") -> {
                            val kind = mediaKind(src, null) ?: if (tag == "audio") DetectedMedia.Kind.Audio else DetectedMedia.Kind.Video
                            addMedia(DetectedMedia(src, kind, title, pageUrl))
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun blobChunk(id: String, base64: String, last: Boolean) = onBlobChunk(id, base64, last)

        @JavascriptInterface
        fun mediaState(playing: Boolean, title: String, video: Boolean, aspect: Double) {
            main.post {
                media = MediaState(
                    playing = playing, title = title.ifBlank { this@BrowserTab.title }, hasMedia = true,
                    video = video, aspect = aspect.toFloat().takeIf { it.isFinite() && it > 0f } ?: (16f / 9f),
                )
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

    fun onPause() {
        webView.onPause()
        // Pre-rendering offscreen tiles only pays off for the visible tab; save memory otherwise.
        webView.settings.offscreenPreRaster = false
    }

    fun onResume() {
        webView.onResume()
        webView.settings.offscreenPreRaster = true
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
        // Wipe the private profile (cookies, storage, cache) once no tab's WebView uses it.
        profileName?.let { name ->
            val left = profileUsers.merge(name, -1, Int::plus) ?: 0
            if (left <= 0) {
                profileUsers.remove(name)
                main.postDelayed({ runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(name) } }, 500)
            }
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
            main.post { blockedCount = 0; detectedMedia.clear(); streamOnlyMedia = false; translatedFrom = null; translateOffer = null }
            return null // never block top-level navigations
        }
        if (!Shield.activeFor(pageHost)) { sniffMedia(urlString, request.requestHeaders); return null }
        val verdict = NativeShield.check(
            urlString, pageUrl, request.requestHeaders["Accept"], false, request.method,
        ) ?: run { sniffMedia(urlString, request.requestHeaders); return null }
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
            // Skip the (large) scriptlet bundle when it was already registered natively.
            val origin = runCatching { frameUrl.toUri().let { "${it.scheme}://${it.host}" + if (it.port != -1) ":${it.port}" else "" } }.getOrNull()
            val handled = origin != null && synchronized(scriptletOrigins) { origin in scriptletOrigins }
            val json = NativeShield.cosmeticResources(frameUrl, includeScript = !handled)
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
            val script = NativeShield.scriptlets(frameUrl)
            if (script.isNotEmpty()) main.post { webView.evaluateJavascript(script, null) }
        }
    }

    private val appScope get() = (appContext as dev.breezy.browser.BreezyApp).scope

    private fun maybeHttpsFailed(request: WebResourceRequest) {
        if (!request.isForMainFrame) return
        val from = upgradedFrom ?: return
        if (request.url.host == upgradeHost && request.url.scheme == "https") httpsFailure = from
    }

    private inner class Client : androidx.webkit.WebViewClientCompat() {
        /**
         * The page's renderer crashed or was killed for memory. Without handling this, Android
         * terminates the whole app. Hand the tab to the host, which swaps in a fresh WebView.
         */
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            android.util.Log.w("BrowserTab", "Renderer gone (crash=${detail.didCrash()}) on $url")
            dev.breezy.browser.data.CrashReport.note("renderer gone crash=${detail.didCrash()} on $pageHost")
            main.post { host.onRendererGone(this@BrowserTab) }
            return true
        }

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
                dev.breezy.browser.data.CrashReport.note("hand off $scheme: to ${intent.`package` ?: "system"}")
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
            checkTranslateOffer()
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
            // A plain link with target=_blank (e.g. "View on Reddit" inside an embed): open its URL
            // in an ordinary new tab. Only script-opened windows (sign-in popups that talk back to
            // their opener) need a WebView handed to the opener.
            val hit = view.hitTestResult
            val link = hit.extra?.takeIf {
                (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) &&
                    (it.startsWith("https://") || it.startsWith("http://"))
            }
            dev.breezy.browser.data.CrashReport.note("popup from ${pageHost} link=${link != null} dialog=$isDialog")
            if (link != null) {
                main.post { host.openLinkInNewTab(link, isPrivate) }
                return false
            }
            // Links with target=_blank inside embeds (Reddit, X, YouTube cards) land here. The
            // popup must share the opener's profile; if WebView still refuses it, drop the popup
            // rather than take the app down.
            val tab = runCatching { BrowserTab(view.context, host, isPrivate, sharedProfile = profileName) }.getOrNull()
                ?: return false
            return try {
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = tab.webView
                resultMsg.sendToTarget()
                host.openInNewTab(tab)
                true
            } catch (e: Exception) {
                android.util.Log.w("BrowserTab", "Popup failed", e)
                tab.destroy()
                false
            }
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

    // --- Media detection & downloads ----------------------------------------------------------------

    /** Note media files the page requests (called on WebView IO threads). */
    private fun sniffMedia(url: String, headers: Map<String, String>?) {
        val accept = headers?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value
        val kind = mediaKind(url, accept) ?: return
        val pageTitleNow = title
        val page = pageUrl
        val kept = headers.orEmpty().filterKeys { it.lowercase() in COPIED_HEADERS }
        main.post { addMedia(DetectedMedia(url, kind, pageTitleNow, page, kept)) }
    }

    private fun addMedia(m: DetectedMedia) {
        // Dedupe by URL without query (players re-request the same file with range params).
        val key = m.url.substringBefore('?')
        if (detectedMedia.any { it.url.substringBefore('?') == key }) return
        // HLS: once a playlist is known, its variant/rendition playlists add nothing new.
        if (m.kind == DetectedMedia.Kind.Hls && detectedMedia.count { it.kind == DetectedMedia.Kind.Hls } >= 3) return
        if (detectedMedia.size >= 20) detectedMedia.removeAt(0)
        detectedMedia += m
    }

    private fun mediaKind(url: String, accept: String?): DetectedMedia.Kind? {
        val host = runCatching { url.toUri().host }.getOrNull().orEmpty()
        // YouTube serves video as range-requested DASH chunks of separate audio/video tracks.
        if (host.endsWith("googlevideo.com")) return null
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('.', "")
        return when {
            ext == "m3u8" -> DetectedMedia.Kind.Hls
            ext in setOf("mp4", "m4v", "webm", "mov", "mkv") -> DetectedMedia.Kind.Video
            ext in setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav") -> DetectedMedia.Kind.Audio
            accept?.startsWith("video/") == true -> DetectedMedia.Kind.Video
            accept?.startsWith("audio/") == true -> DetectedMedia.Kind.Audio
            else -> null
        }
    }

    /**
     * Headers a download needs to look like the page's own request (cookies, UA, referrer).
     * [media]'s captured headers win: players embedded in an iframe (e.g. a third-party video host)
     * send the frame's Referer/Origin, and their CDNs reject anything else with 403.
     */
    fun downloadHeaders(url: String, media: DetectedMedia? = null): Map<String, String> = buildMap {
        put("User-Agent", webView.settings.userAgentString)
        cookieManager().getCookie(url)?.let { put("Cookie", it) }
        if (pageUrl.startsWith("http")) put("Referer", pageUrl)
        media?.requestHeaders?.forEach { (k, v) ->
            val name = COPIED_HEADERS_CANONICAL[k.lowercase()] ?: k
            keys.firstOrNull { it.equals(name, true) }?.let { remove(it) }
            put(name, v)
        }
        // Cross-origin player requests (hls.js uses fetch/XHR) carry an Origin; some CDNs require it.
        if (keys.none { it.equals("Origin", true) }) {
            val ref = get("Referer")?.toUri()
            val target = url.toUri()
            if (ref?.host != null && ref.host != target.host) put("Origin", "${ref.scheme}://${ref.host}" + (if (ref.port > 0) ":${ref.port}" else ""))
        }
    }

    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trimStart('.').take(120).ifEmpty { "download" }

    private fun download(context: Context, url: String, userAgent: String, disposition: String?, mime: String?) {
        val name = safeName(URLUtil.guessFileName(url, disposition, mime))
        val type = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        runCatching {
            when {
                url.startsWith("http://") || url.startsWith("https://") -> {
                    dev.breezy.browser.downloads.Downloads.enqueue(url, name, type, downloadHeaders(url) + ("User-Agent" to userAgent))
                }
                url.startsWith("data:") -> {
                    val (dataMime, bytes) = dev.breezy.browser.downloads.Downloads.decodeDataUrl(url) ?: error("bad data URL")
                    dev.breezy.browser.downloads.Downloads.saveBytes(name, dataMime, bytes)
                }
                url.startsWith("blob:") -> fetchBlob(url, name, type)
                else -> error("unsupported")
            }
            host.onDownloadStarted(name)
        }.onFailure {
            Toast.makeText(context, "Couldn't download $name", Toast.LENGTH_SHORT).show()
        }
    }

    // blob: URLs only exist inside the page, so read them there and stream them out in chunks.
    private val blobParts = HashMap<String, java.io.ByteArrayOutputStream>()

    private fun fetchBlob(url: String, name: String, mime: String) {
        val id = java.util.UUID.randomUUID().toString()
        synchronized(blobParts) { blobParts[id] = java.io.ByteArrayOutputStream() }
        val js = """(async function(){try{var b=await (await fetch(${org.json.JSONObject.quote(url)})).blob();
            var CH=393216;for(var o=0;o<b.size;o+=CH){var buf=new Uint8Array(await b.slice(o,o+CH).arrayBuffer());
            var s='';for(var i=0;i<buf.length;i+=8192){s+=String.fromCharCode.apply(null,buf.subarray(i,i+8192));}
            BreezyPage.blobChunk('$id',btoa(s),false);}BreezyPage.blobChunk('$id','',true);}
            catch(e){BreezyPage.blobChunk('$id','',true);}})()"""
        pendingBlobNames[id] = name to mime
        webView.evaluateJavascript(js, null)
    }

    private val pendingBlobNames = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    private fun onBlobChunk(id: String, base64: String, last: Boolean) {
        val out = synchronized(blobParts) { blobParts[id] } ?: return
        if (base64.isNotEmpty()) out.write(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
        if (!last) return
        synchronized(blobParts) { blobParts.remove(id) }
        val (name, mime) = pendingBlobNames.remove(id) ?: return
        val bytes = out.toByteArray()
        main.post {
            if (bytes.isEmpty()) Toast.makeText(webView.context, "Couldn't download $name", Toast.LENGTH_SHORT).show()
            else appScope.launch { dev.breezy.browser.downloads.Downloads.saveBytes(name, mime, bytes) }
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
        val code = NativeShield.scriptlets(uri.toString())
        if (code.isEmpty()) { scriptlets[origin] = null; return }
        val wrapped = "(function(){if(window.__breezyScriptlets)return;" +
            "Object.defineProperty(window,'__breezyScriptlets',{value:true});try{\n$code\n}catch(e){}})();"
        scriptlets[origin] = runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, wrapped, setOf(origin))
        }.getOrNull()
        if (scriptlets[origin] != null) synchronized(scriptletOrigins) { scriptletOrigins += origin }
    }

    companion object {
        private val COPIED_HEADERS_CANONICAL = mapOf("referer" to "Referer", "origin" to "Origin", "user-agent" to "User-Agent", "authorization" to "Authorization")
        private val COPIED_HEADERS = COPIED_HEADERS_CANONICAL.keys
        private var nextId = 1L
        /** How many live tabs use each private profile (a popup shares its opener's). */
        private val profileUsers = HashMap<String, Int>()
        private const val PICKER = "breezy/picker.js"
        private const val PAGE = "breezy/page.js"
        private const val READABILITY = "reader/Readability.js"

        val multiProfileSupported: Boolean by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
        private const val COSMETIC = "adblock/cosmetic.js"
        private const val PRIVACY = "breezy/privacy.js"
        private const val SPONSORBLOCK = "breezy/sponsorblock.js"
        private const val TRANSLATE = "breezy/translate.js"
        private val YOUTUBE_ORIGINS = setOf("https://www.youtube.com", "https://m.youtube.com", "https://youtube.com")
        /** Per-process key for farbling: fingerprints change every app session. */
        private val farbleKey = java.security.SecureRandom().let { r -> ByteArray(16).also(r::nextBytes) }
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
         * "Version/4.0", use Chrome's reduced platform string and advertise Chromium client hints.
         * (Current WebView no longer sends X-Requested-With.) Google sign-in and YouTube treat WebViews differently
         * (sign-in often refused or not persisted), which also kept YouTube Premium from applying.
         */
        private fun presentAsChrome(settings: WebSettings) {
            val default = settings.userAgentString
            val chromeVersion = Regex("Chrome/([\\d.]+)").find(default)?.groupValues?.get(1) ?: return
            val major = chromeVersion.substringBefore('.')
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
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
                // Privacy signals and farbling first, before any page script can read the APIs.
                WebViewCompat.addDocumentStartJavaScript(webView, asset(webView.context, PRIVACY), setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(webView, asset(webView.context, SPONSORBLOCK), YOUTUBE_ORIGINS)
                WebViewCompat.addDocumentStartJavaScript(webView, asset(webView.context, SCROLL_REPAIR), setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(webView, asset(webView.context, PAGE), setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(webView, asset(webView.context, COSMETIC), setOf("*"))
            }
        }
    }
}
