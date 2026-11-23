package dev.breezy.browser.downloads

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal HLS support: enough to download a stream as one playable file.
 *  - Master playlists list quality [Variant]s; media playlists list segments.
 *  - MPEG-TS segments are simply concatenated (TS is designed for that); fMP4 streams start with
 *    the EXT-X-MAP init segment, giving a fragmented MP4.
 *  - AES-128 encrypted segments are decrypted. SAMPLE-AES / DRM streams are rejected.
 */
object Hls {
    data class Variant(val url: String, val bandwidth: Long, val resolution: String?, val codecs: String?) {
        val label: String
            get() = listOfNotNull(resolution?.substringAfter('x')?.let { "${it}p" }, "%.1f Mbps".format(bandwidth / 1e6))
                .joinToString(" · ")
    }

    data class Key(val uri: String, val iv: ByteArray?)

    data class MediaPlaylist(
        val segments: List<String>,
        val keys: List<Key?>,
        val mediaSequence: Long,
        val initSegment: String?,
        val durationSeconds: Double,
    ) {
        /** fMP4 streams (with an init segment) become .mp4; MPEG-TS streams stay .ts. */
        val extension: String get() = if (initSegment != null) "mp4" else "ts"
        val mime: String get() = if (initSegment != null) "video/mp4" else "video/mp2t"
    }

    sealed interface Playlist
    data class Master(val variants: List<Variant>) : Playlist
    data class Media(val playlist: MediaPlaylist) : Playlist

    fun fetchText(url: String, headers: Map<String, String>): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        headers.forEach { (k, v) -> if (v.isNotEmpty()) conn.setRequestProperty(k, v) }
        try {
            HttpStatusException.check(conn)
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        headers.forEach { (k, v) -> if (v.isNotEmpty()) conn.setRequestProperty(k, v) }
        try {
            HttpStatusException.check(conn)
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun resolve(base: String, ref: String): String = URI(base).resolve(ref.trim()).toString()

    private fun attrs(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        val re = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
        re.findAll(line.substringAfter(':')).forEach { m -> out[m.groupValues[1]] = m.groupValues[2].trim('"') }
        return out
    }

    fun parse(url: String, text: String): Playlist {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull() != "#EXTM3U") throw IOException("Not an HLS playlist")
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val variants = ArrayList<Variant>()
            for ((i, line) in lines.withIndex()) {
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                val a = attrs(line)
                val next = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") } ?: continue
                variants += Variant(resolve(url, next), a["BANDWIDTH"]?.toLongOrNull() ?: 0, a["RESOLUTION"], a["CODECS"])
            }
            return Master(variants.sortedByDescending { it.bandwidth })
        }
        val segments = ArrayList<String>()
        val keys = ArrayList<Key?>()
        var key: Key? = null
        var sequence = 0L
        var init: String? = null
        var duration = 0.0
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") -> sequence = line.substringAfter(':').toLongOrNull() ?: 0
                line.startsWith("#EXT-X-MAP") -> init = attrs(line)["URI"]?.let { resolve(url, it) }
                line.startsWith("#EXT-X-KEY") -> {
                    val a = attrs(line)
                    key = when (a["METHOD"]) {
                        "NONE", null -> null
                        "AES-128" -> Key(resolve(url, a["URI"] ?: throw IOException("Key without URI")),
                            a["IV"]?.removePrefix("0x")?.removePrefix("0X")?.let(::hexToBytes))
                        else -> throw IOException("This stream is DRM-protected and can't be downloaded")
                    }
                }
                line.startsWith("#EXTINF") -> duration += line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                !line.startsWith("#") -> { segments += resolve(url, line); keys += key }
            }
        }
        if (segments.isEmpty()) throw IOException("Empty playlist")
        return Media(MediaPlaylist(segments, keys, sequence, init, duration))
    }

    fun fetchMedia(url: String, headers: Map<String, String>): MediaPlaylist =
        when (val p = parse(url, fetchText(url, headers))) {
            is Media -> p.playlist
            is Master -> fetchMedia(p.variants.firstOrNull()?.url ?: throw IOException("No variants"), headers)
        }

    private val keyCache = HashMap<String, ByteArray>()

    /** Download segment [index], decrypting it if the playlist uses AES-128. */
    fun fetchSegment(playlist: MediaPlaylist, index: Int, headers: Map<String, String>): ByteArray {
        val data = fetchBytes(playlist.segments[index], headers)
        val key = playlist.keys[index] ?: return data
        val keyBytes = synchronized(keyCache) { keyCache[key.uri] } ?: fetchBytes(key.uri, headers).also {
            synchronized(keyCache) { keyCache[key.uri] = it }
        }
        // Without an explicit IV, the segment's media sequence number is the IV (big-endian).
        val iv = key.iv ?: ByteArray(16).also { b ->
            var n = playlist.mediaSequence + index
            for (i in 15 downTo 8) { b[i] = (n and 0xff).toByte(); n = n shr 8 }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv.copyOf(16)))
        return cipher.doFinal(data)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.padStart(32, '0')
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
