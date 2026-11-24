package dev.breezy.browser.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ObscureTest {
    private val assets = File("src/main/assets").walkTopDown().filter { it.isFile && it.extension == "js" }.toList()

    @Test fun noFixedBreezyNamesReachPages() {
        assertTrue(assets.isNotEmpty())
        for (f in assets) {
            val out = Obscure.js(f.readText())
            // Comments may mention Breezy; identifiers and attributes must not.
            val code = out.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            assertFalse("${f.name}: bridge name", Regex("Breezy(Shield|Page|Host)").containsMatchIn(code))
            assertFalse("${f.name}: marker", code.contains("__breezy"))
            assertFalse("${f.name}: attribute", code.contains("data-breezy-"))
        }
    }

    @Test fun namesAreDistinctIdentifiers() {
        val names = listOf(Obscure.shield, Obscure.page, Obscure.host)
        assertTrue(names.all { Regex("[a-z][a-z0-9]{7}").matches(it) })
        assertNotEquals(names[0], names[1]); assertNotEquals(names[1], names[2])
        assertTrue(Obscure.js("window.__breezyMedia&&__breezyMedia.play()").let { !it.contains("breezy") })
    }
}
