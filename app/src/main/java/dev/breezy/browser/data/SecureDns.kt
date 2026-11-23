package dev.breezy.browser.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** DNS-over-HTTPS providers (RFC 8484 endpoints). */
enum class DnsProvider(val label: String, val url: String?, val note: String) {
    Off("Off", null, "Use the network's DNS"),
    Cloudflare("Cloudflare", "https://cloudflare-dns.com/dns-query", "1.1.1.1 · fast, no logging of IPs"),
    Quad9("Quad9", "https://dns.quad9.net/dns-query", "Blocks known malware domains · Swiss non-profit"),
    Mullvad("Mullvad", "https://dns.mullvad.net/dns-query", "No logging · run by Mullvad VPN"),
    AdGuard("AdGuard", "https://dns.adguard-dns.com/dns-query", "Also blocks ad and tracker domains"),
    Google("Google", "https://dns.google/dns-query", "8.8.8.8"),
    Custom("Custom", null, "Your own DoH endpoint"),
}

/**
 * Resolves hostnames over HTTPS (RFC 8484, binary wire format), with a TTL-respecting cache.
 *
 * The DoH request itself goes out directly (Proxy.NO_PROXY) and finds the provider's own address
 * through the system resolver; every other lookup the browser makes goes through here.
 */
object SecureDns {
    @Volatile var endpoint: String? = null
    /** When true, never fall back to the network's DNS if the DoH provider is unreachable. */
    @Volatile var strict = false

    private class Entry(val addresses: List<InetAddress>, val expires: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    val enabled: Boolean get() = endpoint != null

    fun clearCache() = cache.clear()

    /** Addresses for [host], IPv4 first. Literal IPs and localhost are returned as is. */
    fun resolve(host: String): List<InetAddress> {
        val h = host.trim('[', ']').lowercase()
        if (isLiteral(h) || h == "localhost") return InetAddress.getAllByName(h).toList()
        val url = endpoint ?: return InetAddress.getAllByName(h).toList()
        cache[h]?.let { if (it.expires > System.currentTimeMillis()) return it.addresses }
        return try {
            val (v4, ttl4) = query(url, h, 1)
            val (v6, ttl6) = if (v4.isEmpty()) query(url, h, 28) else emptyList<InetAddress>() to Int.MAX_VALUE
            val all = v4 + v6
            if (all.isEmpty()) throw java.net.UnknownHostException(h)
            val ttl = minOf(ttl4, ttl6).coerceIn(30, 3600)
            cache[h] = Entry(all, System.currentTimeMillis() + ttl * 1000L)
            all
        } catch (e: java.net.UnknownHostException) {
            throw e
        } catch (e: IOException) {
            if (strict) throw java.net.UnknownHostException("$h (secure DNS unavailable: ${e.message})")
            InetAddress.getAllByName(h).toList()
        }
    }

    /** Round-trip test for the settings screen: returns milliseconds, or throws. */
    fun test(url: String): Long {
        val start = System.nanoTime()
        val (a, _) = query(url, "example.com", 1)
        if (a.isEmpty()) throw IOException("No answer")
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun isLiteral(h: String) = h.contains(':') || h.all { it.isDigit() || it == '.' }

    /** One DNS query; returns (addresses, min TTL). [type] 1 = A, 28 = AAAA. */
    private fun query(endpoint: String, host: String, type: Int): Pair<List<InetAddress>, Int> {
        val q = buildQuery(host, type)
        val dns = Base64.getUrlEncoder().withoutPadding().encodeToString(q)
        val conn = URI("$endpoint?dns=$dns").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("Accept", "application/dns-message")
        try {
            if (conn.responseCode != 200) throw IOException("DoH HTTP ${conn.responseCode}")
            return parseAnswer(conn.inputStream.use { it.readBytes() }, host, type)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildQuery(host: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // Header: id 0 (RFC 8484 recommends 0 for cacheability), RD flag, 1 question.
        out.write(byteArrayOf(0, 0, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in host.trimEnd('.').split('.')) {
            val b = java.net.IDN.toASCII(label).toByteArray()
            require(b.size in 1..63) { "bad label" }
            out.write(b.size); out.write(b)
        }
        out.write(0)
        out.write(type shr 8); out.write(type and 0xff)
        out.write(0); out.write(1) // class IN
        return out.toByteArray()
    }

    private fun parseAnswer(msg: ByteArray, host: String, type: Int): Pair<List<InetAddress>, Int> {
        val buf = ByteBuffer.wrap(msg)
        buf.short // id
        val flags = buf.short.toInt() and 0xffff
        val rcode = flags and 0xf
        if (rcode == 3) throw java.net.UnknownHostException(host) // NXDOMAIN
        if (rcode != 0) throw IOException("DNS error $rcode")
        val qd = buf.short.toInt() and 0xffff
        val an = buf.short.toInt() and 0xffff
        buf.short; buf.short // NS, AR
        repeat(qd) { skipName(buf); buf.short; buf.short }
        val out = ArrayList<InetAddress>()
        var ttl = Int.MAX_VALUE
        repeat(an) {
            skipName(buf)
            val rtype = buf.short.toInt() and 0xffff
            buf.short // class
            val rttl = buf.int
            val len = buf.short.toInt() and 0xffff
            val data = ByteArray(len).also { buf.get(it) }
            // CNAME chains are followed by the resolver; we only need the address records.
            if (rtype == type && (len == 4 || len == 16)) {
                out += InetAddress.getByAddress(host, data)
                ttl = minOf(ttl, rttl)
            }
        }
        return out.sortedBy { if (it is Inet4Address) 0 else 1 } to ttl
    }

    private fun skipName(buf: ByteBuffer) {
        while (true) {
            val len = buf.get().toInt() and 0xff
            when {
                len == 0 -> return
                len and 0xc0 == 0xc0 -> { buf.get(); return } // compression pointer
                else -> buf.position(buf.position() + len)
            }
        }
    }
}
