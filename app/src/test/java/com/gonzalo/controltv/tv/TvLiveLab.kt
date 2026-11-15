package com.gonzalo.controltv.tv

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/**
 * Laboratorio contra la tele de verdad, para investigar partes del protocolo que no están documentadas (la voz,
 * el mensaje 24). No corre en las pruebas normales: hace falta `TV_LAB_DIR` (carpeta de trabajo), `TV_LAB_HOST`
 * y `TV_LAB_STEP`:
 *  - `pair`: vincula un "control de pruebas" y espera el código en TV_LAB_DIR/codigo.txt (se lee de la tele).
 *  - `voice`: con ese control, aprieta Buscar, manda TV_LAB_DIR/voz.pcm (16 bits, 8 kHz, mono) y anota todo.
 *  - `text`: abre el buscador y manda texto, para ver el mensaje 24.
 */
class TvLiveLab {
    private val dir = System.getenv("TV_LAB_DIR")?.let(::File)
    private val host = System.getenv("TV_LAB_HOST").orEmpty()
    private val step = System.getenv("TV_LAB_STEP").orEmpty()
    private val log = TvLog { println("  [log] $it") }

    @Test
    fun lab() {
        assumeTrue("sin TV_LAB_DIR / TV_LAB_HOST / TV_LAB_STEP", dir != null && host.isNotBlank() && step.isNotBlank())
        val certs = CertManager(File(dir, "certificado"))
        when (step) {
            "pair" -> pair(certs)
            "voice" -> session(certs) { s, events -> voice(s, events) }
            "text" -> session(certs) { s, events -> text(s, events) }
            "keys" -> session(certs) { s, _ ->
                for ((key, name) in listOf(24 to "VOLUME_UP", 25 to "VOLUME_DOWN", 164 to "MUTE", 164 to "MUTE")) {
                    println("  [lab] → $name")
                    send(s, Remote.keyInject(key, Direction.SHORT))
                    Thread.sleep(1_500)
                }
            }
            else -> error("TV_LAB_STEP desconocido: $step")
        }
    }

    private fun pair(certs: CertManager) {
        val codeFile = File(dir, "codigo.txt").apply { delete() }
        val client = PairingClient(host, certs.identityForPairing(), log)
        client.start("PC de pruebas")
        println("  [lab] la tele muestra el código: escribirlo en $codeFile")
        val deadline = System.currentTimeMillis() + 120_000
        while (!codeFile.exists() && System.currentTimeMillis() < deadline) Thread.sleep(500)
        val code = codeFile.readText().trim()
        val fingerprint = client.finish(code)
        File(dir, "huella.txt").writeText(fingerprint)
        println("  [lab] vinculado, huella de la tele $fingerprint")
    }

    /** Sesión de control mínima que anota todo lo que llega, con los bytes de lo que no se conoce. */
    private fun session(certs: CertManager, body: (SSLSocket, java.util.concurrent.LinkedBlockingQueue<ProtoMessage>) -> Unit) {
        val socket = Tls.connect(host, CONTROL_PORT, certs.identity(), Tls.CapturingTrustManager())
        socket.soTimeout = 0
        val events = java.util.concurrent.LinkedBlockingQueue<ProtoMessage>()
        val input = BufferedInputStream(socket.inputStream)
        val features = Features.REQUESTED or Features.VOICE
        val reader = Thread {
            runCatching {
                while (true) {
                    val raw = Framing.read(input)
                    val m = ProtoMessage.parse(raw)
                    val fields = m.fieldNumbers()
                    when {
                        m.has(1) -> {
                            println("  [lab] ← configure: la tele soporta ${Features.describe(m.message(1)!!.int(1))}")
                            send(socket, Remote.configure(features))
                        }
                        m.has(2) -> send(socket, Remote.setActive(features))
                        m.has(8) -> send(socket, Remote.pingResponse(m.message(8)!!.int(1)))
                        else -> println("  [lab] ← campos $fields: ${raw.joinToString("") { "%02x".format(it) }}")
                    }
                    events += m
                }
            }.onFailure { println("  [lab] lectura terminada: $it") }
        }.apply { isDaemon = true; start() }
        Thread.sleep(2_000) // configure, set_active, start
        body(socket, events)
        Thread.sleep(8_000) // lo que conteste la tele después
        socket.close()
        reader.join(2_000)
    }

    private fun voice(socket: SSLSocket, events: java.util.concurrent.LinkedBlockingQueue<ProtoMessage>) {
        events.clear()
        println("  [lab] → tecla SEARCH (84)")
        send(socket, Remote.keyInject(84, Direction.SHORT))
        var session = 0
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val m = events.poll(200, TimeUnit.MILLISECONDS) ?: continue
            m.message(30)?.let { begin ->
                session = begin.int(1)
                println("  [lab] ← voice_begin: sesión $session, app «${begin.string(2)}»")
            }
            if (session != 0) break
        }
        if (session == 0) {
            println("  [lab] la tele no abrió una sesión de voz: pruebo abrirla yo")
            session = 1
            send(socket, ProtoWriter().message(30) { int(1, session) }.toByteArray())
            Thread.sleep(500)
        }
        if (System.getenv("TV_LAB_ACK") == "1") {
            println("  [lab] → voice_begin propio (sesión $session)")
            send(socket, ProtoWriter().message(30) { int(1, session) }.toByteArray())
        }
        val wait = System.getenv("TV_LAB_DELAY")?.toLongOrNull() ?: 0L
        if (wait > 0) {
            println("  [lab] espero $wait ms antes del audio")
            Thread.sleep(wait)
        }
        val audio = File(dir, System.getenv("TV_LAB_AUDIO") ?: "voz.pcm").readBytes()
        println("  [lab] → ${audio.size} bytes de audio en la sesión $session")
        var off = 0
        while (off < audio.size) {
            val n = minOf(System.getenv("TV_LAB_CHUNK")?.toIntOrNull() ?: CHUNK, audio.size - off)
            send(socket, ProtoWriter().message(31) { int(1, session); bytes(2, audio.copyOfRange(off, off + n)) }.toByteArray())
            off += n
            Thread.sleep(n * 1000L / (8_000 * 2)) // al ritmo en que se habla
        }
        send(socket, ProtoWriter().message(32) { int(1, session) }.toByteArray())
        println("  [lab] → voice_end")
    }

    private fun text(socket: SSLSocket, events: java.util.concurrent.LinkedBlockingQueue<ProtoMessage>) {
        println("  [lab] → tecla SEARCH (84) y espero un campo de texto")
        send(socket, Remote.keyInject(84, Direction.SHORT))
        Thread.sleep(3_000)
    }

    private fun send(socket: SSLSocket, bytes: ByteArray) = synchronized(socket) { Framing.write(socket.outputStream, bytes) }

    private companion object {
        /** El protocolo manda el audio en trozos de 20 KB. */
        const val CHUNK = 20 * 1024
    }
}
