package dev.breezy.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll

@Composable
fun BrowserScreen(
    tabs: TabManager,
    onNewTab: (url: String?, private: Boolean) -> Unit,
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
    var newTabPrivate by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    // Frozen, blurred snapshot of the page behind the search screen (null = solid background).
    var omniboxBackdrop by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    fun showOmnibox() {
        captureBackdrop(context.findActivity()) { bitmap ->
            omniboxBackdrop = bitmap?.asImageBitmap()
            omniboxOpen = true
        }
    }
    var bookmarksOpen by rememberSaveable { mutableStateOf(false) }
    var textSizeOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var article by remember { mutableStateOf<BrowserTab.Article?>(null) }
    var mediaOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var updateNotes by remember { mutableStateOf<dev.breezy.browser.data.Updater.Release?>(null) }
    var tabsOpen by rememberSaveable { mutableStateOf(false) }
    var shieldOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var addHome by remember { mutableStateOf<Shortcut?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    val toolbarExpanded = tab?.chromeVisible ?: true

    val showHome = tab == null || tab.url.isEmpty()
    val host = remember(tab?.url) { tab?.url?.toUri()?.host }
    val overlayOpen = omniboxOpen || settingsOpen || historyOpen || bookmarksOpen || article != null || tabs.showDownloads
    val finding = tab?.findResult != null

    // Back: page history first; then close the tab (falls back to another tab or the home page);
    // on the home page, leave the app.
    BackHandler(enabled = !overlayOpen && tab != null) {
        if (tab!!.canGoBack) tab.goBack() else onCloseTab(tab)
    }

    fun openNewTab(private: Boolean = false) {
        newTabMode = true
        newTabPrivate = private
        showOmnibox()
    }

    fun navigate(input: String) {
        val url = UrlInput.resolve(input)
        if (url.isNotEmpty()) {
            if (tab == null || newTabMode) onNewTab(url, newTabMode && newTabPrivate) else tab.load(url)
        }
        omniboxOpen = false
        newTabMode = false
        newTabPrivate = false
        tabs.current?.chromeVisible = true
    }

    // The current tab's WebView lives directly in the window *behind* this Compose layer (see
    // MainActivity), so the root is transparent and must not swallow touches: a plain Box, not a
    // Material Surface.
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    showHome,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    // Compose-drawn, so it's safe to capture as the blur source on older Android.
                    modifier = Modifier.hazeSource(hazeState),
                ) {
                    HomePage(
                        totalBlocked = shield.totalBlocked,
                        onOpen = ::navigate,
                    )
                }
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

            // --- Find in page bar (replaces the toolbar while searching) ------------------------
            AnimatedVisibility(
                visible = finding && picking == null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                if (tab != null) FindBar(tab = tab, onClose = { tab.stopFind() })
            }
            BackHandler(enabled = finding) { tab?.stopFind() }

            // --- HTTPS-only warning -----------------------------------------------------------
            tab?.httpsFailure?.let { insecure ->
                Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
                    HttpsWarning(tab = tab, insecureUrl = insecure)
                }
            }

            // --- Undo close snackbar ----------------------------------------------------------
            SnackbarHost(
                snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
            )

            // --- Frosted, movable floating toolbar --------------------------------------------
            if (picking == null && !finding) MovableBrowserBar(
                hazeState = hazeState,
                expanded = toolbarExpanded || showHome,
                tab = tab,
                tabCount = tabs.tabs.size,
                shieldsActive = shield.ready && shield.enabled && host !in shield.allowlist,
                onShield = { shieldOpen = true },
                onAddress = { showOmnibox() },
                onTabs = { tabsOpen = true },
                onMenu = { menuOpen = true },
                onSearch = { showOmnibox() },
                mediaCount = tab?.detectedMedia?.size ?: 0,
                onMedia = { mediaOpen = true },
                onTranslate = { tab?.let { if (it.translatedFrom != null) it.showOriginal() else it.translate() } },
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
                    privateMode = (newTabMode && newTabPrivate) || (!newTabMode && tab?.isPrivate == true),
                    initial = if (newTabMode) "" else tab?.url.orEmpty(),
                    tabs = tabs.tabs,
                    onGo = ::navigate,
                    onSwitchTab = { tabs.select(it); omniboxOpen = false; newTabMode = false },
                    onDismiss = { omniboxOpen = false; newTabMode = false; newTabPrivate = false },
                    backdrop = omniboxBackdrop,
                )
            }

            // --- Reader mode -------------------------------------------------------------------
            AnimatedVisibility(
                visible = article != null,
                enter = fadeIn() + slideInVertically { it / 6 },
                exit = fadeOut() + slideOutVertically { it / 6 },
            ) {
                article?.let { ReaderScreen(it, onClose = { article = null }) }
            }

            // --- Downloads ---------------------------------------------------------------------
            AnimatedVisibility(
                visible = tabs.showDownloads,
                enter = fadeIn() + slideInHorizontally { it / 4 },
                exit = fadeOut() + slideOutHorizontally { it / 4 },
            ) {
                DownloadsScreen(onDismiss = { tabs.showDownloads = false })
            }

            // --- History -----------------------------------------------------------------------
            AnimatedVisibility(
                visible = historyOpen,
                enter = fadeIn() + slideInHorizontally { it / 4 },
                exit = fadeOut() + slideOutHorizontally { it / 4 },
            ) {
                HistoryScreen(
                    onOpen = { url -> historyOpen = false; navigate(url) },
                    onDismiss = { historyOpen = false },
                )
            }

            // --- Bookmarks ---------------------------------------------------------------------
            AnimatedVisibility(
                visible = bookmarksOpen,
                enter = fadeIn() + slideInHorizontally { it / 4 },
                exit = fadeOut() + slideOutHorizontally { it / 4 },
            ) {
                BookmarksScreen(
                    onOpen = { url -> bookmarksOpen = false; navigate(url) },
                    onDismiss = { bookmarksOpen = false },
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
            onNewPrivateTab = { tabsOpen = false; openNewTab(private = true) },
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
            onFind = { menuOpen = false; tab?.startFind() },
            onTranslate = {
                menuOpen = false
                tab?.let { if (it.translatedFrom != null) it.showOriginal() else it.translate() }
            },
            onReader = {
                menuOpen = false
                tab?.extractArticle { a ->
                    if (a != null) article = a
                    else Toast.makeText(context, "No article found on this page", Toast.LENGTH_SHORT).show()
                }
            },
            onTextSize = { menuOpen = false; textSizeOpen = true },
            onSpeed = { menuOpen = false; speedOpen = true },
            onNewPrivateTab = { menuOpen = false; openNewTab(private = true) },
            onHistory = { menuOpen = false; historyOpen = true },
            onBookmarks = { menuOpen = false; bookmarksOpen = true },
            onShields = { menuOpen = false; shieldOpen = true },
            onDownloads = { menuOpen = false; tabs.showDownloads = true },
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

    // "Downloaded <file> · Open", like other browsers, so an APK installs in one tap.
    LaunchedEffect(Unit) {
        dev.breezy.browser.downloads.Downloads.completed.collect { job ->
            // A Breezy update the user asked for: verify, then go straight to the installer.
            if (dev.breezy.browser.data.Updater.onDownloadCompleted(context, job.id)) {
                (context as? android.app.Activity)?.let { dev.breezy.browser.downloads.FileOpener.open(it, job) }
                return@collect
            }
            if (dev.breezy.browser.data.Updater.status is dev.breezy.browser.data.Updater.Status.Failed) {
                Toast.makeText(context, (dev.breezy.browser.data.Updater.status as dev.breezy.browser.data.Updater.Status.Failed).message, Toast.LENGTH_LONG).show()
            }
            val result = snackbar.showSnackbar(
                message = "Downloaded ${job.fileName}".take(60),
                actionLabel = if (dev.breezy.browser.downloads.Downloads.isApk(job)) "Install" else "Open",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                (context as? android.app.Activity)?.let { dev.breezy.browser.downloads.FileOpener.open(it, job) }
            }
        }
    }

    // "Breezy x.y.z is available · Update", once per version unless dismissed.
    val update = (dev.breezy.browser.data.Updater.status as? dev.breezy.browser.data.Updater.Status.Available)?.release
    LaunchedEffect(update?.version) {
        val r = update ?: return@LaunchedEffect
        if (dev.breezy.browser.data.Updater.dismissed(context, r.version)) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = "Breezy ${r.version} is available",
            actionLabel = "Update",
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            updateNotes = r
        } else {
            dev.breezy.browser.data.Updater.dismiss(context, r.version)
        }
    }

    updateNotes?.let { r ->
        UpdateDialog(r, onInstall = {
            updateNotes = null
            dev.breezy.browser.data.Updater.download(r)
            Toast.makeText(context, "Downloading Breezy ${r.version}…", Toast.LENGTH_SHORT).show()
        }, onDismiss = { updateNotes = null })
    }

    // Translation errors (the offer itself is the translate button in the toolbar).
    val translateError = tab?.translateError
    LaunchedEffect(translateError) {
        if (translateError != null) {
            Toast.makeText(context, "Couldn't translate: $translateError", Toast.LENGTH_SHORT).show()
            tab.translateError = null
        }
    }

    val closed = tabs.recentlyClosed
    LaunchedEffect(closed) {
        if (closed == null) return@LaunchedEffect
        val label = closed.tab.title.ifBlank { UrlInput.display(closed.tab.url) }.ifBlank { "Tab" }
        val result = snackbar.showSnackbar(
            message = "Closed $label".take(60),
            actionLabel = "Undo",
            withDismissAction = false,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) tabs.undoClose()
    }

    if (mediaOpen && tab != null) {
        MediaSheet(
            tab = tab,
            onStarted = { name ->
                mediaOpen = false
                Toast.makeText(context, "Downloading $name", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { mediaOpen = false },
        )
    }

    tabs.permissionPrompt?.let { prompt ->
        SitePermissionDialog(prompt.host, prompt.kinds, prompt.onResult)
    }

    if (textSizeOpen && tab != null) {
        val host = tab.url.toUri().host.orEmpty()
        TextSizeDialog(
            host = host,
            current = dev.breezy.browser.data.SiteSettings.get(host).textZoom,
            onChange = { v ->
                dev.breezy.browser.data.SiteSettings.update(host) { it.copy(textZoom = v) }
                tab.applySiteSettings()
            },
            onDismiss = { textSizeOpen = false },
        )
    }

    if (speedOpen && tab != null) {
        SpeedDialog(current = tab.playbackRate, onPick = { tab.changePlaybackRate(it) }, onDismiss = { speedOpen = false })
    }
}

/** "Breezy x.y.z · What's new" with the release notes, before downloading the update. */
@Composable
internal fun UpdateDialog(release: dev.breezy.browser.data.Updater.Release, onInstall: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Breezy ${release.version}") },
        text = {
            androidx.compose.foundation.layout.Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                Text("What's new", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                Text(release.notes.ifBlank { "Bug fixes and improvements." }, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onInstall) { Text("Update") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Later") } },
    )
}
