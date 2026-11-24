package dev.breezy.browser.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.net.toUri
import dev.breezy.browser.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Breezy's download manager.
 *
 * Files are fetched over several connections at once when the server supports byte ranges
 * (each connection fills its own [Segment] with positional writes into the final file), and can be
 * paused, resumed and retried; progress is persisted, so downloads survive app restarts.
 * HLS playlists are downloaded segment-by-segment in parallel and joined in order.
 */
object Downloads {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = HashMap<Long, Job>()
    private val lock = Any()
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()
    private val stateFile by lazy { File(app.filesDir, "downloads.json") }

    /** Called when a download finishes (to post a "Downloaded" notification). */
    var onCompleted: ((DownloadJob) -> Unit)? = null

    private const val MIN_SEGMENT = 1L shl 20 // don't split below 1 MiB per connection
    private const val MAX_PARALLEL_JOBS = 3
    private val jobSlots = Semaphore(MAX_PARALLEL_JOBS)

    fun init(context: Context) {
        app = context.applicationContext
        val loaded = runCatching {
            val arr = JSONArray(stateFile.readText())
            List(arr.length()) { DownloadJob.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        // Anything that was running when the process died is paused, ready to resume.
        _jobs.value = loaded.map { if (it.active) it.copy(status = DownloadStatus.Paused) else it }
    }

    // --- Public API ------------------------------------------------------------------------------

    fun enqueue(
        url: String,
        fileName: String,
        mime: String,
        headers: Map<String, String>,
        kind: DownloadKind = DownloadKind.File,
    ): Long {
        val id = System.currentTimeMillis() * 1000 + (Math.random() * 1000).toLong()
        val job = DownloadJob(id = id, url = url, fileName = fileName, mime = mimeFor(fileName, mime), headers = headers, kind = kind)
        update { it + job }
        start(id)
        return id
    }

    /** Save bytes already in hand (data: URLs, page-generated blob: files). */
    fun saveBytes(fileName: String, mime: String, bytes: ByteArray): Long {
        val id = System.currentTimeMillis() * 1000 + (Math.random() * 1000).toLong()
        val mime = mimeFor(fileName, mime)
        val uri = Storage.create(app, fileName, mime)
        Storage.open(app, uri).apply { channel.write(ByteBuffer.wrap(bytes)); close() }
        Storage.finish(app, uri)
        val job = DownloadJob(
            id = id, url = "", fileName = fileName, mime = mime, headers = emptyMap(), total = bytes.size.toLong(),
            downloaded = bytes.size.toLong(), status = DownloadStatus.Completed, uri = uri.toString(),
        )
        update { it + job }
        persist()
        onCompleted?.invoke(job); completed.tryEmit(job)
        return id
    }

    fun start(id: Long): Unit = synchronized(lock) {
        if (running[id]?.isActive == true) return
        mutate(id) { it.copy(status = DownloadStatus.Queued, error = null) }
        running[id] = scope.launch {
            jobSlots.withPermit {
                mutate(id) { it.copy(status = DownloadStatus.Running) }
                try {
                    val job = get(id) ?: return@withPermit
                    if (job.kind == DownloadKind.Hls) runHls(id) else runFile(id)
                    val done = get(id)!!
                    Storage.finish(app, done.uri!!.toUri())
                    mutate(id) { it.copy(status = DownloadStatus.Completed, speed = 0) }
                    persist()
                    get(id)?.let { onCompleted?.invoke(it); completed.tryEmit(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutate(id) { it.copy(status = DownloadStatus.Failed, error = e.message ?: e.javaClass.simpleName, speed = 0) }
                    persist()
                }
            }
        }
        DownloadService.update(app)
    }

    fun pause(id: Long) {
        synchronized(lock) { running.remove(id)?.cancel() }
        mutate(id) { if (it.active) it.copy(status = DownloadStatus.Paused, speed = 0) else it }
        persist()
    }

    fun cancel(id: Long) {
        synchronized(lock) { running.remove(id)?.cancel() }
        get(id)?.uri?.let { Storage.delete(app, it.toUri()) }
        mutate(id) { it.copy(status = DownloadStatus.Cancelled, speed = 0, uri = null) }
        persist()
    }

    /** Remove from the list; [deleteFile] also deletes the downloaded file. */
    fun remove(id: Long, deleteFile: Boolean) {
        synchronized(lock) { running.remove(id)?.cancel() }
        val job = get(id)
        if (job != null && (deleteFile || job.status != DownloadStatus.Completed)) {
            job.uri?.let { Storage.delete(app, it.toUri()) }
        }
        update { list -> list.filter { it.id != id } }
        persist()
    }

    fun clearFinished() {
        update { list -> list.filter { it.active || it.status == DownloadStatus.Paused } }
        persist()
    }

    /**
     * Servers often label files `application/octet-stream`; trust a known extension instead so
     * the right app (e.g. the package installer for .apk) opens them.
     */
    fun mimeFor(fileName: String, mime: String?): String {
        val generic = mime.isNullOrBlank() || mime == "application/octet-stream" || mime == "binary/octet-stream" ||
            mime == "application/force-download" || mime == "application/x-download"
        if (!generic) return mime
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return APK_MIME
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    const val APK_MIME = "application/vnd.android.package-archive"

    fun isApk(job: DownloadJob) = mimeFor(job.fileName, job.mime) == APK_MIME

    fun openIntent(job: DownloadJob): Intent? {
        val uri = job.uri?.toUri() ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(Storage.shareable(app, uri), mimeFor(job.fileName, job.mime))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Finished downloads, for the browser's "Downloaded … · Open" snackbar. */
    val completed = kotlinx.coroutines.flow.MutableSharedFlow<DownloadJob>(extraBufferCapacity = 4)

    fun shareIntent(job: DownloadJob): Intent? {
        val uri = job.uri?.toUri() ?: return null
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(job.mime).putExtra(Intent.EXTRA_STREAM, Storage.shareable(app, uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val anyActive: Boolean get() = _jobs.value.any { it.active }

    // --- State helpers ---------------------------------------------------------------------------

    fun get(id: Long) = _jobs.value.firstOrNull { it.id == id }
    private fun update(f: (List<DownloadJob>) -> List<DownloadJob>) = synchronized(lock) { _jobs.value = f(_jobs.value) }
    private fun mutate(id: Long, f: (DownloadJob) -> DownloadJob) = update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun persist() = synchronized(lock) {
        runCatching {
            val arr = JSONArray()
            _jobs.value.forEach { arr.put(it.toJson()) }
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(stateFile)
        }
    }

    // --- HTTP ------------------------------------------------------------------------------------

    private fun connect(url: String, headers: Map<String, String>, range: String? = null): HttpURLConnection {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        headers.forEach { (k, v) -> if (v.isNotEmpty()) conn.setRequestProperty(k, v) }
        // Raw bytes: a transparently gunzipped body would break byte ranges.
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (range != null) conn.setRequestProperty("Range", range)
        return conn
    }

    /**
     * Run [block], retrying network errors with backoff. Throttling (429/503) waits out the
     * server's Retry-After (or an exponential pause) for every request to that host, and gets more
     * attempts; permanent errors (403, 404, ...) fail at once. [onThrottled] lets callers shed
     * parallel connections.
     */
    private inline fun <T> retrying(
        url: String? = null,
        times: Int = 5,
        onThrottled: () -> Unit = {},
        block: (attempt: Int) -> T,
    ): T {
        val host = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        var last: Exception? = null
        var throttles = 0
        var attempt = 0
        while (attempt < times || (last as? HttpStatusException)?.throttled == true && throttles < 12) {
            if (host != null) HostCooldown.waitFor(host)
            try {
                return block(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                last = e
                if (e.permanent) throw e
                if (e.throttled) {
                    throttles++
                    onThrottled()
                    val wait = (e.retryAfterMs ?: (1_000L shl minOf(throttles, 5))).coerceIn(1_000L, 60_000L)
                    if (host != null) HostCooldown.hit(host, wait) else Thread.sleep(wait)
                    continue
                }
                Thread.sleep(minOf(8_000L, 500L shl attempt))
                attempt++
            } catch (e: IOException) {
                last = e
                Thread.sleep(minOf(8_000L, 500L shl attempt))
                attempt++
            }
        }
        throw last ?: IOException("Download failed")
    }

    // --- Files (multi-connection) ----------------------------------------------------------------

    private suspend fun runFile(id: Long) = coroutineScope {
        var job = get(id)!!
        if (job.uri == null) {
            val uri = Storage.create(app, job.fileName, job.mime)
            mutate(id) { it.copy(uri = uri.toString()) }
            job = get(id)!!
        }
        val uri = job.uri!!.toUri()

        // First run: probe size and range support, then split into segments.
        if (job.segments.isEmpty()) {
            val (total, ranges) = retrying(job.url) { probe(job) }
            val count = if (ranges && total > 0) {
                AppSettings.downloadConnections.coerceIn(1, 8).coerceAtMost(maxOf(1, (total / MIN_SEGMENT).toInt()))
            } else 1
            val segs = if (total <= 0 || !ranges) {
                listOf(Segment(0, if (total > 0) total - 1 else -1, 0))
            } else {
                val size = total / count
                List(count) { i ->
                    val start = i * size
                    Segment(start, if (i == count - 1) total - 1 else start + size - 1, 0)
                }
            }
            mutate(id) { it.copy(total = total, resumable = ranges, segments = segs, downloaded = 0) }
            persist()
            job = get(id)!!
        } else if (!job.resumable) {
            // Can't resume without ranges: start over.
            Storage.truncate(app, uri)
            mutate(id) { it.copy(downloaded = 0, segments = it.segments.map { s -> s.copy(done = 0) }) }
            job = get(id)!!
        }

        val handle = Storage.open(app, uri)
        val counters = job.segments.map { AtomicLong(it.done) }
        val reporter = launch {
            var last = counters.sumOf { it.get() }
            var lastSave = System.currentTimeMillis()
            while (isActive) {
                delay(500)
                val now = counters.sumOf { it.get() }
                mutate(id) { j ->
                    j.copy(
                        downloaded = now, speed = (now - last) * 2,
                        segments = j.segments.mapIndexed { i, s -> s.copy(done = counters[i].get()) },
                    )
                }
                last = now
                if (System.currentTimeMillis() - lastSave > 2_000) { persist(); lastSave = System.currentTimeMillis() }
            }
        }
        try {
            job.segments.mapIndexed { i, seg ->
                async { downloadSegment(job, seg, counters[i], handle) }
            }.awaitAll()
        } finally {
            reporter.cancel()
            handle.close()
            mutate(id) { j ->
                j.copy(downloaded = counters.sumOf { it.get() }, segments = j.segments.mapIndexed { i, s -> s.copy(done = counters[i].get()) })
            }
        }
        val done = get(id)!!
        if (done.total > 0 && done.downloaded < done.total) throw IOException("Download incomplete")
        if (done.total <= 0) mutate(id) { it.copy(total = it.downloaded) }
    }

    /** Returns (total size or -1, server supports byte ranges). */
    private fun probe(job: DownloadJob): Pair<Long, Boolean> {
        val conn = connect(job.url, job.headers, "bytes=0-")
        try {
            HttpStatusException.check(conn)
            val code = conn.responseCode
            val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: conn.contentLengthLong
            return total to (code == 206)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadSegment(job: DownloadJob, seg: Segment, done: AtomicLong, handle: Storage.Handle) {
        if (seg.complete) return
        retrying(job.url) { _ ->
            val offset = seg.start + done.get()
            if (seg.end >= 0 && offset > seg.end) return
            val range = if (job.resumable) "bytes=$offset-${if (seg.end >= 0) seg.end else ""}" else null
            val conn = connect(job.url, job.headers, range)
            try {
                HttpStatusException.check(conn)
                val code = conn.responseCode
                if (range != null && code != 206) throw IOException("Server stopped supporting resume")
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var pos = offset
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val limit = if (seg.end >= 0) minOf(buf.size.toLong(), seg.end - pos + 1).toInt() else buf.size
                        if (limit <= 0) break
                        val n = input.read(buf, 0, limit)
                        if (n < 0) break
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        while (bb.hasRemaining()) handle.channel.write(bb, pos + (n - bb.remaining()))
                        pos += n
                        done.addAndGet(n.toLong())
                    }
                }
                if (seg.end >= 0 && seg.start + done.get() <= seg.end) throw IOException("Connection closed early")
            } finally {
                conn.disconnect()
            }
        }
    }

    // --- HLS ---------------------------------------------------------------------------------------

    private suspend fun runHls(id: Long) = coroutineScope {
        var job = get(id)!!
        val playlist = retrying(job.url) { Hls.fetchMedia(job.url, job.headers) }
        if (job.uri == null) {
            val uri = Storage.create(app, job.fileName, job.mime)
            mutate(id) { it.copy(uri = uri.toString(), hlsTotal = playlist.segments.size) }
            job = get(id)!!
        }
        val uri = job.uri!!.toUri()
        val handle = Storage.open(app, uri)
        val tmp = File(app.cacheDir, "hls-$id").apply { mkdirs() }
        try {
            // Resume after the last segment fully written; drop any partial tail.
            var position = job.downloaded
            handle.channel.truncate(position)
            if (job.hlsWritten == 0 && playlist.initSegment != null) {
                val init = retrying(playlist.initSegment) { Hls.fetchBytes(playlist.initSegment, job.headers) }
                handle.channel.write(ByteBuffer.wrap(init), position)
                position += init.size
                mutate(id) { it.copy(downloaded = position) }
            }
            val parallel = AdaptiveLimiter(AppSettings.downloadConnections.coerceIn(1, 8))
            val start = job.hlsWritten
            val pending = (start until playlist.segments.size).map { i ->
                async {
                    parallel.withPermit {
                        val file = File(tmp, "$i.seg")
                        if (!file.exists()) {
                            var throttled = false
                            val bytes = retrying(playlist.segments[i], times = 6, onThrottled = { throttled = true }) {
                                Hls.fetchSegment(playlist, i, job.headers)
                            }
                            if (throttled) parallel.onThrottled() else parallel.onSuccess()
                            File(tmp, "$i.part").apply { writeBytes(bytes); renameTo(file) }
                        }
                        file
                    }
                }
            }
            var lastTick = System.currentTimeMillis()
            var bytesSinceTick = 0L
            // Append in order as each segment arrives.
            for ((k, deferred) in pending.withIndex()) {
                val file = deferred.await()
                val bytes = file.readBytes()
                handle.channel.write(ByteBuffer.wrap(bytes), position)
                position += bytes.size
                bytesSinceTick += bytes.size
                file.delete()
                val now = System.currentTimeMillis()
                val speed = if (now - lastTick >= 1_000) (bytesSinceTick * 1000 / (now - lastTick)).also { lastTick = now; bytesSinceTick = 0 } else null
                mutate(id) { j ->
                    j.copy(hlsWritten = start + k + 1, downloaded = position, total = -1, speed = speed ?: j.speed)
                }
                if ((start + k) % 10 == 0) persist()
            }
            mutate(id) { it.copy(total = position) }
        } finally {
            handle.close()
            if (get(id)?.status == DownloadStatus.Running && get(id)?.hlsWritten == get(id)?.hlsTotal) tmp.deleteRecursively()
        }
    }

    // --- Helpers for the browser -------------------------------------------------------------------

    /** Decode a `data:` URL into (mime, bytes). */
    fun decodeDataUrl(url: String): Pair<String, ByteArray>? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',').takeIf { it > 0 } ?: return null
        val meta = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val mime = meta.substringBefore(';').ifEmpty { "application/octet-stream" }
        val bytes = if (meta.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
        return mime to bytes
    }
}
