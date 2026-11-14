package com.gonzalo.controltv.data

import android.util.Log
import com.gonzalo.controltv.tv.TvLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log de conexión que se ve en Ajustes (los pings no se anotan). También va a Logcat. */
class ConnectionLog : TvLog {
    data class Line(val time: Long, val text: String)

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    override fun log(message: String) {
        Log.d(TAG, message)
        val line = Line(System.currentTimeMillis(), message)
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun asText(): String = _lines.value.joinToString("\n") { "${format(it.time)}  ${it.text}" }

    companion object {
        const val TAG = "ControlTV"
        private const val MAX_LINES = 500

        fun format(time: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date(time))
    }
}
