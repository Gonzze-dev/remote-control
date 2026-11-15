package com.gonzalo.controltv

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.gonzalo.controltv.data.AppIcons
import com.gonzalo.controltv.data.AppsRepository
import com.gonzalo.controltv.data.ConnectionLog
import com.gonzalo.controltv.data.Settings
import com.gonzalo.controltv.tv.CertManager
import com.gonzalo.controltv.tv.TvConnection
import com.gonzalo.controltv.tv.TvDiscovery
import com.gonzalo.controltv.tv.TvLocator
import com.gonzalo.controltv.tv.TvState
import kotlinx.coroutines.flow.first
import com.gonzalo.controltv.update.Updater
import com.gonzalo.controltv.voice.AssistantVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Arma las piezas una sola vez y conecta con la tele mientras la app está en pantalla. */
class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val log = ConnectionLog()
    lateinit var settings: Settings
        private set
    lateinit var apps: AppsRepository
        private set
    lateinit var certs: CertManager
        private set
    lateinit var icons: AppIcons
        private set
    lateinit var connection: TvConnection
        private set
    lateinit var updater: Updater
        private set
    lateinit var discovery: TvDiscovery
        private set
    lateinit var voice: AssistantVoice
        private set
    private lateinit var locator: TvLocator

    /** Ajustes → Botones de volumen del celu (lo lee MainActivity en cada tecla). */
    @Volatile var volumeKeysToTv = true
        private set
    private var locating = false
    private var lastLocate = 0L

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        apps = AppsRepository(this)
        certs = CertManager(filesDir)
        icons = AppIcons(this)
        connection = TvConnection(certs, log, scope)
        updater = Updater(this, log)
        discovery = TvDiscovery(this, log)
        voice = AssistantVoice(connection, scope, log)
        locator = TvLocator(discovery::discover, certs, log)
        // ¿Hay una versión nueva en GitHub? Como mucho una consulta por día.
        scope.launch { updater.check() }

        scope.launch {
            settings.flow
                .map { if (it.isPaired) TvConnection.Target(it.ip, it.tvFingerprint) else null }
                .distinctUntilChanged()
                .collect { connection.setTarget(it) }
        }

        scope.launch { settings.flow.collect { volumeKeysToTv = it.volumeKeys } }
        scope.launch { apps.migrate() }

        // Si la tele no contesta en su IP, puede que el router le haya dado otra: se la busca en la red.
        scope.launch {
            connection.state.collect { if (it is TvState.Desconectado && it.noAparece) findMovedTv() }
        }

        // Conectado mientras se ve la app; al volver a primer plano se reintenta enseguida.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = connection.start()
            override fun onStop(owner: LifecycleOwner) = connection.stop()
        })

        // El certificado se genera una vez; mejor tenerlo listo antes de vincular.
        scope.launch(Dispatchers.IO) {
            runCatching { certs.identity() }.onFailure { log.log("✗ No se pudo generar el certificado: $it") }
        }
    }

    /** Busca la tele que se controla en la red (como mucho una vez por minuto) y, si cambió de IP, la actualiza. */
    private fun findMovedTv() {
        val now = System.currentTimeMillis()
        if (locating || now - lastLocate < LOCATE_EVERY_MS) return
        locating = true
        lastLocate = now
        scope.launch {
            try {
                val tv = settings.flow.first().activeTv ?: return@launch
                log.log("· Busco «${tv.name}» en la red…")
                val ip = locator.locate(tv.fingerprint)
                when {
                    ip == null -> log.log("· No apareció en la red: ¿está prendida y en el mismo Wi‑Fi?")
                    ip != tv.ip -> {
                        log.log("✓ «${tv.name}» está ahora en $ip (antes ${tv.ip})")
                        settings.moveTv(tv.fingerprint, ip)
                    }
                }
            } finally {
                locating = false
            }
        }
    }

    private companion object {
        const val LOCATE_EVERY_MS = 60_000L
    }
}
