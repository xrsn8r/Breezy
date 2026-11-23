package dev.breezy.browser.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray

/** Holds all open tabs. Activity-scoped (the activity handles config changes itself). */
class TabManager(private val context: Context, private val host: TabHost) {
    val tabs = mutableStateListOf<BrowserTab>()
    var current by mutableStateOf<BrowserTab?>(null)
        private set

    private val prefs = context.getSharedPreferences("tabs", Context.MODE_PRIVATE)

    fun newTab(url: String? = null, select: Boolean = true): BrowserTab {
        val tab = BrowserTab(context, host)
        add(tab, select)
        if (!url.isNullOrEmpty()) tab.load(url)
        return tab
    }

    fun add(tab: BrowserTab, select: Boolean = true) {
        val index = current?.let { tabs.indexOf(it) + 1 } ?: tabs.size
        tabs.add(index.coerceIn(0, tabs.size), tab)
        if (select) select(tab)
    }

    fun select(tab: BrowserTab) {
        if (current === tab) return
        current?.onPause()
        current = tab
        tab.onResume()
    }

    fun close(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        if (current === tab) {
            current = null
            tabs.getOrNull(index.coerceAtMost(tabs.size - 1))?.let { select(it) }
        }
        tab.destroy()
    }

    fun closeAll() {
        tabs.toList().forEach { it.destroy() }
        tabs.clear()
        current = null
    }

    /** Rebuild every WebView (e.g. after toggling dark web pages), keeping each tab's URL. */
    fun recreateAll() {
        if (tabs.isEmpty()) return
        val selected = tabs.indexOf(current).coerceAtLeast(0)
        val urls = tabs.map { it.url }
        closeAll()
        urls.forEachIndexed { i, url ->
            val tab = BrowserTab(context, host)
            tabs.add(tab)
            if (url.isNotEmpty()) tab.deferLoad(url)
            if (i != selected) tab.onPause()
        }
        select(tabs[selected])
    }

    fun save() {
        val urls = JSONArray()
        tabs.forEach { urls.put(it.url) }
        prefs.edit {
            putString("urls", urls.toString())
            putInt("current", tabs.indexOf(current))
        }
    }

    fun restore(): Boolean {
        val json = prefs.getString("urls", null) ?: return false
        val urls = runCatching { JSONArray(json) }.getOrNull() ?: return false
        if (urls.length() == 0) return false
        val selected = prefs.getInt("current", 0)
        for (i in 0 until urls.length()) {
            val tab = BrowserTab(context, host)
            tabs.add(tab)
            urls.optString(i).takeIf { it.isNotEmpty() }?.let(tab::deferLoad)
            tab.onPause()
        }
        select(tabs.getOrNull(selected) ?: tabs.first())
        return true
    }
}
