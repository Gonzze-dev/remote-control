package com.gonzalo.controltv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gonzalo.controltv.App
import com.gonzalo.controltv.data.AppSettings
import com.gonzalo.controltv.data.TvApp
import com.gonzalo.controltv.tv.Direction
import com.gonzalo.controltv.tv.Keys
import com.gonzalo.controltv.tv.PairingClient
import com.gonzalo.controltv.tv.TvState
import com.gonzalo.controltv.tv.WrongCodeException
import com.gonzalo.controltv.tv.describeNetworkError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Conecta las pantallas con [com.gonzalo.controltv.tv.TvConnection]. Los botones nunca tocan el socket. */
class RemoteViewModel(application: Application) : AndroidViewModel(application), RemoteActions {
    private val app = application as App
    private val connection = app.connection

    val state: StateFlow<TvState> = connection.state
    val settings: StateFlow<AppSettings?> = app.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val apps: StateFlow<List<TvApp>> = app.apps.apps.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val logLines = app.log.lines

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun toast(text: String) {
        _messages.tryEmit(text)
    }

    // ---- Control ----

    override fun key(code: Int) {
        connection.sendKey(code)
    }

    override fun keyLongStart(code: Int) {
        connection.sendKey(code, Direction.START_LONG)
    }

    override fun keyLongEnd(code: Int) {
        connection.sendKey(code, Direction.END_LONG)
    }

    override fun sendText(text: String): Boolean {
        if (text.isEmpty()) return false
        if (!connection.sendText(text)) {
            toast("Sin conexión con la tele: no se mandó el texto")
            return false
        }
        if (settings.value?.enterAfterText == true) {
            viewModelScope.launch {
                delay(150) // que la tele termine de escribir antes de buscar
                connection.sendKey(Keys.ENTER)
            }
        }
        return true
    }

    /** Modo en vivo: manda solo la diferencia (borra con DEL lo que sobra y agrega lo nuevo). */
    override fun liveEdit(old: String, new: String) {
        val common = old.commonPrefixWith(new).length
        repeat(old.length - common) { connection.sendKey(Keys.DEL) }
        val added = new.substring(common)
        if (added.isNotEmpty()) connection.sendText(added)
    }

    override fun retry() = connection.retryNow()

    fun onPermissionGranted() = connection.retryNow()

    fun launch(tvApp: TvApp, byPackage: Boolean = false) {
        val link = if (byPackage) tvApp.packageLink ?: tvApp.link else tvApp.link
        if (!connection.launchApp(link)) toast("Sin conexión con la tele")
    }

    // ---- Apps ----

    fun saveApp(tvApp: TvApp) {
        viewModelScope.launch { app.apps.upsert(tvApp) }
    }

    fun deleteApp(tvApp: TvApp) {
        viewModelScope.launch { app.apps.delete(tvApp.id) }
        toast("Se borró ${tvApp.name}")
    }

    // ---- Ajustes ----

    fun setIp(ip: String) {
        if (!isValidHost(ip)) {
            toast("Esa IP no parece válida")
            return
        }
        viewModelScope.launch { app.settings.setIp(ip.trim()) }
    }

    fun setClientName(name: String) {
        viewModelScope.launch { app.settings.setClientName(name.trim()) }
    }

    fun setEnterAfterText(value: Boolean) {
        viewModelScope.launch { app.settings.setEnterAfterText(value) }
    }

    fun setVibration(value: Boolean) {
        viewModelScope.launch { app.settings.setVibration(value) }
    }

    fun setLiveText(value: Boolean) {
        viewModelScope.launch { app.settings.setLiveText(value) }
    }

    fun clearLog() = app.log.clear()

    fun logText(): String = app.log.asText()

    // ---- Vinculación ----

    private val _pairing = MutableStateFlow<PairingUi>(PairingUi.EnterIp())
    val pairing: StateFlow<PairingUi> = _pairing.asStateFlow()

    /** El asistente sigue en pantalla aunque ya esté vinculado (para mostrar "¡Listo!" o al re-vincular). */
    private val _pairingOpen = MutableStateFlow(false)
    val pairingOpen: StateFlow<Boolean> = _pairingOpen.asStateFlow()

    @Volatile private var pairingClient: PairingClient? = null
    private var pairingIp = ""
    private var pairingName = ""
    private var codeAttempts = 0

    fun openPairing() {
        closePairingConnection()
        _pairing.value = PairingUi.EnterIp()
        _pairingOpen.value = true
    }

    fun closePairing() {
        closePairingConnection()
        _pairing.value = PairingUi.EnterIp()
        _pairingOpen.value = false
    }

    fun startPairing(ip: String, name: String) {
        val host = ip.trim()
        if (!isValidHost(host)) {
            _pairing.value = PairingUi.EnterIp("Esa IP no parece válida. Tiene que ser algo como 192.168.0.50")
            return
        }
        closePairingConnection()
        pairingIp = host
        pairingName = name.trim().ifBlank { AppSettings.DEFAULT_CLIENT_NAME }
        codeAttempts = 0
        _pairingOpen.value = true
        _pairing.value = PairingUi.Connecting
        viewModelScope.launch {
            val client = withContext(Dispatchers.IO) {
                runCatching {
                    PairingClient(host, app.certs.identity(), app.log).also {
                        pairingClient = it
                        it.start(pairingName)
                    }
                }
            }
            client.onSuccess {
                _pairing.value = PairingUi.EnterCode()
            }.onFailure { e ->
                closePairingConnection()
                app.log.log("✗ Vinculación: ${e.javaClass.simpleName}: ${e.message}")
                _pairing.value = PairingUi.EnterIp(pairingError(e, host))
            }
        }
    }

    fun submitCode(code: String) {
        val client = pairingClient ?: return
        if (_pairing.value == PairingUi.Verifying) return
        viewModelScope.launch {
            val plausible = withContext(Dispatchers.IO) { client.isCodePlausible(code) }
            if (!plausible) {
                // No se manda nada: la tele sigue mostrando el mismo código.
                codeAttempts++
                _pairing.value = PairingUi.EnterCode(
                    "Ese código no coincide con el de la tele. Revisalo y escribilo de nuevo.",
                    codeAttempts,
                )
                return@launch
            }
            _pairing.value = PairingUi.Verifying
            val result = withContext(Dispatchers.IO) { runCatching { client.finish(code) } }
            result.onSuccess { fingerprint ->
                pairingClient = null
                app.settings.savePairing(pairingIp, fingerprint, pairingName)
                _pairing.value = PairingUi.Done
                connection.reconnect()
            }.onFailure { e ->
                closePairingConnection()
                app.log.log("✗ Vinculación: ${e.javaClass.simpleName}: ${e.message}")
                _pairing.value = PairingUi.EnterIp(
                    if (e is WrongCodeException) "El código no coincide. Probá de nuevo."
                    else "No se pudo vincular: ${pairingError(e, pairingIp)}. Probá de nuevo.",
                )
            }
        }
    }

    fun cancelCode() {
        closePairingConnection()
        _pairing.value = PairingUi.EnterIp()
    }

    fun finishPairing() {
        _pairingOpen.value = false
        _pairing.value = PairingUi.EnterIp()
    }

    private fun closePairingConnection() {
        pairingClient?.close()
        pairingClient = null
    }

    private fun pairingError(e: Throwable, host: String): String = when {
        e is java.net.SocketTimeoutException && e.message?.contains("connect", true) == true ->
            "No encuentro la tele en $host. ¿Está prendida y en la misma red Wi‑Fi?"
        e is java.net.SocketTimeoutException -> "La tele no respondió a tiempo"
        else -> describeNetworkError(e, host)
    }

    override fun onCleared() {
        closePairingConnection()
    }

    companion object {
        private val HOST = Regex("^[A-Za-z0-9.:\\-]{1,253}$")

        fun isValidHost(host: String): Boolean {
            val h = host.trim()
            if (!HOST.matches(h)) return false
            val parts = h.split('.')
            // Si parece una IPv4, cada número tiene que ir de 0 a 255.
            if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all(Char::isDigit) }) {
                return parts.all { it.length <= 3 && it.toInt() in 0..255 }
            }
            return h.any { it.isLetter() } || h.contains(':')
        }
    }
}
