package dev.breezy.browser.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Tri-state permission decision for a site. */
enum class SitePermission { Ask, Allow, Block }

/** Per-site overrides shown in the Shields sheet. Defaults mean "use the global behaviour". */
data class SiteConfig(
    val javascript: Boolean = true,
    val cookies: Boolean = true,
    val camera: SitePermission = SitePermission.Ask,
    val microphone: SitePermission = SitePermission.Ask,
    val location: SitePermission = SitePermission.Ask,
    /** Text zoom percent, or null for the global default. */
    val textZoom: Int? = null,
) {
    val isDefault: Boolean get() = this == SiteConfig()

    fun toJson(): JSONObject = JSONObject()
        .put("js", javascript).put("cookies", cookies)
        .put("camera", camera.name).put("mic", microphone.name).put("location", location.name)
        .apply { textZoom?.let { put("zoom", it) } }

    companion object {
        fun fromJson(o: JSONObject) = SiteConfig(
            javascript = o.optBoolean("js", true),
            cookies = o.optBoolean("cookies", true),
            camera = perm(o.optString("camera")),
            microphone = perm(o.optString("mic")),
            location = perm(o.optString("location")),
            textZoom = if (o.has("zoom")) o.getInt("zoom") else null,
        )

        private fun perm(s: String) = runCatching { SitePermission.valueOf(s) }.getOrDefault(SitePermission.Ask)
    }
}

object SiteSettings {
    private lateinit var prefs: SharedPreferences
    private val _all = MutableStateFlow<Map<String, SiteConfig>>(emptyMap())
    val all: StateFlow<Map<String, SiteConfig>> = _all.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("site_settings", Context.MODE_PRIVATE)
        _all.value = prefs.all.mapNotNull { (host, v) ->
            runCatching { host to SiteConfig.fromJson(JSONObject(v as String)) }.getOrNull()
        }.toMap()
    }

    private fun key(host: String) = host.lowercase().removePrefix("www.")

    fun get(host: String?): SiteConfig = host?.let { _all.value[key(it)] } ?: SiteConfig()

    fun update(host: String, transform: (SiteConfig) -> SiteConfig) {
        val k = key(host)
        val next = transform(get(k))
        prefs.edit { if (next.isDefault) remove(k) else putString(k, next.toJson().toString()) }
        _all.value = if (next.isDefault) _all.value - k else _all.value + (k to next)
    }

    fun reset(host: String) = update(host) { SiteConfig() }
}
