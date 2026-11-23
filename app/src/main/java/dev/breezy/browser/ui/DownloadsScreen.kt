package dev.breezy.browser.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.breezy.browser.downloads.DownloadJob
import dev.breezy.browser.downloads.DownloadKind
import dev.breezy.browser.downloads.DownloadStatus
import dev.breezy.browser.downloads.Downloads

@Composable
fun DownloadsScreen(onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val jobs by Downloads.jobs.collectAsState()
    val sorted = jobs.sortedWith(compareByDescending<DownloadJob> { it.active }.thenByDescending { it.createdAt })

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Downloads", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = Downloads::clearFinished, enabled = jobs.any { !it.active && it.status != DownloadStatus.Paused }) {
                    Icon(Icons.Rounded.DeleteSweep, "Clear finished")
                }
            }
            if (sorted.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No downloads yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Files are saved to Download/Breezy", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                items(sorted, key = { it.id }) { job -> DownloadCard(job) }
            }
        }
    }
}

@Composable
private fun DownloadCard(job: DownloadJob) {
    val context = LocalContext.current
    val size = { b: Long -> Formatter.formatShortFileSize(context, b) }
    val status = when (job.status) {
        DownloadStatus.Running -> buildString {
            append(if (job.total > 0) "${size(job.downloaded)} of ${size(job.total)}" else size(job.downloaded))
            if (job.kind == DownloadKind.Hls && job.hlsTotal > 0) append(" · ${job.hlsWritten}/${job.hlsTotal} parts")
            if (job.speed > 0) append(" · ${size(job.speed)}/s")
            if (job.segments.size > 1) append(" · ${job.segments.size} connections")
        }
        DownloadStatus.Queued -> "Waiting…"
        DownloadStatus.Paused -> "Paused · ${size(job.downloaded)}" + if (job.total > 0) " of ${size(job.total)}" else ""
        DownloadStatus.Completed -> size(job.total.coerceAtLeast(job.downloaded)) + " · " +
            DateUtils.getRelativeTimeSpanString(job.createdAt)
        DownloadStatus.Failed -> "Failed: ${job.error ?: "unknown error"}"
        DownloadStatus.Cancelled -> "Cancelled"
    }
    val open = {
        (context as? android.app.Activity)?.let { dev.breezy.browser.downloads.FileOpener.open(it, job) }
        Unit
    }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clickable(enabled = job.status == DownloadStatus.Completed, onClick = open),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(job.mime), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(job.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        color = if (job.status == DownloadStatus.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (job.status) {
                    DownloadStatus.Running, DownloadStatus.Queued -> {
                        IconButton(onClick = { Downloads.pause(job.id) }) { Icon(Icons.Rounded.Pause, "Pause") }
                        IconButton(onClick = { Downloads.cancel(job.id) }) { Icon(Icons.Rounded.Close, "Cancel") }
                    }
                    DownloadStatus.Paused -> {
                        IconButton(onClick = { Downloads.start(job.id) }) { Icon(Icons.Rounded.PlayArrow, "Resume") }
                        IconButton(onClick = { Downloads.cancel(job.id) }) { Icon(Icons.Rounded.Close, "Cancel") }
                    }
                    DownloadStatus.Failed -> {
                        IconButton(onClick = { Downloads.start(job.id) }) { Icon(Icons.Rounded.Refresh, "Retry") }
                        IconButton(onClick = { Downloads.remove(job.id, deleteFile = true) }) { Icon(Icons.Rounded.Delete, "Remove") }
                    }
                    DownloadStatus.Completed -> {
                        IconButton(onClick = { Downloads.shareIntent(job)?.let(context::startActivity) }) { Icon(Icons.Rounded.Share, "Share") }
                        IconButton(onClick = { Downloads.remove(job.id, deleteFile = true) }) { Icon(Icons.Rounded.Delete, "Delete file") }
                    }
                    DownloadStatus.Cancelled -> {
                        IconButton(onClick = { Downloads.remove(job.id, deleteFile = true) }) { Icon(Icons.Rounded.Close, "Remove") }
                    }
                }
            }
            if (job.active || job.status == DownloadStatus.Paused) {
                Spacer(Modifier.height(10.dp))
                LinearWavyProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                )
            }
        }
    }
}

private fun iconFor(mime: String): ImageVector = when {
    mime.startsWith("video/") -> Icons.Rounded.VideoFile
    mime.startsWith("audio/") -> Icons.Rounded.AudioFile
    mime.startsWith("image/") -> Icons.Rounded.Image
    else -> Icons.Rounded.Description
}
