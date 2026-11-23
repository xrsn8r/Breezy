package dev.breezy.browser.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwipeVertical
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import dev.breezy.browser.AppSettings
import androidx.core.net.toUri
import dev.breezy.browser.adblock.Shield
import dev.breezy.browser.browser.BrowserTab
import dev.breezy.browser.browser.UrlInput
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

// --- Home page -------------------------------------------------------------------------------

private val shortcuts = listOf(
    "Wikipedia" to "https://wikipedia.org",
    "GitHub" to "https://github.com",
    "Hacker News" to "https://news.ycombinator.com",
    "YouTube" to "https://youtube.com",
)

@Composable
fun HomePage(totalBlocked: Long, onSearch: () -> Unit, onOpen: (String) -> Unit) {
    val spin = rememberInfiniteTransition(label = "logo")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000), RepeatMode.Restart), label = "angle")

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(120.dp)
                        .rotate(angle)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )
                Icon(
                    Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("Breezy", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "${NumberFormat.getIntegerInstance().format(totalBlocked)} ads & trackers blocked",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Surface(
                onClick = onSearch,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Search or type URL", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                shortcuts.forEachIndexed { i, (name, url) ->
                    val shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        shortcuts.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                    ToggleButton(
                        checked = false,
                        onCheckedChange = { onOpen(url) },
                        shapes = shapes,
                        colors = FilledTonalToggleButtonDefaults.colors(),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text(name, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    }
}

// --- Omnibox ---------------------------------------------------------------------------------

@Composable
fun Omnibox(
    initial: String,
    tabs: List<BrowserTab>,
    onGo: (String) -> Unit,
    onSwitchTab: (BrowserTab) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().imePadding().padding(top = 8.dp)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close")
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    singleLine = true,
                    shape = CircleShape,
                    placeholder = { Text("Search or type URL") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (value.text.isNotEmpty()) {
                            IconButton(onClick = { value = TextFieldValue("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onGo(value.text) }),
                )
            }
            Spacer(Modifier.height(8.dp))
            val query = value.text.trim()
            val matches = remember(query, tabs.size) {
                if (query.isEmpty()) tabs.toList()
                else tabs.filter { it.title.contains(query, true) || it.url.contains(query, true) }
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                if (query.isNotEmpty()) {
                    item {
                        val resolved = UrlInput.resolve(query)
                        val isSearch = UrlInput.isSearch(resolved)
                        ListItem(
                            headlineContent = { Text(if (isSearch) "Search “$query”" else resolved, maxLines = 1) },
                            supportingContent = { Text(if (isSearch) AppSettings.searchEngine.label else "Open address") },
                            leadingContent = {
                                Icon(if (isSearch) Icons.Rounded.Search else Icons.Rounded.Language, null)
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            modifier = Modifier.clickable { onGo(query) },
                        )
                    }
                }
                if (matches.isNotEmpty()) {
                    item {
                        Text(
                            "Open tabs",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(matches, key = { it.id }) { t ->
                        ListItem(
                            headlineContent = { Text(t.title.ifEmpty { "New tab" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(UrlInput.display(t.url), maxLines = 1) },
                            leadingContent = { Favicon(t) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            modifier = Modifier.clickable { onSwitchTab(t) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Favicon(tab: BrowserTab, size: Int = 24) {
    val icon = tab.favicon
    if (icon != null) {
        Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size.dp).clip(CircleShape))
    } else {
        Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(size.dp))
    }
}

// --- Tabs ------------------------------------------------------------------------------------

@Composable
fun TabsSheet(
    tabs: List<BrowserTab>,
    current: BrowserTab,
    onSelect: (BrowserTab) -> Unit,
    onClose: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${tabs.size} tabs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onNewTab) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("New tab")
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(tabs, key = { it.id }) { t ->
                val selected = t === current
                val corner by animateFloatAsState(if (selected) 32f else 20f, label = "corner")
                Card(
                    onClick = { onSelect(t) },
                    shape = RoundedCornerShape(corner.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.height(120.dp),
                ) {
                    Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Favicon(t, 20)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { onClose(t) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Close tab")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            t.title.ifEmpty { "New tab" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            UrlInput.display(t.url),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// --- Shields ---------------------------------------------------------------------------------

@Composable
fun ShieldSheet(
    tab: BrowserTab,
    state: Shield.State,
    onSiteToggle: (host: String, on: Boolean) -> Unit,
    onGlobalToggle: (Boolean) -> Unit,
    onUpdateLists: () -> Unit,
    onManageLists: () -> Unit,
    onDismiss: () -> Unit,
) {
    val host = remember(tab.url) { tab.url.toUri().host }
    val siteOn = host != null && host !in state.allowlist
    val active = state.enabled && siteOn && state.ready

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val shape = if (active) MaterialShapes.SoftBurst.toShape() else MaterialShapes.Cookie4Sided.toShape()
            Box(
                Modifier
                    .size(132.dp)
                    .clip(shape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        tab.blockedCount.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text("blocked", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(host ?: "Breezy shields", style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    !state.ready -> "Loading filter engine…"
                    !state.enabled -> "Shields are off everywhere"
                    siteOn -> "Shields are up for this site"
                    else -> "Shields are down for this site"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (host != null) {
                Spacer(Modifier.height(20.dp))
                // Expressive connected button group: Up / Down for this site.
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    ToggleButton(
                        checked = siteOn,
                        onCheckedChange = { onSiteToggle(host, true) },
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Shields up")
                    }
                    ToggleButton(
                        checked = !siteOn,
                        onCheckedChange = { onSiteToggle(host, false) },
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.RemoveModerator, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Down")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Block ads & trackers everywhere") },
                supportingContent = {
                    Text("${NumberFormat.getIntegerInstance().format(state.totalBlocked)} blocked so far")
                },
                trailingContent = { Switch(checked = state.enabled, onCheckedChange = onGlobalToggle) },
            )
            ListItem(
                headlineContent = { Text("Filter lists") },
                supportingContent = {
                    Text(
                        state.lastError?.let { "Update failed: $it" }
                            ?: if (state.listsUpdatedAt > 0) "Updated " +
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.listsUpdatedAt))
                            else "EasyList, EasyPrivacy, uBlock filters (bundled)",
                    )
                },
                trailingContent = {
                    if (state.updating) {
                        LoadingIndicator(Modifier.size(40.dp))
                    } else {
                        IconButton(onClick = onUpdateLists) { Icon(Icons.Rounded.Sync, contentDescription = "Update lists") }
                    }
                },
            )
            TextButton(onClick = onManageLists) { Text("Manage filter lists") }
            Text(
                "Powered by Brave's adblock-rust engine",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Menu ------------------------------------------------------------------------------------

@Composable
fun MenuSheet(
    tab: BrowserTab,
    onNewTab: () -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit,
    onUnlockScrolling: () -> Unit,
    onDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            // Connected nav buttons: back · forward · reload · share
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                val items = listOf(
                    Triple(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tab.canGoBack) to { tab.goBack() },
                    Triple(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", tab.canGoForward) to { tab.goForward() },
                    Triple(if (tab.loading) Icons.Rounded.Close else Icons.Rounded.Refresh, if (tab.loading) "Stop" else "Reload", true) to
                        { if (tab.loading) tab.stop() else tab.reload() },
                    Triple(Icons.Rounded.Share, "Share", tab.url.isNotEmpty()) to onShare,
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
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Icon(icon, contentDescription = label) }
                }
            }
            Spacer(Modifier.height(12.dp))
            ListItem(
                headlineContent = { Text("New tab") },
                leadingContent = { Icon(Icons.Rounded.Add, null) },
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onNewTab),
            )
            ListItem(
                headlineContent = { Text("Desktop site") },
                leadingContent = { Icon(Icons.Rounded.DesktopWindows, null) },
                trailingContent = { Switch(checked = tab.desktopMode, onCheckedChange = { tab.toggleDesktopMode() }) },
            )
            ListItem(
                headlineContent = { Text("Unlock scrolling") },
                supportingContent = { Text("For pages stuck behind a hidden pop-up") },
                leadingContent = { Icon(Icons.Rounded.SwipeVertical, null) },
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onUnlockScrolling),
            )
            ListItem(
                headlineContent = { Text("Page diagnostics") },
                leadingContent = { Icon(Icons.Rounded.BugReport, null) },
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onDiagnostics),
            )
            ListItem(
                headlineContent = { Text("Settings") },
                supportingContent = { Text("Filter lists, search engine, dark mode") },
                leadingContent = { Icon(Icons.Rounded.Settings, null) },
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onSettings),
            )
        }
    }
}

@Composable
fun DiagnosticsDialog(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
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
            TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copy") }
        },
    )
}
