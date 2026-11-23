package dev.breezy.browser.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.breezy.browser.browser.BrowserTab
import dev.breezy.browser.browser.BrowserTab.DetectedMedia
import dev.breezy.browser.browser.UrlInput
import dev.breezy.browser.downloads.DownloadKind
import dev.breezy.browser.downloads.Downloads
import dev.breezy.browser.downloads.Hls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** One downloadable choice: a file, or one quality variant of an HLS stream. */
private data class MediaOption(
    val label: String,
    val detail: String,
    val url: String,
    val kind: DownloadKind,
    val mime: String,
    val extension: String,
    val video: Boolean,
)

private sealed interface Probe {
    data object Loading : Probe
    data class Ready(val options: List<MediaOption>) : Probe
    data class Failed(val reason: String) : Probe
}

/** Lists media found on the page and downloads the one you pick. */
@Composable
fun MediaSheet(tab: BrowserTab, onStarted: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val probes = remember { mutableStateMapOf<String, Probe>() }
    val media = tab.detectedMedia.toList()

    LaunchedEffect(media.size) {
        for (m in media) {
            if (m.url in probes) continue
            probes[m.url] = Probe.Loading
            launch { probes[m.url] = probe(m, tab.downloadHeaders(m.url), context) }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Media on this page", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
            if (media.isEmpty()) {
                ListItem(
                    headlineContent = { Text(if (tab.streamOnlyMedia) "This video can't be downloaded directly" else "No media found yet") },
                    supportingContent = {
                        Text(if (tab.streamOnlyMedia) "It's streamed in separate pieces (like YouTube) rather than as a file."
                        else "Start playing the video, then check again.")
                    },
                    leadingContent = { Icon(Icons.Rounded.Info, null) },
                )
            }
            for (m in media) {
                when (val p = probes[m.url] ?: Probe.Loading) {
                    Probe.Loading -> ListItem(
                        headlineContent = { Text(fileLabel(m), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("Checking…") },
                        leadingContent = { LoadingIndicator(Modifier.size(28.dp)) },
                    )
                    is Probe.Failed -> ListItem(
                        headlineContent = { Text(fileLabel(m), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(p.reason, color = MaterialTheme.colorScheme.error) },
                        leadingContent = { Icon(Icons.Rounded.Info, null) },
                    )
                    is Probe.Ready -> p.options.forEach { opt ->
                        ListItem(
                            headlineContent = { Text(opt.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(opt.detail, maxLines = 1) },
                            leadingContent = { Icon(if (opt.video) Icons.Rounded.VideoFile else Icons.Rounded.AudioFile, null) },
                            trailingContent = {
                                FilledTonalIconButton(onClick = {
                                    val name = downloadName(m, opt.extension)
                                    Downloads.enqueue(opt.url, name, opt.mime, tab.downloadHeaders(opt.url), opt.kind)
                                    onStarted(name)
                                }) { Icon(Icons.Rounded.Download, "Download") }
                            },
                        )
                    }
                }
            }
            if (media.isNotEmpty() && tab.streamOnlyMedia) {
                Spacer(Modifier.height(8.dp))
                Text("Some players on this page stream in pieces and can't be saved.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
    }
}

private fun fileLabel(m: DetectedMedia): String =
    m.url.substringBefore('?').substringAfterLast('/').ifBlank { UrlInput.display(m.url) }

private fun downloadName(m: DetectedMedia, ext: String): String {
    val base = m.pageTitle.ifBlank { fileLabel(m).substringBeforeLast('.') }
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifEmpty { "video" }
    return "$base.$ext"
}

private suspend fun probe(m: DetectedMedia, headers: Map<String, String>, context: android.content.Context): Probe =
    withContext(Dispatchers.IO) {
        runCatching {
            if (m.kind == DetectedMedia.Kind.Hls) {
                when (val pl = Hls.parse(m.url, Hls.fetchText(m.url, headers))) {
                    is Hls.Master -> {
                        // Peek at the first variant to learn the container (TS vs fMP4).
                        val first = Hls.fetchMedia(pl.variants.first().url, headers)
                        Probe.Ready(pl.variants.distinctBy { it.resolution ?: it.bandwidth.toString() }.map { v ->
                            MediaOption(v.label, "Stream · ${UrlInput.display(m.url)}", v.url, DownloadKind.Hls,
                                first.mime, first.extension, true)
                        })
                    }
                    is Hls.Media -> {
                        val p = pl.playlist
                        val mins = (p.durationSeconds / 60).toInt()
                        Probe.Ready(listOf(MediaOption("Stream", "${p.segments.size} parts · ~$mins min · ${UrlInput.display(m.url)}",
                            m.url, DownloadKind.Hls, p.mime, p.extension, true)))
                    }
                }
            } else {
                val conn = URI(m.url).toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 10_000
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.setRequestProperty("Range", "bytes=0-0")
                conn.setRequestProperty("Accept-Encoding", "identity")
                val (size, type) = try {
                    if (conn.responseCode >= 400) error("HTTP ${conn.responseCode}")
                    val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: conn.contentLengthLong
                    total to (conn.contentType?.substringBefore(';') ?: "")
                } finally { conn.disconnect() }
                val video = m.kind == DetectedMedia.Kind.Video
                val mime = type.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
                    ?: if (video) "video/mp4" else "audio/mpeg"
                val ext = fileLabel(m).substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 }
                    ?: android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (video) "mp4" else "mp3"
                val sizeText = if (size > 0) Formatter.formatShortFileSize(context, size) else "Unknown size"
                Probe.Ready(listOf(MediaOption(fileLabel(m), "$sizeText · ${mime.substringAfter('/')} · ${UrlInput.display(m.url)}",
                    m.url, DownloadKind.File, mime, ext, video)))
            }
        }.getOrElse { Probe.Failed(it.message ?: "Couldn't read this media") }
    }
