package com.gonzalo.controltv.tv

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

const val PAIRING_PORT = 6467
const val CONTROL_PORT = 6466

/**
 * La tele ya no reconoce al celu (o cambió de certificado): hay que vincular de nuevo.
 * Si no es [definitive], puede ser un corte casual: se confirma con unos reintentos.
 */
class NeedsPairingException(message: String, val definitive: Boolean, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Falló el TLS después de conectar por TCP. Según el momento, el rechazo del certificado
 * llega como alerta TLS, "connection reset" o "connection aborted"; todos caen acá.
 */
class TlsHandshakeException(cause: IOException) : IOException("Falló el TLS con la tele: ${cause.message}", cause)

object Tls {
    const val CONNECT_TIMEOUT_MS = 5_000

    /**
     * Abre TCP + TLS presentando el certificado del celu. [onSocket] recibe el socket TCP
     * antes de conectar, para que otro hilo pueda cortar la conexión si hace falta.
     */
    fun connect(
        host: String,
        port: Int,
        identity: ClientIdentity,
        trust: X509TrustManager,
        onSocket: (Socket) -> Unit = {},
    ): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(FixedKeyManager(identity)), arrayOf(trust), null)
        val raw = Socket()
        onSocket(raw)
        try {
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = 10_000 // para el handshake; después cada cliente pone el suyo
            val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            onSocket(ssl)
            try {
                ssl.startHandshake()
            } catch (e: SocketTimeoutException) {
                throw e
            } catch (e: IOException) {
                // La tele atendió por TCP pero cortó el TLS: casi siempre es que no acepta el certificado.
                throw TlsHandshakeException(e)
            }
            return ssl
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    /** Acepta cualquier certificado y guarda el de la tele. Solo para vincular (la tele usa uno autofirmado). */
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager") // el protocolo lo exige: la tele usa un certificado autofirmado
    class CapturingTrustManager : X509TrustManager {
        @Volatile
        var serverCertificate: X509Certificate? = null
            private set

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            serverCertificate = chain.firstOrNull() ?: throw CertificateException("La tele no mandó certificado")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("Solo cliente")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Solo acepta la tele con la que se vinculó (compara la huella SHA-256 de su certificado). */
    @Suppress("CustomX509TrustManager") // verifica la huella guardada al vincular
    class PinnedTrustManager(private val expectedSha256: String) : X509TrustManager {
        @Volatile
        var mismatch = false
            private set

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val cert = chain.firstOrNull() ?: throw CertificateException("La tele no mandó certificado")
            if (!sha256Hex(cert.encoded).equals(expectedSha256, ignoreCase = true)) {
                mismatch = true
                throw CertificateException("El certificado de la tele no es el que se guardó al vincular")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("Solo cliente")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Siempre presenta el certificado del celu. El KeyManager por defecto de Java lo
     * descartaría si la tele pide emisores que no coinciden con uno autofirmado.
     */
    private class FixedKeyManager(private val identity: ClientIdentity) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

        private companion object {
            const val ALIAS = "cliente"
        }
    }
}
