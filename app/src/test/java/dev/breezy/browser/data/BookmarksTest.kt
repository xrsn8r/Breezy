package dev.breezy.browser.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarksTest {
    @Test fun jsonRoundTrip() {
        val list = listOf(
            Bookmark(1, "Breezy", "https://github.com/xrsn8r/Breezy", "Dev", 100),
            Bookmark(2, "News \"quoted\"", "https://example.com/a?b=c&d=é", "", 200),
        )
        assertEquals(list, Bookmarks.parse(Bookmarks.toJson(list).toString()))
    }
}
