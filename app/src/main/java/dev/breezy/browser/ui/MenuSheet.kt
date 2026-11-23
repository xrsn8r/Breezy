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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwipeVertical
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onTextSize: () -> Unit,
    onSpeed: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onHistory: () -> Unit,
    onShields: () -> Unit,
    onDismiss: () -> Unit,
) {
    val onPage = tab != null && tab.url.startsWith("http")
    val itemModifier = Modifier.clip(RoundedCornerShape(16.dp))
    var pageOpen by rememberSaveable { mutableStateOf(true) }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            // --- Most-used actions --------------------------------------------------------------
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Icons.Rounded.Add, "New tab", Modifier.weight(1f), onNewTab)
                QuickAction(Icons.Rounded.Shield, "Shields", Modifier.weight(1f), onShields)
                QuickAction(Icons.Rounded.History, "History", Modifier.weight(1f), onHistory)
            }
            Spacer(Modifier.height(12.dp))

            if (tab != null) {
                // Connected nav buttons: back · forward · reload · share
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    val items = listOf(
                        Triple(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tab.canGoBack) to { tab.goBack() },
                        Triple(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", tab.canGoForward) to { tab.goForward() },
                        Triple(if (tab.loading) Icons.Rounded.Close else Icons.Rounded.Refresh, if (tab.loading) "Stop" else "Reload", onPage) to
                            { if (tab.loading) tab.stop() else tab.reload() },
                        Triple(Icons.Rounded.Share, "Share", onPage) to onShare,
                    )
                    items.forEachIndexed { i, (meta, action) ->
                        val (icon, label, enabled) = meta
                        ToggleButton(
                            checked = false,
                            onCheckedChange = { action(); if (i < 3) onDismiss() },
                            enabled = enabled,
                            shapes = when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            colors = FilledTonalToggleButtonDefaults.colors(),
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) { Icon(icon, contentDescription = label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (BrowserTab.multiProfileSupported) {
                ListItem(
                    headlineContent = { Text("New private tab") },
                    leadingContent = { Icon(Icons.Rounded.VisibilityOff, null) },
                    modifier = itemModifier.clickable(onClick = onNewPrivateTab),
                )
            }

            if (onPage) {
                tab!!
                MenuGroup("Page", Icons.AutoMirrored.Rounded.Article, pageOpen, { pageOpen = it }) {
                    MenuRow(Icons.Rounded.Search, "Find in page", onClick = onFind)
                    MenuRow(Icons.AutoMirrored.Rounded.Article, "Reader mode", onClick = onReader)
                    MenuRow(Icons.Rounded.FormatSize, "Text size", onClick = onTextSize)
                    if (tab.media.hasMedia) {
                        MenuRow(Icons.Rounded.Speed, "Playback speed", "${tab.playbackRate}×".replace(".0×", "×"), onClick = onSpeed)
                    }
                    MenuRow(Icons.Rounded.AddHome, "Add to home", onClick = onAddToHome)
                    ListItem(
                        headlineContent = { Text("Desktop site") },
                        leadingContent = { Icon(Icons.Rounded.DesktopWindows, null) },
                        trailingContent = { Switch(checked = tab.desktopMode, onCheckedChange = { tab.toggleDesktopMode() }) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
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
                headlineContent = { Text("Settings") },
                leadingContent = { Icon(Icons.Rounded.Settings, null) },
                modifier = itemModifier.clickable(onClick = onSettings),
            )
        }
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
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
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            ListItem(
                headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
                leadingContent = { Icon(icon, null) },
                trailingContent = { Icon(Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand", Modifier.rotate(rotation)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onToggle(!open) },
            )
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
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    )
}

/** Plain-text list URLs (…/list.txt, raw?file=…txt) get "Add page as filter list". */
fun looksLikeFilterList(url: String): Boolean {
    val u = url.substringBefore('#').lowercase()
    return u.substringBefore('?').endsWith(".txt") || Regex("[?&]file=[^&]*\\.txt").containsMatchIn(u)
}
