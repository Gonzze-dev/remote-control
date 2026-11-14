package com.gonzalo.controltv.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** Bytes exactos de los mensajes y del cálculo del secreto. */
class ProtocolTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02x".format(it) }

    @Test
    fun keyInjectBytes() {
        // remote_key_inject (10) { key_code: 19, direction: SHORT(3) }
        assertEquals("52 04 08 13 10 03", hex(Remote.keyInject(Keys.DPAD_UP, Direction.SHORT)))
    }

    @Test
    fun pairingRequestBytes() {
        // protocol_version 2, status 200, pairing_request { service_name "atvremote", client_name "Mi" }
        val expected = "08 02 10 c8 01 52 0f 0a 09 61 74 76 72 65 6d 6f 74 65 12 02 4d 69"
        assertEquals(expected, hex(Polo.pairingRequest("Mi")))
    }

    @Test
    fun optionsAndConfigurationBytes() {
        // options (20) { input_encodings { type 3, symbol_length 6 }, preferred_role 1 }
        assertEquals("08 02 10 c8 01 a2 01 08 0a 04 08 03 10 06 18 01", hex(Polo.options()))
        // configuration (30) { encoding { type 3, symbol_length 6 }, client_role 1 }
        assertEquals("08 02 10 c8 01 f2 01 08 0a 04 08 03 10 06 10 01", hex(Polo.configuration()))
    }

    @Test
    fun configureReplyHasFeatures615() {
        val m = ProtoMessage.parse(Remote.configure(Features.REQUESTED)).message(1)!!
        assertEquals(615, m.int(1))
        val info = m.message(2)!!
        assertEquals(1, info.int(3))
        assertEquals("1", info.string(4))
        assertEquals("atvremote", info.string(5))
        assertEquals("1.0.0", info.string(6))
    }

    @Test
    fun imeBatchEditCarriesCountersAndText() {
        val m = ProtoMessage.parse(Remote.imeBatchEdit(5, 7, "hola")).message(21)!!
        assertEquals(5, m.int(1))
        assertEquals(7, m.int(2))
        val edit = m.message(3)!!
        assertEquals(1, edit.int(1))
        val status = edit.message(2)!!
        assertEquals(4, status.int(1))
        assertEquals(4, status.int(2))
        assertEquals("hola", status.string(3))
    }

    @Test
    fun emptySubmessageStillCounts() {
        // remote_start {} (proto3 no escribe started=false): campo 40, largo 0.
        assertEquals(RemoteEvent.Start(false), Remote.parse(byteArrayOf(0xc2.toByte(), 0x02, 0x00)))
        // configuration_ack {} en vinculación.
        val reply = Polo.parse(ProtoWriter().int(1, 2, true).int(2, 200, true).message(31) {}.toByteArray())
        assertEquals(Polo.Kind.CONFIGURATION_ACK, reply.kind)
    }

    @Test
    fun parsesVolumeAndApp() {
        val vol = ProtoWriter().message(50) { int(6, 100); int(7, 12); int(8, 1) }.toByteArray()
        assertEquals(RemoteEvent.Volume(12, 100, true, ""), Remote.parse(vol))
        val app = ProtoWriter().message(20) {
            message(1) { int(1, 3); string(12, "com.netflix.ninja") }
            message(2) { int(1, 9) }
        }.toByteArray()
        assertEquals(RemoteEvent.ImeKeyInject("com.netflix.ninja", 3, 9), Remote.parse(app))
    }

    @Test
    fun negativeIntsRoundTrip() {
        val bytes = ProtoWriter().int(1, -1).toByteArray()
        assertEquals(11, bytes.size) // tag + 10 bytes, como int32 en protobuf
        assertEquals(-1, ProtoMessage.parse(bytes).int(1))
    }

    @Test
    fun framingRoundTripWithLongMessage() {
        val msg = ByteArray(300) { it.toByte() }
        val out = ByteArrayOutputStream()
        Framing.write(out, msg)
        Framing.write(out, byteArrayOf(1, 2, 3))
        val bytes = out.toByteArray()
        assertEquals(0xac.toByte(), bytes[0]) // 300 = 0xac 0x02
        assertEquals(0x02.toByte(), bytes[1])
        val input = ByteArrayInputStream(bytes)
        assertArrayEquals(msg, Framing.read(input))
        assertArrayEquals(byteArrayOf(1, 2, 3), Framing.read(input))
    }

    @Test
    fun unsignedBytesDropsSignByte() {
        val n = BigInteger("ff01", 16) // toByteArray() da 00 ff 01
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0x01), PairingSecret.unsignedBytes(n))
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x01), PairingSecret.unsignedBytes(BigInteger.valueOf(65537)))
    }

    @Test
    fun normalizesCodes() {
        assertEquals("A1B2C3", PairingSecret.normalize(" a1b2c3 "))
        assertEquals(null, PairingSecret.normalize("A1B2G3"))
        assertEquals(null, PairingSecret.normalize("A1B2C"))
    }

    @Test
    fun featureMask() {
        assertEquals(615, Features.REQUESTED)
        assertEquals("PING+KEY+IME+POWER+VOLUME+APP_LINK", Features.describe(615))
    }

    @Test
    fun certificateIsGeneratedOnceAndReloaded() {
        val dir = createTempDirectory()
        val first = CertManager(dir).identity()
        val again = CertManager(dir).identity()
        assertArrayEquals(first.certificate.encoded, again.certificate.encoded)
        assertEquals("RSA", first.certificate.publicKey.algorithm)
        assertTrue(first.certificate.notAfter.time - System.currentTimeMillis() > 9L * 365 * 24 * 3600 * 1000)
        first.certificate.verify(first.certificate.publicKey) // autofirmado
        assertNotNull(first.privateKey)
        CertManager(dir).reset()
        assertFalse(CertManager(dir).identity().certificate.encoded.contentEquals(first.certificate.encoded))
    }
}

fun createTempDirectory(): java.io.File = kotlin.io.path.createTempDirectory("controltv").toFile().apply { deleteOnExit() }
