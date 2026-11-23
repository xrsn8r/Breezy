package dev.breezy.browser.downloads

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val key = ByteArray(16) { (it * 7 + 3).toByte() }
    private val plain = listOf(ByteArray(1000) { 1 }, ByteArray(1500) { 2 }, ByteArray(700) { 3 })

    private fun encrypt(data: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            .doFinal(data)

    /** IV = media sequence number, big-endian in the last 8 bytes. */
    private fun seqIv(n: Long) = ByteArray(16).also { b -> var v = n; for (i in 15 downTo 8) { b[i] = (v and 0xff).toByte(); v = v shr 8 } }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun route(path: String, body: ByteArray) = server.createContext(path) { ex ->
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        route("/master.m3u8", """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
            hi/index.m3u8
        """.trimIndent().toByteArray())
        route("/hi/index.m3u8", """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:42
            #EXT-X-KEY:METHOD=AES-128,URI="../key.bin"
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:6.0,
            seg1.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.5,
            /plain/seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray())
        route("/key.bin", key)
        route("/hi/seg0.ts", encrypt(plain[0], seqIv(42)))
        route("/hi/seg1.ts", encrypt(plain[1], seqIv(43)))
        route("/plain/seg2.ts", plain[2])
        route("/drm.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXTINF:6,\na.ts\n".toByteArray())
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun masterListsVariantsBestFirst() {
        val p = Hls.parse("$base/master.m3u8", Hls.fetchText("$base/master.m3u8", emptyMap())) as Hls.Master
        assertEquals(2, p.variants.size)
        assertEquals("$base/hi/index.m3u8", p.variants[0].url)
        assertEquals("720p · 2.5 Mbps", p.variants[0].label)
    }

    @Test
    fun followsMasterAndDecryptsAes128Segments() {
        val media = Hls.fetchMedia("$base/master.m3u8", emptyMap())
        assertEquals(3, media.segments.size)
        assertEquals("$base/plain/seg2.ts", media.segments[2])
        assertEquals("ts", media.extension)
        assertEquals(16.5, media.durationSeconds, 0.001)
        plain.indices.forEach { i -> assertArrayEquals(plain[i], Hls.fetchSegment(media, i, emptyMap())) }
    }

    @Test
    fun rejectsDrm() {
        val e = runCatching { Hls.fetchMedia("$base/drm.m3u8", emptyMap()) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("DRM"))
    }
}
