package com.gonzalo.controltv.tv

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Una "tele" de prueba que habla el mismo protocolo que el Chromecast, con TLS de verdad
 * y certificado de cliente obligatorio. Sirve para probar el protocolo sin la tele real.
 */
class FakeTv(dir: File) : Closeable {
    val identity: ClientIdentity = CertManager(File(dir, "tele")).identity()
    val fingerprint: String = Tls.sha256Hex(identity.certificate.encoded)

    /** Huellas de los celus vinculados. */
    val pairedClients = CopyOnWriteArrayList<String>()

    /** Lo que "aparece en la pantalla" de la tele al vincular. */
    val shownCodes = LinkedBlockingQueue<String>()

    /** Mensajes de control que recibió la tele, ya decodificados. */
    val received = LinkedBlockingQueue<ProtoMessage>()
    val pairingNames = LinkedBlockingQueue<String>()

    private val pairingServer = serverSocket(requirePaired = false)
    private val controlServer = serverSocket(requirePaired = true)
    val pairingPort get() = pairingServer.localPort
    val controlPort get() = controlServer.localPort

    @Volatile var supportedFeatures = 0b1001101111 // PING, KEY, IME, VOICE, POWER, VOLUME, APP_LINK = 623
    private val sessions = CopyOnWriteArrayList<SSLSocket>()

    init {
        thread("pairing") { while (!pairingServer.isClosed) runCatching { handlePairing(pairingServer.accept() as SSLSocket) } }
        thread("control") {
            while (!controlServer.isClosed) {
                val s = runCatching { controlServer.accept() as SSLSocket }.getOrNull() ?: continue
                thread("session") { runCatching { handleControl(s) } }
            }
        }
    }

    private fun serverSocket(requirePaired: Boolean): SSLServerSocket {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("tele", identity.privateKey, PASS, arrayOf(identity.certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, PASS) }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf(ClientTrust(requirePaired)), SecureRandom())
        return (ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket)
            .apply { needClientAuth = true }
    }

    /** En el puerto de control solo entran celus vinculados; en el de vinculación, cualquiera. */
    private inner class ClientTrust(private val requirePaired: Boolean) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            if (requirePaired && Tls.sha256Hex(chain[0].encoded) !in pairedClients) {
                throw CertificateException("celu no vinculado")
            }
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun handlePairing(s: SSLSocket) = s.use {
        s.startHandshake()
        val client = s.session.peerCertificates[0] as X509Certificate
        val input = BufferedInputStream(s.inputStream)
        fun reply(block: ProtoWriter.() -> Unit, status: Int = 200) =
            Framing.write(s.outputStream, ProtoWriter().int(1, 2, true).int(2, status, true).apply(block).toByteArray())

        val req = ProtoMessage.parse(Framing.read(input))
        val pr = req.message(10) ?: error("se esperaba pairing_request")
        check(pr.string(1) == "atvremote") { "service_name" }
        pairingNames.add(pr.string(2))
        reply({ message(11) { string(1, "Tele de prueba") } })

        val opt = ProtoMessage.parse(Framing.read(input)).message(20) ?: error("se esperaba options")
        check(opt.message(1)!!.int(1) == 3 && opt.message(1)!!.int(2) == 6) { "encoding" }
        reply({ message(20) { message(1) { int(1, 3, true); int(2, 6, true) }; int(3, 1, true) } })

        val conf = ProtoMessage.parse(Framing.read(input)).message(30) ?: error("se esperaba configuration")
        check(conf.int(2) == 1) { "client_role" }
        reply({ message(31) {} })

        // La tele elige 2 bytes al azar y muestra el código.
        val nonce = ByteArray(2).also { SecureRandom().nextBytes(it) }
        val nonceHex = nonce.joinToString("") { "%02X".format(it) }
        val expected = PairingSecret.compute(client, identity.certificate, "00$nonceHex")
        shownCodes.add("%02X".format(expected[0]) + nonceHex)

        val secret = ProtoMessage.parse(Framing.read(input)).message(40)?.bytes(1) ?: error("se esperaba secret")
        if (secret.contentEquals(expected)) {
            pairedClients.add(Tls.sha256Hex(client.encoded))
            reply({ message(41) { bytes(1, secret) } })
        } else {
            reply({}, status = 402)
        }
    }

    private fun handleControl(s: SSLSocket) = s.use {
        s.soTimeout = 20_000
        s.startHandshake()
        sessions.add(s)
        val input = BufferedInputStream(s.inputStream)
        fun send(bytes: ByteArray) = synchronized(s) { Framing.write(s.outputStream, bytes) }
        fun next(): ProtoMessage = ProtoMessage.parse(Framing.read(input)).also { received.add(it) }

        send(ProtoWriter().message(1) {
            int(1, supportedFeatures)
            message(2) { string(1, "Chromecast"); string(2, "Google"); string(6, "6.0") }
        }.toByteArray())
        next() // remote_configure del celu
        send(ProtoWriter().message(2) { int(1, 622) }.toByteArray())
        next() // remote_set_active del celu
        send(ProtoWriter().message(40) { int(1, 1) }.toByteArray()) // remote_start, prendida
        send(ProtoWriter().message(20) {
            message(1) { int(1, 5); string(12, "com.netflix.ninja") }
            message(2) { int(1, 7) }
        }.toByteArray())
        send(ProtoWriter().message(50) { int(6, 100); int(7, 12) }.toByteArray())
        send(ProtoWriter().message(8) { int(1, 42); int(2, 1) }.toByteArray()) // ping
        while (true) next()
    }

    /** Corta todas las sesiones de control abiertas (como si la tele se reiniciara). */
    fun dropSessions() {
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    fun nextReceived(): ProtoMessage = received.poll(10, TimeUnit.SECONDS) ?: error("la tele no recibió nada")

    override fun close() {
        runCatching { pairingServer.close() }
        runCatching { controlServer.close() }
        dropSessions()
    }

    private fun thread(name: String, block: () -> Unit) =
        Thread(block, "faketv-$name").apply { isDaemon = true; start() }

    private companion object {
        val PASS = "tele".toCharArray()
    }
}
