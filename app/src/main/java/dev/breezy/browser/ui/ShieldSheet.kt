package dev.breezy.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.breezy.browser.adblock.Shield
import dev.breezy.browser.browser.BrowserTab
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.launch

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
