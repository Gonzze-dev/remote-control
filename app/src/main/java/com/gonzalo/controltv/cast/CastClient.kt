package com.gonzalo.controltv.cast

import com.gonzalo.controltv.tv.Tls
import com.gonzalo.controltv.tv.TvLog
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class CastException(message: String) : IOException(message)

/**
 * Cliente mínimo de Cast v2: abre el reproductor estándar de Google (Default Media Receiver) en el Chromecast
 * y le pide que reproduzca una dirección. Es el mismo protocolo que usa el botón de transmitir de YouTube.
 *
 * Bloqueante: [connect], [launch] y [load] se llaman desde un hilo de fondo. Un hilo propio lee los mensajes,
 * contesta los PING y avisa por [onMedia] y [onEnded].
 */
class CastClient(
    private val host: String,
    private val log: TvLog,
    private val port: Int = PORT,
) : Closeable {

    /** Estado del reproductor: `playerState` (IDLE, BUFFERING, PLAYING, PAUSED) e `idleReason` si terminó. */
    var onMedia: ((state: String, idleReason: String?) -> Unit)? = null

    /** La sesión terminó: se cerró en la tele, otro la reemplazó o se cortó la conexión. Se llama una sola vez. */
    var onEnded: ((reason: String) -> Unit)? = null

    private lateinit var socket: SSLSocket
    private lateinit var out: OutputStream
    private val requestIds = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, LinkedBlockingQueue<JSONObject>>()
    @Volatile private var lastHeard = 0L
    @Volatile private var closed = false
    @Volatile private var ended = false

    @Volatile var transportId: String? = null
        private set
    @Volatile var sessionId: String? = null
        private set
    @Volatile var mediaSessionId: Int? = null
        private set

    val isOpen: Boolean get() = !closed && !ended

    /** La IP del celu en la red de la tele: la dirección que el Chromecast puede usar para bajar el archivo. */
    val localAddress: InetAddress get() = socket.localAddress

    fun connect() {
        val context = SSLContext.getInstance("TLS")
        // El Chromecast usa un certificado propio, firmado por Google pero no por una CA pública: no se verifica.
        context.init(null, arrayOf(Tls.CapturingTrustManager()), null)
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), Tls.CONNECT_TIMEOUT_MS)
            socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket.soTimeout = READ_TIMEOUT_MS
            socket.startHandshake()
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
        out = socket.outputStream
        lastHeard = System.currentTimeMillis()
        send(RECEIVER_ID, CastNamespace.CONNECTION, JSONObject().put("type", "CONNECT"))
        thread("cast-reader") { readLoop() }
        thread("cast-ping") { pingLoop() }
        log.log("→ Cast: conectado a $host:$port")
    }

    /** Abre el reproductor estándar (o se suma al que ya está abierto) y se conecta a él. */
    fun launch(appId: String = DEFAULT_MEDIA_RECEIVER) {
        val status = request(RECEIVER_ID, CastNamespace.RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", appId))
        if (status.optString("type") == "LAUNCH_ERROR") throw CastException("El Chromecast no abrió el reproductor: ${status.optString("reason")}")
        val app = applications(status).firstOrNull { it.optString("appId") == appId }
            ?: throw CastException("El Chromecast no abrió el reproductor")
        transportId = app.getString("transportId")
        sessionId = app.getString("sessionId")
        send(app.getString("transportId"), CastNamespace.CONNECTION, JSONObject().put("type", "CONNECT"))
        log.log("← Cast: reproductor abierto")
    }

    /** Pide reproducir [url]. Vuelve cuando el reproductor la aceptó; si no la puede abrir, tira [CastException]. */
    fun load(url: String, contentType: String, title: String) {
        val transport = transportId ?: throw CastException("El reproductor no está abierto")
        val media = JSONObject()
            .put("contentId", url)
            .put("contentUrl", url)
            .put("streamType", "BUFFERED")
            .put("contentType", contentType)
            .put("metadata", JSONObject().put("metadataType", 0).put("title", title))
        val load = JSONObject().put("type", "LOAD").put("media", media).put("autoplay", true).put("currentTime", 0)
        sessionId?.let { load.put("sessionId", it) }
        val answer = request(transport, CastNamespace.MEDIA, load)
        when (answer.optString("type")) {
            "MEDIA_STATUS" -> {
                mediaSessionId = answer.optJSONArray("status")?.optJSONObject(0)?.optInt("mediaSessionId")
                log.log("← Cast: reproduciendo «$title»")
            }
            "LOAD_FAILED" -> throw CastException("El Chromecast no pudo abrir «$title» (¿formato no soportado?)")
            "LOAD_CANCELLED" -> throw CastException("Se canceló la carga de «$title»")
            else -> throw CastException("El Chromecast rechazó el archivo: ${answer.optString("type")} ${answer.optString("reason")}")
        }
    }

    /** Cierra el reproductor en la tele. */
    fun stopApp() {
        val session = sessionId ?: return
        runCatching { send(RECEIVER_ID, CastNamespace.RECEIVER, JSONObject().put("type", "STOP").put("sessionId", session).put("requestId", requestIds.getAndIncrement())) }
    }

    override fun close() {
        closed = true
        pending.values.forEach { it.offer(CLOSED) }
        if (::socket.isInitialized) runCatching { socket.close() }
    }

    private fun request(destination: String, namespace: String, body: JSONObject, timeoutMs: Long = REQUEST_TIMEOUT_MS): JSONObject {
        val id = requestIds.getAndIncrement()
        val answer = LinkedBlockingQueue<JSONObject>(1)
        pending[id] = answer
        try {
            send(destination, namespace, body.put("requestId", id))
            val reply = answer.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw CastException("El Chromecast no contestó (${body.optString("type")})")
            if (reply === CLOSED) throw CastException("Se cortó la conexión con el Chromecast")
            return reply
        } finally {
            pending.remove(id)
        }
    }

    private fun send(destination: String, namespace: String, body: JSONObject) {
        val bytes = CastMessage(SENDER_ID, destination, namespace, body.toString()).encode()
        synchronized(this) { CastFraming.write(out, bytes) }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val bytes = try {
                    CastFraming.read(socket.inputStream)
                } catch (e: SocketTimeoutException) {
                    if (System.currentTimeMillis() - lastHeard > DEAD_AFTER_MS) throw CastException("El Chromecast dejó de contestar")
                    continue
                }
                lastHeard = System.currentTimeMillis()
                handle(CastMessage.decode(bytes))
            }
        } catch (e: Exception) {
            if (!closed) end("Se cortó la conexión con el Chromecast (${e.message})")
        }
    }

    private fun handle(message: CastMessage) {
        val json = runCatching { JSONObject(message.payload) }.getOrNull() ?: return
        val type = json.optString("type")
        when (message.namespace) {
            CastNamespace.HEARTBEAT -> if (type == "PING") send(message.source, CastNamespace.HEARTBEAT, JSONObject().put("type", "PONG"))
            CastNamespace.CONNECTION -> if (type == "CLOSE" && message.source == transportId) end("Se cerró el reproductor en la tele")
            CastNamespace.RECEIVER -> {
                val session = sessionId
                if (type == "RECEIVER_STATUS" && session != null && applications(json).none { it.optString("sessionId") == session }) {
                    end("Se cerró el reproductor en la tele")
                }
            }
            CastNamespace.MEDIA -> if (type == "MEDIA_STATUS") {
                val status = json.optJSONArray("status")?.optJSONObject(0)
                if (status != null) {
                    if (status.has("mediaSessionId")) mediaSessionId = status.getInt("mediaSessionId")
                    onMedia?.invoke(status.optString("playerState"), status.optString("idleReason").ifEmpty { null })
                }
            }
        }
        val id = json.optInt("requestId", 0)
        if (id != 0) pending[id]?.offer(json)
    }

    private fun pingLoop() {
        while (!closed && !ended) {
            Thread.sleep(PING_EVERY_MS)
            if (closed || ended) return
            runCatching { send(RECEIVER_ID, CastNamespace.HEARTBEAT, JSONObject().put("type", "PING")) }
                .onFailure { if (!closed) end("Se cortó la conexión con el Chromecast (${it.message})") }
        }
    }

    private fun end(reason: String) {
        if (ended) return
        ended = true
        log.log("· Cast: $reason")
        pending.values.forEach { it.offer(CLOSED) }
        onEnded?.invoke(reason)
    }

    private fun applications(status: JSONObject): List<JSONObject> {
        val apps = status.optJSONObject("status")?.optJSONArray("applications") ?: return emptyList()
        return List(apps.length()) { apps.getJSONObject(it) }
    }

    private fun thread(name: String, block: () -> Unit) =
        Thread({ runCatching(block) }, name).apply { isDaemon = true; start() }

    companion object {
        const val PORT = 8009
        /** El reproductor estándar de Google: fotos, video y audio por URL, sin registrar una app propia. */
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
        const val SENDER_ID = "sender-0"
        const val RECEIVER_ID = "receiver-0"
        const val PING_EVERY_MS = 5_000L
        const val READ_TIMEOUT_MS = 5_000
        const val DEAD_AFTER_MS = 20_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
        private val CLOSED = JSONObject()
    }
}
