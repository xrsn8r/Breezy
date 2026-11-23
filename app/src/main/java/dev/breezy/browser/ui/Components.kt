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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.AddHome
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Block
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

// --- Omnibox ---------------------------------------------------------------------------------

@Composable
fun Omnibox(
    initial: String,
    tabs: List<BrowserTab>,
    onGo: (String) -> Unit,
    onSwitchTab: (BrowserTab) -> Unit,
    onDismiss: () -> Unit,
    privateMode: Boolean = false,
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
            // History matches and the search engine's suggestions, fetched as you type (debounced).
            var visited by remember { mutableStateOf<List<dev.breezy.browser.data.HistoryEntry>>(emptyList()) }
            var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
            LaunchedEffect(query) {
                if (query.isEmpty()) { visited = emptyList(); suggestions = emptyList(); return@LaunchedEffect }
                kotlinx.coroutines.delay(150)
                visited = if (privateMode) emptyList() else dev.breezy.browser.data.History.suggest(query)
                suggestions = if (AppSettings.searchSuggestions && !query.contains("://")) fetchSuggestions(query) else emptyList()
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
                items(suggestions.filter { !it.equals(query, true) }.take(5), key = { "s-$it" }) { sug ->
                    ListItem(
                        headlineContent = { Text(sug, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            // Put the suggestion in the box to refine it, like Chrome's ↖ arrow.
                            IconButton(onClick = { value = TextFieldValue("$sug ", TextRange(sug.length + 1)) }) {
                                Icon(Icons.AutoMirrored.Rounded.CallMade, "Edit suggestion",
                                    modifier = Modifier.rotate(-90f))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.clickable { onGo(sug) },
                    )
                }
                items(visited, key = { "h-${it.url}" }) { h ->
                    ListItem(
                        headlineContent = { Text(h.title.ifBlank { UrlInput.display(h.url) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(h.url.removePrefix("https://").removePrefix("www."), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.clickable { onGo(h.url) },
                    )
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

/** OpenSearch suggestions from the selected engine: `["query", ["s1", "s2", ...]]`. */
private suspend fun fetchSuggestions(query: String): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val base = AppSettings.searchEngine.suggestUrl ?: return@withContext emptyList()
    runCatching {
        val conn = java.net.URI(base + android.net.Uri.encode(query)).toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 2_500; conn.readTimeout = 2_500
        try {
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val arr = org.json.JSONArray(body).getJSONArray(1)
            List(arr.length()) { arr.getString(it) }
        } finally { conn.disconnect() }
    }.getOrDefault(emptyList())
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
    current: BrowserTab?,
    onSelect: (BrowserTab) -> Unit,
    onClose: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${tabs.size} tabs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (BrowserTab.multiProfileSupported) {
                IconButton(onClick = onNewPrivateTab) { Icon(Icons.Rounded.VisibilityOff, "New private tab") }
            }
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
                            if (t.isPrivate) Icon(Icons.Rounded.VisibilityOff, "Private", Modifier.size(20.dp))
                            else Favicon(t, 20)
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
    tab: BrowserTab?,
    state: Shield.State,
    onSiteToggle: (host: String, on: Boolean) -> Unit,
    onGlobalToggle: (Boolean) -> Unit,
    onUpdateLists: () -> Unit,
    onManageLists: () -> Unit,
    onDismiss: () -> Unit,
) {
    val url = tab?.url.orEmpty()
    val host = remember(url) { url.toUri().host }
    val siteOn = host != null && host !in state.allowlist
    val active = state.enabled && siteOn && state.ready

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
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
                        (tab?.blockedCount ?: 0).toString(),
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
            Spacer(Modifier.height(8.dp))
            val stats by dev.breezy.browser.data.Stats.snapshot.collectAsState()
            LaunchedEffect(Unit) { dev.breezy.browser.data.Stats.flush() }
            StatsCard(stats)
            if (host != null && tab != null) {
                val sites by dev.breezy.browser.data.SiteSettings.all.collectAsState()
                val cfg = sites[host.removePrefix("www.")] ?: dev.breezy.browser.data.SiteConfig()
                val scope = rememberCoroutineScope()
                Spacer(Modifier.height(16.dp))
                Text("Site settings for $host", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth())
                SiteControls(
                    host = host,
                    config = cfg,
                    onChange = { transform ->
                        val before = dev.breezy.browser.data.SiteSettings.get(host)
                        dev.breezy.browser.data.SiteSettings.update(host, transform)
                        val after = dev.breezy.browser.data.SiteSettings.get(host)
                        tab.applySiteSettings()
                        if (before.javascript != after.javascript || before.cookies != after.cookies) tab.reload()
                    },
                    onForget = {
                        scope.launch {
                            dev.breezy.browser.data.SiteData.forget(host)
                            tab.applySiteSettings()
                            tab.reload()
                        }
                    },
                )
            }
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
    onDismiss: () -> Unit,
) {
    val onPage = tab != null && tab.url.startsWith("http")
    val itemModifier = Modifier.clip(RoundedCornerShape(16.dp))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
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
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) { Icon(icon, contentDescription = label) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            ListItem(
                headlineContent = { Text("New tab") },
                leadingContent = { Icon(Icons.Rounded.Add, null) },
                modifier = itemModifier.clickable(onClick = onNewTab),
            )
            if (BrowserTab.multiProfileSupported) {
                ListItem(
                    headlineContent = { Text("New private tab") },
                    supportingContent = { Text("No history; cookies and site data are wiped when it closes") },
                    leadingContent = { Icon(Icons.Rounded.VisibilityOff, null) },
                    modifier = itemModifier.clickable(onClick = onNewPrivateTab),
                )
            }
            if (onPage) {
                ListItem(
                    headlineContent = { Text("Find in page") },
                    leadingContent = { Icon(Icons.Rounded.Search, null) },
                    modifier = itemModifier.clickable(onClick = onFind),
                )
                ListItem(
                    headlineContent = { Text("Reader mode") },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.Article, null) },
                    modifier = itemModifier.clickable(onClick = onReader),
                )
                ListItem(
                    headlineContent = { Text("Text size") },
                    leadingContent = { Icon(Icons.Rounded.FormatSize, null) },
                    modifier = itemModifier.clickable(onClick = onTextSize),
                )
                if (tab!!.media.hasMedia) {
                    ListItem(
                        headlineContent = { Text("Playback speed") },
                        supportingContent = { Text("${tab.playbackRate}×".replace(".0×", "×")) },
                        leadingContent = { Icon(Icons.Rounded.Speed, null) },
                        modifier = itemModifier.clickable(onClick = onSpeed),
                    )
                }
            }
            ListItem(
                headlineContent = { Text("History") },
                leadingContent = { Icon(Icons.Rounded.History, null) },
                modifier = itemModifier.clickable(onClick = onHistory),
            )
            if (onPage) {
                ListItem(
                    headlineContent = { Text("Add to home") },
                    supportingContent = { Text("Pin this page as a home page shortcut") },
                    leadingContent = { Icon(Icons.Rounded.AddHome, null) },
                    modifier = itemModifier.clickable(onClick = onAddToHome),
                )
                ListItem(
                    headlineContent = { Text("Block element") },
                    supportingContent = { Text("Tap anything on the page to hide it") },
                    leadingContent = { Icon(Icons.Rounded.Block, null) },
                    modifier = itemModifier.clickable(onClick = onBlockElement),
                )
                ListItem(
                    headlineContent = { Text("Desktop site") },
                    leadingContent = { Icon(Icons.Rounded.DesktopWindows, null) },
                    trailingContent = { Switch(checked = tab!!.desktopMode, onCheckedChange = { tab.toggleDesktopMode() }) },
                )
                ListItem(
                    headlineContent = { Text("Unlock scrolling") },
                    supportingContent = { Text("For pages stuck behind a hidden pop-up") },
                    leadingContent = { Icon(Icons.Rounded.SwipeVertical, null) },
                    modifier = itemModifier.clickable(onClick = onUnlockScrolling),
                )
                if (looksLikeFilterList(tab!!.url)) {
                    ListItem(
                        headlineContent = { Text("Add page as filter list") },
                        supportingContent = { Text("Use this page's rules in the ad blocker") },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                        modifier = itemModifier.clickable(onClick = onAddAsFilterList),
                    )
                }
                ListItem(
                    headlineContent = { Text("Page diagnostics") },
                    leadingContent = { Icon(Icons.Rounded.BugReport, null) },
                    modifier = itemModifier.clickable(onClick = onDiagnostics),
                )
            }
            ListItem(
                headlineContent = { Text("Settings") },
                supportingContent = { Text("Filter lists, my filters, search engine, dark mode") },
                leadingContent = { Icon(Icons.Rounded.Settings, null) },
                modifier = itemModifier.clickable(onClick = onSettings),
            )
        }
    }
}

/** Plain-text list URLs (…/list.txt, raw?file=…txt) get "Add page as filter list". */
fun looksLikeFilterList(url: String): Boolean {
    val u = url.substringBefore('#').lowercase()
    return u.substringBefore('?').endsWith(".txt") || Regex("[?&]file=[^&]*\\.txt").containsMatchIn(u)
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
