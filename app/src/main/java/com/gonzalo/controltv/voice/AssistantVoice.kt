package com.gonzalo.controltv.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.gonzalo.controltv.tv.TvConnection
import com.gonzalo.controltv.tv.TvLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Hablarle al Asistente de la tele desde el micrófono del celu, como con el botón del control físico: mientras se
 * mantiene apretado el micrófono, el audio va a la tele y el Asistente lo entiende y lo hace ("abrí Netflix",
 * "subí el volumen", "películas de terror").
 *
 * Se graba desde el primer momento, mientras la tele abre la sesión, para no perder el principio de la frase.
 * Necesita el permiso de micrófono (RECORD_AUDIO), que pide la pantalla.
 */
class AssistantVoice(
    private val connection: TvConnection,
    private val scope: CoroutineScope,
    private val log: TvLog,
) {
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    @Volatile private var holding = false
    private var job: Job? = null

    /** Se apretó el micrófono. El permiso ya tiene que estar dado. */
    fun start() {
        if (job?.isActive == true) return
        holding = true
        _listening.value = true
        job = scope.launch(Dispatchers.IO) {
            try {
                record()
            } catch (e: Exception) {
                log.log("✗ Voz: ${e.message}")
                _messages.tryEmit("No se pudo usar el micrófono")
            } finally {
                _listening.value = false
            }
        }
    }

    /** Se soltó el micrófono: termina la frase. */
    fun stop() {
        holding = false
    }

    @SuppressLint("MissingPermission") // la pantalla pide el permiso antes de llamar a start()
    private suspend fun record() = coroutineScope {
        // Se graba a 16 kHz (lo soportan todos los celus) y se baja a los 8 kHz que espera la tele.
        val minBuffer = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CHUNK_BYTES * 8),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "el micrófono no se pudo abrir" }
        val pending = ByteArrayOutputStream()
        var sentBytes = 0L
        var peak = 0
        try {
            recorder.startRecording()
            val opening = async { connection.beginVoice() }
            val started = SystemClock.elapsedRealtime()
            val buffer = ShortArray(MIC_RATE / 10) // 100 ms
            var session: Int? = null
            while (holding && SystemClock.elapsedRealtime() - started < MAX_MS) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n < 0) error("el micrófono devolvió el error $n")
                if (n > 0) {
                    val samples = downsample(buffer, n)
                    pending.write(samples)
                    peak = maxOf(peak, peakOf(buffer, n))
                }
                if (session == null && opening.isCompleted) {
                    session = opening.await() ?: run {
                        _messages.tryEmit("La tele no abrió el Asistente. ¿Está conectada?")
                        return@coroutineScope
                    }
                }
                if (session != null && pending.size() >= CHUNK_BYTES) {
                    sentBytes += pending.size()
                    connection.sendVoice(session, pending.toByteArray())
                    pending.reset()
                }
            }
            // Si se soltó antes de que la tele abriera la sesión, igual se manda lo que se dijo.
            val s = session ?: withTimeoutOrNull(OPEN_GRACE_MS) { opening.await() } ?: run {
                opening.cancel()
                _messages.tryEmit("La tele no abrió el Asistente. ¿Está conectada?")
                return@coroutineScope
            }
            // El último trozo se completa con silencio: la tele solo procesa trozos enteros de 20 KB.
            if (pending.size() > 0) {
                pending.write(ByteArray(CHUNK_BYTES - pending.size()))
                sentBytes += pending.size()
                connection.sendVoice(s, pending.toByteArray())
            }
            connection.endVoice(s)
            // El pico dice si el micrófono oyó algo: menos de ~500 (de 32767) es prácticamente silencio.
            log.log("→ Voz: ${(SystemClock.elapsedRealtime() - started) / 1000.0} s al Asistente, $sentBytes bytes, pico $peak")
            if (peak < SILENCE_PEAK) _messages.tryEmit("El micrófono no captó tu voz. Probá más cerca del celu")
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /** 16 kHz → 8 kHz: promedia cada par de muestras (un filtro simple que evita el ruido de bajarla sin más). */
    private fun downsample(input: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count / 2 * 2)
        var o = 0
        var i = 0
        while (i + 1 < count) {
            val v = (input[i] + input[i + 1]) / 2
            out[o++] = (v and 0xFF).toByte() // little-endian, como manda la tele
            out[o++] = (v shr 8 and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun peakOf(input: ShortArray, count: Int): Int {
        var p = 0
        for (i in 0 until count) p = maxOf(p, kotlin.math.abs(input[i].toInt()))
        return p
    }

    private companion object {
        /** El formato que espera la tele: PCM de 16 bits, 8 kHz, mono. */
        const val RATE = 8_000
        const val MIC_RATE = 16_000
        const val SILENCE_PEAK = 500
        /**
         * Trozos de 20 KB (1,28 s de audio), como manda el control de Google. Probado con la tele real: con trozos
         * de 3,2 KB el Asistente recibe el audio pero no entiende nada; con 20 KB entiende.
         */
        const val CHUNK_BYTES = 20 * 1024
        /** Una frase larga entra de sobra; después se corta sola. */
        const val MAX_MS = 15_000L
        const val OPEN_GRACE_MS = 3_000L
    }
}
