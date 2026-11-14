package com.gonzalo.controltv.cast

import com.gonzalo.controltv.tv.TvLog
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Un archivo para servir: nombre, tipo MIME, tamaño (−1 si no se sabe) y cómo leerlo desde un byte. */
interface MediaSource {
    val name: String
    val type: String
    val size: Long
    fun open(offset: Long): InputStream
}

/**
 * Servidor HTTP mínimo que le da al Chromecast el archivo elegido en el celu. Sirve un solo archivo por vez,
 * bajo una ruta con un código al azar, y entiende `Range` para que el video se pueda adelantar.
 */
class MediaServer(private val log: TvLog, bindAddress: InetAddress? = null) : Closeable {
    private val server = ServerSocket(0, 50, bindAddress)
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "media-server").apply { isDaemon = true } }
    @Volatile private var current: Pair<String, MediaSource>? = null

    val port: Int get() = server.localPort

    init {
        Thread({
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: continue
                workers.execute { client.use { runCatching { serve(it) } } }
            }
        }, "media-accept").apply { isDaemon = true; start() }
    }

    /** Pasa a servir [source] (el anterior deja de estar disponible) y devuelve su ruta. */
    fun publish(source: MediaSource): String {
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        current = token to source
        val safeName = URLEncoder.encode(source.name, "UTF-8").replace("+", "%20")
        return "/m/$token/$safeName"
    }

    override fun close() {
        runCatching { server.close() }
        workers.shutdownNow()
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1) ?: ""
        val out = socket.getOutputStream()
        val (token, source) = current ?: return respond(out, 404, "Not Found")
        if (method != "GET" && method != "HEAD") return respond(out, 405, "Method Not Allowed")
        if (path.split('/').getOrNull(2) != token) return respond(out, 404, "Not Found")

        val range = headers["range"]?.let { parseRange(it, source.size) }
        if (range == INVALID) {
            return respond(out, 416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */${source.size}"))
        }
        val (start, end) = range ?: (0L to source.size - 1)
        val length = if (source.size >= 0) end - start + 1 else -1
        val extra = mutableListOf("Content-Type" to source.type, "Accept-Ranges" to "bytes")
        if (length >= 0) extra += "Content-Length" to length.toString()
        if (range != null) extra += "Content-Range" to "bytes $start-$end/${source.size}"
        writeHead(out, if (range != null) 206 else 200, if (range != null) "Partial Content" else "OK", extra)
        if (method == "HEAD") return
        if (start == 0L) log.log("→ Cast: el Chromecast está bajando «${source.name}»")
        source.open(start).use { copy(it, out, length) }
    }

    private fun respond(out: OutputStream, code: Int, text: String, extra: List<Pair<String, String>> = emptyList()) {
        writeHead(out, code, text, extra + ("Content-Length" to "0"))
    }

    private fun writeHead(out: OutputStream, code: Int, text: String, headers: List<Pair<String, String>>) {
        val head = buildString {
            append("HTTP/1.1 $code $text\r\n")
            // El reproductor de Google es una página web: sin este encabezado puede negarse a leer el archivo.
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun copy(input: InputStream, out: OutputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var left = if (length < 0) Long.MAX_VALUE else length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8 * 1024) throw IOException("Encabezado demasiado largo")
            sb.append(b.toChar())
        }
    }

    companion object {
        private val INVALID = -1L to -1L

        /**
         * `bytes=a-b`, `bytes=a-` o `bytes=-n` → primer y último byte (incluidos). Null si no se puede usar
         * (varios rangos o tamaño desconocido: se manda el archivo entero); [INVALID] si queda fuera del archivo.
         */
        fun parseRange(header: String, size: Long): Pair<Long, Long>? {
            if (size < 0) return null
            val spec = header.trim().removePrefix("bytes=").takeIf { header.trim().startsWith("bytes=") } ?: return null
            if (spec.contains(',')) return null
            val dash = spec.indexOf('-').takeIf { it >= 0 } ?: return null
            val first = spec.substring(0, dash).trim()
            val last = spec.substring(dash + 1).trim()
            val range = when {
                first.isEmpty() -> {
                    val n = last.toLongOrNull() ?: return null
                    if (n <= 0) return INVALID
                    maxOf(0, size - n) to size - 1
                }
                else -> {
                    val a = first.toLongOrNull() ?: return null
                    val b = if (last.isEmpty()) size - 1 else (last.toLongOrNull() ?: return null).coerceAtMost(size - 1)
                    a to b
                }
            }
            return if (range.first > range.second || range.first >= size) INVALID else range
        }
    }
}
