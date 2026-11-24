package dev.breezy.browser.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.breezy.browser.BuildConfig
import dev.breezy.browser.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup and restore to a JSON file the user picks.
 *
 * Included: settings and home shortcuts, per-site settings, Shields (enabled and custom filter
 * lists, sites with Shields down), element-picker rules, and bookmarks. Not included: history,
 * cookies and site data, open tabs.
 */
object Backup {
    private const val FORMAT = 1
    /** SharedPreferences files that make up the user's setup. */
    private val PREFS = listOf("settings", "site_settings", "shield", "user_filters")

    data class Summary(val created: Long, val version: String, val bookmarks: Int, val shortcuts: Int)

    suspend fun export(context: Context, target: Uri) = withContext(Dispatchers.IO) {
        val prefs = JSONObject()
        for (name in PREFS) prefs.put(name, dump(context.getSharedPreferences(name, Context.MODE_PRIVATE).all))
        val root = JSONObject()
            .put("breezy_backup", FORMAT)
            .put("version", BuildConfig.VERSION_NAME)
            .put("created", System.currentTimeMillis())
            .put("prefs", prefs)
            .put("bookmarks", Bookmarks.toJson())
        context.contentResolver.openOutputStream(target, "wt")!!.use { it.write(root.toString(2).toByteArray()) }
    }

    /** Read and check a backup without applying it. */
    suspend fun read(context: Context, source: Uri): Pair<JSONObject, Summary> = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(source)!!.use { input ->
            val bytes = input.readBytes()
            require(bytes.size < 20 * 1024 * 1024) { "File is too large" }
            bytes.decodeToString()
        }
        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("Not a Breezy backup") }
        require(root.optInt("breezy_backup") in 1..FORMAT) { "Not a Breezy backup (or from a newer version)" }
        val shortcuts = root.optJSONObject("prefs")?.optJSONObject("settings")?.optJSONObject("shortcuts")
            ?.optString("v")?.let { runCatching { JSONArray(it).length() }.getOrNull() } ?: 0
        root to Summary(
            created = root.optLong("created"),
            version = root.optString("version"),
            bookmarks = root.optJSONArray("bookmarks")?.length() ?: 0,
            shortcuts = shortcuts,
        )
    }

    /** Replace the current setup with [root], then restart Breezy so everything reloads. */
    suspend fun restore(context: Context, root: JSONObject) {
        withContext(Dispatchers.IO) {
            val prefs = root.optJSONObject("prefs") ?: JSONObject()
            for (name in PREFS) {
                val data = prefs.optJSONObject(name) ?: continue
                val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                for (key in data.keys()) {
                    val e = data.optJSONObject(key) ?: continue
                    when (e.optString("t")) {
                        "b" -> editor.putBoolean(key, e.optBoolean("v"))
                        "i" -> editor.putInt(key, e.optInt("v"))
                        "l" -> editor.putLong(key, e.optLong("v"))
                        "f" -> editor.putFloat(key, e.optDouble("v").toFloat())
                        "s" -> editor.putString(key, e.optString("v"))
                        "set" -> editor.putStringSet(key, e.optJSONArray("v")?.let { a -> List(a.length()) { a.getString(it) }.toSet() } ?: emptySet())
                    }
                }
                editor.commit()
            }
            root.optJSONArray("bookmarks")?.let { Bookmarks.replaceAll(Bookmarks.parse(it.toString())) }
        }
        val restart = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(restart)
        Runtime.getRuntime().exit(0)
    }

    private fun dump(all: Map<String, *>): JSONObject = JSONObject().apply {
        for ((k, v) in all) {
            val e = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            } ?: continue
            put(k, e)
        }
    }
}
