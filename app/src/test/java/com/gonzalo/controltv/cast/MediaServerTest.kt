package com.gonzalo.controltv.cast

import com.gonzalo.controltv.tv.TvLog
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** El servidor que le da el archivo al Chromecast: rangos para adelantar, y solo el archivo publicado. */
class MediaServerTest {
    private val data = ByteArray(100) { it.toByte() }
    private val source = object : MediaSource {
        override val name = "peli de prueba.mp4"
        override val type = "video/mp4"
        override val size = data.size.toLong()
        override fun open(offset: Long) = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }
    private val server = MediaServer(TvLog { }, InetAddress.getLoopbackAddress())
    private val path = server.publish(source)

    @After
    fun tearDown() = server.close()

    private fun get(path: String, range: String? = null, method: String = "GET"): HttpURLConnection =
        (URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            range?.let { setRequestProperty("Range", it) }
        }

    @Test
    fun servesTheWholeFile() {
        val c = get(path)
        assertEquals(200, c.responseCode)
        assertEquals("video/mp4", c.contentType)
        assertEquals("100", c.getHeaderField("Content-Length"))
        assertEquals("bytes", c.getHeaderField("Accept-Ranges"))
        assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"))
        assertArrayEquals(data, c.inputStream.readBytes())
    }

    @Test
    fun servesARangeToSeek() {
        val c = get(path, "bytes=10-19")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 10-19/100", c.getHeaderField("Content-Range"))
        assertArrayEquals(data.copyOfRange(10, 20), c.inputStream.readBytes())

        val open = get(path, "bytes=95-")
        assertEquals(206, open.responseCode)
        assertArrayEquals(data.copyOfRange(95, 100), open.inputStream.readBytes())

        val tail = get(path, "bytes=-5")
        assertEquals("bytes 95-99/100", tail.getHeaderField("Content-Range"))
        assertArrayEquals(data.copyOfRange(95, 100), tail.inputStream.readBytes())
    }

    @Test
    fun aRangePastTheEndIsRefused() {
        val c = get(path, "bytes=200-")
        assertEquals(416, c.responseCode)
        assertEquals("bytes */100", c.getHeaderField("Content-Range"))
    }

    @Test
    fun onlyThePublishedFileIsServed() {
        assertEquals(404, get("/m/otrocodigo/peli.mp4").responseCode)
        val next = server.publish(source)
        assertEquals(404, get(path).responseCode) // el anterior deja de existir
        assertEquals(200, get(next).responseCode)
    }

    @Test
    fun headHasNoBody() {
        val c = get(path, method = "HEAD")
        assertEquals(200, c.responseCode)
        assertEquals("100", c.getHeaderField("Content-Length"))
    }

    @Test
    fun parsesRanges() {
        assertEquals(0L to 99L, MediaServer.parseRange("bytes=0-", 100))
        assertEquals(0L to 99L, MediaServer.parseRange("bytes=0-500", 100))
        assertEquals(50L to 99L, MediaServer.parseRange("bytes=-50", 100))
        assertEquals(0L to 99L, MediaServer.parseRange("bytes=-500", 100))
        assertEquals(-1L to -1L, MediaServer.parseRange("bytes=100-", 100))
        assertNull(MediaServer.parseRange("bytes=0-1,5-6", 100))
        assertNull(MediaServer.parseRange("items=0-1", 100))
        assertNull(MediaServer.parseRange("bytes=0-", -1))
    }
}
