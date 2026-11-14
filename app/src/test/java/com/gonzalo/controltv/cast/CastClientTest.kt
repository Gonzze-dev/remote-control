package com.gonzalo.controltv.cast

import android.app.Application
import com.gonzalo.controltv.tv.TvLog
import com.gonzalo.controltv.tv.createTempDirectory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Cast v2 contra [FakeCast], con TLS de verdad. Robolectric solo pone el org.json real de Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CastClientTest {
    private lateinit var chromecast: FakeCast
    private val log = TvLog { println("  [log] $it") }
    private var client: CastClient? = null

    @Before
    fun setUp() {
        chromecast = FakeCast(createTempDirectory())
    }

    @After
    fun tearDown() {
        client?.close()
        chromecast.close()
    }

    private fun connected(): CastClient =
        CastClient("127.0.0.1", log, chromecast.port).also { client = it; it.connect() }

    /** El próximo mensaje que mandó el celu en [namespace], salteando los PING. */
    private fun next(namespace: String): JSONObject {
        while (true) {
            val m = chromecast.received.poll(5, TimeUnit.SECONDS) ?: error("no llegó nada en $namespace")
            if (m.namespace == namespace) return JSONObject(m.payload).put("_to", m.destination)
        }
    }

    @Test
    fun messageBytesMatchCastChannelProto() {
        val bytes = CastMessage("sender-0", "receiver-0", CastNamespace.CONNECTION, """{"type":"CONNECT"}""").encode()
        val ns = CastNamespace.CONNECTION.toByteArray()
        val payload = """{"type":"CONNECT"}""".toByteArray()
        val expected = byteArrayOf(0x08, 0x00, 0x12, 8) + "sender-0".toByteArray() +
            byteArrayOf(0x1a, 10) + "receiver-0".toByteArray() +
            byteArrayOf(0x22, ns.size.toByte()) + ns +
            byteArrayOf(0x28, 0x00, 0x32, payload.size.toByte()) + payload
        assertArrayEquals(expected, bytes)

        val framed = ByteArrayOutputStream().also { CastFraming.write(it, bytes) }.toByteArray()
        assertArrayEquals(byteArrayOf(0, 0, 0, bytes.size.toByte()), framed.copyOfRange(0, 4))
        assertEquals(CastMessage.decode(bytes), CastMessage.decode(CastFraming.read(ByteArrayInputStream(framed))))
    }

    @Test
    fun launchesTheDefaultReceiverAndLoadsTheUrl() {
        val cast = connected()
        assertEquals("CONNECT", next(CastNamespace.CONNECTION).getString("type"))

        cast.launch()
        val launch = next(CastNamespace.RECEIVER)
        assertEquals("LAUNCH", launch.getString("type"))
        assertEquals("CC1AD845", launch.getString("appId"))
        assertEquals(FakeCast.TRANSPORT, cast.transportId)
        val connectPlayer = next(CastNamespace.CONNECTION)
        assertEquals(FakeCast.TRANSPORT, connectPlayer.getString("_to"))

        cast.load("http://192.168.1.6:1234/m/abc/foto.jpg", "image/jpeg", "foto.jpg")
        val load = next(CastNamespace.MEDIA)
        assertEquals("LOAD", load.getString("type"))
        assertEquals(FakeCast.TRANSPORT, load.getString("_to"))
        assertEquals(FakeCast.SESSION, load.getString("sessionId"))
        assertEquals("http://192.168.1.6:1234/m/abc/foto.jpg", load.getJSONObject("media").getString("contentId"))
        assertEquals("image/jpeg", load.getJSONObject("media").getString("contentType"))
        assertEquals(1, cast.mediaSessionId)
    }

    @Test
    fun aFileThePlayerCannotOpenIsAnError() {
        chromecast.loadAnswer = "LOAD_FAILED"
        val cast = connected()
        cast.launch()
        val e = assertThrows(CastException::class.java) { cast.load("http://x/m/a/peli.mkv", "video/mp4", "peli.mkv") }
        assertTrue(e.message!!.contains("peli.mkv"))
    }

    @Test
    fun answersPingsAndReportsTheEndOfTheMedia() {
        val media = LinkedBlockingQueue<String>()
        val cast = connected()
        cast.onMedia = { state, reason -> media += "$state/$reason" }
        cast.launch()
        cast.load("http://x/m/a/peli.mp4", "video/mp4", "peli.mp4")

        chromecast.ping()
        assertEquals("PONG", next(CastNamespace.HEARTBEAT).getString("type"))

        assertEquals("BUFFERING/null", media.poll(5, TimeUnit.SECONDS)) // la respuesta al LOAD
        chromecast.finishMedia()
        assertEquals("IDLE/FINISHED", media.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun closingThePlayerOnTheTvEndsTheSession() {
        val ended = LinkedBlockingQueue<String>()
        val cast = connected()
        cast.onEnded = { ended += it }
        cast.launch()
        chromecast.closePlayerFromTv()
        assertNotNull(ended.poll(5, TimeUnit.SECONDS))
        assertTrue(!cast.isOpen)
    }

    @Test
    fun stopClosesThePlayer() {
        val cast = connected()
        cast.launch()
        cast.stopApp()
        var stop: JSONObject
        do stop = next(CastNamespace.RECEIVER) while (stop.getString("type") != "STOP")
        assertEquals(FakeCast.SESSION, stop.getString("sessionId"))
    }
}
