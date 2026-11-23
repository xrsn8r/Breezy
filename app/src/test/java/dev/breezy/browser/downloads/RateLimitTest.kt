package dev.breezy.browser.downloads

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitTest {
    @Test fun statusClassification() {
        assertTrue(HttpStatusException(429, null).throttled)
        assertTrue(HttpStatusException(503, null).throttled)
        assertTrue(HttpStatusException(403, null).permanent)
        assertFalse(HttpStatusException(429, null).permanent)
        assertFalse(HttpStatusException(500, null).permanent)
    }

    @Test fun limiterHalvesAndRecovers() = runBlocking {
        val l = AdaptiveLimiter(8)
        l.onThrottled(); assertEquals(4, l.limit)
        l.onThrottled(); assertEquals(2, l.limit)
        repeat(8) { l.onSuccess() }; assertEquals(3, l.limit)
        repeat(100) { l.onThrottled() }; assertEquals(1, l.limit)
    }
}
