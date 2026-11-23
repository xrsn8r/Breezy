package dev.breezy.browser.adblock

import android.content.Context
import dev.breezy.browser.BuildConfig
import android.content.SharedPreferences
import android.net.ConnectivityManager
import androidx.core.net.toUri
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

    private const val KEY_LISTS_ENABLED = "lists_enabled"
    private const val KEY_LISTS_COMPILED = "lists_compiled"
    private const val KEY_CUSTOM = "custom_lists"
    private const val AUTO_UPDATE_INTERVAL = 7L * 24 * 60 * 60 * 1000

    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    private lateinit var listsDir: File

    @Volatile var ready = false
        private set

    @Volatile var enabled = true
        private set

    @Volatile private var allowlist: Set<String> = emptySet()

    /** Bumped whenever the engine or shield settings change, so cached per-site work is redone. */
    @Volatile var generation = 0
        private set

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
        val lists: List<FilterList> = FilterCatalog.builtIn,
        val enabledLists: Set<String> = FilterCatalog.defaultIds,
        val compiledLists: Set<String> = FilterCatalog.defaultIds,
        val listMeta: Map<String, ListMeta> = emptyMap(),
    ) {
        /** Selection differs from what the running engine was built from. */
        val pendingChanges: Boolean get() = enabledLists != compiledLists
    }

    data class ListMeta(val rules: Int, val fetchedAt: Long, val bytes: Long)

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        dir = File(context.filesDir, "adblock").apply { mkdirs() }
        listsDir = File(dir, "lists").apply { mkdirs() }
        File(dir, "resources.json").delete()
        enabled = prefs.getBoolean(KEY_ENABLED, true)
        allowlist = prefs.getStringSet(KEY_ALLOWLIST, emptySet())!!.toSet()
        totalBlocked.set(prefs.getLong(KEY_TOTAL, 0))
        publish()
    }

    /** Load the engine. Call once from a background thread at app start. */
    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        // 1. Engine from an on-device list update, 2. bundled engine (unpacked once per app
        //    version so later launches read it natively without touching the Java heap).
        val cached = File(dir, "engine.dat")
        var ok = cached.isFile && NativeShield.loadSerializedFile(cached.path)
        if (!ok) ok = runCatching { NativeShield.loadSerializedFile(bundledEngine(context).path) }.getOrDefault(false)
        if (ok) loadResources(context)
        ready = ok
        generation++
        Log.i(TAG, "engine loaded=$ok in ${(System.nanoTime() - started) / 1_000_000} ms")
        publish()
    }

    private fun bundledEngine(context: Context): File {
        val file = File(dir, "bundled-${BuildConfig.VERSION_CODE}.dat")
        if (!file.isFile) {
            dir.listFiles { f -> f.name.startsWith("bundled-") }?.forEach { it.delete() }
            val tmp = File(dir, "bundled.tmp")
            context.assets.open("adblock/engine.dat").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(file)
        }
        return file
    }

    /**
     * Scriptlet/redirect resources always come from the APK: they're Brave's resources merged with
     * uBlock Origin's scriptlets at build time. (0.2.x stored a Brave-only download in filesDir,
     * which lacked uBO's scriptlets; it's deleted in [init].)
     */
    private fun loadResources(context: Context) {
        val bytes = runCatching { context.assets.open("adblock/resources.json").use { it.readBytes() } }.getOrNull()
        if (bytes != null) NativeShield.loadResources(bytes)
    }

    // --- Filter lists ---------------------------------------------------------------------------

    private fun customLists(): List<FilterList> = FilterList.customListFromJson(prefs.getString(KEY_CUSTOM, null))
    private fun allLists(): List<FilterList> = FilterCatalog.builtIn + customLists()
    private fun enabledIds(): Set<String> = prefs.getStringSet(KEY_LISTS_ENABLED, FilterCatalog.defaultIds)!!.toSet()
    private fun compiledIds(): Set<String> = prefs.getStringSet(KEY_LISTS_COMPILED, FilterCatalog.defaultIds)!!.toSet()
    private fun listFile(id: String) = File(listsDir, "$id.txt")

    fun setListEnabled(id: String, on: Boolean) {
        val ids = if (on) enabledIds() + id else enabledIds() - id
        prefs.edit { putStringSet(KEY_LISTS_ENABLED, ids) }
        publish()
    }

    /** Download and validate a custom list, then enable it. Returns an error message or null. */
    suspend fun addCustomList(url: String, name: String): String? = withContext(Dispatchers.IO) {
        val clean = url.trim()
        // HTTPS only: a list fetched over plain HTTP could be tampered with in transit.
        if (!clean.startsWith("https://")) return@withContext "Enter an https:// URL"
        if (customLists().any { it.url == clean }) return@withContext "That list is already added"
        val bytes = runCatching { download(clean) }.getOrElse { return@withContext it.message ?: "Download failed" }
        val head = String(bytes, 0, minOf(bytes.size, 512)).trimStart()
        if (head.startsWith("<")) return@withContext "That URL returned a web page, not a filter list"
        val rules = countRules(bytes)
        if (rules == 0) return@withContext "No filter rules found at that URL"
        val id = "custom-" + Integer.toHexString(clean.hashCode())
        listFile(id).writeBytes(bytes)
        saveMeta(id, rules, bytes.size.toLong())
        val list = FilterList(id, name.trim().ifEmpty { clean.toUri().host ?: clean }, clean, clean,
            FilterList.Category.Custom, custom = true)
        prefs.edit {
            putString(KEY_CUSTOM, FilterList.customListToJson(customLists() + list))
            putStringSet(KEY_LISTS_ENABLED, enabledIds() + id)
        }
        publish()
        null
    }

    /**
     * Add a list from text already loaded in a tab ("Add page as filter list"). Useful for lists
     * behind browser challenges (DDoS-Guard/Cloudflare) that plain downloads can't fetch; later
     * refreshes fall back to this cached copy if the download is refused.
     */
    suspend fun addCustomListFromText(url: String, name: String, text: String): String? = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray()
        if (text.trimStart().startsWith("<")) return@withContext "This page isn't a filter list"
        val rules = countRules(bytes)
        if (rules < 1) return@withContext "No filter rules found on this page"
        val id = "custom-" + Integer.toHexString(url.hashCode())
        listFile(id).writeBytes(bytes)
        saveMeta(id, rules, bytes.size.toLong())
        val title = Regex("^! *Title: *(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
        val list = FilterList(id, name.ifBlank { title ?: (url.toUri().host ?: url) }, url, url,
            FilterList.Category.Custom, custom = true)
        prefs.edit {
            putString(KEY_CUSTOM, FilterList.customListToJson(customLists().filter { it.id != id } + list))
            putStringSet(KEY_LISTS_ENABLED, enabledIds() + id)
        }
        publish()
        null
    }

    fun removeCustomList(id: String) {
        listFile(id).delete()
        prefs.edit {
            putString(KEY_CUSTOM, FilterList.customListToJson(customLists().filter { it.id != id }))
            putStringSet(KEY_LISTS_ENABLED, enabledIds() - id)
            remove("meta_$id")
        }
        publish()
    }

    /** Re-download every enabled list, then rebuild the engine. */
    suspend fun updateLists(context: Context): Boolean = rebuild(context, refresh = true)

    /** Rebuild the engine from the enabled lists, downloading only lists not cached yet. */
    suspend fun applyListChanges(context: Context): Boolean = rebuild(context, refresh = false)

    /** Weekly background refresh on unmetered networks. */
    suspend fun maybeAutoUpdate(context: Context, enabled: Boolean) {
        if (!enabled || _state.value.updating) return
        val last = prefs.getLong(KEY_UPDATED, 0)
        if (System.currentTimeMillis() - last < AUTO_UPDATE_INTERVAL) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null || cm.activeNetwork == null || cm.isActiveNetworkMetered) return
        updateLists(context)
    }

    private suspend fun rebuild(context: Context, refresh: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (_state.value.updating) return@withContext false
        _state.value = _state.value.copy(updating = true, lastError = null)
        val failed = mutableListOf<String>()
        val result = runCatching {
            val wanted = allLists().filter { it.id in enabledIds() }
            val texts = mutableListOf<ByteArray>()
            val trusted = mutableListOf<Boolean>()
            val included = mutableSetOf<String>()
            for (list in wanted) {
                val file = listFile(list.id)
                if (refresh || !file.isFile) {
                    runCatching { download(list.url) }
                        .onSuccess { file.writeBytes(it); saveMeta(list.id, countRules(it), it.size.toLong()) }
                        .onFailure { failed += list.name }
                }
                if (file.isFile) {
                    texts += file.readBytes()
                    trusted += FilterCatalog.isTrusted(list)
                    included += list.id
                }
            }
            check(texts.isNotEmpty()) { "No filter lists could be downloaded" }
            check(NativeShield.compileLists(texts.toTypedArray(), trusted.toBooleanArray())) { "compile failed" }
            loadResources(context)
            val tmp = File(dir, "engine.dat.tmp")
            tmp.writeBytes(NativeShield.serialize())
            tmp.renameTo(File(dir, "engine.dat"))
            prefs.edit {
                putStringSet(KEY_LISTS_COMPILED, included)
                if (refresh) putLong(KEY_UPDATED, System.currentTimeMillis())
            }
            ready = true
            generation++
        }
        result.exceptionOrNull()?.let { Log.w(TAG, "list rebuild failed", it) }
        val error = result.exceptionOrNull()?.message
            ?: failed.takeIf { it.isNotEmpty() }?.let { "Couldn't download: ${it.joinToString()}" }
        _state.value = _state.value.copy(updating = false, lastError = error)
        publish()
        result.isSuccess
    }

    /** Count non-comment, non-blank lines (approximate rule count). */
    private fun countRules(bytes: ByteArray): Int {
        var count = 0
        var atLineStart = true
        for (b in bytes) {
            val c = b.toInt()
            if (c == '\n'.code) { atLineStart = true; continue }
            if (!atLineStart || c == ' '.code || c == '\t'.code || c == '\r'.code) continue
            atLineStart = false
            if (c != '!'.code && c != '['.code) count++
        }
        return count
    }

    private fun saveMeta(id: String, rules: Int, bytes: Long) {
        prefs.edit { putString("meta_$id", "$rules|${System.currentTimeMillis()}|$bytes") }
    }

    private fun readMeta(): Map<String, ListMeta> = allLists().mapNotNull { list ->
        val parts = prefs.getString("meta_${list.id}", null)?.split('|') ?: return@mapNotNull null
        runCatching { list.id to ListMeta(parts[0].toInt(), parts[1].toLong(), parts[2].toLong()) }.getOrNull()
    }.toMap()

    private fun download(url: String): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        check(url.startsWith("https://")) { "Refusing non-HTTPS download" }
        conn.instanceFollowRedirects = true // HttpURLConnection never follows https -> http
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode} for $url" }
            check(conn.url.protocol == "https") { "Redirected away from HTTPS" }
            // Guard against absurdly large responses (largest known list is ~15 MB).
            val limit = 64L * 1024 * 1024
            check(conn.contentLengthLong <= limit) { "List too large" }
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    check(total <= limit) { "List too large" }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        generation++
        prefs.edit { putBoolean(KEY_ENABLED, value) }
        publish()
    }

    /** Whether shields apply on this host (global toggle and per-site allowlist). */
    fun activeFor(host: String?): Boolean = ready && enabled && (host == null || host !in allowlist)

    fun allowlistedSites(): Set<String> = allowlist

    fun setSiteAllowed(host: String, allowed: Boolean) {
        allowlist = if (allowed) allowlist + host else allowlist - host
        generation++
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
            lists = allLists(),
            enabledLists = enabledIds(),
            compiledLists = compiledIds(),
            listMeta = readMeta(),
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
