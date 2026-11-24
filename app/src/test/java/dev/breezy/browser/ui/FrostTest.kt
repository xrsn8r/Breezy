package dev.breezy.browser.ui

import android.app.Application
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** FrostWindow needs the sheet's own window; make sure it's reachable from the sheet content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FrostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sheetContentReachesItsWindow() {
        var window: android.view.Window? = null
        compose.setContent {
            ModalBottomSheet(onDismissRequest = {}) {
                val view = LocalView.current
                window = ((view as? DialogWindowProvider) ?: (view.parent as? DialogWindowProvider))?.window
                Text("x")
            }
        }
        compose.waitForIdle()
        assertNotNull("sheet window not found", window)
    }
}
