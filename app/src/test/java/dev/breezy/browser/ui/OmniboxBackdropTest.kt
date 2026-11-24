package dev.breezy.browser.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
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

/** The search screen works over a frosted (snapshot) backdrop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OmniboxBackdropTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(app)
        dev.breezy.browser.data.Bookmarks.init(app)
    }

    @Test fun omniboxWithBackdrop() {
        var closed = 0
        val snapshot = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).asImageBitmap()
        compose.setContent {
            BreezyTheme(dark = true) {
                Omnibox(privateMode = true, initial = "", tabs = emptyList(), onGo = {}, onSwitchTab = {}, onDismiss = { closed++ }, backdrop = snapshot)
            }
        }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun tapAboveSuggestionsCloses() {
        var closed = 0
        compose.setContent {
            BreezyTheme(dark = true) {
                Omnibox(privateMode = true, initial = "", tabs = emptyList(), onGo = {}, onSwitchTab = {}, onDismiss = { closed++ })
            }
        }
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, height * 0.2f)) }
        compose.waitForIdle()
        assertEquals(1, closed)
    }
}
