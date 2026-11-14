package com.gonzalo.controltv.tv

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Codificador y decodificador mínimo de protobuf (el "plan B" del plan).
 *
 * Alcanza para los pocos mensajes de polo.proto y remotemessage.proto que usa la app,
 * así no hace falta el plugin de protobuf ni la librería protobuf-lite. Los números de
 * campo salen de los .proto que están en app/src/main/proto.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    /**
     * Entero (int32/uint32/enum). Como en proto3, el 0 no se escribe salvo que se pida con
     * [always] (los campos `required` de proto2 se escriben siempre).
     */
    fun int(field: Int, value: Int, always: Boolean = false): ProtoWriter {
        if (value != 0 || always) {
            tag(field, WIRE_VARINT)
            varint(value.toLong()) // los negativos van con extensión de signo, como int32
        }
        return this
    }

    fun string(field: Int, value: String, always: Boolean = false): ProtoWriter {
        if (value.isNotEmpty() || always) bytes(field, value.toByteArray(Charsets.UTF_8))
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LEN)
        varint(value.size.toLong())
        out.write(value)
        return this
    }

    /** Submensaje. Se escribe aunque quede vacío: su presencia ya es información. */
    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    private fun varint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private companion object {
        const val WIRE_VARINT = 0
        const val WIRE_LEN = 2
    }
}

/** Mensaje decodificado: número de campo → valores (Long para varints, ByteArray para el resto). */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int): Boolean = fields.containsKey(field)

    fun long(field: Int): Long = fields[field]?.lastOrNull() as? Long ?: 0L

    fun int(field: Int): Int = long(field).toInt()

    fun bool(field: Int): Boolean = long(field) != 0L

    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray

    fun string(field: Int): String = bytes(field)?.toString(Charsets.UTF_8) ?: ""

    /** Submensaje, o null si no vino. Un submensaje vacío (por ej. `remote_start {}`) no es null. */
    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    fun fieldNumbers(): Set<Int> = fields.keys

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            var pos = 0

            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw ProtoException("varint cortado")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift >= 64) throw ProtoException("varint demasiado largo")
                }
            }

            fun readFixed(size: Int): ByteArray {
                if (pos + size > data.size) throw ProtoException("campo fijo cortado")
                return data.copyOfRange(pos, pos + size).also { pos += size }
            }

            while (pos < data.size) {
                val tag = readVarint()
                val field = (tag ushr 3).toInt()
                if (field <= 0) throw ProtoException("número de campo inválido: $field")
                val value: Any = when (val wire = (tag and 7).toInt()) {
                    0 -> readVarint()
                    1 -> readFixed(8)
                    2 -> {
                        val len = readVarint()
                        if (len < 0 || len > data.size - pos) throw ProtoException("largo inválido: $len")
                        readFixed(len.toInt())
                    }
                    5 -> readFixed(4)
                    else -> throw ProtoException("tipo de campo no soportado: $wire")
                }
                fields.getOrPut(field) { mutableListOf() }.add(value)
            }
            return ProtoMessage(fields)
        }
    }
}

class ProtoException(message: String) : IOException(message)
