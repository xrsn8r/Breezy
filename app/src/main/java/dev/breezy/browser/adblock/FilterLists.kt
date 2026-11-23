package dev.breezy.browser.adblock

import org.json.JSONArray
import org.json.JSONObject

/** A filter list the engine can be built from. */
data class FilterList(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val category: Category,
    val defaultEnabled: Boolean = false,
    val custom: Boolean = false,
) {
    enum class Category(val label: String) {
        Ads("Ads"),
        Privacy("Privacy"),
        Annoyances("Annoyances"),
        Security("Security"),
        Fixes("Fixes"),
        Custom("Custom"),
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url)

    companion object {
        fun customFromJson(o: JSONObject) = FilterList(
            id = o.getString("id"),
            name = o.optString("name").ifEmpty { o.getString("url") },
            description = o.getString("url"),
            url = o.getString("url"),
            category = Category.Custom,
            custom = true,
        )

        fun customListToJson(lists: List<FilterList>): String =
            JSONArray().apply { lists.forEach { put(it.toJson()) } }.toString()

        fun customListFromJson(json: String?): List<FilterList> = runCatching {
            val arr = JSONArray(json ?: "[]")
            List(arr.length()) { customFromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

/**
 * Built-in catalog. The `defaultEnabled` set must match the lists compiled into the bundled
 * engine in app/build.gradle.kts, so first launch needs no download.
 */
object FilterCatalog {
    private const val UBO = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters"

    val builtIn = listOf(
        FilterList("easylist", "EasyList", "The primary ad-blocking list", "https://easylist.to/easylist/easylist.txt",
            FilterList.Category.Ads, defaultEnabled = true),
        FilterList("ublock-filters", "uBlock filters", "uBlock Origin's ad and anti-adblock rules", "$UBO/filters.txt",
            FilterList.Category.Ads, defaultEnabled = true),
        FilterList("adguard-mobile", "AdGuard Mobile Ads", "Ads specific to mobile sites", "https://filters.adtidy.org/extension/ublock/filters/11.txt",
            FilterList.Category.Ads),
        FilterList("peter-lowe", "Peter Lowe's list", "Ad and tracking servers (hosts)", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
            FilterList.Category.Ads),
        FilterList("easyprivacy", "EasyPrivacy", "Trackers and analytics", "https://easylist.to/easylist/easyprivacy.txt",
            FilterList.Category.Privacy, defaultEnabled = true),
        FilterList("ublock-privacy", "uBlock privacy", "uBlock Origin's tracker rules", "$UBO/privacy.txt",
            FilterList.Category.Privacy, defaultEnabled = true),
        FilterList("hide-yt-shorts", "Hide YouTube Shorts", "Removes Shorts from YouTube feeds, search and navigation",
            "https://raw.githubusercontent.com/gijsdev/ublock-hide-yt-shorts/master/list.txt",
            FilterList.Category.Annoyances, defaultEnabled = true),
        FilterList("ublock-cookies", "uBlock cookie notices", "Hide cookie consent banners", "$UBO/annoyances-cookies.txt",
            FilterList.Category.Annoyances),
        FilterList("easylist-cookie", "EasyList Cookie", "Cookie banners and GDPR overlays", "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
            FilterList.Category.Annoyances),
        FilterList("ublock-annoyances", "uBlock annoyances", "Pop-ups, newsletter prompts, app banners", "$UBO/annoyances-others.txt",
            FilterList.Category.Annoyances),
        FilterList("fanboy-social", "Fanboy Social", "Social share buttons and widgets", "https://easylist.to/easylist/fanboy-social.txt",
            FilterList.Category.Annoyances),
        FilterList("ublock-badware", "uBlock badware risks", "Scam and badware sites", "$UBO/badware.txt",
            FilterList.Category.Security),
        FilterList("urlhaus", "URLhaus malicious URLs", "Malware distribution sites (large)", "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-online.txt",
            FilterList.Category.Security),
        FilterList("brave-unbreak", "Brave unbreak", "Fixes sites broken by other lists", "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-unbreak.txt",
            FilterList.Category.Fixes, defaultEnabled = true),
        FilterList("ublock-unbreak", "uBlock unbreak", "uBlock Origin's site fixes", "$UBO/unbreak.txt",
            FilterList.Category.Fixes),
        FilterList("ublock-quick-fixes", "uBlock quick fixes", "Fast-moving fixes for popular sites", "$UBO/quick-fixes.txt",
            FilterList.Category.Fixes),
    )

    val defaultIds: Set<String> = builtIn.filter { it.defaultEnabled }.map { it.id }.toSet()

    /** uBlock Origin's and Brave's own lists may use "trusted" scriptlets (e.g. YouTube ad removal). */
    fun isTrusted(list: FilterList) = !list.custom && (list.id.startsWith("ublock-") || list.id.startsWith("brave-"))
}
