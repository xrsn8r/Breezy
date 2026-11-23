package dev.breezy.browser.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/** Deleting what sites stored: "forget this site" and "clear data on exit". */
object SiteData {

    /** Remove cookies, site storage, history and per-site settings for [host] and its subdomains. */
    suspend fun forget(host: String) {
        val cm = CookieManager.getInstance()
        val base = host.removePrefix("www.")
        val hosts = listOf(base, "www.$base", host).distinct()
        for (h in hosts) for (scheme in listOf("https", "http")) {
            val url = "$scheme://$h/"
            val cookies = cm.getCookie(url) ?: continue
            cookies.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.forEach { name ->
                // Expire the cookie for the host, the parent domain and the root path.
                for (domain in listOf(null, base, ".$base")) {
                    val attr = domain?.let { "; Domain=$it" }.orEmpty()
                    cm.setCookie(url, "$name=; Max-Age=0; Path=/$attr")
                }
            }
        }
        cm.flush()
        val storage = WebStorage.getInstance()
        for (h in hosts) for (scheme in listOf("https", "http")) storage.deleteOrigin("$scheme://$h")
        History.deleteHost(base)
        SiteSettings.reset(base)
    }

    /** Wipe all browsing data: cookies, storage, cache, history and form data. */
    suspend fun clearAll(context: Context) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        runCatching { WebView(context).apply { clearCache(true); clearFormData(); destroy() } }
        History.clear()
    }
}
