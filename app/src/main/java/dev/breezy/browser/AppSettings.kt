package dev.breezy.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

enum class SearchEngine(val label: String, val queryUrl: String, val queryParam: String) {
    Startpage("Startpage", "https://www.startpage.com/sp/search?query=", "query"),
    DuckDuckGo("DuckDuckGo", "https://duckduckgo.com/?q=", "q"),
    Brave("Brave Search", "https://search.brave.com/search?q=", "q"),
    Google("Google", "https://www.google.com/search?q=", "q");

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
    var frostedToolbar by mutableStateOf(false)
        private set

    /** Present as mobile Chrome rather than an Android WebView (sign-in compatibility). */
    var identifyAsChrome by mutableStateOf(true)
        private set

    /** Floating toolbar position: 0 = bottom, 1 = top of the available area. */
    var toolbarPosition by mutableFloatStateOf(0f)
        private set

    var autoUpdateLists by mutableStateOf(true)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        darkWebPages = prefs.getBoolean("dark_web_pages", true)
        forceDarkAllSites = prefs.getBoolean("force_dark_all_sites", false)
        frostedToolbar = prefs.getBoolean("frosted_toolbar", false)
        identifyAsChrome = prefs.getBoolean("identify_as_chrome", true)
        searchEngine = runCatching { SearchEngine.valueOf(prefs.getString("search_engine", null)!!) }
            .getOrDefault(SearchEngine.Startpage)
        toolbarPosition = prefs.getFloat("toolbar_position", 0f)
        autoUpdateLists = prefs.getBoolean("auto_update_lists", true)
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

    fun updateFrostedToolbar(value: Boolean) {
        frostedToolbar = value
        prefs.edit { putBoolean("frosted_toolbar", value) }
    }

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

    fun updateAutoUpdateLists(value: Boolean) {
        autoUpdateLists = value
        prefs.edit { putBoolean("auto_update_lists", value) }
    }
}
