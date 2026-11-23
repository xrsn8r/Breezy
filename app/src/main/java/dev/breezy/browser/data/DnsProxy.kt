package dev.breezy.browser.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * A tiny HTTP proxy on 127.0.0.1 that resolves every hostname through [SecureDns].
 *
 * WebView can't be given a custom DNS resolver, but it can be pointed at a proxy
 * (androidx.webkit ProxyController). HTTPS traffic arrives as `CONNECT host:443` and is tunnelled
 * byte-for-byte (end-to-end TLS is untouched; the proxy never sees page content). Plain HTTP
 * requests are forwarded with `Connection: close`, one request per connection.
 */
object DnsProxy {
    @Volatile private var server: ServerSocket? = null
    @Volatile var port: Int = 0
        private set

    private val pool = Executors.newCachedThreadPool(object : ThreadFactory {
        private var n = 0
        override fun newThread(r: Runnable) = Thread(r, "dns-proxy-${n++}").apply { isDaemon = true }
    })

    val running: Boolean get() = server != null

    @Synchronized
    fun start(): Int {
        server?.let { return port }
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        port = s.localPort
        pool.execute {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { handle(client) }
            }
        }
        return port
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        port = 0
    }

    /** Send the app's own HttpURLConnection traffic (downloads, lists, suggestions) through the proxy. */
    fun installAppProxySelector(default: ProxySelector?) {
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                val p = port
                val scheme = uri?.scheme?.lowercase()
                if (p == 0 || (scheme != "http" && scheme != "https")) return default?.select(uri) ?: listOf(Proxy.NO_PROXY)
                return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", p)))
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
                default?.connectFailed(uri, sa, ioe)
            }
        })
    }

    private fun handle(client: Socket) {
        client.use {
            runCatching {
                client.soTimeout = 30_000
                client.tcpNoDelay = true
                val input = BufferedInputStream(client.getInputStream())
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val parts = lines.first().split(' ')
                if (parts.size < 3) return
                val (method, target) = parts[0] to parts[1]
                if (method.equals("CONNECT", ignoreCase = true)) {
                    val host = target.substringBeforeLast(':').trim('[', ']')
                    val port = target.substringAfterLast(':').toIntOrNull() ?: 443
                    val upstream = connect(host, port) ?: return reply(client, "502 Bad Gateway")
                    upstream.use {
                        client.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
                        client.soTimeout = 0
                        tunnel(input, client, upstream)
                    }
                } else {
                    val uri = runCatching { URI(target) }.getOrNull() ?: return reply(client, "400 Bad Request")
                    val host = uri.host ?: return reply(client, "400 Bad Request")
                    val port = if (uri.port > 0) uri.port else 80
                    val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                    val headers = lines.drop(1).filter {
                        val name = it.substringBefore(':').trim().lowercase()
                        it.isNotEmpty() && name != "proxy-connection" && name != "connection" && name != "keep-alive"
                    }
                    val rebuilt = buildString {
                        append("$method $path ${parts[2]}\r\n")
                        headers.forEach { append(it).append("\r\n") }
                        append("Connection: close\r\n\r\n")
                    }
                    val upstream = connect(host, port) ?: return reply(client, "502 Bad Gateway")
                    upstream.use {
                        upstream.getOutputStream().apply { write(rebuilt.toByteArray()); flush() }
                        client.soTimeout = 0
                        tunnel(input, client, upstream)
                    }
                }
            }
        }
    }

    private fun connect(host: String, port: Int): Socket? {
        val addresses = runCatching { SecureDns.resolve(host) }.getOrNull() ?: return null
        for (addr in addresses) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(addr, port), 10_000)
                s.tcpNoDelay = true
                return s
            } catch (_: Exception) {
                runCatching { s.close() }
            }
        }
        return null
    }

    private fun reply(client: Socket, status: String) {
        runCatching { client.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
    }

    /** Read the request head up to the blank line (max 64 KiB). */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (out.size() < 65_536) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == 4) return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
        }
        return null
    }

    /**
     * Copy both directions. Returns when the server side finishes; the callers' `use` blocks then
     * close both sockets, which also ends the client-to-server pump. [clientIn] may hold
     * already-buffered bytes.
     */
    private fun tunnel(clientIn: InputStream, client: Socket, upstream: Socket) {
        pool.execute { pump(clientIn, upstream.getOutputStream()); runCatching { upstream.shutdownOutput() } }
        pump(upstream.getInputStream(), client.getOutputStream())
        runCatching { client.shutdownOutput() }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
        }
    }
}
