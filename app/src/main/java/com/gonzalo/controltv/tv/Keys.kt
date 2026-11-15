package com.gonzalo.controltv.tv

/** Códigos de teclas de Android (enum RemoteKeyCode de remotemessage.proto). */
object Keys {
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val ENTER = 66
    const val DEL = 67
    const val SEARCH = 84
    const val MEDIA_PLAY_PAUSE = 85

    /** Silencia la tele. Ojo: el 91 (KEYCODE_MUTE) silencia el micrófono, no el sonido. */
    const val VOLUME_MUTE = 164
    const val SETTINGS = 176
    const val TV_INPUT = 178

    private val names = mapOf(
        HOME to "HOME", BACK to "BACK",
        DPAD_UP to "DPAD_UP", DPAD_DOWN to "DPAD_DOWN", DPAD_LEFT to "DPAD_LEFT", DPAD_RIGHT to "DPAD_RIGHT",
        DPAD_CENTER to "DPAD_CENTER", VOLUME_UP to "VOLUME_UP", VOLUME_DOWN to "VOLUME_DOWN", POWER to "POWER",
        ENTER to "ENTER", DEL to "DEL", SEARCH to "SEARCH", MEDIA_PLAY_PAUSE to "MEDIA_PLAY_PAUSE", VOLUME_MUTE to "VOLUME_MUTE",
        SETTINGS to "SETTINGS", TV_INPUT to "TV_INPUT",
    )

    fun name(code: Int): String = names[code] ?: code.toString()
}

/** Cómo se aprieta una tecla (enum RemoteDirection). */
enum class Direction(val value: Int) {
    START_LONG(1),
    END_LONG(2),
    SHORT(3),
}

/** Funciones que se negocian en remote_configure (campo code1). Se suman como bits. */
object Features {
    const val PING = 1
    const val KEY = 2
    const val IME = 4
    const val VOICE = 8
    const val POWER = 32
    const val VOLUME = 64
    const val APP_LINK = 512

    /** PING + KEY + IME + VOICE + POWER + VOLUME + APP_LINK = 623 (la versión 1 pedía 615, sin voz). */
    const val REQUESTED = PING or KEY or IME or VOICE or POWER or VOLUME or APP_LINK

    /** Para mostrar en Ajustes: "teclas, texto, encendido…". */
    fun describeForPeople(mask: Int): String = listOf(
        KEY to "teclas", IME to "texto", POWER to "encendido", VOLUME to "volumen", APP_LINK to "abrir apps", VOICE to "voz",
    ).filter { mask and it.first != 0 }.joinToString(", ") { it.second }.replaceFirstChar { it.uppercase() }.ifEmpty { "Ninguna" }

    fun describe(mask: Int): String = listOf(
        PING to "PING", KEY to "KEY", IME to "IME", VOICE to "VOICE",
        POWER to "POWER", VOLUME to "VOLUME", APP_LINK to "APP_LINK",
    ).filter { mask and it.first != 0 }.joinToString("+") { it.second }.ifEmpty { "ninguna" }
}
