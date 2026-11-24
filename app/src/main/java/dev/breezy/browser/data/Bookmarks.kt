package dev.breezy.browser.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved page. [folder] is "" for the top level. */
data class Bookmark(val id: Long, val title: String, val url: String, val folder: String = "", val created: Long = System.currentTimeMillis()) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("url", url).put("folder", folder).put("created", created)

    companion object {
        fun fromJson(o: JSONObject) = Bookmark(
            id = o.optLong("id", System.nanoTime()), title = o.optString("title"), url = o.getString("url"),
            folder = o.optString("folder"), created = o.optLong("created", System.currentTimeMillis()),
        )
    }
}

/** Bookmarks, kept in files/bookmarks.json (small, and easy to back up). */
object Bookmarks {
    private lateinit var file: File
    private val _all = MutableStateFlow<List<Bookmark>>(emptyList())
    val all: StateFlow<List<Bookmark>> = _all.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "bookmarks.json")
        _all.value = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    fun parse(json: String): List<Bookmark> {
        val arr = JSONArray(json)
        return List(arr.length()) { Bookmark.fromJson(arr.getJSONObject(it)) }
    }

    fun toJson(list: List<Bookmark> = _all.value): JSONArray = JSONArray().apply { list.forEach { put(it.toJson()) } }

    private fun save(list: List<Bookmark>) {
        _all.value = list
        runCatching { file.writeText(toJson(list).toString()) }
    }

    val folders: List<String> get() = _all.value.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()

    fun find(url: String): Bookmark? = _all.value.firstOrNull { same(it.url, url) }

    /** Treat http/https and a trailing slash as the same page. */
    private fun same(a: String, b: String): Boolean {
        fun norm(u: String) = u.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return norm(a) == norm(b)
    }

    fun add(title: String, url: String, folder: String = ""): Bookmark {
        find(url)?.let { return it }
        // Unique even when two are added in the same millisecond (ids key the bookmark list).
        val id = maxOf(System.currentTimeMillis(), (_all.value.maxOfOrNull { it.id } ?: 0) + 1)
        val b = Bookmark(id, title.ifBlank { url }, url, folder.trim())
        save(_all.value + b)
        return b
    }

    fun update(b: Bookmark) = save(_all.value.map { if (it.id == b.id) b.copy(folder = b.folder.trim()) else it })

    fun remove(b: Bookmark) = save(_all.value.filterNot { it.id == b.id })

    fun search(query: String, limit: Int = 3): List<Bookmark> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return _all.value.filter { it.title.contains(q, true) || it.url.contains(q, true) }.take(limit)
    }

    /** Replace everything (backup restore). */
    fun replaceAll(list: List<Bookmark>) {
        // A hand-edited or merged backup could repeat ids; give duplicates fresh ones.
        val seen = HashSet<Long>()
        var next = (list.maxOfOrNull { it.id } ?: 0) + 1
        save(list.map { if (seen.add(it.id)) it else it.copy(id = next++).also { c -> seen.add(c.id) } })
    }
}
