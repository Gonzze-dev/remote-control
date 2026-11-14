package com.gonzalo.controltv.tv

/**
 * Mensajes de vinculación (OuterMessage de polo.proto, puerto 6467).
 * Todos llevan protocol_version = 2 y status = 200.
 */
object Polo {
    const val STATUS_OK = 200
    private const val PROTOCOL_VERSION = 2
    private const val ENCODING_HEXADECIMAL = 3
    private const val SYMBOL_LENGTH = 6
    private const val ROLE_INPUT = 1

    // Campos de OuterMessage
    private const val F_VERSION = 1
    private const val F_STATUS = 2
    private const val F_PAIRING_REQUEST = 10
    private const val F_PAIRING_REQUEST_ACK = 11
    private const val F_OPTIONS = 20
    private const val F_CONFIGURATION = 30
    private const val F_CONFIGURATION_ACK = 31
    private const val F_SECRET = 40
    private const val F_SECRET_ACK = 41

    enum class Kind { PAIRING_REQUEST_ACK, OPTIONS, CONFIGURATION_ACK, SECRET_ACK, OTHER }

    data class Reply(val status: Int, val kind: Kind, val fields: Set<Int>)

    private fun outer(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter()
        .int(F_VERSION, PROTOCOL_VERSION, always = true)
        .int(F_STATUS, STATUS_OK, always = true)
        .apply(block)
        .toByteArray()

    private fun ProtoWriter.hexEncoding() {
        int(1, ENCODING_HEXADECIMAL, always = true) // type
        int(2, SYMBOL_LENGTH, always = true) // symbol_length
    }

    fun pairingRequest(clientName: String): ByteArray = outer {
        message(F_PAIRING_REQUEST) {
            string(1, "atvremote", always = true) // service_name
            string(2, clientName) // client_name: lo que se ve en la tele
        }
    }

    fun options(): ByteArray = outer {
        message(F_OPTIONS) {
            message(1) { hexEncoding() } // input_encodings
            int(3, ROLE_INPUT, always = true) // preferred_role
        }
    }

    fun configuration(): ByteArray = outer {
        message(F_CONFIGURATION) {
            message(1) { hexEncoding() } // encoding
            int(2, ROLE_INPUT, always = true) // client_role
        }
    }

    fun secret(hash: ByteArray): ByteArray = outer {
        message(F_SECRET) { bytes(1, hash) }
    }

    fun parse(bytes: ByteArray): Reply {
        val m = ProtoMessage.parse(bytes)
        val kind = when {
            m.has(F_PAIRING_REQUEST_ACK) -> Kind.PAIRING_REQUEST_ACK
            m.has(F_OPTIONS) -> Kind.OPTIONS
            m.has(F_CONFIGURATION_ACK) -> Kind.CONFIGURATION_ACK
            m.has(F_SECRET_ACK) -> Kind.SECRET_ACK
            else -> Kind.OTHER
        }
        return Reply(m.int(F_STATUS), kind, m.fieldNumbers())
    }
}

/** Lo que la tele manda por la conexión de control. */
sealed interface RemoteEvent {
    data class Configure(val features: Int, val vendor: String, val model: String, val appVersion: String) : RemoteEvent
    data object SetActive : RemoteEvent
    data class PingRequest(val val1: Int) : RemoteEvent
    data class ImeKeyInject(val appPackage: String, val imeCounter: Int, val fieldCounter: Int) : RemoteEvent
    data class ImeShowRequest(val fieldCounter: Int?) : RemoteEvent
    data class ImeBatchEdit(val imeCounter: Int, val fieldCounter: Int) : RemoteEvent
    data class Volume(val level: Int, val max: Int, val muted: Boolean, val playerModel: String) : RemoteEvent
    data class Start(val started: Boolean) : RemoteEvent
    data class Error(val fields: Set<Int>) : RemoteEvent
    data class Other(val fields: Set<Int>) : RemoteEvent
}

/** Mensajes de control (RemoteMessage de remotemessage.proto, puerto 6466). */
object Remote {
    // Campos de RemoteMessage
    private const val F_CONFIGURE = 1
    private const val F_SET_ACTIVE = 2
    private const val F_ERROR = 3
    private const val F_PING_REQUEST = 8
    private const val F_PING_RESPONSE = 9
    private const val F_KEY_INJECT = 10
    private const val F_IME_KEY_INJECT = 20
    private const val F_IME_BATCH_EDIT = 21
    private const val F_IME_SHOW_REQUEST = 22
    private const val F_START = 40
    private const val F_SET_VOLUME_LEVEL = 50
    private const val F_APP_LINK_LAUNCH = 90

    private fun message(field: Int, block: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().message(field, block).toByteArray()

    fun configure(features: Int, packageName: String = "atvremote", appVersion: String = "1.0.0"): ByteArray =
        message(F_CONFIGURE) {
            int(1, features) // code1
            message(2) { // device_info
                int(3, 1) // unknown1
                string(4, "1") // unknown2
                string(5, packageName)
                string(6, appVersion)
            }
        }

    fun setActive(features: Int): ByteArray = message(F_SET_ACTIVE) { int(1, features) }

    fun pingResponse(val1: Int): ByteArray = message(F_PING_RESPONSE) { int(1, val1) }

    fun keyInject(keyCode: Int, direction: Direction): ByteArray = message(F_KEY_INJECT) {
        int(1, keyCode)
        int(2, direction.value)
    }

    /** Texto para el campo enfocado. Sin los contadores correctos, la tele lo ignora. */
    fun imeBatchEdit(imeCounter: Int, fieldCounter: Int, text: String): ByteArray = message(F_IME_BATCH_EDIT) {
        int(1, imeCounter)
        int(2, fieldCounter)
        message(3) { // edit_info
            int(1, 1) // insert
            message(2) { // text_field_status
                int(1, text.length) // start
                int(2, text.length) // end
                string(3, text) // value
            }
        }
    }

    fun appLinkLaunch(appLink: String): ByteArray = message(F_APP_LINK_LAUNCH) { string(1, appLink) }

    fun parse(bytes: ByteArray): RemoteEvent {
        val m = ProtoMessage.parse(bytes)
        m.message(F_CONFIGURE)?.let { c ->
            val info = c.message(2)
            return RemoteEvent.Configure(
                features = c.int(1),
                vendor = info?.string(2).orEmpty(),
                model = info?.string(1).orEmpty(),
                appVersion = info?.string(6).orEmpty(),
            )
        }
        if (m.has(F_SET_ACTIVE)) return RemoteEvent.SetActive
        m.message(F_PING_REQUEST)?.let { return RemoteEvent.PingRequest(it.int(1)) }
        m.message(F_IME_KEY_INJECT)?.let { k ->
            val app = k.message(1)
            return RemoteEvent.ImeKeyInject(
                appPackage = app?.string(12).orEmpty(),
                imeCounter = app?.int(1) ?: 0,
                fieldCounter = k.message(2)?.int(1) ?: 0,
            )
        }
        m.message(F_IME_SHOW_REQUEST)?.let { s ->
            return RemoteEvent.ImeShowRequest(s.message(2)?.int(1))
        }
        m.message(F_IME_BATCH_EDIT)?.let { b -> return RemoteEvent.ImeBatchEdit(b.int(1), b.int(2)) }
        m.message(F_SET_VOLUME_LEVEL)?.let { v ->
            return RemoteEvent.Volume(level = v.int(7), max = v.int(6), muted = v.bool(8), playerModel = v.string(3))
        }
        m.message(F_START)?.let { return RemoteEvent.Start(it.bool(1)) }
        m.message(F_ERROR)?.let { e -> return RemoteEvent.Error(e.message(2)?.fieldNumbers().orEmpty()) }
        return RemoteEvent.Other(m.fieldNumbers())
    }
}
