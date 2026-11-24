package dev.breezy.browser.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.breezy.browser.browser.UrlInput
import dev.breezy.browser.data.History
import dev.breezy.browser.data.HistoryEntry
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val version by History.version.collectAsState()
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(query, version) { entries = History.search(query) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding()) {
            androidx.compose.foundation.layout.Row(
                Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("History", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty() || query.isNotEmpty()) {
                    Icon(Icons.Rounded.DeleteSweep, "Clear history")
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search history") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true, shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (entries.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (query.isEmpty()) "No history yet" else "No matches",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.navigationBarsPadding()) {
                var lastDay = ""
                entries.forEach { e ->
                    val day = DateUtils.getRelativeTimeSpanString(e.lastVisit, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "h-$day-${e.lastVisit}") {
                            Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp))
                        }
                    }
                    item(key = e.url) {
                        ListItem(
                            supportingContent = { Text(UrlInput.display(e.url) + " · " +
                                DateUtils.formatDateTime(context, e.lastVisit, DateUtils.FORMAT_SHOW_TIME), maxLines = 1) },
                            trailingContent = {
                                IconButton(onClick = { scope.launch { History.delete(e.url) } }) { Icon(Icons.Rounded.Close, "Remove") }
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                            modifier = Modifier.clickable { onOpen(e.url) },
                        ) { Text(e.title.ifBlank { UrlInput.display(e.url) }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("Removes every visited page from history. Cookies and site data aren't affected.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { History.clear() } }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
