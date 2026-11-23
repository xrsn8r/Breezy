package dev.breezy.browser.adblock

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the adblock engine lifecycle and the user's shield preferences.
 *
 * Startup is fast: the engine is deserialized from either the on-device cache (after a list
 * update) or the precompiled `assets/adblock/engine.dat` produced at build time.
 */
object Shield {
    private const val TAG = "BreezyShield"
    private const val PREFS = "shield"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ALLOWLIST = "allowlist"
    private const val KEY_UPDATED = "lists_updated_at"
    private const val KEY_TOTAL = "total_blocked"

    /** Lists fetched by "Update filter lists". Keep in sync with app/build.gradle.kts. */
    private val LIST_URLS = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
        "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-unbreak.txt",
    )
    private const val RESOURCES_URL =
        "https://raw.githubusercontent.com/brave/adblock-resources/master/dist/resources.json"

    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File

    @Volatile var ready = false
        private set

    @Volatile var enabled = true
        private set

    @Volatile private var allowlist: Set<String> = emptySet()

    private val totalBlocked = AtomicLong()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        val ready: Boolean = false,
        val enabled: Boolean = true,
        val allowlist: Set<String> = emptySet(),
        val updating: Boolean = false,
        val listsUpdatedAt: Long = 0,
        val totalBlocked: Long = 0,
        val lastError: String? = null,
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        dir = File(context.filesDir, "adblock").apply { mkdirs() }
        enabled = prefs.getBoolean(KEY_ENABLED, true)
        allowlist = prefs.getStringSet(KEY_ALLOWLIST, emptySet())!!.toSet()
        totalBlocked.set(prefs.getLong(KEY_TOTAL, 0))
        publish()
    }

    /** Load the engine. Call once from a background thread at app start. */
    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val cached = File(dir, "engine.dat")
        var ok = cached.isFile && NativeShield.loadSerialized(cached.readBytes())
        if (!ok) {
            ok = runCatching {
                context.assets.open("adblock/engine.dat").use { NativeShield.loadSerialized(it.readBytes()) }
            }.getOrDefault(false)
        }
        if (ok) loadResources(context)
        ready = ok
        Log.i(TAG, "engine loaded=$ok in ${(System.nanoTime() - started) / 1_000_000} ms")
        publish()
    }

    private fun loadResources(context: Context) {
        val updated = File(dir, "resources.json")
        val bytes = if (updated.isFile) updated.readBytes()
        else runCatching { context.assets.open("adblock/resources.json").use { it.readBytes() } }.getOrNull()
        if (bytes != null) NativeShield.loadResources(bytes)
    }

    /** Download fresh filter lists, compile them on-device and swap the engine in. */
    suspend fun updateLists(context: Context): Boolean = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(updating = true, lastError = null)
        val result = runCatching {
            val lists = LIST_URLS.map { download(it) }
            val resources = download(RESOURCES_URL)
            check(NativeShield.compileLists(lists.toTypedArray())) { "compile failed" }
            File(dir, "resources.json").writeBytes(resources)
            NativeShield.loadResources(resources)
            val tmp = File(dir, "engine.dat.tmp")
            tmp.writeBytes(NativeShield.serialize())
            tmp.renameTo(File(dir, "engine.dat"))
            prefs.edit { putLong(KEY_UPDATED, System.currentTimeMillis()) }
            ready = true
        }
        result.exceptionOrNull()?.let { Log.w(TAG, "list update failed", it) }
        _state.value = _state.value.copy(updating = false, lastError = result.exceptionOrNull()?.message)
        publish()
        result.isSuccess
    }

    private fun download(url: String): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode} for $url" }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        prefs.edit { putBoolean(KEY_ENABLED, value) }
        publish()
    }

    /** Whether shields apply on this host (global toggle and per-site allowlist). */
    fun activeFor(host: String?): Boolean = ready && enabled && (host == null || host !in allowlist)

    fun setSiteAllowed(host: String, allowed: Boolean) {
        allowlist = if (allowed) allowlist + host else allowlist - host
        prefs.edit { putStringSet(KEY_ALLOWLIST, allowlist) }
        publish()
    }

    fun recordBlocked() {
        val n = totalBlocked.incrementAndGet()
        // Persist occasionally rather than on every hit.
        if (n % 25 == 0L) prefs.edit { putLong(KEY_TOTAL, n) }
    }

    fun flush() {
        if (::prefs.isInitialized) prefs.edit { putLong(KEY_TOTAL, totalBlocked.get()) }
        publish()
    }

    private fun publish() {
        _state.value = _state.value.copy(
            ready = ready,
            enabled = enabled,
            allowlist = allowlist,
            listsUpdatedAt = prefs.getLong(KEY_UPDATED, 0),
            totalBlocked = totalBlocked.get(),
        )
    }

    /** Response used in place of a blocked request (empty body). */
    fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Turn a `data:<mime>;base64,<payload>` redirect into a response. */
    fun redirectResponse(dataUrl: String): WebResourceResponse {
        val comma = dataUrl.indexOf(',')
        val meta = dataUrl.substring(5, comma.coerceAtLeast(5))
        val mime = meta.substringBefore(';').ifEmpty { "text/plain" }
        val payload = if (comma >= 0) dataUrl.substring(comma + 1) else ""
        val body = if (meta.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT) else payload.toByteArray()
        return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body))
    }
}
