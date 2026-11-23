package dev.breezy.browser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {
    @Test fun parsesBatch() {
        val r = Translator.parse("""[["Hello world","es"],["Good morning","es"]]""", listOf("a", "b"))
        assertEquals(listOf("Hello world", "Good morning"), r.texts)
        assertEquals("es", r.sourceLang)
    }

    @Test fun parsesSingle() {
        assertEquals(listOf("Hi"), Translator.parse("""[["Hi","fr"]]""", listOf("Salut")).texts)
        assertEquals(listOf("Hi"), Translator.parse("""["Hi"]""", listOf("Salut")).texts)
        assertEquals(listOf("Hi"), Translator.parse("\"Hi\"", listOf("Salut")).texts)
    }

    @Test fun languageDiffers() {
        assertTrue(Translator.differs("es-ES", "en"))
        assertFalse(Translator.differs("en-US", "en"))
        assertFalse(Translator.differs("", "en"))
        assertTrue(Translator.differs("zh", "en"))
    }
}
