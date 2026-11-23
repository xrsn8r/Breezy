package dev.breezy.browser.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Shield statistics: blocked requests per day and estimated data saved. The data figure uses
 * typical transfer sizes per resource type (ad scripts and images dominate), so it's an estimate.
 */
object Stats {
    data class Day(val date: LocalDate, val blocked: Long, val bytes: Long)
    data class Snapshot(val days: List<Day>, val totalBlocked: Long, val totalBytes: Long)

    private lateinit var prefs: SharedPreferences
    private val pending = ConcurrentHashMap<String, LongArray>() // date -> [count, bytes]
    private val totalBytes = AtomicLong()
    private val _snapshot = MutableStateFlow(Snapshot(emptyList(), 0, 0))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("shield_stats", Context.MODE_PRIVATE)
        totalBytes.set(prefs.getLong("total_bytes", 0))
        publish()
    }

    /** Typical size of a blocked resource of [type] (bytes). */
    fun estimate(type: String): Long = when (type) {
        "script" -> 28_000
        "image" -> 14_000
        "media" -> 250_000
        "subdocument" -> 40_000
        "stylesheet" -> 9_000
        "font" -> 30_000
        "xmlhttprequest" -> 3_000
        else -> 2_000
    }

    /** Called from WebView IO threads. */
    fun record(type: String) {
        val day = LocalDate.now().toString()
        val bytes = estimate(type)
        pending.compute(day) { _, v -> (v ?: LongArray(2)).also { it[0]++; it[1] += bytes } }
        totalBytes.addAndGet(bytes)
    }

    /** Persist pending counts (on pause and when the stats UI opens). */
    fun flush() {
        if (!::prefs.isInitialized) return
        val days = JSONObject(prefs.getString("days", "{}")!!)
        for (key in pending.keys.toList()) {
            val v = pending.remove(key) ?: continue
            val cur = days.optJSONArray(key)
            val count = (cur?.optLong(0) ?: 0) + v[0]
            val bytes = (cur?.optLong(1) ?: 0) + v[1]
            days.put(key, org.json.JSONArray().put(count).put(bytes))
        }
        // Keep 90 days.
        val cutoff = LocalDate.now().minusDays(90).toString()
        days.keys().asSequence().toList().filter { it < cutoff }.forEach(days::remove)
        prefs.edit { putString("days", days.toString()); putLong("total_bytes", totalBytes.get()) }
        publish()
    }

    private fun publish() {
        val days = JSONObject(prefs.getString("days", "{}")!!)
        val today = LocalDate.now()
        val list = (13 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val arr = days.optJSONArray(d.toString())
            val p = pending[d.toString()]
            Day(d, (arr?.optLong(0) ?: 0) + (p?.get(0) ?: 0), (arr?.optLong(1) ?: 0) + (p?.get(1) ?: 0))
        }
        val totalBlocked = days.keys().asSequence().sumOf { days.getJSONArray(it).optLong(0) } +
            pending.values.sumOf { it[0] }
        _snapshot.value = Snapshot(list, totalBlocked, totalBytes.get())
    }

    fun clear() {
        pending.clear(); totalBytes.set(0)
        prefs.edit { remove("days"); remove("total_bytes") }
        publish()
    }
}
