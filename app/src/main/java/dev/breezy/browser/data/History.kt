package dev.breezy.browser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class HistoryEntry(val url: String, val title: String, val lastVisit: Long, val visits: Int)

/** Browsing history in a small SQLite table (one row per URL). Private tabs never write here. */
object History {
    private lateinit var db: Helper
    private val _version = MutableStateFlow(0)
    /** Bumped on every change so UIs can re-query. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        db = Helper(context)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL("CREATE TABLE history (url TEXT PRIMARY KEY, title TEXT NOT NULL, last_visit INTEGER NOT NULL, visits INTEGER NOT NULL)")
            d.execSQL("CREATE INDEX history_time ON history(last_visit)")
        }
        override fun onUpgrade(d: SQLiteDatabase, old: Int, new: Int) = Unit
    }

    suspend fun record(url: String, title: String) = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext
        val w = db.writableDatabase
        val now = System.currentTimeMillis()
        w.execSQL(
            "INSERT INTO history(url, title, last_visit, visits) VALUES(?, ?, ?, 1) " +
                "ON CONFLICT(url) DO UPDATE SET last_visit = excluded.last_visit, visits = visits + 1, " +
                "title = CASE WHEN excluded.title <> '' THEN excluded.title ELSE title END",
            arrayOf<Any>(url, title, now),
        )
        _version.value++
    }

    suspend fun updateTitle(url: String, title: String) = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext
        db.writableDatabase.update("history", ContentValues().apply { put("title", title) }, "url = ?", arrayOf(url))
        _version.value++
    }

    /** Most recent first; [query] matches title or URL. */
    suspend fun search(query: String, limit: Int = 200): List<HistoryEntry> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val (where, args) = if (q.isEmpty()) null to null
        else "url LIKE ? OR title LIKE ?" to arrayOf("%$q%", "%$q%")
        db.readableDatabase.query("history", null, where, args, null, null, "last_visit DESC", limit.toString()).use { c ->
            buildList {
                while (c.moveToNext()) add(HistoryEntry(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3)))
            }
        }
    }

    /** Address-bar suggestions: frequently and recently visited matches. */
    suspend fun suggest(query: String, limit: Int = 4): List<HistoryEntry> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        db.readableDatabase.query(
            "history", null, "url LIKE ? OR title LIKE ?", arrayOf("%$q%", "%$q%"),
            null, null, "visits DESC, last_visit DESC", limit.toString(),
        ).use { c ->
            buildList { while (c.moveToNext()) add(HistoryEntry(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3))) }
        }
    }

    suspend fun delete(url: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("history", "url = ?", arrayOf(url)); _version.value++
    }

    /** Remove every entry for a host (and its subdomains): "forget this site". */
    suspend fun deleteHost(host: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(
            "history", "url LIKE ? OR url LIKE ? OR url LIKE ? OR url LIKE ?",
            arrayOf("http://$host/%", "https://$host/%", "http://%.$host/%", "https://%.$host/%"),
        )
        _version.value++
    }

    suspend fun clear() = withContext(Dispatchers.IO) { db.writableDatabase.delete("history", null, null); _version.value++ }
}
