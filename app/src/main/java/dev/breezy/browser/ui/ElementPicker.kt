package dev.breezy.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.breezy.browser.browser.BrowserTab
import dev.breezy.browser.browser.UrlInput

/**
 * Bottom panel shown while "Block element" is active. The page stays interactive above it: swipe to
 * scroll, tap to select. The selection can be widened or narrowed, edited by hand, and previewed
 * before saving it as a "My filters" rule for this site.
 */
@Composable
fun ElementPickerPanel(
    tab: BrowserTab,
    state: BrowserTab.PickerState,
    onBlocked: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Block element", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            state.selector.isEmpty() -> "Tap something on the page · swipe to scroll"
                            state.count == 0 -> "No elements match"
                            else -> "${state.label} · ${state.count} match${if (state.count == 1) "" else "es"} on ${UrlInput.display(tab.url)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = tab::stopPicker) { Icon(Icons.Rounded.Close, contentDescription = "Cancel") }
            }

            if (state.selector.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.selector,
                    onValueChange = tab::pickerSetSelector,
                    singleLine = true,
                    label = { Text("CSS selector") },
                    isError = state.count == 0,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    ToggleButton(
                        checked = false,
                        onCheckedChange = { tab.pickerParent() },
                        enabled = state.canParent,
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                        colors = FilledTonalToggleButtonDefaults.colors(),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Bigger")
                    }
                    ToggleButton(
                        checked = false,
                        onCheckedChange = { tab.pickerChild() },
                        enabled = state.canChild,
                        shapes = ButtonGroupDefaults.connectedMiddleButtonShapes(),
                        colors = FilledTonalToggleButtonDefaults.colors(),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.UnfoldLess, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Smaller")
                    }
                    ToggleButton(
                        checked = state.preview,
                        onCheckedChange = tab::pickerPreview,
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(if (state.preview) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Preview")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { val sel = state.selector; if (tab.pickerBlock()) onBlocked(sel) },
                    enabled = state.count > 0,
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(end = 8.dp),
                ) {
                    Icon(Icons.Rounded.Block, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Block on this site")
                }
            }
        }
    }
}
