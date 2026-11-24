package dev.breezy.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

enum class SearchEngine(
    val label: String,
    val queryUrl: String,
    val queryParam: String,
    /** OpenSearch-JSON suggestions endpoint (`["q", ["s1", ...]]`), or null if none we can use. */
    val suggestUrl: String?,
) {
    Startpage("Startpage", "https://www.startpage.com/sp/search?query=", "query", "https://www.startpage.com/osuggestions?q="),
    DuckDuckGo("DuckDuckGo", "https://duckduckgo.com/?q=", "q", "https://duckduckgo.com/ac/?type=list&q="),
    Brave("Brave Search", "https://search.brave.com/search?q=", "q", null),
    Google("Google", "https://www.google.com/search?q=", "q", "https://suggestqueries.google.com/complete/search?client=firefox&q=");

    /** Host + path prefix used to recognise result pages, e.g. "www.startpage.com/sp/search". */
    val resultPrefix: String get() = queryUrl.substringAfter("://").substringBefore('?')
}

data class Shortcut(val name: String, val url: String)

/** App-wide user preferences, observable from Compose. */
object AppSettings {
    private lateinit var prefs: SharedPreferences

    private val defaultShortcuts = listOf(
        Shortcut("Wikipedia", "https://wikipedia.org"),
        Shortcut("GitHub", "https://github.com"),
        Shortcut("YouTube", "https://m.youtube.com"),
        Shortcut("Hacker News", "https://news.ycombinator.com"),
    )

    /** Home page shortcuts, in display order. */
    val shortcuts = mutableStateListOf<Shortcut>()

    /** Render websites dark (prefers-color-scheme + algorithmic darkening). */
    var darkWebPages by mutableStateOf(true)
        private set

    /**
     * Algorithmically recolor sites that have no dark theme. Off by default: it flips the colors of
     * already-dark sites that don't declare `color-scheme: dark` (white buttons become invisible,
     * login forms lose their text).
     */
    var forceDarkAllSites by mutableStateOf(false)
        private set

    var searchEngine by mutableStateOf(SearchEngine.Startpage)
        private set

    /**
     * Real backdrop blur behind the floating toolbar (Android 16+). Off by default while we confirm
     * whether it causes some pages (X login, blurred-backdrop dialogs) to render incorrectly: the
     * window backdrop forces offscreen rendering, which WebView doesn't always survive.
     */
    var frostedSurfaces by mutableStateOf(true); private set

    // --- Privacy / browsing ---
    var httpsOnly by mutableStateOf(true); private set
    var stripTracking by mutableStateOf(true); private set
    var clearOnExit by mutableStateOf(false); private set
    var searchSuggestions by mutableStateOf(true); private set
    /** Global default text zoom (percent). */
    var textZoom by mutableIntStateOf(100); private set
    var pullToRefresh by mutableStateOf(true); private set
    // --- Privacy signals & fingerprinting ---
    var globalPrivacyControl by mutableStateOf(true); private set
    var doNotTrack by mutableStateOf(false); private set
    var farbling by mutableStateOf(true); private set
    // --- SponsorBlock ---
    var sponsorBlock by mutableStateOf(true); private set
    var sponsorCategories by mutableStateOf(dev.breezy.browser.data.SponsorBlock.defaultCategories); private set
    // --- Translation ---
    /** Target language code, or "" for the device language. */
    var translateTo by mutableStateOf(""); private set
    var offerTranslate by mutableStateOf(true); private set
    var swipeToCloseTabs by mutableStateOf(true); private set
    // --- Appearance ---
    var themeMode by mutableStateOf(ThemeMode.System); private set
    var pureBlack by mutableStateOf(false); private set
    // --- Secure DNS ---
    var dnsProvider by mutableStateOf(dev.breezy.browser.data.DnsProvider.Off); private set
    var customDnsUrl by mutableStateOf(""); private set
    var dnsStrict by mutableStateOf(false); private set
    // --- Downloads ---
    /** Parallel connections per download (when the server supports byte ranges). */
    var downloadConnections by mutableIntStateOf(4); private set
    // --- Media ---
    var backgroundPlay by mutableStateOf(true); private set
    var forceZoom by mutableStateOf(false); private set

    /** Present as mobile Chrome rather than an Android WebView (sign-in compatibility). */
    var identifyAsChrome by mutableStateOf(true)
        private set

    /** Floating toolbar position: 0 = bottom, 1 = top of the available area. */
    var toolbarPosition by mutableFloatStateOf(0f)
        private set

    var autoUpdateLists by mutableStateOf(true)
        private set
    var autoCheckUpdates by mutableStateOf(true); private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        darkWebPages = prefs.getBoolean("dark_web_pages", true)
        forceDarkAllSites = prefs.getBoolean("force_dark_all_sites", false)
        frostedSurfaces = prefs.getBoolean("frosted_surfaces", true)
        identifyAsChrome = prefs.getBoolean("identify_as_chrome", true)
        httpsOnly = prefs.getBoolean("https_only", true)
        stripTracking = prefs.getBoolean("strip_tracking", true)
        clearOnExit = prefs.getBoolean("clear_on_exit", false)
        searchSuggestions = prefs.getBoolean("search_suggestions", true)
        textZoom = prefs.getInt("text_zoom", 100)
        pullToRefresh = prefs.getBoolean("pull_to_refresh", true)
        backgroundPlay = prefs.getBoolean("background_play", true)
        forceZoom = prefs.getBoolean("force_zoom", false)
        downloadConnections = prefs.getInt("download_connections", 4)
        globalPrivacyControl = prefs.getBoolean("gpc", true)
        doNotTrack = prefs.getBoolean("dnt", false)
        farbling = prefs.getBoolean("farbling", true)
        sponsorBlock = prefs.getBoolean("sponsorblock", true)
        sponsorCategories = prefs.getStringSet("sponsor_categories", null)?.toSet()
            ?: dev.breezy.browser.data.SponsorBlock.defaultCategories
        translateTo = prefs.getString("translate_to", "")!!
        offerTranslate = prefs.getBoolean("offer_translate", true)
        swipeToCloseTabs = prefs.getBoolean("swipe_close_tabs", true)
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", "System")!!) }.getOrDefault(ThemeMode.System)
        pureBlack = prefs.getBoolean("pure_black", false)
        dnsProvider = runCatching { dev.breezy.browser.data.DnsProvider.valueOf(prefs.getString("dns_provider", "Off")!!) }
            .getOrDefault(dev.breezy.browser.data.DnsProvider.Off)
        customDnsUrl = prefs.getString("dns_custom_url", "")!!
        dnsStrict = prefs.getBoolean("dns_strict", false)
        searchEngine = runCatching { SearchEngine.valueOf(prefs.getString("search_engine", null)!!) }
            .getOrDefault(SearchEngine.Startpage)
        toolbarPosition = prefs.getFloat("toolbar_position", 0f)
        autoUpdateLists = prefs.getBoolean("auto_update_lists", true)
        autoCheckUpdates = prefs.getBoolean("auto_check_updates", true)
        shortcuts.clear()
        val saved = prefs.getString("shortcuts", null)
        shortcuts.addAll(
            if (saved == null) defaultShortcuts
            else runCatching {
                val arr = JSONArray(saved)
                List(arr.length()) { arr.getJSONObject(it).let { o -> Shortcut(o.getString("name"), o.getString("url")) } }
            }.getOrDefault(defaultShortcuts),
        )
    }

    fun addShortcut(shortcut: Shortcut) {
        shortcuts.removeAll { it.url == shortcut.url }
        shortcuts.add(shortcut)
        saveShortcuts()
    }

    fun updateShortcut(old: Shortcut, new: Shortcut) {
        val i = shortcuts.indexOf(old)
        if (i >= 0) shortcuts[i] = new else shortcuts.add(new)
        saveShortcuts()
    }

    fun removeShortcut(shortcut: Shortcut) {
        shortcuts.remove(shortcut)
        saveShortcuts()
    }

    private fun saveShortcuts() {
        val arr = JSONArray()
        shortcuts.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        prefs.edit { putString("shortcuts", arr.toString()) }
    }

    fun updateDarkWebPages(value: Boolean) {
        darkWebPages = value
        prefs.edit { putBoolean("dark_web_pages", value) }
    }

    fun updateForceDarkAllSites(value: Boolean) {
        forceDarkAllSites = value
        prefs.edit { putBoolean("force_dark_all_sites", value) }
    }

    fun updateFrostedSurfaces(v: Boolean) { frostedSurfaces = v; prefs.edit { putBoolean("frosted_surfaces", v) } }


    fun updateHttpsOnly(v: Boolean) { httpsOnly = v; prefs.edit { putBoolean("https_only", v) } }
    fun updateStripTracking(v: Boolean) { stripTracking = v; prefs.edit { putBoolean("strip_tracking", v) } }
    fun updateClearOnExit(v: Boolean) { clearOnExit = v; prefs.edit { putBoolean("clear_on_exit", v) } }
    fun updateSearchSuggestions(v: Boolean) { searchSuggestions = v; prefs.edit { putBoolean("search_suggestions", v) } }
    fun updateTextZoom(v: Int) { textZoom = v.coerceIn(50, 300); prefs.edit { putInt("text_zoom", textZoom) } }
    fun updatePullToRefresh(v: Boolean) { pullToRefresh = v; prefs.edit { putBoolean("pull_to_refresh", v) } }
    fun updateBackgroundPlay(v: Boolean) { backgroundPlay = v; prefs.edit { putBoolean("background_play", v) } }
    fun updateGpc(v: Boolean) { globalPrivacyControl = v; prefs.edit { putBoolean("gpc", v) } }
    fun updateDnt(v: Boolean) { doNotTrack = v; prefs.edit { putBoolean("dnt", v) } }
    fun updateFarbling(v: Boolean) { farbling = v; prefs.edit { putBoolean("farbling", v) } }
    fun updateSponsorBlock(v: Boolean) { sponsorBlock = v; prefs.edit { putBoolean("sponsorblock", v) } }
    fun toggleSponsorCategory(id: String, on: Boolean) {
        sponsorCategories = if (on) sponsorCategories + id else sponsorCategories - id
        prefs.edit { putStringSet("sponsor_categories", sponsorCategories) }
    }
    fun updateTranslateTo(v: String) { translateTo = v; prefs.edit { putString("translate_to", v) } }
    fun updateSwipeToCloseTabs(v: Boolean) { swipeToCloseTabs = v; prefs.edit { putBoolean("swipe_close_tabs", v) } }
    fun updateOfferTranslate(v: Boolean) { offerTranslate = v; prefs.edit { putBoolean("offer_translate", v) } }

    fun updateThemeMode(v: ThemeMode) { themeMode = v; prefs.edit { putString("theme_mode", v.name) } }
    fun updatePureBlack(v: Boolean) { pureBlack = v; prefs.edit { putBoolean("pure_black", v) } }

    fun updateDns(provider: dev.breezy.browser.data.DnsProvider, custom: String = customDnsUrl, strict: Boolean = dnsStrict) {
        dnsProvider = provider; customDnsUrl = custom.trim(); dnsStrict = strict
        prefs.edit { putString("dns_provider", provider.name); putString("dns_custom_url", customDnsUrl); putBoolean("dns_strict", strict) }
    }

    /** The DoH endpoint in effect, or null when secure DNS is off. */
    val dnsEndpoint: String?
        get() = when (dnsProvider) {
            dev.breezy.browser.data.DnsProvider.Off -> null
            dev.breezy.browser.data.DnsProvider.Custom -> customDnsUrl.takeIf { it.startsWith("https://") }
            else -> dnsProvider.url
        }

    fun updateDownloadConnections(v: Int) { downloadConnections = v.coerceIn(1, 8); prefs.edit { putInt("download_connections", downloadConnections) } }
    fun updateForceZoom(v: Boolean) { forceZoom = v; prefs.edit { putBoolean("force_zoom", v) } }

    fun updateIdentifyAsChrome(value: Boolean) {
        identifyAsChrome = value
        prefs.edit { putBoolean("identify_as_chrome", value) }
    }

    fun updateSearchEngine(value: SearchEngine) {
        searchEngine = value
        prefs.edit { putString("search_engine", value.name) }
    }

    fun updateToolbarPosition(value: Float) {
        toolbarPosition = value.coerceIn(0f, 1f)
        prefs.edit { putFloat("toolbar_position", toolbarPosition) }
    }

    fun updateAutoCheckUpdates(value: Boolean) { autoCheckUpdates = value; prefs.edit { putBoolean("auto_check_updates", value) } }

    fun updateAutoUpdateLists(value: Boolean) {
        autoUpdateLists = value
        prefs.edit { putBoolean("auto_update_lists", value) }
    }
}

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }
