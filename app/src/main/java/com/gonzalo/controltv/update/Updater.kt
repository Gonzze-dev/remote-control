package com.gonzalo.controltv.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.gonzalo.controltv.tv.TvLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Una versión publicada en GitHub Releases. */
data class Release(val version: String, val versionCode: Int, val notes: String, val apkUrl: String, val size: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/**
 * Actualizaciones desde GitHub Releases: cada versión es una release `vX.Y.Z` del repo con el APK
 * `control-tv.apk` (la publica el workflow de GitHub Actions). La app mira la última release como mucho una
 * vez por día, y al actualizar baja el APK directo a una sesión de instalación de Android.
 */
class Updater(context: Context, private val log: TvLog) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("actualizaciones", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String = packageInfo().versionName ?: "?"
    private val currentCode: Long = if (Build.VERSION.SDK_INT >= 28) packageInfo().longVersionCode else @Suppress("DEPRECATION") packageInfo().versionCode.toLong()

    /** Pregunta a GitHub por la última versión. Sin [force], no más de una vez cada [CHECK_EVERY_MS]. */
    suspend fun check(force: Boolean = false) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(LAST_CHECK, 0) < CHECK_EVERY_MS) {
            // Lo que se encontró la última vez sigue valiendo hasta la próxima consulta.
            cachedRelease()?.takeIf { it.versionCode > currentCode }?.let { _state.value = UpdateState.Available(it) }
            return
        }
        _state.value = UpdateState.Checking
        try {
            val release = withContext(Dispatchers.IO) { fetchLatest() }
            prefs.edit().putLong(LAST_CHECK, now).putString(LAST_RELEASE, release?.let { toJson(it) }).apply()
            _state.value = if (release != null && release.versionCode > currentCode) {
                log.log("· Hay una versión nueva: ${release.version}")
                UpdateState.Available(release)
            } else {
                UpdateState.UpToDate
            }
        } catch (e: Exception) {
            _state.value = if (force) UpdateState.Failed("No se pudo consultar GitHub: ${e.message}") else UpdateState.Idle
        }
    }

    /** Android tiene que permitir que Control TV instale apps (se pide una sola vez, en Ajustes del celu). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Baja la versión y le pide a Android que la instale. Android muestra su confirmación. */
    suspend fun install(release: Release) {
        _state.value = UpdateState.Downloading(release, 0f)
        try {
            withContext(Dispatchers.IO) { downloadAndCommit(release) }
            _state.value = UpdateState.Installing(release)
        } catch (e: Exception) {
            log.log("✗ No se pudo actualizar: $e")
            _state.value = UpdateState.Failed("No se pudo bajar la versión ${release.version}: ${e.message}", release)
        }
    }

    /** Lo informa [InstallReceiver] cuando Android termina (o cancela) la instalación. */
    internal fun installFinished(ok: Boolean, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        _state.value = if (ok) UpdateState.UpToDate
        else UpdateState.Failed(message ?: "Se canceló la instalación", release)
    }

    private fun fetchLatest(): Release? {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "ControlTV-Android")
            if (conn.responseCode == 404) return null // todavía no hay ninguna release
            if (conn.responseCode != 200) throw IOException("GitHub contestó ${conn.responseCode}")
            return parseRelease(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadAndCommit(release: Release) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (release.size > 0) setSize(release.size)
            // Desde la segunda actualización (Control TV ya es quien la instaló) Android puede no pedir confirmación.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "ControlTV-Android")
            if (conn.responseCode != 200) throw IOException("GitHub contestó ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                session.openWrite(APK_NAME, 0, total.takeIf { it > 0 } ?: -1).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                    session.fsync(out)
                }
            }
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            log.log("→ Instalando la versión ${release.version}")
            session.commit(callback.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    private fun cachedRelease(): Release? = prefs.getString(LAST_RELEASE, null)?.let { runCatching { fromJson(it) }.getOrNull() }

    private fun packageInfo() =
        if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)

    companion object {
        const val REPO = "Gonzze-dev/remote-control"
        const val APK_NAME = "control-tv.apk"
        /** El link para compartir: siempre baja la última versión. */
        const val DOWNLOAD_URL = "https://github.com/$REPO/releases/latest/download/$APK_NAME"
        private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L
        private const val LAST_CHECK = "ultima_consulta"
        private const val LAST_RELEASE = "ultima_release"

        /** `v1.2.3` → 10203. Es el mismo cálculo que hace app/build.gradle.kts con la versión. */
        fun versionCode(version: String): Int? {
            val parts = version.trim().removePrefix("v").split('.')
            if (parts.size != 3) return null
            val (major, minor, patch) = parts.map { it.toIntOrNull() ?: return null }
            if (minor > 99 || patch > 99) return null
            return major * 10_000 + minor * 100 + patch
        }

        /** La respuesta de `releases/latest`, o null si no trae el APK o la etiqueta no es una versión. */
        fun parseRelease(json: String): Release? {
            val o = JSONObject(json)
            val tag = o.optString("tag_name")
            val code = versionCode(tag) ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == APK_NAME }
                ?: return null
            return Release(
                version = tag.removePrefix("v"),
                versionCode = code,
                notes = o.optString("body").trim(),
                apkUrl = apk.getString("browser_download_url"),
                size = apk.optLong("size", -1),
            )
        }

        private fun toJson(r: Release) = JSONObject().put("version", r.version).put("code", r.versionCode)
            .put("notes", r.notes).put("url", r.apkUrl).put("size", r.size).toString()

        private fun fromJson(s: String) = JSONObject(s).let {
            Release(it.getString("version"), it.getInt("code"), it.optString("notes"), it.getString("url"), it.optLong("size", -1))
        }
    }
}
