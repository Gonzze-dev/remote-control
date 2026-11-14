package com.gonzalo.controltv.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** El único estado que mira la interfaz. */
sealed interface TvState {
    data class Desconectado(val motivo: String? = null, val reintentoEnMs: Long? = null) : TvState
    data class Conectando(val intento: Int) : TvState
    data class Conectado(
        val app: String? = null,
        val volumen: VolumeInfo? = null,
        val prendida: Boolean = true,
        val tele: TvInfo? = null,
    ) : TvState
    data class HayQueVincular(val motivo: String) : TvState
}

/**
 * Mantiene viva la sesión de control mientras la app está en pantalla: reconecta con
 * espera creciente (0,1 s → 30 s) y deja de intentar si la tele ya no reconoce al celu.
 *
 * [start], [stop], [setTarget] y los envíos se llaman desde el hilo principal.
 */
class TvConnection(
    private val certs: CertManager,
    private val log: TvLog,
    private val scope: CoroutineScope,
    private val port: Int = CONTROL_PORT,
) {
    data class Target(val host: String, val fingerprint: String)

    private val _state = MutableStateFlow<TvState>(TvState.Desconectado())
    val state: StateFlow<TvState> = _state.asStateFlow()

    @Volatile private var target: Target? = null
    @Volatile private var running = false
    @Volatile private var client: RemoteClient? = null
    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Cuida que una sesión que se estaba abriendo al cancelar no quede viva por su cuenta. */
    private val lock = Any()

    /** Los comandos salen de a uno y en orden. */
    private val writer = Dispatchers.IO.limitedParallelism(1)

    fun setTarget(newTarget: Target?) {
        if (newTarget == target) return
        target = newTarget
        if (running) restart()
    }

    /** La app pasó a primer plano. */
    fun start() {
        running = true
        if (job?.isActive == true) wake.trySend(Unit) else launchLoop()
    }

    /** La app pasó a segundo plano. */
    fun stop() {
        running = false
        cancelLoop()
        _state.value = TvState.Desconectado()
    }

    /** Reintentar ya, sin esperar el tiempo que falta. */
    fun retryNow() {
        if (job?.isActive == true) wake.trySend(Unit) else if (running) launchLoop()
    }

    /** Después de vincular: si el bucle terminó (por ejemplo, en "hay que vincular"), lo vuelve a lanzar. */
    fun reconnect() = retryNow()

    fun sendKey(keyCode: Int, direction: Direction = Direction.SHORT): Boolean =
        dispatch("tecla ${Keys.name(keyCode)}") { it.sendKey(keyCode, direction) }

    fun sendText(text: String): Boolean = dispatch("texto") { it.sendText(text) }

    /** ¿La tele tiene un campo de texto abierto ahora? */
    fun textFieldOpen(): Boolean = client?.textFieldOpen == true

    /** Espera hasta [timeoutMs] a que la tele avise un campo de texto abierto. */
    suspend fun awaitTextField(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { while (!textFieldOpen()) delay(100) } != null

    fun launchApp(appLink: String): Boolean = dispatch("abrir $appLink") { it.launchApp(appLink) }

    private fun dispatch(what: String, action: (RemoteClient) -> Unit): Boolean {
        val c = client
        if (c == null || !c.isReady) {
            log.log("· Descartado ($what): sin conexión")
            return false
        }
        scope.launch(writer) {
            try {
                action(c)
            } catch (e: IOException) {
                log.log("✗ No se pudo mandar ($what): ${e.message}")
                c.close() // el bucle de lectura se entera y reconecta
            }
        }
        return true
    }

    private fun restart() {
        cancelLoop()
        launchLoop()
    }

    private fun cancelLoop() = synchronized(lock) {
        job?.cancel()
        job = null
        client?.close()
        client = null
    }

    private fun launchLoop() {
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    private suspend fun loop() {
        var delayMs = INITIAL_DELAY_MS
        var attempt = 0
        var authFailures = 0
        while (currentCoroutineContext().isActive) {
            val t = target ?: run {
                _state.value = TvState.Desconectado("Falta vincular la tele")
                return
            }
            attempt++
            _state.value = TvState.Conectando(attempt)

            val identity = try {
                certs.identity()
            } catch (e: Exception) {
                log.log("✗ No se pudo cargar el certificado del celu: $e")
                _state.value = TvState.HayQueVincular("No se pudo cargar el certificado del celu")
                return
            }

            val listener = SessionListener()
            val c = RemoteClient(t.host, identity, t.fingerprint, log, listener, port)
            listener.owner = c
            val myJob = currentCoroutineContext().job
            synchronized(lock) {
                // Si cancelaron este bucle mientras se preparaba, no se abre otra sesión.
                if (!myJob.isActive) return
                client = c
            }

            val reason: String = try {
                c.run()
                "Se cortó la conexión"
            } catch (e: NeedsPairingException) {
                if (!currentCoroutineContext().isActive) return
                authFailures++
                if (e.definitive || authFailures >= AUTH_FAILURES_TO_GIVE_UP) {
                    log.log("✗ ${e.message}. Hay que vincular de nuevo.")
                    _state.value = TvState.HayQueVincular(e.message ?: "La tele no reconoce este celu")
                    if (client === c) client = null
                    return
                }
                log.log("✗ ${e.message} (${e.cause?.message}). Reintento para confirmar…")
                e.message ?: "Error de TLS"
            } catch (e: Exception) {
                authFailures = 0
                describeNetworkError(e, t.host)
            } finally {
                c.close()
                if (client === c) client = null
            }

            if (!currentCoroutineContext().isActive) return
            if (c.isReady) {
                // Estaba andando y se cortó: el primer reintento va enseguida.
                delayMs = INITIAL_DELAY_MS
                attempt = 0
                authFailures = 0
            }
            log.log("✗ $reason. Reintento en ${formatDelay(delayMs)}")
            _state.value = TvState.Desconectado(reason, delayMs)
            withTimeoutOrNull(delayMs) { wake.receive() }
            delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
        }
    }

    /** Pasa los avisos de la tele al estado, solo si vienen de la sesión actual. */
    private inner class SessionListener : RemoteClient.Listener {
        var owner: RemoteClient? = null
        private var app: String? = null
        private var volume: VolumeInfo? = null

        private fun isCurrent() = owner != null && client === owner

        override fun onReady(info: TvInfo?, poweredOn: Boolean) {
            if (!isCurrent()) return
            log.log("✓ Conectado")
            _state.value = TvState.Conectado(app = app, volumen = volume, prendida = poweredOn, tele = info)
        }

        override fun onPowerChanged(poweredOn: Boolean) = update { it.copy(prendida = poweredOn) }

        override fun onAppChanged(appPackage: String) {
            app = appPackage
            update { it.copy(app = appPackage) }
        }

        override fun onVolumeChanged(volume: VolumeInfo) {
            this.volume = volume
            update { it.copy(volumen = volume) }
        }

        private fun update(change: (TvState.Conectado) -> TvState.Conectado) {
            if (!isCurrent()) return
            _state.update { if (it is TvState.Conectado) change(it) else it }
        }
    }

    private companion object {
        const val INITIAL_DELAY_MS = 100L
        const val MAX_DELAY_MS = 30_000L

        /** Rechazos seguidos (no definitivos) antes de pedir que se vincule de nuevo. */
        const val AUTH_FAILURES_TO_GIVE_UP = 3

        fun formatDelay(ms: Long) = if (ms < 1000) "$ms ms" else "${ms / 1000} s"
    }
}

/** Traduce los errores de red a algo que se entienda en la pantalla. */
fun describeNetworkError(e: Throwable, host: String): String = when (e) {
    is SocketTimeoutException ->
        if (e.message?.contains("connect", ignoreCase = true) == true) "No encuentro la tele en $host"
        else e.message ?: "La tele no respondió a tiempo"
    is ConnectException -> "La tele rechazó la conexión en $host"
    is NoRouteToHostException -> "No hay camino a $host. ¿Estás en la misma red Wi‑Fi?"
    is UnknownHostException -> "La dirección $host no existe"
    is EOFException -> e.message ?: "La tele cerró la conexión"
    else -> e.message ?: e.javaClass.simpleName
}
