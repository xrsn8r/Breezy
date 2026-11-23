package dev.breezy.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class SearchEngine(val label: String, val queryUrl: String, val queryParam: String) {
    Startpage("Startpage", "https://www.startpage.com/sp/search?query=", "query"),
    DuckDuckGo("DuckDuckGo", "https://duckduckgo.com/?q=", "q"),
    Brave("Brave Search", "https://search.brave.com/search?q=", "q"),
    Google("Google", "https://www.google.com/search?q=", "q");

    /** Host + path prefix used to recognise result pages, e.g. "www.startpage.com/sp/search". */
    val resultPrefix: String get() = queryUrl.substringAfter("://").substringBefore('?')
}

/** App-wide user preferences, observable from Compose. */
object AppSettings {
    private lateinit var prefs: SharedPreferences

    /** Render websites dark (prefers-color-scheme + algorithmic darkening). */
    var darkWebPages by mutableStateOf(true)
        private set

    var searchEngine by mutableStateOf(SearchEngine.Startpage)
        private set

    /** Floating toolbar position: 0 = bottom, 1 = top of the available area. */
    var toolbarPosition by mutableFloatStateOf(0f)
        private set

    var autoUpdateLists by mutableStateOf(true)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        darkWebPages = prefs.getBoolean("dark_web_pages", true)
        searchEngine = runCatching { SearchEngine.valueOf(prefs.getString("search_engine", null)!!) }
            .getOrDefault(SearchEngine.Startpage)
        toolbarPosition = prefs.getFloat("toolbar_position", 0f)
        autoUpdateLists = prefs.getBoolean("auto_update_lists", true)
    }

    fun updateDarkWebPages(value: Boolean) {
        darkWebPages = value
        prefs.edit { putBoolean("dark_web_pages", value) }
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
