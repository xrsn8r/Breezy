package dev.breezy.browser.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.breezy.browser.AppSettings
import dev.breezy.browser.SearchEngine
import dev.breezy.browser.adblock.FilterList
import dev.breezy.browser.adblock.Shield
import dev.breezy.browser.adblock.UserFilters
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onDarkPagesChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val state by Shield.state.collectAsState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var addDialog by remember { mutableStateOf(false) }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val myRules by UserFilters.rules.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                subtitle = { Text("Breezy ${dev.breezy.browser.BuildConfig.VERSION_NAME}") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(state.pendingChanges || state.updating, enter = scaleIn(), exit = scaleOut()) {
                ExtendedFloatingActionButton(
                    onClick = { if (!state.updating) scope.launch { Shield.applyListChanges(app) } },
                    icon = {
                        if (state.updating) LoadingIndicator(Modifier.size(24.dp))
                        else Icon(Icons.Rounded.Check, null)
                    },
                    text = { Text(if (state.updating) "Building engine…" else "Apply list changes") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // --- Appearance ----------------------------------------------------------------------
            item(key = "appearance") {
                SettingsGroup(
                    title = "Appearance",
                    summary = buildList {
                        add(AppSettings.themeMode.label + if (AppSettings.pureBlack) " · pure black" else "")
                        if (AppSettings.darkWebPages) add("dark websites")
                    }.joinToString(" · "),
                    icon = Icons.Rounded.Palette,
                ) {
                    ListItem(
                        headlineContent = { Text("Theme") },
                        supportingContent = {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                Segmented(dev.breezy.browser.ThemeMode.entries, AppSettings.themeMode, { it.label },
                                    AppSettings::updateThemeMode)
                            }
                        },
                        colors = cardItemColors(),
                    )
                    ToggleRow("Pure black (AMOLED)", "True black backgrounds in dark theme. Saves battery on OLED screens",
                        AppSettings.pureBlack, AppSettings::updatePureBlack)
                    ToggleRow("Dark mode for websites", "Sites that have a dark theme use it", AppSettings.darkWebPages) {
                        AppSettings.updateDarkWebPages(it)
                        onDarkPagesChanged()
                    }
                    ListItem(
                        headlineContent = { Text("Force dark on all sites") },
                        supportingContent = { Text("Recolors sites without a dark theme. Can make some pages unreadable") },
                        trailingContent = {
                            Switch(
                                checked = AppSettings.forceDarkAllSites,
                                enabled = AppSettings.darkWebPages,
                                onCheckedChange = {
                                    AppSettings.updateForceDarkAllSites(it)
                                    onDarkPagesChanged()
                                },
                            )
                        },
                        colors = cardItemColors(),
                    )
                    ToggleRow("Frosted toolbar blur", "Real blur behind the toolbar (Android 16+). Turn off if some pages render blank or half-drawn",
                        AppSettings.frostedToolbar, AppSettings::updateFrostedToolbar)
                    ListItem(
                        headlineContent = { Text("Toolbar position") },
                        supportingContent = { Text("Long-press the toolbar and drag it up or down") },
                        trailingContent = {
                            TextButton(onClick = { AppSettings.updateToolbarPosition(0f) },
                                enabled = AppSettings.toolbarPosition != 0f) { Text("Reset") }
                        },
                        colors = cardItemColors(),
                    )
                }
            }

            // --- Browsing ------------------------------------------------------------------------
            item(key = "browsing") {
                SettingsGroup(
                    title = "Search & browsing",
                    summary = "${AppSettings.searchEngine.label} · text ${AppSettings.textZoom}%",
                    icon = Icons.Rounded.TravelExplore,
                ) {
                    ListItem(
                        headlineContent = { Text("Search engine") },
                        supportingContent = {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                Segmented(SearchEngine.entries, AppSettings.searchEngine, { it.label.removeSuffix(" Search") },
                                    AppSettings::updateSearchEngine)
                            }
                        },
                        colors = cardItemColors(),
                    )
                    ToggleRow("Search suggestions", "Ask ${AppSettings.searchEngine.label} for suggestions as you type",
                        AppSettings.searchSuggestions, AppSettings::updateSearchSuggestions)
                    ListItem(
                        headlineContent = { Text("Default text size: ${AppSettings.textZoom}%") },
                        supportingContent = {
                            androidx.compose.material3.Slider(
                                value = AppSettings.textZoom.toFloat(),
                                onValueChange = { AppSettings.updateTextZoom(((it / 10).roundToInt()) * 10) },
                                valueRange = 50f..200f, steps = 14,
                            )
                        },
                        colors = cardItemColors(),
                    )
                    ToggleRow("Pull to refresh", "Swipe down at the top of a page to reload it",
                        AppSettings.pullToRefresh, AppSettings::updatePullToRefresh)
                    ToggleRow("Force allow zoom", "Pinch-zoom on sites that try to disable it",
                        AppSettings.forceZoom, AppSettings::updateForceZoom)
                }
            }

            // --- Privacy & security --------------------------------------------------------------
            item(key = "privacy") {
                var confirmClear by remember { mutableStateOf(false) }
                SettingsGroup(
                    title = "Privacy & security",
                    summary = buildList {
                        if (AppSettings.httpsOnly) add("HTTPS-only")
                        add(if (AppSettings.dnsEndpoint != null) "DNS: ${AppSettings.dnsProvider.label}" else "Secure DNS off")
                        if (AppSettings.clearOnExit) add("clear on exit")
                    }.joinToString(" · "),
                    icon = Icons.Rounded.Lock,
                ) {
                    ToggleRow("HTTPS-only mode", "Upgrade every site to HTTPS and warn before using an insecure connection",
                        AppSettings.httpsOnly, AppSettings::updateHttpsOnly)
                    ToggleRow("Strip tracking parameters", "Remove utm_*, fbclid, gclid and similar from links you open",
                        AppSettings.stripTracking, AppSettings::updateStripTracking)
                    ToggleRow("Clear data on exit", "Wipe cookies, site data, history and tabs when you leave Breezy",
                        AppSettings.clearOnExit, AppSettings::updateClearOnExit)
                    ListItem(
                        headlineContent = { Text("Clear browsing data now") },
                        supportingContent = { Text("Cookies, site storage, cache and history") },
                        colors = cardItemColors(),
                        modifier = Modifier.clickable { confirmClear = true },
                    )
                    SubHeader("Secure DNS")
                    SecureDnsRows()
                }
                if (confirmClear) {
                    AlertDialog(
                        onDismissRequest = { confirmClear = false },
                        title = { Text("Clear browsing data?") },
                        text = { Text("You'll be signed out of sites. Open tabs stay open.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmClear = false
                                scope.launch { dev.breezy.browser.data.SiteData.clearAll(app) }
                            }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
                    )
                }
            }

            // --- Shields -------------------------------------------------------------------------
            item(key = "shields") {
                SettingsGroup(
                    title = "Shields",
                    summary = if (state.enabled) "${numbers.format(state.totalBlocked)} blocked · ${state.enabledLists.size} lists"
                    else "Off",
                    icon = Icons.Rounded.Shield,
                ) {
                    ListItem(
                        headlineContent = { Text("Block ads & trackers") },
                        supportingContent = { Text("${numbers.format(state.totalBlocked)} blocked so far") },
                        trailingContent = { Switch(checked = state.enabled, onCheckedChange = Shield::setEnabled) },
                        colors = cardItemColors(),
                    )
                    ToggleRow("Update lists automatically", "Weekly, on Wi-Fi",
                        AppSettings.autoUpdateLists, AppSettings::updateAutoUpdateLists)
                    if (state.allowlist.isNotEmpty()) {
                        SubHeader("Shields down on")
                        state.allowlist.sorted().forEach { host ->
                            ListItem(
                                headlineContent = { Text(host) },
                                trailingContent = {
                                    IconButton(onClick = { Shield.setSiteAllowed(host, false) }) {
                                        Icon(Icons.Rounded.Delete, "Turn shields back on for $host")
                                    }
                                },
                                colors = cardItemColors(),
                            )
                        }
                    }
                    SubHeader("My filters")
                    if (myRules.isEmpty()) {
                        ListItem(
                            headlineContent = { Text("No custom rules yet") },
                            supportingContent = { Text("Use Menu → Block element on any page to hide things you don't want to see") },
                            colors = cardItemColors(),
                        )
                    }
                    myRules.groupBy { it.host }.toSortedMap().forEach { (host, rules) ->
                        rules.forEach { rule ->
                            ListItem(
                                headlineContent = { Text(host) },
                                supportingContent = {
                                    Text(rule.selector, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                                },
                                trailingContent = {
                                    IconButton(onClick = { UserFilters.remove(rule) }) {
                                        Icon(Icons.Rounded.Delete, "Delete rule for $host")
                                    }
                                },
                                colors = cardItemColors(),
                            )
                        }
                    }
                }
            }

            // --- Filter lists --------------------------------------------------------------------
            item(key = "lists") {
                SettingsGroup(
                    title = "Filter lists",
                    summary = state.lastError
                        ?: if (state.listsUpdatedAt > 0) "Updated " + DateUtils.getRelativeTimeSpanString(state.listsUpdatedAt)
                        else "Using lists bundled with the app",
                    icon = Icons.Rounded.FilterList,
                    initiallyExpanded = state.pendingChanges,
                ) {
                    ListItem(
                        headlineContent = { Text("Update all lists") },
                        supportingContent = { Text("Download the latest versions now") },
                        trailingContent = {
                            FilledTonalButton(onClick = { scope.launch { Shield.updateLists(app) } }, enabled = !state.updating) {
                                Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Update")
                            }
                        },
                        colors = cardItemColors(),
                    )
                    val grouped = state.lists.groupBy { it.category }
                    FilterList.Category.entries.forEach { category ->
                        val lists = grouped[category].orEmpty()
                        if (lists.isEmpty() && category != FilterList.Category.Custom) return@forEach
                        SubHeader(category.label)
                        lists.forEach { list ->
                            FilterListRow(
                                list = list,
                                enabled = list.id in state.enabledLists,
                                active = list.id in state.compiledLists,
                                meta = state.listMeta[list.id],
                                numbers = numbers,
                                onToggle = { Shield.setListEnabled(list.id, it) },
                                onDelete = { Shield.removeCustomList(list.id) },
                            )
                        }
                        if (category == FilterList.Category.Custom) {
                            ListItem(
                                headlineContent = { Text("Add filter list") },
                                supportingContent = { Text("Any ABP / uBlock Origin list by URL") },
                                leadingContent = { Icon(Icons.Rounded.Add, null) },
                                colors = cardItemColors(),
                                modifier = Modifier.clickable { addDialog = true },
                            )
                        }
                    }
                    Text(
                        "Powered by Brave's adblock-rust engine. Changes to the selection are compiled into a " +
                            "new engine on this device when you apply them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            // --- Downloads & media ---------------------------------------------------------------
            item(key = "media") {
                SettingsGroup(
                    title = "Downloads & media",
                    summary = "${AppSettings.downloadConnections} connections" + if (AppSettings.backgroundPlay) " · background play" else "",
                    icon = Icons.Rounded.Download,
                ) {
                    ListItem(
                        headlineContent = { Text("Connections per download: ${AppSettings.downloadConnections}") },
                        supportingContent = {
                            Column {
                                Text("More connections can be faster on servers that support it. Files are saved to Download/Breezy.")
                                androidx.compose.material3.Slider(
                                    value = AppSettings.downloadConnections.toFloat(),
                                    onValueChange = { AppSettings.updateDownloadConnections(it.roundToInt()) },
                                    valueRange = 1f..8f, steps = 6,
                                )
                            }
                        },
                        colors = cardItemColors(),
                    )
                    ToggleRow("Background play", "Keep audio and video playing with the screen off, with media controls",
                        AppSettings.backgroundPlay, AppSettings::updateBackgroundPlay)
                    ListItem(
                        headlineContent = { Text("Picture-in-picture") },
                        supportingContent = { Text("Leave the app while a video is fullscreen to keep watching in a small window") },
                        colors = cardItemColors(),
                    )
                }
            }

            // --- Advanced ------------------------------------------------------------------------
            item(key = "advanced") {
                SettingsGroup(
                    title = "Advanced",
                    summary = (if (AppSettings.identifyAsChrome) "Identifying as Chrome" else "Identifying as WebView") +
                        " · updates " + (if (AppSettings.autoCheckUpdates) "on" else "off"),
                    icon = Icons.Rounded.Tune,
                ) {
                    ToggleRow("Identify as Chrome", "Helps Google/YouTube sign-in. Turn off if a site misbehaves (reloads tabs)",
                        AppSettings.identifyAsChrome) {
                        AppSettings.updateIdentifyAsChrome(it)
                        onDarkPagesChanged() // recreates tabs so the new identity applies
                    }
                    ToggleRow("Check for updates automatically", "Look for a new Breezy release on GitHub once a day",
                        AppSettings.autoCheckUpdates, AppSettings::updateAutoCheckUpdates)
                    UpdateRow()
                    ListItem(
                        headlineContent = { Text("Share diagnostics") },
                        supportingContent = { Text("Recent app exits and events, to report a bug. No pages or history") },
                        colors = cardItemColors(),
                        modifier = Modifier.clickable {
                            val text = dev.breezy.browser.data.CrashReport.diagnostics(context)
                            context.startActivity(android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "Breezy diagnostics")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, text),
                                "Share diagnostics"))
                        },
                    )
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    if (addDialog) AddListDialog(onDismiss = { addDialog = false })
}

@Composable
private fun FilterListRow(
    list: FilterList,
    enabled: Boolean,
    active: Boolean,
    meta: Shield.ListMeta?,
    numbers: NumberFormat,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val details = buildList {
        add(list.description)
        meta?.let {
            add("${numbers.format(it.rules)} rules · ${Formatter.formatShortFileSize(context, it.bytes)}")
        }
        if (enabled && !active) add("Not applied yet")
        if (!enabled && active) add("Will be removed")
    }
    ListItem(
        headlineContent = { Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(details.joinToString("\n"), maxLines = 3, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (list.custom) {
                    IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Remove ${list.name}") }
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        },
        colors = cardItemColors(),
    )
}

@Composable
private fun AddListDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add filter list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it; error = null },
                    label = { Text("List URL") }, singleLine = true, isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(32.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Downloading and checking…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        error = Shield.addCustomList(url, name)
                        busy = false
                        if (error == null) onDismiss()
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/**
 * A collapsible settings group: a card whose header shows the group's icon, title and a summary of
 * its current values; tapping it expands the rows. Open/closed state survives rotation.
 */
@Composable
private fun SettingsGroup(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val rotation by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    SettingsCard {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            },
            trailingContent = {
                Icon(Icons.Rounded.ExpandMore, if (expanded) "Collapse" else "Expand",
                    Modifier.graphicsLayer { rotationZ = rotation })
            },
            colors = cardItemColors(),
            modifier = Modifier.clickable { expanded = !expanded },
        )
        AnimatedVisibility(
            expanded,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) {
            Column {
                androidx.compose.material3.HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                content()
            }
        }
    }
}

/** Connected button group for picking one of a few options. */
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, option ->
            ToggleButton(
                checked = selected == option,
                onCheckedChange = { onSelect(option) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(label(option), maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp)),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun cardItemColors() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

@Composable
private fun ToggleRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = cardItemColors(),
    )
}

@Composable
private fun UpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = dev.breezy.browser.data.Updater.status
    var notes by remember { mutableStateOf<dev.breezy.browser.data.Updater.Release?>(null) }
    notes?.let { r ->
        UpdateDialog(r, onInstall = { notes = null; dev.breezy.browser.data.Updater.download(r) }, onDismiss = { notes = null })
    }
    val (headline, detail) = when (status) {
        is dev.breezy.browser.data.Updater.Status.Available -> "Update to ${status.release.version}" to "Download and install the new version"
        is dev.breezy.browser.data.Updater.Status.Downloading -> "Downloading ${status.release.version}…" to "Progress is in Downloads; the installer opens when it's done"
        dev.breezy.browser.data.Updater.Status.Verifying -> "Verifying download…" to "Checking it against the published checksum"
        dev.breezy.browser.data.Updater.Status.Checking -> "Checking for updates…" to "Breezy ${dev.breezy.browser.BuildConfig.VERSION_NAME}"
        dev.breezy.browser.data.Updater.Status.UpToDate -> "Breezy is up to date" to "Version ${dev.breezy.browser.BuildConfig.VERSION_NAME}"
        is dev.breezy.browser.data.Updater.Status.Failed -> "Check for updates" to status.message
        dev.breezy.browser.data.Updater.Status.Idle -> "Check for updates" to "Version ${dev.breezy.browser.BuildConfig.VERSION_NAME}"
    }
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(detail) },
        trailingContent = {
            if (status is dev.breezy.browser.data.Updater.Status.Checking || status is dev.breezy.browser.data.Updater.Status.Verifying) {
                LoadingIndicator(Modifier.size(28.dp))
            }
        },
        colors = cardItemColors(),
        modifier = Modifier.clickable {
            when (status) {
                is dev.breezy.browser.data.Updater.Status.Available -> notes = status.release
                is dev.breezy.browser.data.Updater.Status.Downloading, dev.breezy.browser.data.Updater.Status.Verifying,
                dev.breezy.browser.data.Updater.Status.Checking -> Unit
                else -> scope.launch { dev.breezy.browser.data.Updater.check(context.applicationContext) }
            }
        },
    )
}

@Composable
private fun SecureDnsRows() {
    val scope = rememberCoroutineScope()
    var custom by remember { mutableStateOf(AppSettings.customDnsUrl) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val supported = dev.breezy.browser.data.NetworkPrivacy.supported
    fun apply(provider: dev.breezy.browser.data.DnsProvider, strict: Boolean = AppSettings.dnsStrict) {
        AppSettings.updateDns(provider, custom, strict)
        dev.breezy.browser.data.NetworkPrivacy.apply()
        testResult = null
    }
    Column {
        Text(
            if (supported) "Looks up sites over an encrypted connection, so your network can't see or tamper with them."
            else "Your WebView version doesn't support this. Update Android System WebView.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        dev.breezy.browser.data.DnsProvider.entries.forEach { p ->
            ListItem(
                headlineContent = { Text(p.label) },
                supportingContent = { Text(p.note) },
                leadingContent = {
                    androidx.compose.material3.RadioButton(selected = AppSettings.dnsProvider == p, onClick = { apply(p) }, enabled = supported)
                },
                colors = cardItemColors(),
                modifier = Modifier.clickable(enabled = supported) { apply(p) },
            )
        }
        if (AppSettings.dnsProvider == dev.breezy.browser.data.DnsProvider.Custom) {
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                label = { Text("DoH URL (https://…/dns-query)") },
                singleLine = true,
                isError = custom.isNotBlank() && !custom.startsWith("https://"),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            TextButton(onClick = { apply(dev.breezy.browser.data.DnsProvider.Custom) }, modifier = Modifier.padding(start = 8.dp)) {
                Text("Save")
            }
        }
        if (AppSettings.dnsEndpoint != null) {
            ToggleRow("Strict mode", "Never fall back to your network's DNS if the provider can't be reached",
                AppSettings.dnsStrict) { apply(AppSettings.dnsProvider, it) }
            ListItem(
                headlineContent = { Text("Test connection") },
                supportingContent = { Text(testResult ?: "Check that the provider answers") },
                colors = cardItemColors(),
                modifier = Modifier.clickable {
                    val url = AppSettings.dnsEndpoint ?: return@clickable
                    testResult = "Testing…"
                    scope.launch {
                        testResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { "Working · ${dev.breezy.browser.data.SecureDns.test(url)} ms" }
                                .getOrElse { "Failed: ${it.message}" }
                        }
                    }
                },
            )
        }
    }
}
