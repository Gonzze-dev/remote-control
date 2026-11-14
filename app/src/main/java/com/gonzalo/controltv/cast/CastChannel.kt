package com.gonzalo.controltv.cast

import com.gonzalo.controltv.tv.ProtoException
import com.gonzalo.controltv.tv.ProtoMessage
import com.gonzalo.controltv.tv.ProtoWriter
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Un mensaje de Cast v2 (`CastMessage` de cast_channel.proto, puerto 8009). Solo se usan mensajes de texto
 * con JSON: el reproductor estándar de Google no necesita los binarios.
 *
 * ```
 * message CastMessage {
 *   required ProtocolVersion protocol_version = 1;  // CASTV2_1_0 = 0
 *   required string source_id = 2;
 *   required string destination_id = 3;
 *   required string namespace = 4;
 *   required PayloadType payload_type = 5;          // STRING = 0
 *   optional string payload_utf8 = 6;
 * }
 * ```
 */
data class CastMessage(val source: String, val destination: String, val namespace: String, val payload: String) {

    fun encode(): ByteArray = ProtoWriter()
        .int(1, 0, always = true)
        .string(2, source, always = true)
        .string(3, destination, always = true)
        .string(4, namespace, always = true)
        .int(5, 0, always = true)
        .string(6, payload, always = true)
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): CastMessage {
            val m = ProtoMessage.parse(bytes)
            return CastMessage(m.string(2), m.string(3), m.string(4), m.string(6))
        }
    }
}

/** Los canales (namespaces) que usa la app. */
object CastNamespace {
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val MEDIA = "urn:x-cast:com.google.cast.media"
}

/** En Cast v2 cada mensaje va precedido de su largo en 4 bytes big-endian (no un varint, como en el control). */
object CastFraming {
    /** El protocolo limita los mensajes a 64 KiB. */
    const val MAX_MESSAGE = 64 * 1024

    fun write(out: OutputStream, message: ByteArray) {
        val frame = ByteBuffer.allocate(4 + message.size).putInt(message.size).put(message).array()
        out.write(frame) // un solo write: largo y mensaje viajan juntos
        out.flush()
    }

    fun read(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        val len = try {
            data.readInt()
        } catch (e: EOFException) {
            throw EOFException("El Chromecast cerró la conexión")
        }
        if (len < 0 || len > MAX_MESSAGE) throw ProtoException("Mensaje de Cast inválido: $len bytes")
        return ByteArray(len).also { data.readFully(it) }
    }
}
