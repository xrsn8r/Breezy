package dev.breezy.browser.downloads

import org.json.JSONArray
import org.json.JSONObject

enum class DownloadStatus { Queued, Running, Paused, Completed, Failed, Cancelled }

/** [File]: a single resource (multi-connection when the server supports ranges). [Hls]: an HLS
 *  media playlist whose segments are fetched in parallel and joined in order. */
enum class DownloadKind { File, Hls }

/** A byte range of a multi-connection download. [end] is inclusive (-1 = until EOF). */
data class Segment(val start: Long, val end: Long, val done: Long) {
    val length: Long get() = if (end < 0) -1 else end - start + 1
    val complete: Boolean get() = end >= 0 && done >= length
}

data class DownloadJob(
    val id: Long,
    val url: String,
    val fileName: String,
    val mime: String,
    val headers: Map<String, String>,
    val kind: DownloadKind = DownloadKind.File,
    val total: Long = -1,
    val downloaded: Long = 0,
    val status: DownloadStatus = DownloadStatus.Queued,
    val error: String? = null,
    val segments: List<Segment> = emptyList(),
    /** Where the file is being written (MediaStore or file:// URI). */
    val uri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Bytes per second (live only, not persisted). */
    val speed: Long = 0,
    /** HLS: number of media segments, and how many are already written to the file. */
    val hlsTotal: Int = 0,
    val hlsWritten: Int = 0,
    /** Server supports byte ranges (pause/resume and multiple connections). */
    val resumable: Boolean = false,
) {
    val progress: Float
        get() = when {
            kind == DownloadKind.Hls && hlsTotal > 0 -> hlsWritten.toFloat() / hlsTotal
            total > 0 -> (downloaded.toFloat() / total).coerceIn(0f, 1f)
            else -> 0f
        }
    val active: Boolean get() = status == DownloadStatus.Running || status == DownloadStatus.Queued

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("name", fileName); put("mime", mime)
        put("headers", JSONObject(headers)); put("kind", kind.name); put("total", total)
        put("downloaded", downloaded); put("status", status.name); put("error", error)
        put("uri", uri); put("created", createdAt); put("hlsTotal", hlsTotal); put("hlsWritten", hlsWritten)
        put("resumable", resumable)
        put("segments", JSONArray().apply { segments.forEach { put(JSONArray().put(it.start).put(it.end).put(it.done)) } })
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadJob {
            val h = o.optJSONObject("headers")
            val segs = o.optJSONArray("segments")
            return DownloadJob(
                id = o.getLong("id"), url = o.getString("url"), fileName = o.getString("name"),
                mime = o.optString("mime", "application/octet-stream"),
                headers = h?.keys()?.asSequence()?.associateWith { h.getString(it) } ?: emptyMap(),
                kind = runCatching { DownloadKind.valueOf(o.getString("kind")) }.getOrDefault(DownloadKind.File),
                total = o.optLong("total", -1), downloaded = o.optLong("downloaded"),
                status = runCatching { DownloadStatus.valueOf(o.getString("status")) }.getOrDefault(DownloadStatus.Failed),
                error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" },
                uri = o.optString("uri").takeIf { it.isNotEmpty() && it != "null" },
                createdAt = o.optLong("created"), hlsTotal = o.optInt("hlsTotal"), hlsWritten = o.optInt("hlsWritten"),
                resumable = o.optBoolean("resumable"),
                segments = if (segs == null) emptyList() else List(segs.length()) {
                    val a = segs.getJSONArray(it); Segment(a.getLong(0), a.getLong(1), a.getLong(2))
                },
            )
        }
    }
}
