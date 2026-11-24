package dev.breezy.browser.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.breezy.browser.AppSettings
import dev.breezy.browser.ui.theme.BreezyTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rows must react to taps (regression test for the ListItem migration). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h2400dp-night-xhdpi")
class ClickTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(app)
        dev.breezy.browser.data.Favicons.init(app)
        dev.breezy.browser.data.Bookmarks.init(app)
    }

    @Test fun menuRowsClick() {
        var settings = 0; var bookmarks = 0
        compose.setContent {
            BreezyTheme(dark = true) {
                MenuSheet(
                    tab = null, onNewTab = {}, onShare = {}, onSettings = { settings++ }, onBlockElement = {}, onUnlockScrolling = {},
                    onDiagnostics = {}, onAddToHome = {}, onAddAsFilterList = {}, onFind = {}, onReader = {}, onTranslate = {},
                    onTextSize = {}, onSpeed = {}, onNewPrivateTab = {}, onHistory = {}, onBookmarks = { bookmarks++ }, onShields = {},
                    onDownloads = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Bookmarks").performClick()
        compose.waitForIdle()
        assertEquals(1, settings)
        assertEquals(1, bookmarks)
    }


    /** A tap while the sheet is still sliding up (row already on screen) must not be lost. */
    @Test fun menuRowClickDuringOpenAnimation() = tapAt(200L)

    private fun tapAt(ms: Long) {
        var settings = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            BreezyTheme(dark = true) {
                MenuSheet(
                    tab = null, onNewTab = {}, onShare = {}, onSettings = { settings++ }, onBlockElement = {}, onUnlockScrolling = {},
                    onDiagnostics = {}, onAddToHome = {}, onAddAsFilterList = {}, onFind = {}, onReader = {}, onTranslate = {},
                    onTextSize = {}, onSpeed = {}, onNewPrivateTab = {}, onHistory = {}, onBookmarks = {}, onShields = {},
                    onDownloads = {}, onDismiss = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(ms)
        compose.onNodeWithText("Settings").performClick()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(1, settings)
    }

    @Test fun settingsGroupHeaderClicks() {
        compose.setContent { BreezyTheme(dark = true) { SettingsScreen(onDarkPagesChanged = {}, onDismiss = {}) } }
        compose.onNodeWithText("Appearance").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Pure black (AMOLED)").assertExists()
    }
}
