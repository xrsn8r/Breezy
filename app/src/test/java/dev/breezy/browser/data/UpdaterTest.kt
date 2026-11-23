package dev.breezy.browser.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versionCompare() {
        assertTrue(Updater.isNewer("0.7.5", "0.7.4"))
        assertTrue(Updater.isNewer("0.10.0", "0.9.9"))
        assertTrue(Updater.isNewer("1.0", "0.99.1"))
        assertFalse(Updater.isNewer("0.7.4", "0.7.4"))
        assertFalse(Updater.isNewer("0.7.3", "0.7.4"))
        assertFalse(Updater.isNewer("0.7", "0.7.0"))
    }
}

class UpdaterNotesTest {
    @Test fun extractsWhatsNew() {
        val body = "## What's new\n\n- **Faster** tabs\n- Fix `x`\n\n**Download:** `a.apk`\n\n<details>about</details>"
        org.junit.Assert.assertEquals("• Faster tabs\n• Fix x", Updater.notes(body))
    }
}
