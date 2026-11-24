package dev.breezy.browser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.breezy.browser.AppSettings
import dev.breezy.browser.browser.BrowserTab
import dev.breezy.browser.browser.UrlInput

// --- Omnibox ---------------------------------------------------------------------------------

@Composable
fun Omnibox(
    initial: String,
    tabs: List<BrowserTab>,
    onGo: (String) -> Unit,
    onSwitchTab: (BrowserTab) -> Unit,
    onDismiss: () -> Unit,
    privateMode: Boolean = false,
    /** Blurred snapshot of the page to show behind the search screen; null for a solid background. */
    backdrop: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Frosted: the page's blurred snapshot behind translucent rows; otherwise the usual solid surface.
    val frosted = backdrop != null
    val rowColor = if (frosted) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surfaceContainer
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    if (backdrop != null) FrostedBackdrop(backdrop)
    androidx.compose.runtime.CompositionLocalProvider(LocalFrosted provides frosted) {
    Surface(
        color = if (frosted) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        // A transparent Surface can't derive a content colour; without this, icons turn black.
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize(),
    ) {
        // The text field sits at the bottom, just above the keyboard (where the toolbar was), with
        // suggestions stacking upward from it.
        Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
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
            // Drawn bottom-up (reverseLayout), so the first items sit right above the text field, a
            // thumb's reach from the keyboard: what you typed, then pages you've visited, then
            // the engine's suggestions, then open tabs furthest away.
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // A tap on the empty space above the suggestions goes back to the page. Rows
                    // handle their own taps first, and drags still scroll.
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
                reverseLayout = true,
            ) {
                if (query.isNotEmpty()) {
                    item {
                        val resolved = UrlInput.resolve(query)
                        val isSearch = UrlInput.isSearch(resolved)
                        ListItem(
                            supportingContent = { Text(if (isSearch) AppSettings.searchEngine.label else "Open address") },
                            leadingContent = {
                                Icon(if (isSearch) Icons.Rounded.Search else Icons.Rounded.Language, null)
                            },
                            colors = ListItemDefaults.colors(containerColor = rowColor),
                            modifier = Modifier.clickable { onGo(query) },
                        ) { Text(if (isSearch) "Search “$query”" else resolved, maxLines = 1) }
                    }
                }
                // Bookmarks first: pages you chose to keep beat pages you merely visited.
                val marks = dev.breezy.browser.data.Bookmarks.search(query, 2)
                items(marks, key = { "b-${it.id}" }) { b ->
                    ListItem(
                        supportingContent = { Text(UrlInput.display(b.url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = rowColor),
                        modifier = Modifier.clickable { onGo(b.url) },
                    ) { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                items(visited.filter { v -> marks.none { it.url == v.url } }.take(3), key = { "h-${it.url}" }) { h ->
                    ListItem(
                        supportingContent = { Text(h.url.removePrefix("https://").removePrefix("www."), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = rowColor),
                        modifier = Modifier.clickable { onGo(h.url) },
                    ) { Text(h.title.ifBlank { UrlInput.display(h.url) }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                items(suggestions.filter { !it.equals(query, true) }.take(4), key = { "s-$it" }) { sug ->
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            // Put the suggestion in the box to refine it, like Chrome's ↖ arrow.
                            IconButton(onClick = { value = TextFieldValue("$sug ", TextRange(sug.length + 1)) }) {
                                Icon(Icons.AutoMirrored.Rounded.CallMade, "Edit suggestion",
                                    modifier = Modifier.rotate(-90f))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = rowColor),
                        modifier = Modifier.clickable { onGo(sug) },
                    ) { Text(sug, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (matches.isNotEmpty()) {
                    items(matches, key = { it.id }) { t ->
                        ListItem(
                            supportingContent = { Text(UrlInput.display(t.url), maxLines = 1) },
                            leadingContent = { Favicon(t) },
                            colors = ListItemDefaults.colors(containerColor = rowColor),
                            modifier = Modifier.clickable { onSwitchTab(t) },
                        ) { Text(t.title.ifEmpty { "New tab" }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    item {
                        Text(
                            "Open tabs",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                }
            }
            Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        unfocusedContainerColor = frostedFill(MaterialTheme.colorScheme.surfaceContainerHighest),
                        focusedContainerColor = frostedFill(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onGo(value.text) }),
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
internal fun Favicon(tab: BrowserTab, size: Int = 24) {
    val icon = tab.favicon
    if (icon != null) {
        Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size.dp).clip(CircleShape))
    } else {
        Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(size.dp))
    }
}
