package com.gonzalo.controltv.tv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Formato de los mensajes en los dos puertos: un varint con el largo, seguido del protobuf.
 */
object Framing {
    /** Ningún mensaje del protocolo se acerca a esto; si llega algo así, el stream está roto. */
    private const val MAX_MESSAGE = 1 shl 20

    fun write(out: OutputStream, message: ByteArray) {
        val frame = ByteArrayOutputStream(message.size + 5)
        var len = message.size
        while (len and 0x7F.inv() != 0) {
            frame.write((len and 0x7F) or 0x80)
            len = len ushr 7
        }
        frame.write(len)
        frame.write(message)
        out.write(frame.toByteArray()) // un solo write: largo y mensaje viajan juntos
        out.flush()
    }

    /** Bloquea hasta leer un mensaje completo. */
    fun read(input: InputStream): ByteArray {
        var len = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("La tele cerró la conexión")
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw ProtoException("Largo de mensaje inválido")
        }
        if (len < 0 || len > MAX_MESSAGE) throw ProtoException("Mensaje demasiado grande: $len bytes")
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException("La tele cerró la conexión a mitad de un mensaje")
            off += n
        }
        return buf
    }
}
