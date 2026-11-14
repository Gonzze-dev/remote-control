package com.gonzalo.controltv.tv

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class PairingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** El código tipeado no corresponde al que muestra la tele. No se manda nada. */
class WrongCodeException : Exception("El código no coincide")

/**
 * Vinculación con la tele por el puerto 6467. Uso:
 * 1. [start]: la tele muestra un código de 6 caracteres.
 * 2. [finish] con el código: devuelve la huella SHA-256 del certificado de la tele.
 *
 * Los métodos bloquean: llamarlos desde Dispatchers.IO.
 */
class PairingClient(
    private val host: String,
    private val identity: ClientIdentity,
    private val log: TvLog,
    private val port: Int = PAIRING_PORT,
) : Closeable {
    @Volatile private var socket: Socket? = null
    private var ssl: SSLSocket? = null
    private var input: BufferedInputStream? = null
    private var serverCert: X509Certificate? = null

    fun start(clientName: String) {
        log.log("Vinculación: conectando a $host:$port…")
        val trust = Tls.CapturingTrustManager()
        val s = Tls.connect(host, port, identity, trust) { socket = it }
        s.soTimeout = STEP_TIMEOUT_MS
        ssl = s
        input = BufferedInputStream(s.inputStream)
        serverCert = trust.serverCertificate ?: throw PairingException("La tele no mandó su certificado")
        log.log("Vinculación: TLS listo")

        exchange(Polo.pairingRequest(clientName), "pairing_request (nombre \"$clientName\")", Polo.Kind.PAIRING_REQUEST_ACK)
        exchange(Polo.options(), "options (hexadecimal, 6 símbolos, rol INPUT)", Polo.Kind.OPTIONS)
        exchange(Polo.configuration(), "configuration", Polo.Kind.CONFIGURATION_ACK)
        log.log("Vinculación: la tele muestra el código")
    }

    /** Revisa el código sin mandar nada: el primer byte del secreto tiene que coincidir. */
    fun isCodePlausible(code: String): Boolean {
        val cert = serverCert ?: return false
        return PairingSecret.normalize(code)?.let {
            PairingSecret.checkByteMatches(PairingSecret.compute(identity.certificate, cert, it), it)
        } ?: false
    }

    /** Manda el secreto. Devuelve la huella SHA-256 (hex) del certificado de la tele. */
    fun finish(code: String): String {
        val cert = serverCert ?: throw PairingException("La vinculación no empezó")
        val normalized = PairingSecret.normalize(code) ?: throw WrongCodeException()
        val secret = PairingSecret.compute(identity.certificate, cert, normalized)
        if (!PairingSecret.checkByteMatches(secret, normalized)) throw WrongCodeException()
        exchange(Polo.secret(secret), "secret", Polo.Kind.SECRET_ACK)
        val fingerprint = Tls.sha256Hex(cert.encoded)
        log.log("Vinculación: lista. Huella de la tele ${fingerprint.take(16)}…")
        close()
        return fingerprint
    }

    private fun exchange(message: ByteArray, description: String, expected: Polo.Kind) {
        val s = ssl ?: throw PairingException("Sin conexión")
        log.log("→ $description")
        Framing.write(s.outputStream, message)
        val reply = Polo.parse(Framing.read(input!!))
        log.log("← ${reply.kind.name.lowercase()} (status ${reply.status})")
        if (reply.status != Polo.STATUS_OK) {
            throw PairingException(
                when (reply.status) {
                    402 -> "La tele rechazó el código"
                    401 -> "La tele rechazó la configuración"
                    else -> "La tele respondió con error ${reply.status}"
                },
            )
        }
        if (reply.kind != expected) {
            throw PairingException("Respuesta inesperada de la tele (campos ${reply.fields})")
        }
    }

    override fun close() {
        runCatching { ssl?.close() }
        runCatching { socket?.close() }
    }

    private companion object {
        /** Cada paso espera como máximo 10 segundos. */
        const val STEP_TIMEOUT_MS = 10_000
    }
}

/**
 * El secreto de la vinculación:
 * SHA256(n_celu ‖ e_celu ‖ n_tele ‖ e_tele ‖ bytes(código[2..5])).
 * El primer byte del hash tiene que ser igual a código[0..1].
 */
object PairingSecret {
    private val HEX = Regex("^[0-9A-F]{6}$")

    /** Mayúsculas, sin espacios; null si no son 6 caracteres hexadecimales. */
    fun normalize(code: String): String? = code.trim().uppercase().takeIf { HEX.matches(it) }

    fun compute(client: X509Certificate, server: X509Certificate, code: String): ByteArray {
        val c = client.publicKey as? RSAPublicKey ?: throw PairingException("El certificado del celu no es RSA")
        val s = server.publicKey as? RSAPublicKey ?: throw PairingException("El certificado de la tele no es RSA")
        val md = MessageDigest.getInstance("SHA-256")
        md.update(unsignedBytes(c.modulus))
        md.update(unsignedBytes(c.publicExponent))
        md.update(unsignedBytes(s.modulus))
        md.update(unsignedBytes(s.publicExponent))
        md.update(hexToBytes(code.substring(2)))
        return md.digest()
    }

    fun checkByteMatches(hash: ByteArray, code: String): Boolean =
        (hash[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)

    /**
     * Bytes sin signo y sin ceros adelante. Ojo: BigInteger.toByteArray() agrega un 0x00
     * cuando el bit alto está prendido; si no se saca, el hash nunca coincide.
     */
    fun unsignedBytes(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        var i = 0
        while (i < b.size - 1 && b[i] == 0.toByte()) i++
        return b.copyOfRange(i, b.size)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
