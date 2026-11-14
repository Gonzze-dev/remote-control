package com.gonzalo.controltv.cast

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.FileInputStream
import java.io.InputStream

/** Un archivo elegido con el selector de fotos o de archivos (`content://`). */
class ContentMediaSource(context: Context, private val uri: Uri) : MediaSource {
    private val resolver = context.applicationContext.contentResolver

    override val name: String
    override val size: Long
    override val type: String

    init {
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) } ?: -1
            }
        }
        if (size < 0) size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1
        this.name = name ?: uri.lastPathSegment ?: "archivo"
        this.size = size
        this.type = castType(resolver.getType(uri) ?: guessType(this.name))
    }

    override fun open(offset: Long): InputStream {
        val fd = resolver.openFileDescriptor(uri, "r") ?: throw java.io.FileNotFoundException(name)
        val input = FileInputStream(fd.fileDescriptor)
        // El stream es dueño del descriptor: al cerrarlo se cierra todo.
        val stream = object : java.io.FilterInputStream(input) {
            override fun close() {
                runCatching { super.close() }
                runCatching { fd.close() }
            }
        }
        if (offset > 0) {
            val channel = input.channel
            if (runCatching { channel.position(offset) }.isFailure) skipFully(stream, offset)
        }
        return stream
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) {
                if (input.read() < 0) return
                left--
            } else {
                left -= n
            }
        }
    }

    private fun guessType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    val isVideo: Boolean get() = type.startsWith("video/")
    val isImage: Boolean get() = type.startsWith("image/")

    companion object {
        /**
         * El reproductor de Google elige cómo abrir el archivo por el tipo, y Matroska (.mkv) y QuickTime (.mov) no
         * figuran entre sus formatos. Se presentan como MP4 para que al menos lo intente: anda si los códecs son
         * los que el Chromecast entiende (video H.264/HEVC/VP9, audio AAC/MP3/Opus). Si no, el reproductor avisa.
         */
        fun castType(type: String): String = when (type.lowercase()) {
            "video/x-matroska", "video/mkv", "video/quicktime" -> "video/mp4"
            else -> type
        }
    }
}
