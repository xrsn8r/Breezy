package dev.breezy.browser.browser

import android.net.Uri
import android.util.Patterns
import dev.breezy.browser.AppSettings
import dev.breezy.browser.SearchEngine

object UrlInput {
    private val engine get() = AppSettings.searchEngine

    fun isSearch(url: String) = url.startsWith(engine.queryUrl)

    /** Turn omnibox text into a URL: explicit URLs, bare domains, or a search query. */
    fun resolve(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        // Only web URLs load directly; other schemes (file:, intent:, javascript:, ...) are searched.
        if (text.startsWith("https://", true) || text.startsWith("http://", true) || text == "about:blank") return text
        if (!text.contains(' ') && (Patterns.WEB_URL.matcher(text).matches() || text.startsWith("localhost"))) {
            return "https://$text"
        }
        return engine.queryUrl + Uri.encode(text)
    }

    /** Short, human display form of a URL for the toolbar pill. */
    fun display(url: String): String {
        if (url.isEmpty()) return ""
        val uri = Uri.parse(url)
        // Show the query instead of the host on any known engine's result page.
        val path = url.substringAfter("://")
        SearchEngine.entries.firstOrNull { path.startsWith(it.resultPrefix) }?.let { e ->
            runCatching { uri.getQueryParameter(e.queryParam) }.getOrNull()?.let { return it }
        }
        return uri.host?.removePrefix("www.") ?: url
    }

    fun isSecure(url: String) = url.startsWith("https://")
}
