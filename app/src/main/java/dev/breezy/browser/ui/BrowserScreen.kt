package dev.breezy.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
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
import dev.breezy.browser.AppSettings
import dev.breezy.browser.Shortcut
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
    // With no tab open we show the home page; it isn't a tab itself.
    val tab = tabs.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shield by Shield.state.collectAsState()
    val hazeState = rememberHazeState()

    var omniboxOpen by rememberSaveable { mutableStateOf(false) }
    var newTabMode by rememberSaveable { mutableStateOf(false) }
    var tabsOpen by rememberSaveable { mutableStateOf(false) }
    var shieldOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var addHome by remember { mutableStateOf<Shortcut?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var toolbarExpanded by remember { mutableStateOf(true) }

    val showHome = tab == null || tab.url.isEmpty()
    val host = remember(tab?.url) { tab?.url?.toUri()?.host }
    val overlayOpen = omniboxOpen || settingsOpen

    // Back: page history first; then close the tab (falls back to another tab or the home page);
    // on the home page, leave the app.
    BackHandler(enabled = !overlayOpen && tab != null) {
        if (tab!!.canGoBack) tab.goBack() else onCloseTab(tab)
    }

    fun openNewTab() {
        newTabMode = true
        omniboxOpen = true
    }

    fun navigate(input: String) {
        val url = UrlInput.resolve(input)
        if (url.isNotEmpty()) {
            if (tab == null || newTabMode) onNewTab(url) else tab.load(url)
        }
        omniboxOpen = false
        newTabMode = false
        toolbarExpanded = true
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            // --- Web content --------------------------------------------------------------------
            // The WebView must NOT be wrapped in hazeSource: that renders it into an offscreen
            // graphics layer, which breaks WebView's own compositing (backdrop-filter dialogs drawn
            // half-empty, login pages rendering blank). The toolbar's blur over web content uses
            // the native window backdrop on Android 16+ instead; older versions show a tint.
            Box(Modifier.fillMaxSize()) {
                if (tab != null) key(tab.id) {
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

                AnimatedVisibility(
                    showHome,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    // Compose-drawn, so it's safe to capture as the blur source on older Android.
                    modifier = Modifier.hazeSource(hazeState),
                ) {
                    HomePage(
                        totalBlocked = shield.totalBlocked,
                        onSearch = { omniboxOpen = true },
                        onOpen = ::navigate,
                    )
                }
            }

            // --- Expressive wavy progress -----------------------------------------------------
            AnimatedVisibility(
                visible = tab?.loading == true,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.statusBarsPadding().align(Alignment.TopCenter),
            ) {
                LinearWavyProgressIndicator(
                    progress = { tab?.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }

            // --- Element picker panel (replaces the toolbar while picking) --------------------
            val picking = tab?.picker
            AnimatedVisibility(
                visible = picking != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                tab?.picker?.let { state ->
                    ElementPickerPanel(tab = tab, state = state, onBlocked = {
                        Toast.makeText(context, "Blocked · manage in Settings → My filters", Toast.LENGTH_SHORT).show()
                    })
                }
            }
            BackHandler(enabled = picking != null) { tab?.stopPicker() }

            // --- Frosted, movable floating toolbar --------------------------------------------
            if (picking == null) MovableBrowserBar(
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
                onCopyLink = {
                    // Read the selected tab at copy time, not when this lambda was created.
                    val url = tabs.current?.url?.takeIf { it.isNotEmpty() } ?: return@MovableBrowserBar
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("Link", url))
                    // Android 13+ shows its own clipboard confirmation.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    }
                },
            )

            // --- Omnibox overlay ---------------------------------------------------------------
            AnimatedVisibility(
                visible = omniboxOpen,
                enter = fadeIn() + slideInVertically { it / 8 },
                exit = fadeOut() + slideOutVertically { it / 8 },
            ) {
                Omnibox(
                    initial = if (newTabMode) "" else tab?.url.orEmpty(),
                    tabs = tabs.tabs,
                    onGo = ::navigate,
                    onSwitchTab = { tabs.select(it); omniboxOpen = false; newTabMode = false },
                    onDismiss = { omniboxOpen = false; newTabMode = false },
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
            onNewTab = { tabsOpen = false; openNewTab() },
            onDismiss = { tabsOpen = false },
        )
    }

    if (shieldOpen) {
        ShieldSheet(
            tab = tab,
            state = shield,
            onSiteToggle = { siteHost, on ->
                Shield.setSiteAllowed(siteHost, allowed = !on)
                tab?.reload()
            },
            onGlobalToggle = { Shield.setEnabled(it); tab?.reload() },
            onUpdateLists = { scope.launch { Shield.updateLists(context.applicationContext) } },
            onManageLists = { shieldOpen = false; settingsOpen = true },
            onDismiss = { shieldOpen = false },
        )
    }

    if (menuOpen) {
        MenuSheet(
            tab = tab,
            onNewTab = { menuOpen = false; openNewTab() },
            onShare = { tab?.let { onShare(it.url) }; menuOpen = false },
            onSettings = { menuOpen = false; settingsOpen = true },
            onBlockElement = { menuOpen = false; tab?.startPicker() },
            onUnlockScrolling = {
                menuOpen = false
                tab?.unlockScrolling { n ->
                    Toast.makeText(
                        context,
                        if (n > 0) "Scrolling unlocked · $n overlay${if (n == 1) "" else "s"} disabled" else "Scrolling unlocked",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDiagnostics = { menuOpen = false; tab?.diagnostics { diagnostics = it } },
            onAddToHome = {
                menuOpen = false
                tab?.let { t ->
                    addHome = Shortcut(t.title.ifBlank { UrlInput.display(t.url) }.take(40), t.url)
                }
            },
            onAddAsFilterList = {
                menuOpen = false
                val t = tab ?: return@MenuSheet
                t.pageText { text ->
                    scope.launch {
                        val error = Shield.addCustomListFromText(t.url, "", text)
                        Toast.makeText(
                            context,
                            error ?: "Filter list added · tap Apply in Settings → Filter lists",
                            Toast.LENGTH_LONG,
                        ).show()
                        if (error == null) settingsOpen = true
                    }
                }
            },
            onDismiss = { menuOpen = false },
        )
    }

    addHome?.let { initial ->
        ShortcutDialog(
            title = "Add to home",
            initial = initial,
            onSave = { AppSettings.addShortcut(it); addHome = null },
            onRemove = null,
            onDismiss = { addHome = null },
        )
    }

    diagnostics?.let { report ->
        DiagnosticsDialog(report = report, onDismiss = { diagnostics = null })
    }
}
