package dev.breezy.browser.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import java.text.NumberFormat

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
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            // --- Browsing ------------------------------------------------------------------------
            item { SectionHeader("Browsing") }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Dark mode for websites") },
                        supportingContent = { Text("Sites use their dark theme, or are darkened automatically") },
                        trailingContent = {
                            Switch(checked = AppSettings.darkWebPages, onCheckedChange = {
                                AppSettings.updateDarkWebPages(it)
                                onDarkPagesChanged()
                            })
                        },
                        colors = cardItemColors(),
                    )
                    ListItem(
                        headlineContent = { Text("Search engine") },
                        supportingContent = {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                                    SearchEngine.entries.forEachIndexed { i, engine ->
                                        ToggleButton(
                                            checked = AppSettings.searchEngine == engine,
                                            onCheckedChange = { AppSettings.updateSearchEngine(engine) },
                                            shapes = when (i) {
                                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                                SearchEngine.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(engine.label.removeSuffix(" Search"), maxLines = 1,
                                                style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        },
                        colors = cardItemColors(),
                    )
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

            // --- Shields -------------------------------------------------------------------------
            item { SectionHeader("Shields") }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Block ads & trackers") },
                        supportingContent = { Text("${numbers.format(state.totalBlocked)} blocked so far") },
                        trailingContent = { Switch(checked = state.enabled, onCheckedChange = Shield::setEnabled) },
                        colors = cardItemColors(),
                    )
                    ListItem(
                        headlineContent = { Text("Update lists automatically") },
                        supportingContent = { Text("Weekly, on Wi-Fi") },
                        trailingContent = {
                            Switch(checked = AppSettings.autoUpdateLists, onCheckedChange = AppSettings::updateAutoUpdateLists)
                        },
                        colors = cardItemColors(),
                    )
                    if (state.allowlist.isNotEmpty()) {
                        state.allowlist.sorted().forEach { host ->
                            ListItem(
                                headlineContent = { Text(host) },
                                supportingContent = { Text("Shields down") },
                                trailingContent = {
                                    IconButton(onClick = { Shield.setSiteAllowed(host, false) }) {
                                        Icon(Icons.Rounded.Delete, "Turn shields back on for $host")
                                    }
                                },
                                colors = cardItemColors(),
                            )
                        }
                    }
                }
            }

            // --- Filter lists --------------------------------------------------------------------
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Filter lists", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(
                            state.lastError
                                ?: if (state.listsUpdatedAt > 0) "Updated " + DateUtils.getRelativeTimeSpanString(state.listsUpdatedAt)
                                else "Using lists bundled with the app",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.lastError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = { scope.launch { Shield.updateLists(app) } },
                        enabled = !state.updating,
                    ) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Update all")
                    }
                }
            }
            val grouped = state.lists.groupBy { it.category }
            FilterList.Category.entries.forEach { category ->
                val lists = grouped[category].orEmpty()
                if (lists.isEmpty() && category != FilterList.Category.Custom) return@forEach
                item(key = "cat-$category") { SubHeader(category.label) }
                item(key = "card-$category") {
                    SettingsCard {
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
                }
            }
            item {
                Text(
                    "Powered by Brave's adblock-rust engine. Changes to the selection are compiled into a " +
                        "new engine on this device when you apply them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp).navigationBarsPadding(),
                )
            }
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

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 6.dp),
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
