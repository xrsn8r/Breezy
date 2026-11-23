package dev.breezy.browser.downloads

import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An HTTP error status, with the server's Retry-After (ms) when it sent one. */
class HttpStatusException(val code: Int, val retryAfterMs: Long?) : IOException("HTTP $code") {
    /** "Slow down" responses: worth waiting out rather than failing the download. */
    val throttled: Boolean get() = code == 429 || code == 503
    /** Retrying won't help (forbidden, gone, bad request). */
    val permanent: Boolean get() = code in 400..499 && code != 408 && code != 429

    companion object {
        fun check(conn: HttpURLConnection) {
            val code = conn.responseCode
            if (code < 400) return
            val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
            throw HttpStatusException(code, retryAfter)
        }
    }
}

/**
 * Per-host backoff shared by every connection of every download: when a server answers 429/503,
 * all requests to it pause until the cool-down ends, instead of each worker hammering it.
 */
object HostCooldown {
    private val until = ConcurrentHashMap<String, Long>()

    fun hit(host: String, waitMs: Long) {
        val t = System.currentTimeMillis() + waitMs
        until.merge(host, t) { a, b -> maxOf(a, b) }
    }

    fun waitFor(host: String) {
        val t = until[host] ?: return
        val left = t - System.currentTimeMillis()
        if (left > 0) Thread.sleep(left) else until.remove(host, t)
    }
}

/**
 * Concurrency limit that halves when the server throttles and creeps back up after a run of
 * successes (AIMD), so a download settles at the fastest rate the server tolerates.
 */
class AdaptiveLimiter(private val max: Int) {
    private val lock = Mutex()
    @Volatile var limit = max
        private set
    private var active = 0
    private var successes = 0

    suspend fun <T> withPermit(block: suspend () -> T): T {
        while (true) {
            val got = lock.withLock { if (active < limit) { active++; true } else false }
            if (got) break
            delay(100)
        }
        try {
            return block()
        } finally {
            lock.withLock { active-- }
        }
    }

    suspend fun onSuccess() = lock.withLock {
        if (++successes >= 8 && limit < max) { limit++; successes = 0 }
    }

    suspend fun onThrottled() = lock.withLock { limit = maxOf(1, limit / 2); successes = 0 }
}
