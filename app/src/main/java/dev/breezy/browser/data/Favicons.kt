package dev.breezy.browser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Site icons for the home page shortcuts, stored per host in files/favicons.
 *
 * Icons come from pages you visit (WebView hands them over as they load; the largest one seen is
 * kept). A shortcut to a site not visited since gets its icon fetched once from the site itself
 * (apple-touch-icon, then favicon.ico). Private tabs never save icons.
 */
object Favicons {
    private lateinit var dir: File
    private val memory = ConcurrentHashMap<String, Bitmap>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val upgradeTried = ConcurrentHashMap.newKeySet<String>()

    /** Icons smaller than this (px) get replaced by a sharper one from the site when possible. */
    private const val GOOD = 96

    /** Bumped whenever a new icon is saved, so the home page picks it up. */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        dir = File(context.filesDir, "favicons").apply { mkdirs() }
    }

    fun keyOf(url: String): String? =
        runCatching { url.toUri().host }.getOrNull()?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }

    private fun file(key: String) = File(dir, key.replace(Regex("[^a-z0-9.-]"), "_") + ".png")

    /** Remember [icon] for [pageUrl]'s site if it's bigger than what we have. Any thread. */
    fun save(pageUrl: String, icon: Bitmap) {
        val key = keyOf(pageUrl) ?: return
        val current = memory[key] ?: file(key).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.also { memory[key] = it }
        if (current != null && current.width >= icon.width) return
        memory[key] = icon
        missing.remove(key)
        runCatching { file(key).outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        version++
    }

    /** True when [pageUrl]'s site has no icon yet or only a small one, and we haven't tried this session. */
    fun wantsBetter(pageUrl: String): Boolean {
        val key = keyOf(pageUrl) ?: return false
        if (key in upgradeTried) return false
        val have = memory[key]?.width ?: file(key).takeIf { it.exists() }?.let { f ->
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }.outWidth
        } ?: 0
        return have < GOOD
    }

    /**
     * Try sharper icons for [pageUrl]'s site: the page's own `<link rel=icon|apple-touch-icon>`
     * [candidates] (url to declared size, biggest first) and the conventional apple-touch-icon.
     * Keeps the first one bigger than what we have. Runs once per site per session.
     */
    suspend fun upgrade(pageUrl: String, candidates: List<Pair<String, Int>>, manifestUrl: String? = null) = withContext(Dispatchers.IO) {
        val key = keyOf(pageUrl) ?: return@withContext
        if (!upgradeTried.add(key)) return@withContext
        val origin = runCatching { pageUrl.toUri().let { "${it.scheme}://${it.authority}" } }.getOrNull() ?: return@withContext
        val all = candidates + manifestIcons(manifestUrl ?: "$origin/manifest.webmanifest")
        // Prefer the smallest icon that's still sharp (>= 96px), then bigger ones; skip huge files.
        val ranked = all.filter { it.second in 0..512 }
            .sortedWith(compareBy<Pair<String, Int>> { if (it.second >= GOOD) 0 else 1 }.thenBy { if (it.second >= GOOD) it.second else -it.second })
        val urls = (ranked.map { it.first } +
            listOf("$origin/apple-touch-icon.png", "$origin/apple-touch-icon-precomposed.png"))
            .filter { (it.startsWith("https://") || it.startsWith("http://")) && !it.substringBefore('?').endsWith(".svg") }
            .distinct().take(8)
        val have = memory[key]?.width ?: 0
        for (u in urls) {
            val bmp = runCatching { fetch(u) }.getOrNull() ?: continue
            if (bmp.width > have && bmp.width >= 48) {
                memory[key] = bmp
                missing.remove(key)
                runCatching { file(key).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                version++
                return@withContext
            }
        }
    }

    /**
     * The saved icon for [url]'s site. With none, or only a small one, it looks once per session
     * for a sharper one on the site itself (manifest, apple-touch-icon), then favicon.ico.
     */
    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val key = keyOf(url) ?: return@withContext null
        val saved = memory[key] ?: file(key).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.also { memory[key] = it }
        if (saved != null && saved.width >= GOOD) return@withContext saved
        if (wantsBetter(url)) upgrade(url, emptyList())
        memory[key]?.let { return@withContext it }
        if (!missing.add(key)) return@withContext null // already tried this session
        val scheme = if (url.startsWith("http://")) "http" else "https"
        val ico = runCatching { fetch("$scheme://${url.toUri().host}/favicon.ico") }.getOrNull() ?: return@withContext null
        memory[key] = ico
        runCatching { file(key).outputStream().use { ico.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        missing.remove(key)
        version++
        ico
    }

    /** Icons from a web app manifest, minus monochrome ones (those are single-colour silhouettes). */
    private fun manifestIcons(manifestUrl: String): List<Pair<String, Int>> = runCatching {
        val conn = URI(manifestUrl).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000; conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Breezy")
        val body = try {
            if (conn.responseCode != 200) return@runCatching emptyList()
            conn.inputStream.use { it.readBytes() }.takeIf { it.size < 256 * 1024 }?.decodeToString() ?: return@runCatching emptyList()
        } finally { conn.disconnect() }
        val icons = org.json.JSONObject(body).optJSONArray("icons") ?: return@runCatching emptyList()
        val base = URI(manifestUrl)
        List(icons.length()) { icons.optJSONObject(it) }.mapNotNull { o ->
            if (o == null || "monochrome" in o.optString("purpose")) return@mapNotNull null
            val src = o.optString("src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val size = Regex("(\\d+)x\\d+").findAll(o.optString("sizes")).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
            base.resolve(src).toString() to size
        }
    }.getOrDefault(emptyList())

    private fun fetch(url: String): Bitmap? {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000; conn.readTimeout = 8_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Breezy")
        try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > 512 * 1024) return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width >= 16 && it.height >= 16 }
        } finally {
            conn.disconnect()
        }
    }
}
