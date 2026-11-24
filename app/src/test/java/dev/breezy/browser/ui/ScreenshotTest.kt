package dev.breezy.browser.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.breezy.browser.AppSettings
import dev.breezy.browser.ui.theme.BreezyTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders Breezy's main screens to PNGs (build/screenshots/<set>/), so UI changes can be
 * reviewed without a device. Set -Dscreenshots.set=<name> to keep "before"/"after" sets apart.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h2400dp-night-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val out = File("build/screenshots/" + (System.getProperty("screenshots.set") ?: "current")).apply { mkdirs() }

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(app)
        dev.breezy.browser.data.Favicons.init(app)
        dev.breezy.browser.data.Bookmarks.init(app)
        dev.breezy.browser.data.Bookmarks.add("Breezy releases", "https://github.com/xrsn8r/Breezy/releases", "Dev")
        dev.breezy.browser.data.Bookmarks.add("Example", "https://example.com")
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun settingsExpanded() {
        compose.setContent { BreezyTheme(dark = true) { SettingsScreen(onDarkPagesChanged = {}, onDismiss = {}) } }
        shot("settings-collapsed")
        for (group in listOf("Appearance", "Search & browsing", "Privacy & security")) {
            compose.onNodeWithText(group).performClick()
        }
        shot("settings-expanded-1")
    }

    @Test fun settingsExpandedMore() {
        compose.setContent { BreezyTheme(dark = true) { SettingsScreen(onDarkPagesChanged = {}, onDismiss = {}) } }
        for (group in listOf("Shields", "Downloads & media", "Advanced")) {
            compose.onNodeWithText(group).performClick()
        }
        shot("settings-expanded-2")
    }

    @Test fun menu() {
        compose.setContent {
            BreezyTheme(dark = true) {
                MenuSheet(
                    tab = null, onNewTab = {}, onShare = {}, onSettings = {}, onBlockElement = {}, onUnlockScrolling = {},
                    onDiagnostics = {}, onAddToHome = {}, onAddAsFilterList = {}, onFind = {}, onReader = {}, onTranslate = {},
                    onTextSize = {}, onSpeed = {}, onNewPrivateTab = {}, onHistory = {}, onBookmarks = {}, onShields = {},
                    onDownloads = {}, onDismiss = {},
                )
            }
        }
        shot("menu")
    }

    @Test fun bookmarks() {
        compose.setContent { BreezyTheme(dark = true) { BookmarksScreen(onOpen = {}, onDismiss = {}) } }
        shot("bookmarks")
    }

    @Test fun downloads() {
        compose.setContent { BreezyTheme(dark = true) { DownloadsScreen(onDismiss = {}) } }
        shot("downloads")
    }
}
