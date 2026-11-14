package com.gonzalo.controltv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Íconos de las apps. Por defecto se baja una vez el ícono oficial de Google Play (por el
 * nombre de paquete) y queda guardado; también se puede elegir una imagen propia.
 * Si no hay ícono, la grilla muestra la inicial de color.
 */
class AppIcons(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "iconos").apply { mkdirs() }
    private val attempted = ConcurrentHashMap.newKeySet<String>()

    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    /** Clave "custom:<id>" para imágenes elegidas, "pkg:<paquete>" para las de Google Play. */
    val icons: StateFlow<Map<String, ImageBitmap>> = _icons.asStateFlow()

    /** Carga lo que ya está guardado y baja de Google Play los íconos que falten. */
    suspend fun ensure(apps: List<TvApp>, extraPkg: String?) = withContext(Dispatchers.IO) {
        apps.forEach { load(customKey(it.id), customFile(it.id)) }
        val pkgs = (apps.map { it.pkg } + listOfNotNull(extraPkg)).filter { it.isNotBlank() }.distinct()
        coroutineScope {
            pkgs.map { pkg ->
                async {
                    val key = pkgKey(pkg)
                    if (key in _icons.value || load(key, playFile(pkg))) return@async
                    if (!attempted.add(pkg)) return@async // un intento por paquete mientras la app está abierta
                    runCatching { downloadFromPlay(pkg, playFile(pkg)) }
                        .onSuccess { load(key, playFile(pkg)) }
                }
            }.awaitAll()
        }
    }

    /** Usa una imagen de la galería como ícono de la app. */
    suspend fun setCustom(appId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bitmap = runCatching { decodeSquare(uri, CUSTOM_SIZE) }.getOrNull() ?: return@withContext false
        customFile(appId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        _icons.update { it + (customKey(appId) to bitmap.asImageBitmap()) }
        true
    }

    /** Vuelve al ícono automático. */
    fun removeCustom(appId: String) {
        customFile(appId).delete()
        _icons.update { it - customKey(appId) }
    }

    private fun load(key: String, file: File): Boolean {
        if (key in _icons.value) return true
        if (!file.exists()) return false
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return false
        _icons.update { it + (key to bitmap.asImageBitmap()) }
        return true
    }

    private fun downloadFromPlay(pkg: String, target: File) {
        val page = URL("https://play.google.com/store/apps/details?id=${URLEncoder.encode(pkg, "UTF-8")}&hl=es")
        val imageUrl = open(page).use { findOgImage(it) } ?: throw IOException("Play no tiene ícono para $pkg")
        val icon = URL(imageUrl.substringBefore('=') + "=s$PLAY_SIZE")
        // Solo se bajan imágenes del servidor de imágenes de Google.
        if (icon.protocol != "https" || !icon.host.endsWith(".googleusercontent.com")) throw IOException("URL inesperada")
        val bytes = open(icon).use { it.readLimited(MAX_IMAGE_BYTES) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Imagen inválida")
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun open(url: URL): InputStream {
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ControlTV")
        if (c.responseCode != 200) {
            c.disconnect()
            throw IOException("HTTP ${c.responseCode}")
        }
        return c.inputStream
    }

    /** Lee la página de a pedazos hasta encontrar el og:image (está cerca del final del <head>). */
    private fun findOgImage(input: InputStream): String? {
        val text = StringBuilder()
        val buf = ByteArray(16 * 1024)
        var searchFrom = 0
        while (text.length < MAX_PAGE_CHARS) {
            val n = input.read(buf)
            if (n < 0) break
            text.append(String(buf, 0, n, Charsets.ISO_8859_1))
            OG_IMAGE.find(text, (searchFrom - 200).coerceAtLeast(0))?.let { return it.groupValues[1] }
            searchFrom = text.length
        }
        return null
    }

    private fun InputStream.readLimited(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("Imagen demasiado grande")
        }
        return out.toByteArray()
    }

    /** Decodifica achicando y recorta al centro en un cuadrado de [size] px. */
    private fun decodeSquare(uri: Uri, size: Int): Bitmap? {
        val resolver = appContext.contentResolver
        val decoded: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder respeta la rotación de las fotos.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val scale = size.toFloat() / minOf(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: return null
        }
        val side = minOf(decoded.width, decoded.height)
        val cropped = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        return if (side > size) Bitmap.createScaledBitmap(cropped, size, size, true) else cropped
    }

    private fun customFile(appId: String) = File(dir, "propia_${appId.filter { it.isLetterOrDigit() || it == '-' }}.png")

    private fun playFile(pkg: String) = File(dir, "play_${pkg.filter { it.isLetterOrDigit() || it == '.' || it == '_' }}.png")

    companion object {
        private const val PLAY_SIZE = 192
        private const val CUSTOM_SIZE = 256
        private const val MAX_IMAGE_BYTES = 1 shl 20
        private const val MAX_PAGE_CHARS = 3 shl 20
        private val OG_IMAGE = Regex("""<meta property="og:image" content="([^"]+)"""")

        fun customKey(appId: String) = "custom:$appId"
        fun pkgKey(pkg: String) = "pkg:$pkg"

        /** El ícono a mostrar: primero la imagen elegida, después la de Google Play. */
        fun iconFor(app: TvApp, icons: Map<String, ImageBitmap>): ImageBitmap? =
            icons[customKey(app.id)] ?: app.pkg.takeIf { it.isNotBlank() }?.let { icons[pkgKey(it)] }
    }
}
