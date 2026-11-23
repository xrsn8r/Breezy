package dev.breezy.browser.ui

import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import dev.breezy.browser.adblock.Shield
import dev.breezy.browser.browser.BrowserTab
import dev.breezy.browser.browser.TabManager
import dev.breezy.browser.browser.UrlInput
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

@Composable
fun BrowserScreen(
    tabs: TabManager,
    onNewTab: (String?) -> Unit,
    onCloseTab: (BrowserTab) -> Unit,
    onShare: (String) -> Unit,
    onExit: () -> Unit,
) {
    val tab = tabs.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shield by Shield.state.collectAsState()
    val hazeState = rememberHazeState()

    var omniboxOpen by rememberSaveable { mutableStateOf(false) }
    var tabsOpen by rememberSaveable { mutableStateOf(false) }
    var shieldOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var toolbarExpanded by remember { mutableStateOf(true) }

    val showHome = tab.url.isEmpty()
    val host = remember(tab.url) { tab.url.toUri().host }
    val overlayOpen = omniboxOpen || settingsOpen

    BackHandler(enabled = !overlayOpen && tab.canGoBack) { tab.goBack() }
    BackHandler(enabled = !overlayOpen && !tab.canGoBack && tabs.tabs.size > 1) { onCloseTab(tab) }
    BackHandler(enabled = !overlayOpen && !tab.canGoBack && tabs.tabs.size <= 1 && !showHome) { onExit() }

    fun navigate(input: String) {
        val url = UrlInput.resolve(input)
        if (url.isNotEmpty()) tab.load(url)
        omniboxOpen = false
        toolbarExpanded = true
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            // --- Web content (also the source the frosted toolbar blurs) ----------------------
            Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                key(tab.id) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .windowInsetsPadding(WindowInsets.navigationBars),
                        factory = {
                            tab.webView.apply {
                                (parent as? ViewGroup)?.removeView(this)
                                setOnScrollChangeListener { _, _, y, _, oldY ->
                                    if (y > oldY + 12) toolbarExpanded = false
                                    else if (y < oldY - 12 || y == 0) toolbarExpanded = true
                                }
                            }
                        },
                    )
                }

                AnimatedVisibility(showHome, enter = fadeIn(), exit = fadeOut()) {
                    HomePage(
                        totalBlocked = shield.totalBlocked,
                        onSearch = { omniboxOpen = true },
                        onOpen = ::navigate,
                    )
                }
            }

            // --- Expressive wavy progress -----------------------------------------------------
            AnimatedVisibility(
                visible = tab.loading,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.statusBarsPadding().align(Alignment.TopCenter),
            ) {
                LinearWavyProgressIndicator(
                    progress = { tab.progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }

            // --- Frosted, movable floating toolbar --------------------------------------------
            MovableBrowserBar(
                hazeState = hazeState,
                expanded = toolbarExpanded || showHome,
                tab = tab,
                tabCount = tabs.tabs.size,
                shieldsActive = shield.ready && shield.enabled && host !in shield.allowlist,
                onShield = { shieldOpen = true },
                onAddress = { omniboxOpen = true },
                onTabs = { tabsOpen = true },
                onMenu = { menuOpen = true },
                onSearch = { omniboxOpen = true },
            )

            // --- Omnibox overlay ---------------------------------------------------------------
            AnimatedVisibility(
                visible = omniboxOpen,
                enter = fadeIn() + slideInVertically { it / 8 },
                exit = fadeOut() + slideOutVertically { it / 8 },
            ) {
                Omnibox(
                    initial = tab.url,
                    tabs = tabs.tabs,
                    onGo = ::navigate,
                    onSwitchTab = { tabs.select(it); omniboxOpen = false },
                    onDismiss = { omniboxOpen = false },
                )
            }

            // --- Settings ----------------------------------------------------------------------
            AnimatedVisibility(
                visible = settingsOpen,
                enter = fadeIn() + slideInHorizontally { it / 4 },
                exit = fadeOut() + slideOutHorizontally { it / 4 },
            ) {
                SettingsScreen(
                    onDarkPagesChanged = { tabs.recreateAll() },
                    onDismiss = { settingsOpen = false },
                )
            }
        }
    }

    if (tabsOpen) {
        TabsSheet(
            tabs = tabs.tabs,
            current = tab,
            onSelect = { tabs.select(it); tabsOpen = false },
            onClose = onCloseTab,
            onNewTab = { onNewTab(null); tabsOpen = false; omniboxOpen = true },
            onDismiss = { tabsOpen = false },
        )
    }

    if (shieldOpen) {
        ShieldSheet(
            tab = tab,
            state = shield,
            onSiteToggle = { siteHost, on ->
                Shield.setSiteAllowed(siteHost, allowed = !on)
                tab.reload()
            },
            onGlobalToggle = { Shield.setEnabled(it); tab.reload() },
            onUpdateLists = { scope.launch { Shield.updateLists(context.applicationContext) } },
            onManageLists = { shieldOpen = false; settingsOpen = true },
            onDismiss = { shieldOpen = false },
        )
    }

    if (menuOpen) {
        MenuSheet(
            tab = tab,
            onNewTab = { onNewTab(null); menuOpen = false; omniboxOpen = true },
            onShare = { onShare(tab.url); menuOpen = false },
            onSettings = { menuOpen = false; settingsOpen = true },
            onUnlockScrolling = {
                menuOpen = false
                tab.unlockScrolling { n ->
                    Toast.makeText(
                        context,
                        if (n > 0) "Scrolling unlocked · $n overlay${if (n == 1) "" else "s"} disabled" else "Scrolling unlocked",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDiagnostics = { menuOpen = false; tab.diagnostics { diagnostics = it } },
            onDismiss = { menuOpen = false },
        )
    }

    diagnostics?.let { report ->
        DiagnosticsDialog(report = report, onDismiss = { diagnostics = null })
    }
}
