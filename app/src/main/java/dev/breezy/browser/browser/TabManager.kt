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
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The most recently closed tab, kept alive briefly so "Undo" can restore it. */
    data class Closed(val tab: BrowserTab, val index: Int, val at: Long)
    var recentlyClosed by mutableStateOf<Closed?>(null)
        private set

    /** Pending site-permission question for the UI (camera / microphone / location). */
    data class PermissionPrompt(val host: String, val kinds: List<String>, val onResult: (Boolean, Boolean) -> Unit)
    var permissionPrompt by mutableStateOf<PermissionPrompt?>(null)

    fun newTab(url: String? = null, select: Boolean = true, private: Boolean = false): BrowserTab {
        val tab = BrowserTab(context, host, isPrivate = private && BrowserTab.multiProfileSupported)
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

    /** Close [tab] but keep it for a few seconds so it can be restored with [undoClose]. */
    fun closeWithUndo(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        recentlyClosed?.tab?.takeIf { it !== tab }?.destroy() // only one undo slot
        tabs.removeAt(index)
        if (current === tab) {
            current = null
            tabs.getOrNull(index.coerceAtMost(tabs.size - 1))?.let { select(it) }
        }
        tab.onPause()
        val closed = Closed(tab, index, System.currentTimeMillis())
        recentlyClosed = closed
        main.postDelayed({
            if (recentlyClosed === closed) {
                recentlyClosed = null
                tab.destroy()
            }
        }, UNDO_WINDOW_MS)
    }

    fun undoClose() {
        val closed = recentlyClosed ?: return
        recentlyClosed = null
        tabs.add(closed.index.coerceIn(0, tabs.size), closed.tab)
        select(closed.tab)
    }

    /** Drop the undo slot now (e.g. the snackbar was dismissed). */
    fun finalizeClose() {
        val closed = recentlyClosed ?: return
        recentlyClosed = null
        closed.tab.destroy()
    }

    fun closeAll() {
        recentlyClosed?.tab?.destroy()
        recentlyClosed = null
        tabs.toList().forEach { it.destroy() }
        tabs.clear()
        current = null
    }

    /** Rebuild every WebView (e.g. after toggling dark web pages), keeping each tab's URL. */
    fun recreateAll() {
        if (tabs.isEmpty()) return
        val selected = tabs.indexOf(current).coerceAtLeast(0)
        val urls = tabs.map { it.url }
        val privates = tabs.map { it.isPrivate }
        closeAll()
        urls.forEachIndexed { i, url ->
            val tab = BrowserTab(context, host, isPrivate = privates[i])
            tabs.add(tab)
            if (url.isNotEmpty()) tab.deferLoad(url)
            if (i != selected) tab.onPause()
        }
        select(tabs[selected])
    }

    fun save() {
        val urls = JSONArray()
        tabs.filter { !it.isPrivate }.forEach { urls.put(it.url) }
        prefs.edit {
            putString("urls", urls.toString())
            putInt("current", tabs.filter { !it.isPrivate }.indexOf(current))
        }
    }

    fun restore(): Boolean {
        val json = prefs.getString("urls", null) ?: return false
        val urls = runCatching { JSONArray(json) }.getOrNull() ?: return false
        val selected = prefs.getInt("current", 0)
        var selectTab: BrowserTab? = null
        for (i in 0 until urls.length()) {
            val url = urls.optString(i)
            if (url.isEmpty()) continue // blank tabs aren't kept: the home page isn't a tab
            val tab = BrowserTab(context, host)
            tabs.add(tab)
            tab.deferLoad(url)
            tab.onPause()
            if (i == selected) selectTab = tab
        }
        if (tabs.isEmpty()) return false
        select(selectTab ?: tabs.last())
        return true
    }

    companion object {
        const val UNDO_WINDOW_MS = 6_000L
    }
}
