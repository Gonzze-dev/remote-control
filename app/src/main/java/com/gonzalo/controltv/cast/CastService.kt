package com.gonzalo.controltv.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gonzalo.controltv.App
import com.gonzalo.controltv.MainActivity
import com.gonzalo.controltv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Qué se está mandando a la tele. */
sealed interface CastState {
    data object Idle : CastState
    data class Preparing(val name: String) : CastState
    data class Showing(val name: String) : CastState
}

/**
 * Manda una foto, un video o un audio del celu al Chromecast y lo sigue sirviendo mientras se reproduce,
 * aunque la app quede en segundo plano o la pantalla se apague (por eso es un servicio en primer plano, con su
 * notificación y el botón Detener). Un archivo nuevo reemplaza al anterior en la misma sesión.
 */
class CastService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app get() = application as App
    private var client: CastClient? = null
    private var server: MediaServer? = null
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAST -> intent.data?.let { cast(it) } ?: stopSelf()
            ACTION_STOP -> scope.launch {
                client?.stopApp()
                finish(null)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /** El archivo que se está sirviendo: su permiso persistente se suelta cuando ya no hace falta. */
    private var currentUri: Uri? = null

    private fun releaseUri() {
        currentUri?.let { uri ->
            runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        currentUri = null
    }

    private fun cast(uri: Uri) {
        goForeground(notification("Preparando…", stop = false))
        if (currentUri != uri) releaseUri()
        currentUri = uri
        job?.cancel()
        job = scope.launch {
            try {
                val source = ContentMediaSource(this@CastService, uri)
                _state.value = CastState.Preparing(source.name)
                goForeground(notification("Preparando «${source.name}»…", stop = true))
                app.log.log("→ Cast: «${source.name}» (${source.type}, ${source.size} bytes)")
                val cast = client?.takeIf { it.isOpen && it.transportId != null } ?: openSession()
                val media = server ?: MediaServer(app.log).also { server = it }
                val url = "http://${cast.localAddress.hostAddress}:${media.port}${media.publish(source)}"
                cast.load(url, source.type, source.name)
                _state.value = CastState.Showing(source.name)
                holdWakeLock(source.isVideo || source.type.startsWith("audio/"))
                goForeground(notification("En la tele: ${source.name}", stop = true))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                app.log.log("✗ Cast: ${e.message}")
                _messages.tryEmit(e.message ?: "No se pudo mandar a la tele")
                client?.stopApp()
                finish(null)
            }
        }
    }

    private suspend fun openSession(): CastClient {
        client?.close()
        val ip = app.settings.flow.first().ip
        if (ip.isBlank()) throw CastException("Primero vinculá la tele")
        return CastClient(ip, app.log).also { c ->
            client = c
            c.onEnded = { reason -> scope.launch { if (client === c) finish(reason) } }
            c.onMedia = { state, idleReason ->
                when {
                    state != "IDLE" || idleReason == null || idleReason == "INTERRUPTED" -> Unit // INTERRUPTED: llegó otro archivo
                    idleReason == "ERROR" -> {
                        _messages.tryEmit("El Chromecast no pudo reproducir el archivo (¿formato no soportado?)")
                        scope.launch { if (client === c) finish(null) }
                    }
                    else -> scope.launch { if (client === c) finish(null) } // FINISHED o CANCELLED (lo cerraron en la tele)
                }
            }
            c.connect()
            c.launch()
        }
    }

    /** Corta todo: la conexión, el servidor, la notificación. [reason] se avisa en pantalla si no es null. */
    @Synchronized
    private fun finish(reason: String?) {
        if (reason != null && _state.value != CastState.Idle) _messages.tryEmit(reason)
        client?.close()
        client = null
        server?.close()
        server = null
        holdWakeLock(false)
        releaseUri()
        _state.value = CastState.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        client?.close()
        server?.close()
        holdWakeLock(false)
        _state.value = CastState.Idle
        scope.cancel()
        super.onDestroy()
    }

    /** Con video o audio el Chromecast va pidiendo el archivo de a partes: el celu no se puede dormir. */
    private fun holdWakeLock(hold: Boolean) {
        if (hold && wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ControlTV:cast")
                .apply { acquire(WAKE_LOCK_MAX_MS) }
        } else if (!hold) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun goForeground(notification: Notification) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun notification(text: String, stop: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Transmitir a la tele", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("Transmitiendo a la tele")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (stop) {
                    val stopIntent = PendingIntent.getService(
                        this@CastService, 1, Intent(this@CastService, CastService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(R.drawable.ic_cast, "Detener", stopIntent)
                }
            }
            .build()
    }

    companion object {
        private const val ACTION_CAST = "com.gonzalo.controltv.cast.CAST"
        private const val ACTION_STOP = "com.gonzalo.controltv.cast.STOP"
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 7
        /** Tope de seguridad por si algo queda colgado: una peli larga entra de sobra. */
        private const val WAKE_LOCK_MAX_MS = 6 * 60 * 60 * 1000L

        private val _state = MutableStateFlow<CastState>(CastState.Idle)
        val state: StateFlow<CastState> = _state.asStateFlow()

        private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
        /** Avisos para mostrar en pantalla (errores, "se cerró en la tele"). */
        val messages: SharedFlow<String> = _messages.asSharedFlow()

        fun cast(context: Context, uri: Uri) {
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_CAST)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP))
        }
    }
}
