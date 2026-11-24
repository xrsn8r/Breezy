package dev.breezy.browser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddHome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.SwipeVertical
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.breezy.browser.adblock.Shield
import dev.breezy.browser.browser.BrowserTab

// --- Menu ------------------------------------------------------------------------------------

@Composable
fun MenuSheet(
    tab: BrowserTab?,
    onNewTab: () -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit,
    onBlockElement: () -> Unit,
    onUnlockScrolling: () -> Unit,
    onDiagnostics: () -> Unit,
    onAddToHome: () -> Unit,
    onAddAsFilterList: () -> Unit,
    onFind: () -> Unit,
    onReader: () -> Unit,
    onTranslate: () -> Unit,
    onTextSize: () -> Unit,
    onSpeed: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onHistory: () -> Unit,
    onBookmarks: () -> Unit,
    onShields: () -> Unit,
    onDownloads: () -> Unit,
    onDismiss: () -> Unit,
) {
    val onPage = tab != null && tab.url.startsWith("http")
    val itemModifier = Modifier.clip(RoundedCornerShape(16.dp))
    var pageOpen by rememberSaveable { mutableStateOf(true) }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    // Its own bottom window with no drag gestures: earlier, small finger movements while tapping
    // were taken as drags of the sheet and swallowed the tap. Close with a tap outside or Back.
    androidx.compose.runtime.DisposableEffect(Unit) {
        dev.breezy.browser.data.CrashReport.note("menu opened")
        onDispose { dev.breezy.browser.data.CrashReport.note("menu closed") }
    }
    FrostedSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.logTouches { "frosted" }.padding(top = 20.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            // --- Most-used actions --------------------------------------------------------------
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction(Icons.Rounded.Add, "New tab", Modifier.weight(1f), onNewTab)
                QuickAction(Icons.Rounded.Shield, "Shields", Modifier.weight(1f), onShields)
                QuickAction(Icons.Rounded.History, "History", Modifier.weight(1f), onHistory)
                QuickAction(Icons.Rounded.Download, "Downloads", Modifier.weight(1f), onDownloads)
            }
            Spacer(Modifier.height(12.dp))

            if (tab != null) {
                // Connected nav buttons: back · forward · reload · share
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    val bookmarks by dev.breezy.browser.data.Bookmarks.all.collectAsState()
                    val saved = bookmarks.any { it.url.removePrefix("https://").removePrefix("http://").trimEnd('/') ==
                        tab.url.removePrefix("https://").removePrefix("http://").trimEnd('/') }
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val items = listOf(
                        Triple(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tab.canGoBack) to { tab.goBack() },
                        Triple(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", tab.canGoForward) to { tab.goForward() },
                        Triple(if (tab.loading) Icons.Rounded.Close else Icons.Rounded.Refresh, if (tab.loading) "Stop" else "Reload", onPage) to
                            { if (tab.loading) tab.stop() else tab.reload() },
                        Triple(if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (saved) "Remove bookmark" else "Bookmark", onPage) to {
                            val existing = dev.breezy.browser.data.Bookmarks.find(tab.url)
                            if (existing != null) dev.breezy.browser.data.Bookmarks.remove(existing)
                            else dev.breezy.browser.data.Bookmarks.add(tab.title.ifBlank { tab.url }, tab.url)
                            android.widget.Toast.makeText(context, if (existing != null) "Bookmark removed" else "Bookmarked", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        Triple(Icons.Rounded.Share, "Share", onPage) to onShare,
                    )
                    items.forEachIndexed { i, (meta, action) ->
                        val (icon, label, enabled) = meta
                        ToggleButton(
                            checked = label == "Remove bookmark",
                            onCheckedChange = { action(); if (i < 3) onDismiss() },
                            enabled = enabled,
                            shapes = when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            colors = FilledTonalToggleButtonDefaults.colors(
                                containerColor = frostedFill(MaterialTheme.colorScheme.secondaryContainer, tint = MaterialTheme.colorScheme.secondaryContainer),
                                disabledContainerColor = frostedFill(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                            ),
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) { Icon(icon, contentDescription = label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            ListItem(
                leadingContent = { Icon(Icons.Rounded.Bookmarks, null) },
                colors = ListItemDefaults.colors(containerColor = frostedFill(MaterialTheme.colorScheme.surfaceContainerLow)),
                modifier = itemModifier.clickable { tapped("Bookmarks"); onBookmarks() },
            ) { Text("Bookmarks") }
            if (BrowserTab.multiProfileSupported) {
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.VisibilityOff, null) },
                    colors = ListItemDefaults.colors(containerColor = frostedFill(MaterialTheme.colorScheme.surfaceContainerLow)),
                modifier = itemModifier.clickable { tapped("New private tab"); onNewPrivateTab() },
                ) { Text("New private tab") }
            }

            if (onPage) {
                MenuGroup("Page", Icons.AutoMirrored.Rounded.Article, pageOpen, { pageOpen = it }) {
                    MenuRow(Icons.Rounded.Search, "Find in page", onClick = onFind)
                    MenuRow(Icons.AutoMirrored.Rounded.Article, "Reader mode", onClick = onReader)
                    if (tab.translatedFrom != null) {
                        MenuRow(Icons.Rounded.Translate, "Show original",
                            tab.translatedFrom?.takeIf { it != "…" }?.let { "Translated from " + dev.breezy.browser.data.Translator.displayName(it) },
                            onClick = onTranslate)
                    } else {
                        MenuRow(Icons.Rounded.Translate, "Translate page",
                            "To " + dev.breezy.browser.data.Translator.displayName(dev.breezy.browser.data.Translator.target(dev.breezy.browser.AppSettings.translateTo)),
                            onClick = onTranslate)
                    }
                    MenuRow(Icons.Rounded.FormatSize, "Text size", onClick = onTextSize)
                    if (tab.media.hasMedia) {
                        MenuRow(Icons.Rounded.Speed, "Playback speed", "${tab.playbackRate}×".replace(".0×", "×"), onClick = onSpeed)
                    }
                    MenuRow(Icons.Rounded.AddHome, "Add to home", onClick = onAddToHome)
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.DesktopWindows, null) },
                        trailingContent = { Switch(checked = tab.desktopMode, onCheckedChange = { tab.toggleDesktopMode() }) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    ) { Text("Desktop site") }
                }
                MenuGroup("Tools", Icons.Rounded.Build, toolsOpen, { toolsOpen = it }) {
                    MenuRow(Icons.Rounded.Block, "Block element", "Tap anything on the page to hide it", onClick = onBlockElement)
                    if (looksLikeFilterList(tab.url)) {
                        MenuRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add page as filter list", onClick = onAddAsFilterList)
                    }
                    MenuRow(Icons.Rounded.SwipeVertical, "Unlock scrolling", "For pages stuck behind a hidden pop-up", onClick = onUnlockScrolling)
                    MenuRow(Icons.Rounded.BugReport, "Page diagnostics", onClick = onDiagnostics)
                }
            }
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Settings, null) },
                colors = ListItemDefaults.colors(containerColor = frostedFill(MaterialTheme.colorScheme.surfaceContainerLow)),
                modifier = itemModifier.clickable { tapped("Settings"); onSettings() },
            ) { Text("Settings") }
        }
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = { tapped(label); onClick() },
        shape = RoundedCornerShape(24.dp),
        color = frostedFill(MaterialTheme.colorScheme.secondaryContainer, tint = MaterialTheme.colorScheme.secondaryContainer),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.height(76.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A collapsible section of the menu. */
@Composable
private fun MenuGroup(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    open: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = frostedFill(MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            ListItem(
                leadingContent = { Icon(icon, null) },
                trailingContent = { Icon(Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand", Modifier.rotate(rotation)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { tapped(title); onToggle(!open) },
            ) { Text(title, style = MaterialTheme.typography.titleMedium) }
            AnimatedVisibility(visible = open) {
                Column(Modifier.padding(bottom = 8.dp)) { content() }
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    ListItem(
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { tapped(title); onClick() },
    ) { Text(title) }
}

/** Diagnostics trail: which menu item was tapped (its label only). */
private fun tapped(label: String) = dev.breezy.browser.data.CrashReport.note("menu tap: $label")

/**
 * Diagnostics trail: every touch on the menu, with what happened to it. Taps on rows were
 * sometimes ignored; this records how many pointers Compose thought were down, whether the lift
 * was consumed by something else or landed outside, and whether the sheet was still animating.
 */
private fun Modifier.logTouches(sheet: () -> String): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        val atDown = currentEvent.changes.count { it.pressed }
        var outcome = "lost"
        while (true) {
            val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) {
                val others = ev.changes.count { it.id != down.id && it.pressed }
                outcome = "up consumed=${ch.isConsumed} out=${ch.position.x < 0 || ch.position.y < 0 || ch.position.x > size.width || ch.position.y > size.height} othersDown=$others"
                break
            }
        }
        dev.breezy.browser.data.CrashReport.note("menu touch: pointersAtDown=$atDown $outcome sheet=${sheet()}")
    }
}

/** Plain-text list URLs (…/list.txt, raw?file=…txt) get "Add page as filter list". */
fun looksLikeFilterList(url: String): Boolean {
    val u = url.substringBefore('#').lowercase()
    return u.substringBefore('?').endsWith(".txt") || Regex("[?&]file=[^&]*\\.txt").containsMatchIn(u)
}
