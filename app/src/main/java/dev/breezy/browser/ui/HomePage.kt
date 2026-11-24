package dev.breezy.browser.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.breezy.browser.AppSettings
import dev.breezy.browser.Shortcut
import dev.breezy.browser.browser.UrlInput
import java.text.NumberFormat

@Composable
fun HomePage(totalBlocked: Long, onOpen: (String) -> Unit) {
    val spin = rememberInfiniteTransition(label = "logo")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000), RepeatMode.Restart), label = "angle")
    var editing by remember { mutableStateOf<Shortcut?>(null) }
    var adding by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(32.dp))
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
            ShortcutGrid(
                shortcuts = AppSettings.shortcuts,
                onOpen = onOpen,
                onEdit = { editing = it },
                onAdd = { adding = true },
            )
            Spacer(Modifier.height(120.dp))
        }
    }

    if (adding) {
        ShortcutDialog(
            title = "Add shortcut",
            initial = null,
            onSave = { AppSettings.addShortcut(it); adding = false },
            onRemove = null,
            onDismiss = { adding = false },
        )
    }
    editing?.let { shortcut ->
        ShortcutDialog(
            title = "Edit shortcut",
            initial = shortcut,
            onSave = { AppSettings.updateShortcut(shortcut, it); editing = null },
            onRemove = { AppSettings.removeShortcut(shortcut); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutGrid(
    shortcuts: List<Shortcut>,
    onOpen: (String) -> Unit,
    onEdit: (Shortcut) -> Unit,
    onAdd: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        maxItemsInEachRow = 4,
        modifier = Modifier.fillMaxWidth(),
    ) {
        shortcuts.forEachIndexed { i, s ->
            ShortcutTile(
                label = s.name,
                letter = s.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
                url = s.url,
                shape = MaterialShapes.Circle,
                onClick = { onOpen(s.url) },
                onLongClick = { onEdit(s) },
                accent = i % 3,
            )
        }
        ShortcutTile(label = "Add", letter = null, shape = MaterialShapes.Circle, onClick = onAdd, onLongClick = onAdd, accent = -1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutTile(
    label: String,
    letter: String?,
    url: String? = null,
    shape: androidx.graphics.shapes.RoundedPolygon,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    accent: Int,
) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (accent) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.secondaryContainer to colors.onSecondaryContainer
        2 -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    // The site's own icon, like in the tab switcher; the letter shows until (or unless) one exists.
    val icon by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, url, dev.breezy.browser.data.Favicons.version) {
        value = url?.let { dev.breezy.browser.data.Favicons.load(it) }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
                onLongClickLabel = if (letter == null) null else "Edit shortcut",
            )
            .padding(vertical = 6.dp),
    ) {
        val bitmap = icon
        // Every tile is the same circle: the site's icon centred in it, or a letter if there's none.
        Box(
            Modifier.size(56.dp).clip(shape.toShape()).background(if (bitmap != null) colors.surfaceContainerHigh else bg),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap.asImageBitmap(),
                    contentDescription = null,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    modifier = Modifier.size(32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                )
            } else if (letter == null) Icon(Icons.Rounded.Add, contentDescription = null, tint = fg)
            else Text(letter, color = fg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ShortcutDialog(
    title: String,
    initial: Shortcut?,
    onSave: (Shortcut) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    val resolved = UrlInput.resolve(url)
    val valid = url.isNotBlank() && !UrlInput.isSearch(resolved)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text("Address") }, singleLine = true,
                    isError = url.isNotBlank() && !valid,
                    supportingText = if (url.isNotBlank() && !valid) {{ Text("Enter a web address, like example.com") }} else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                    placeholder = { Text(UrlInput.display(resolved).ifEmpty { "Optional" }) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(Shortcut(name.trim().ifEmpty { UrlInput.display(resolved) }, resolved))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
