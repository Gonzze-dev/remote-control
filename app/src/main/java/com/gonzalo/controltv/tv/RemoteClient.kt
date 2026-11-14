package com.gonzalo.controltv.tv

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

data class TvInfo(val vendor: String, val model: String, val appVersion: String, val features: Int)

data class VolumeInfo(val level: Int, val max: Int, val muted: Boolean)

/**
 * Una sesión de control con la tele (puerto 6466). [run] bloquea mientras la conexión
 * está viva y responde sola los pings; los comandos se mandan desde otro hilo.
 */
class RemoteClient(
    private val host: String,
    private val identity: ClientIdentity,
    private val serverFingerprint: String,
    private val log: TvLog,
    private val listener: Listener,
    private val port: Int = CONTROL_PORT,
) : Closeable {

    interface Listener {
        /** Llegó remote_start: desde acá se pueden mandar comandos. */
        fun onReady(info: TvInfo?, poweredOn: Boolean)
        fun onPowerChanged(poweredOn: Boolean)
        fun onAppChanged(appPackage: String)
        fun onVolumeChanged(volume: VolumeInfo)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var ssl: SSLSocket? = null
    @Volatile private var closed = false

    @Volatile
    var isReady = false
        private set

    private val writeLock = Any()
    private var activeFeatures = Features.REQUESTED
    private var info: TvInfo? = null

    // El truco del texto: hay que mandar los mismos contadores que tiene el campo enfocado.
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    private var hasImeCounter = false
    private var hasFieldCounter = false

    /**
     * Conecta y atiende la sesión hasta que se corta. Siempre termina con una excepción:
     * [NeedsPairingException] si la tele no reconoce al celu, IOException si se cortó.
     */
    fun run() {
        val trust = Tls.PinnedTrustManager(serverFingerprint)
        log.log("Conectando a $host:$port…")
        val s = try {
            Tls.connect(host, port, identity, trust) { socket = it }
        } catch (e: TlsHandshakeException) {
            if (trust.mismatch) {
                throw NeedsPairingException("La tele cambió de certificado", definitive = true, cause = e)
            }
            if (closed) throw e
            throw NeedsPairingException("La tele no acepta este celu", definitive = false, cause = e.cause)
        }
        ssl = s
        try {
            if (closed) throw IOException("Conexión cancelada")
            // Si pasan 16 s sin recibir nada, la conexión se da por muerta (la tele manda pings cada 5 s).
            s.soTimeout = IDLE_TIMEOUT_MS
            val input = BufferedInputStream(s.inputStream)
            var receivedAny = false
            while (true) {
                val raw = try {
                    Framing.read(input)
                } catch (e: IOException) {
                    // En TLS 1.3 el rechazo del certificado llega recién en la primera lectura.
                    if (!receivedAny && !closed && e !is SocketTimeoutException) {
                        throw NeedsPairingException("La tele cortó apenas conectó", definitive = false, cause = e)
                    }
                    throw e
                }
                receivedAny = true
                handle(Remote.parse(raw))
            }
        } catch (e: SocketTimeoutException) {
            throw IOException("Pasaron 16 s sin noticias de la tele", e)
        } finally {
            close()
        }
    }

    fun sendKey(keyCode: Int, direction: Direction = Direction.SHORT) =
        write(Remote.keyInject(keyCode, direction), "tecla ${Keys.name(keyCode)} ${direction.name}")

    fun sendText(text: String) =
        write(Remote.imeBatchEdit(imeCounter, fieldCounter, text), "texto \"$text\" (ime $imeCounter, campo $fieldCounter)")

    fun launchApp(appLink: String) = write(Remote.appLinkLaunch(appLink), "abrir $appLink")

    override fun close() {
        closed = true
        runCatching { ssl?.close() }
        runCatching { socket?.close() }
    }

    private fun handle(event: RemoteEvent) {
        when (event) {
            is RemoteEvent.Configure -> {
                activeFeatures = Features.REQUESTED and event.features
                info = TvInfo(event.vendor, event.model, event.appVersion, activeFeatures)
                log.log("← remote_configure: ${event.vendor} ${event.model}, soporta ${Features.describe(event.features)}")
                if (event.features and Features.KEY == 0 || event.features and Features.APP_LINK == 0) {
                    log.log(
                        "⚠ La tele no ofrece KEY o APP_LINK. Probá borrar los datos de la app de sistema " +
                            "\"Android TV Remote Service\" en el Chromecast (Configuración → Apps → Apps del sistema).",
                    )
                }
                write(Remote.configure(activeFeatures), "remote_configure (pido ${Features.describe(activeFeatures)} = $activeFeatures)")
            }
            RemoteEvent.SetActive -> {
                log.log("← remote_set_active")
                write(Remote.setActive(activeFeatures), "remote_set_active ($activeFeatures)")
            }
            is RemoteEvent.PingRequest -> write(Remote.pingResponse(event.val1), description = null)
            is RemoteEvent.Start -> {
                log.log("← remote_start (prendida: ${if (event.started) "sí" else "no"})")
                if (!isReady) {
                    isReady = true
                    listener.onReady(info, event.started)
                } else {
                    listener.onPowerChanged(event.started)
                }
            }
            is RemoteEvent.ImeKeyInject -> {
                log.log("← ime_key_inject: app ${event.appPackage.ifBlank { "?" }}, ime ${event.imeCounter}, campo ${event.fieldCounter}")
                if (event.appPackage.isNotBlank()) listener.onAppChanged(event.appPackage)
                imeCounter = event.imeCounter
                fieldCounter = event.fieldCounter
                hasImeCounter = event.imeCounter != 0
                hasFieldCounter = event.fieldCounter != 0
            }
            is RemoteEvent.ImeShowRequest -> {
                log.log("← ime_show_request: campo ${event.fieldCounter ?: "?"}")
                event.fieldCounter?.let {
                    fieldCounter = it
                    hasFieldCounter = true
                }
            }
            is RemoteEvent.ImeBatchEdit -> {
                log.log("← ime_batch_edit: ime ${event.imeCounter}, campo ${event.fieldCounter}")
                if (!hasImeCounter) imeCounter = event.imeCounter
                if (!hasFieldCounter) fieldCounter = event.fieldCounter
            }
            is RemoteEvent.Volume -> {
                log.log("← volumen ${event.level}/${event.max}${if (event.muted) " (silencio)" else ""}")
                listener.onVolumeChanged(VolumeInfo(event.level, event.max, event.muted))
            }
            is RemoteEvent.Error -> log.log("← remote_error (campos del mensaje rechazado: ${event.fields})")
            is RemoteEvent.Other -> log.log("← mensaje sin manejar (campos ${event.fields})")
        }
    }

    private fun write(bytes: ByteArray, description: String?) {
        val s = ssl ?: throw IOException("Sin conexión")
        synchronized(writeLock) { Framing.write(s.outputStream, bytes) }
        if (description != null) log.log("→ $description")
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 16_000
    }
}
