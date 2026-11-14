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

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        apps = AppsRepository(this)
        certs = CertManager(filesDir)
        icons = AppIcons(this)
        connection = TvConnection(certs, log, scope)

        scope.launch {
            settings.flow
                .map { if (it.isPaired) TvConnection.Target(it.ip, it.tvFingerprint) else null }
                .distinctUntilChanged()
                .collect { connection.setTarget(it) }
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
}
