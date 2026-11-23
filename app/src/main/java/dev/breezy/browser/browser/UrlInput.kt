package dev.breezy.browser.browser

import android.net.Uri
import android.util.Patterns

object UrlInput {
    const val SEARCH = "https://duckduckgo.com/?q="

    /** Turn omnibox text into a URL: explicit URLs, bare domains, or a search query. */
    fun resolve(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text) || text.startsWith("about:")) return text
        if (!text.contains(' ') && (Patterns.WEB_URL.matcher(text).matches() || text.startsWith("localhost"))) {
            return "https://$text"
        }
        return SEARCH + Uri.encode(text)
    }

    /** Short, human display form of a URL for the toolbar pill. */
    fun display(url: String): String {
        if (url.isEmpty()) return ""
        val uri = Uri.parse(url)
        if (url.startsWith(SEARCH.substringBefore('?'))) {
            uri.getQueryParameter("q")?.let { return it }
        }
        return uri.host?.removePrefix("www.") ?: url
    }

    fun isSecure(url: String) = url.startsWith("https://")
}
