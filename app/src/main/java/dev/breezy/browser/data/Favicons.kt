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
        val current = memory[key]
        if (current != null && current.width >= icon.width) return
        memory[key] = icon
        missing.remove(key)
        runCatching { file(key).outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        version++
    }

    /** The saved icon for [url]'s site, fetching it from the site once if there's none. */
    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val key = keyOf(url) ?: return@withContext null
        memory[key]?.let { return@withContext it }
        file(key).takeIf { it.exists() }?.let { f ->
            BitmapFactory.decodeFile(f.path)?.let { memory[key] = it; return@withContext it }
        }
        if (!missing.add(key)) return@withContext null // already tried this session
        val scheme = if (url.startsWith("http://")) "http" else "https"
        val fetched = listOf("/apple-touch-icon.png", "/favicon.ico").firstNotNullOfOrNull { path ->
            runCatching { fetch("$scheme://${url.toUri().host}$path") }.getOrNull()
        } ?: return@withContext null
        memory[key] = fetched
        runCatching { file(key).outputStream().use { fetched.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        missing.remove(key)
        fetched
    }

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
