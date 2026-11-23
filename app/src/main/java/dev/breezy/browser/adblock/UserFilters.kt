package dev.breezy.browser.adblock

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * The user's own element-hiding rules ("My filters"), created with the element picker.
 *
 * Rules use cosmetic filter syntax, `host##selector`. They're merged into the cosmetic resources the
 * page script receives, so they apply instantly with no engine rebuild. A rule for `example.com`
 * also applies to its subdomains (`m.example.com`).
 */
object UserFilters {
    data class Rule(val host: String, val selector: String) {
        val text: String get() = "$host##$selector"
    }

    private lateinit var prefs: SharedPreferences
    @Volatile private var byHost: Map<String, List<String>> = emptyMap()

    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("user_filters", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("rules", "[]")) }.getOrDefault(JSONArray())
        publish(List(arr.length()) { arr.getString(it) }.mapNotNull(::parse))
    }

    fun parse(text: String): Rule? {
        val i = text.indexOf("##")
        if (i <= 0) return null
        val host = text.substring(0, i).trim().lowercase().removePrefix("www.")
        val selector = text.substring(i + 2).trim()
        if (host.isEmpty() || selector.isEmpty() || host.contains('/') || selector.contains('\n')) return null
        return Rule(host, selector)
    }

    fun add(host: String, selector: String) {
        val rule = Rule(host.lowercase().removePrefix("www."), selector.trim())
        if (rule.selector.isEmpty() || rule in _rules.value) return
        save(_rules.value + rule)
    }

    fun remove(rule: Rule) = save(_rules.value - rule)

    /** Selectors that apply to [host] (exact host or any parent domain). */
    fun selectorsFor(host: String?): List<String> {
        if (host.isNullOrEmpty()) return emptyList()
        val map = byHost
        if (map.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var h = host.lowercase().removePrefix("www.")
        while (true) {
            map[h]?.let(out::addAll)
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) break // stop at the registrable domain
            h = h.substring(dot + 1)
        }
        return out
    }

    private fun save(list: List<Rule>) {
        prefs.edit { putString("rules", JSONArray(list.map { it.text }).toString()) }
        publish(list)
    }

    private fun publish(list: List<Rule>) {
        byHost = list.groupBy({ it.host }, { it.selector })
        _rules.value = list
    }
}
