package com.gonzalo.controltv.tv

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/** Certificado del celu y su clave privada. La tele lo recuerda después de vincular. */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate)

/**
 * Genera una vez el certificado RSA 2048 autofirmado del celu y lo guarda en un PKCS12
 * dentro de los archivos privados de la app. Si se borra, hay que vincular de nuevo.
 */
class CertManager(private val dir: File) {
    private val file = File(dir, "cliente.p12")

    @Volatile
    private var cached: ClientIdentity? = null

    /** Carga el certificado, o lo genera si no existe. Puede tardar: llamar fuera del hilo principal. */
    @Synchronized
    fun identity(): ClientIdentity {
        cached?.let { return it }
        val id = (if (file.exists()) runCatching { load() }.getOrNull() else null) ?: create()
        cached = id
        return id
    }

    /** Borra el certificado: el próximo [identity] genera uno nuevo. */
    @Synchronized
    fun reset() {
        file.delete()
        cached = null
    }

    private fun load(): ClientIdentity {
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, PASSWORD) }
        val key = ks.getKey(ALIAS, PASSWORD) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return ClientIdentity(key, cert)
    }

    private fun create(): ClientIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=atvremote")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - TimeUnit.DAYS.toMillis(1)), // un día de margen por si el reloj de la tele está atrasado
            Date(now + TimeUnit.DAYS.toMillis(3650)),
            name,
            keyPair.public,
        )
            .addExtension(Extension.basicConstraints, false, BasicConstraints(true))
            .addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(GeneralName(GeneralName.dNSName, "atvremote")),
            )
        // Sin setProvider: firma con el proveedor por defecto de Android (Conscrypt).
        val holder = builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate

        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry(ALIAS, keyPair.private, PASSWORD, arrayOf(cert))
        dir.mkdirs()
        val tmp = File(dir, "cliente.p12.tmp")
        tmp.outputStream().use { ks.store(it, PASSWORD) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return ClientIdentity(keyPair.private, cert)
    }

    private companion object {
        const val ALIAS = "cliente"

        // El archivo vive en el almacenamiento privado de la app; la clave solo cumple con el formato.
        val PASSWORD = "controltv".toCharArray()
    }
}
