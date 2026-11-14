package com.gonzalo.controltv.cast

import com.gonzalo.controltv.tv.CertManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Un Chromecast de prueba: TLS de verdad en un puerto local y las respuestas del reproductor estándar a
 * CONNECT, PING, LAUNCH, LOAD y STOP. Sirve para probar [CastClient] sin la tele.
 */
class FakeCast(dir: File) : Closeable {
    private val identity = CertManager(File(dir, "chromecast")).identity()
    private val server = serverSocket()
    val port get() = server.localPort

    /** Todo lo que mandó el celu, en orden. */
    val received = LinkedBlockingQueue<CastMessage>()

    /** Qué contestar a LOAD: null = MEDIA_STATUS (lo carga); si no, ese tipo (LOAD_FAILED, …). */
    @Volatile var loadAnswer: String? = null

    private val sessions = CopyOnWriteArrayList<SSLSocket>()

    init {
        Thread({
            while (!server.isClosed) {
                val s = runCatching { server.accept() as SSLSocket }.getOrNull() ?: continue
                sessions += s
                Thread({ runCatching { handle(s) } }, "fake-cast-session").apply { isDaemon = true; start() }
            }
        }, "fake-cast").apply { isDaemon = true; start() }
    }

    /** Lo que hace la tele cuando alguien cierra el reproductor (Atrás o Inicio en el control). */
    fun closePlayerFromTv() {
        sessions.forEach { s ->
            send(s, CastMessage(TRANSPORT, CastClient.SENDER_ID, CastNamespace.CONNECTION, """{"type":"CLOSE"}"""))
        }
    }

    /** Lo que manda el reproductor al terminar un video. */
    fun finishMedia() {
        sessions.forEach { s ->
            send(s, mediaStatus(0, "IDLE", "FINISHED"))
        }
    }

    fun ping() = sessions.forEach { send(it, CastMessage(CastClient.RECEIVER_ID, CastClient.SENDER_ID, CastNamespace.HEARTBEAT, """{"type":"PING"}""")) }

    private fun handle(s: SSLSocket) = s.use {
        while (true) {
            val msg = CastMessage.decode(CastFraming.read(s.inputStream))
            received += msg
            val json = JSONObject(msg.payload)
            val id = json.optInt("requestId")
            when (json.optString("type")) {
                "PING" -> send(s, CastMessage(msg.destination, msg.source, CastNamespace.HEARTBEAT, """{"type":"PONG"}"""))
                "LAUNCH" -> send(s, receiverStatus(id, withApp = true))
                "LOAD" -> {
                    val answer = loadAnswer
                    if (answer == null) send(s, mediaStatus(id, "BUFFERING", null))
                    else send(s, CastMessage(TRANSPORT, msg.source, CastNamespace.MEDIA, JSONObject().put("type", answer).put("requestId", id).toString()))
                }
                "STOP" -> send(s, receiverStatus(id, withApp = false))
            }
        }
    }

    private fun receiverStatus(requestId: Int, withApp: Boolean): CastMessage {
        val apps = JSONArray()
        if (withApp) {
            apps.put(JSONObject().put("appId", CastClient.DEFAULT_MEDIA_RECEIVER).put("sessionId", SESSION).put("transportId", TRANSPORT))
        }
        val body = JSONObject().put("type", "RECEIVER_STATUS").put("requestId", requestId)
            .put("status", JSONObject().put("applications", apps))
        return CastMessage(CastClient.RECEIVER_ID, CastClient.SENDER_ID, CastNamespace.RECEIVER, body.toString())
    }

    private fun mediaStatus(requestId: Int, state: String, idleReason: String?): CastMessage {
        val status = JSONObject().put("mediaSessionId", 1).put("playerState", state)
        idleReason?.let { status.put("idleReason", it) }
        val body = JSONObject().put("type", "MEDIA_STATUS").put("requestId", requestId).put("status", JSONArray().put(status))
        return CastMessage(TRANSPORT, CastClient.SENDER_ID, CastNamespace.MEDIA, body.toString())
    }

    private fun send(s: SSLSocket, message: CastMessage) = synchronized(s) {
        runCatching { CastFraming.write(s.outputStream, message.encode()) }
    }

    private fun serverSocket(): SSLServerSocket {
        val pass = "cast".toCharArray()
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("cast", identity.privateKey, pass, arrayOf(identity.certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
        return ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
    }

    override fun close() {
        runCatching { server.close() }
        sessions.forEach { runCatching { it.close() } }
    }

    companion object {
        const val SESSION = "sesion-1"
        const val TRANSPORT = "transporte-1"
    }
}
