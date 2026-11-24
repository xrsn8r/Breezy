package dev.breezy.browser.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** The search screen still works inside the frosted full-screen window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FrostedScreenTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(app)
        dev.breezy.browser.data.Bookmarks.init(app)
    }

    @Test fun omniboxInFrostedScreen() {
        var closed = 0
        compose.setContent {
            BreezyTheme(dark = true) {
                FrostedScreen(onDismissRequest = { closed++ }) {
                    Omnibox(privateMode = true, initial = "", tabs = emptyList(), onGo = {}, onSwitchTab = {}, onDismiss = { closed++ })
                }
            }
        }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
    }
}
