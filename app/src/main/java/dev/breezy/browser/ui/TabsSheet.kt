package dev.breezy.browser.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.breezy.browser.AppSettings
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.breezy.browser.browser.BrowserTab
import dev.breezy.browser.browser.UrlInput

// --- Tabs ------------------------------------------------------------------------------------

@Composable
fun TabsSheet(
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    tabs: List<BrowserTab>,
    current: BrowserTab?,
    onSelect: (BrowserTab) -> Unit,
    onClose: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onDismiss: () -> Unit,
) {
    BreezySheet(hazeState, onDismissRequest = onDismiss) {
        Spacer(Modifier.height(20.dp))
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
                // Swipe sideways to close (Settings → Search & browsing); the card fades as it goes.
                val dismiss = androidx.compose.material3.rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.35f })
                androidx.compose.material3.SwipeToDismissBox(
                    state = dismiss,
                    backgroundContent = {},
                    enableDismissFromStartToEnd = AppSettings.swipeToCloseTabs,
                    enableDismissFromEndToStart = AppSettings.swipeToCloseTabs,
                    onDismiss = { onClose(t) },
                    modifier = Modifier.graphicsLayer {
                        val f = (1f - kotlin.math.abs(runCatching { dismiss.requireOffset() }.getOrDefault(0f)) / (size.width.coerceAtLeast(1f))).coerceIn(0f, 1f)
                        alpha = 0.3f + 0.7f * f
                    },
                ) {
                    Card(
                        onClick = { onSelect(t) },
                        shape = RoundedCornerShape(corner.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) frostedFill(MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.primaryContainer)
                            else frostedFill(MaterialTheme.colorScheme.surfaceContainerHigh),
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
}
