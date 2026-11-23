package dev.breezy.browser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
internal fun Favicon(tab: BrowserTab, size: Int = 24) {
    val icon = tab.favicon
    if (icon != null) {
        Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size.dp).clip(CircleShape))
    } else {
        Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(size.dp))
    }
}
