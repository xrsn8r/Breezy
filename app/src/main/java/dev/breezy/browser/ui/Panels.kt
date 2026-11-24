package dev.breezy.browser.ui

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.breezy.browser.AppSettings
import dev.breezy.browser.browser.BrowserTab
import dev.breezy.browser.data.SiteConfig
import dev.breezy.browser.data.SitePermission
import dev.breezy.browser.data.Stats
import java.text.NumberFormat
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// --- Find in page ----------------------------------------------------------------------------------

@Composable
fun FindBar(tab: BrowserTab, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val result = tab.findResult
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = { query = it; tab.find(it) },
                placeholder = { Text("Find in page") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                suffix = {
                    if (query.isNotEmpty() && result != null) {
                        Text(if (result.second == 0) "0/0" else "${result.first + 1}/${result.second}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { tab.findNext(true) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = { tab.findNext(false) }, enabled = (result?.second ?: 0) > 0) {
                Icon(Icons.Rounded.KeyboardArrowUp, "Previous match")
            }
            IconButton(onClick = { tab.findNext(true) }, enabled = (result?.second ?: 0) > 0) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Next match")
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close find") }
        }
    }
}

// --- HTTPS-only warning ---------------------------------------------------------------------------

@Composable
fun HttpsWarning(tab: BrowserTab, insecureUrl: String) {
    val host = remember(insecureUrl) { runCatching { android.net.Uri.parse(insecureUrl).host }.getOrNull() ?: insecureUrl }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.LockOpen, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text("No secure connection", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "$host doesn't support HTTPS. If you continue, anyone on your network could see or change " +
                    "what you send and receive on this site.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = tab::dismissHttpsFailure, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Go back")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = tab::continueInsecure, modifier = Modifier.fillMaxWidth()) {
                Text("Continue to $host (not secure)", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// --- Site permission prompt -------------------------------------------------------------------------

@Composable
fun SitePermissionDialog(host: String, kinds: List<String>, onResult: (allow: Boolean, remember: Boolean) -> Unit) {
    var remember by remember { mutableStateOf(true) }
    val what = when {
        kinds.containsAll(listOf("camera", "microphone")) -> "use your camera and microphone"
        "camera" in kinds -> "use your camera"
        "microphone" in kinds -> "use your microphone"
        else -> "know your location"
    }
    AlertDialog(
        onDismissRequest = { onResult(false, false) },
        title = { Text("Allow $host to $what?") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = remember, onCheckedChange = { remember = it })
                Text("Remember for this site")
            }
        },
        confirmButton = { TextButton(onClick = { onResult(true, remember) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { onResult(false, remember) }) { Text("Block") } },
    )
}

// --- Text size --------------------------------------------------------------------------------------

@Composable
fun TextSizeDialog(host: String, current: Int?, onChange: (Int?) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf((current ?: AppSettings.textZoom).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text size") },
        text = {
            Column {
                Text("${value.roundToInt()}% on $host", style = MaterialTheme.typography.titleMedium)
                StepSlider(value = value, valueRange = 50f..250f, steps = 19) { value = it; onChange(it.roundToInt()) }
                Text("Default for all sites: ${AppSettings.textZoom}% (change in Settings)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { value = AppSettings.textZoom.toFloat(); onChange(null) }) { Text("Reset") }
        },
    )
}

// --- Playback speed ---------------------------------------------------------------------------------

private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun SpeedDialog(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback speed") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                speeds.forEachIndexed { i, s ->
                    ToggleButton(
                        checked = current == s,
                        onCheckedChange = { onPick(s) },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            speeds.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (s == 1f) "1×" else "${s}×".replace(".0×", "×"), style = MaterialTheme.typography.labelMedium) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

// --- Shield statistics --------------------------------------------------------------------------------

@Composable
fun StatsCard(snapshot: Stats.Snapshot) {
    val context = LocalContext.current
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val max = (snapshot.days.maxOfOrNull { it.blocked } ?: 0L).coerceAtLeast(1L)
    val week = snapshot.days.takeLast(7).sumOf { it.blocked }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(numbers.format(week), style = MaterialTheme.typography.headlineSmall)
                    Text("blocked this week", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("~" + Formatter.formatShortFileSize(context, snapshot.totalBytes), style = MaterialTheme.typography.headlineSmall)
                    Text("data saved", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Canvas(Modifier.fillMaxWidth().height(72.dp)) {
                val n = snapshot.days.size.coerceAtLeast(1)
                val gap = 6.dp.toPx()
                val w = (size.width - gap * (n - 1)) / n
                snapshot.days.forEachIndexed { i, d ->
                    val x = i * (w + gap)
                    drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
                    val h = (d.blocked.toFloat() / max) * size.height
                    if (h > 0f) {
                        val bh = h.coerceAtLeast(w)
                        drawRoundRect(primary, Offset(x, size.height - bh), Size(w, bh), CornerRadius(w / 2))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                val first = snapshot.days.firstOrNull()?.date
                Text(first?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: "",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text("${numbers.format(snapshot.totalBlocked)} ads & trackers blocked in total. Data saved is estimated from typical sizes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- Per-site controls --------------------------------------------------------------------------------

@Composable
fun SiteControls(
    host: String,
    config: SiteConfig,
    onChange: ((SiteConfig) -> SiteConfig) -> Unit,
    onForget: () -> Unit,
) {
    var confirmForget by remember { mutableStateOf(false) }
    Column {
        ListItem(
            supportingContent = { Text(if (config.javascript) "Allowed" else "Blocked on this site") },
            trailingContent = { Switch(config.javascript, { v -> onChange { it.copy(javascript = v) } }) },
        ) { Text("JavaScript") }
        ListItem(
            supportingContent = { Text(if (config.cookies) "Allowed (third-party always blocked)" else "Blocked on this site") },
            trailingContent = { Switch(config.cookies, { v -> onChange { it.copy(cookies = v) } }) },
        ) { Text("Cookies") }
        PermissionRow("Camera", config.camera) { v -> onChange { it.copy(camera = v) } }
        PermissionRow("Microphone", config.microphone) { v -> onChange { it.copy(microphone = v) } }
        PermissionRow("Location", config.location) { v -> onChange { it.copy(location = v) } }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { confirmForget = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text("Forget this site") }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget $host?") },
            text = { Text("Deletes this site's cookies, stored data, history and site settings. You'll be signed out.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PermissionRow(label: String, value: SitePermission, onPick: (SitePermission) -> Unit) {
    ListItem(
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                SitePermission.entries.forEachIndexed { i, p ->
                    ToggleButton(
                        checked = value == p,
                        onCheckedChange = { onPick(p) },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            SitePermission.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Text(p.name, style = MaterialTheme.typography.labelMedium) }
                }
            }
        },
    ) { Text(label) }
}

// --- Page diagnostics -------------------------------------------------------------------------------

@Composable
fun DiagnosticsDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page diagnostics") },
        text = {
            Text(
                report,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Breezy diagnostics", report))
            }) { Text("Copy") }
        },
    )
}
