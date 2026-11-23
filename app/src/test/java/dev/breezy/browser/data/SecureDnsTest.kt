package dev.breezy.browser.data

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.util.Base64

/** End-to-end, fully local: fake DoH server + origin server + DnsProxy. */
class SecureDnsTest {
    private lateinit var doh: HttpServer
    private lateinit var origin: HttpServer
    private var queries = 0

    @Before
    fun setUp() {
        // Fake DoH: answers A queries for test.breezy with 127.0.0.1, NXDOMAIN for anything else.
        doh = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        doh.createContext("/dns-query") { ex ->
            queries++
            val q = Base64.getUrlDecoder().decode(ex.requestURI.rawQuery.substringAfter("dns="))
            val name = decodeName(q, 12)
            val type = ((q[q.size - 4].toInt() and 0xff) shl 8) or (q[q.size - 3].toInt() and 0xff)
            val out = ByteArrayOutputStream()
            val found = name == "test.breezy" && type == 1
            out.write(byteArrayOf(q[0], q[1], 0x81.toByte(), if (found) 0x80.toByte() else 0x83.toByte(), 0, 1, 0, if (found) 1 else 0, 0, 0, 0, 0))
            out.write(q, 12, q.size - 12) // question
            if (found) out.write(byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 127, 0, 0, 1))
            val body = out.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/dns-message")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        doh.start()
        origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.createContext("/hello") { ex ->
            val body = "hi from ${ex.requestHeaders.getFirst("Host")}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        origin.start()
        SecureDns.endpoint = "http://127.0.0.1:${doh.address.port}/dns-query"
        SecureDns.strict = true
        SecureDns.clearCache()
        DnsProxy.start()
    }

    @After
    fun tearDown() {
        DnsProxy.stop(); doh.stop(0); origin.stop(0); SecureDns.endpoint = null
    }

    private fun decodeName(msg: ByteArray, start: Int): String {
        val parts = ArrayList<String>(); var i = start
        while (msg[i].toInt() != 0) { val len = msg[i].toInt(); parts += String(msg, i + 1, len); i += len + 1 }
        return parts.joinToString(".")
    }

    @Test
    fun resolvesThroughDohAndCaches() {
        val a = SecureDns.resolve("test.breezy")
        assertEquals("127.0.0.1", a.first().hostAddress)
        SecureDns.resolve("test.breezy")
        assertEquals("second lookup served from cache", 1, queries)
        val nx = runCatching { SecureDns.resolve("nope.breezy") }.exceptionOrNull()
        assertTrue(nx is java.net.UnknownHostException)
    }

    @Test
    fun plainHttpThroughProxy() {
        val conn = URI("http://test.breezy:${origin.address.port}/hello").toURL()
            .openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", DnsProxy.port))) as HttpURLConnection
        assertEquals(200, conn.responseCode)
        assertEquals("hi from test.breezy:${origin.address.port}", conn.inputStream.readBytes().decodeToString())
    }

    @Test
    fun connectTunnel() {
        Socket("127.0.0.1", DnsProxy.port).use { s ->
            val port = origin.address.port
            s.getOutputStream().write("CONNECT test.breezy:$port HTTP/1.1\r\nHost: test.breezy:$port\r\n\r\n".toByteArray())
            val reader = s.getInputStream().bufferedReader()
            assertTrue(reader.readLine().contains("200"))
            reader.readLine() // blank line
            s.getOutputStream().write("GET /hello HTTP/1.1\r\nHost: tunnel\r\nConnection: close\r\n\r\n".toByteArray())
            val response = reader.readText()
            assertTrue(response, response.startsWith("HTTP/1.1 200") && response.endsWith("hi from tunnel"))
        }
    }
}
