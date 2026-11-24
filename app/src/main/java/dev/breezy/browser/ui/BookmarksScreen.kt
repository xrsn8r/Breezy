package dev.breezy.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.breezy.browser.AppSettings
import dev.breezy.browser.Shortcut
import dev.breezy.browser.browser.UrlInput
import dev.breezy.browser.data.Bookmark
import dev.breezy.browser.data.Bookmarks
import dev.breezy.browser.data.Favicons

/** Saved pages: search, filter by folder, open, edit, add to home, delete. */
@Composable
fun BookmarksScreen(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val all by Bookmarks.all.collectAsState()
    var query by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf<String?>(null) } // null = all
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    val folders = all.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()
    val shown = all.filter { b ->
        (folder == null || b.folder == folder) &&
            (query.isBlank() || b.title.contains(query, true) || b.url.contains(query, true))
    }.sortedWith(compareBy({ it.folder.lowercase() }, { it.title.lowercase() }))

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Bookmarks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search bookmarks") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true, shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (folders.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = folder == null, onClick = { folder = null }, label = { Text("All") })
                    folders.forEach { f ->
                        FilterChip(
                            selected = folder == f, onClick = { folder = if (folder == f) null else f },
                            label = { Text(f) }, leadingIcon = { Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Bookmark, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (all.isEmpty()) "No bookmarks yet. Tap the star in the menu to save a page." else "No matches",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.navigationBarsPadding()) {
                items(shown, key = { it.id }) { b ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        supportingContent = {
                            Text(UrlInput.display(b.url) + if (folder == null && b.folder.isNotBlank()) " · ${b.folder}" else "",
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { SiteIcon(b.url) },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; editing = b })
                                    DropdownMenuItem(text = { Text("Add to home") }, onClick = {
                                        menu = false; AppSettings.addShortcut(Shortcut(b.title.take(40), b.url))
                                    })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; Bookmarks.remove(b) })
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        modifier = Modifier.clickable { onOpen(b.url) },
                    ) { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
    editing?.let { b -> BookmarkDialog(b, onDismiss = { editing = null }) }
}

/** Edit a bookmark's title, address and folder (pick an existing folder or type a new one). */
@Composable
fun BookmarkDialog(bookmark: Bookmark, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(bookmark.title) }
    var url by remember { mutableStateOf(bookmark.url) }
    var folder by remember { mutableStateOf(bookmark.folder) }
    val existing = Bookmarks.folders
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit bookmark") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("Address") }, singleLine = true)
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder (optional)") }, singleLine = true)
                if (existing.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        existing.forEach { f -> FilterChip(selected = folder == f, onClick = { folder = f }, label = { Text(f) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val resolved = UrlInput.resolve(url.trim())
                Bookmarks.update(bookmark.copy(title = title.trim().ifEmpty { UrlInput.display(resolved) }, url = resolved, folder = folder))
                onDismiss()
            }, enabled = url.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A site's saved icon in a small circle, or a placeholder. */
@Composable
internal fun SiteIcon(url: String, size: Int = 36) {
    val icon by produceState<android.graphics.Bitmap?>(null, url, Favicons.version) { value = Favicons.load(url) }
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = icon
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size((size * 0.6f).dp).clip(RoundedCornerShape(4.dp)))
        else Icon(Icons.Rounded.Bookmark, null, Modifier.size((size * 0.5f).dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
