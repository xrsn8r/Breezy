package dev.breezy.browser.data

import dev.breezy.browser.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Community skip segments from the SponsorBlock API (sponsor.ajay.app).
 *
 * Uses the privacy-preserving lookup: only the first 4 hex characters of the SHA-256 of the video
 * id are sent, and the matching video is picked out of the response locally, so the server
 * can't tell which video you're watching.
 */
object SponsorBlock {
    /** Category id to label, in the order shown in Settings. */
    val categories = linkedMapOf(
        "sponsor" to "Sponsors",
        "selfpromo" to "Self-promotion",
        "interaction" to "Subscribe reminders",
        "intro" to "Intros",
        "outro" to "End cards and credits",
        "preview" to "Previews and recaps",
        "filler" to "Tangents and jokes",
        "music_offtopic" to "Non-music in music videos",
    )
    val defaultCategories = setOf("sponsor", "selfpromo", "interaction")

    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    /** Segments for [videoId] as a JSON array of `{start, end, category, uuid, duration}`. */
    fun segments(videoId: String): String {
        val cats = AppSettings.sponsorCategories
        val key = videoId + cats.sorted().joinToString(",")
        cache[key]?.let { (at, json) -> if (System.currentTimeMillis() - at < 30 * 60_000) return json }
        val hash = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray()).joinToString("") { "%02x".format(it) }
        val catParam = URLEncoder.encode(JSONArray(cats.toList()).toString(), "UTF-8")
        val conn = URI("https://sponsor.ajay.app/api/skipSegments/${hash.take(4)}?categories=$catParam&actionType=skip")
            .toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Breezy")
        val out = JSONArray()
        try {
            if (conn.responseCode == 200) {
                val all = JSONArray(conn.inputStream.use { it.readBytes().decodeToString() })
                for (i in 0 until all.length()) {
                    val video = all.getJSONObject(i)
                    if (video.optString("videoID") != videoId) continue
                    val segs = video.getJSONArray("segments")
                    for (j in 0 until segs.length()) {
                        val s = segs.getJSONObject(j)
                        if (s.optString("actionType", "skip") != "skip") continue
                        val range = s.getJSONArray("segment")
                        out.put(JSONObject()
                            .put("start", range.getDouble(0)).put("end", range.getDouble(1))
                            .put("category", s.optString("category")).put("uuid", s.optString("UUID"))
                            .put("duration", s.optDouble("videoDuration", 0.0)))
                    }
                }
            } // 404 = no segments for any video with this prefix
        } finally {
            conn.disconnect()
        }
        return out.toString().also { cache[key] = System.currentTimeMillis() to it }
    }
}
