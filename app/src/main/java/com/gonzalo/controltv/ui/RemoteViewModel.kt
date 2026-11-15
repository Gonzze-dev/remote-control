package com.gonzalo.controltv.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gonzalo.controltv.App
import com.gonzalo.controltv.cast.CastService
import com.gonzalo.controltv.cast.CastState
import com.gonzalo.controltv.update.UpdateState
import com.gonzalo.controltv.data.AppNames
import com.gonzalo.controltv.data.AppSettings
import com.gonzalo.controltv.data.TvApp
import com.gonzalo.controltv.tv.Direction
import com.gonzalo.controltv.tv.Keys
import com.gonzalo.controltv.tv.PairingClient
import com.gonzalo.controltv.tv.TvState
import com.gonzalo.controltv.tv.WrongCodeException
import com.gonzalo.controltv.tv.describeNetworkError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Conecta las pantallas con [com.gonzalo.controltv.tv.TvConnection]. Los botones nunca tocan el socket. */
class RemoteViewModel(application: Application) : AndroidViewModel(application), RemoteActions {
    private val app = application as App
    private val connection = app.connection

    val state: StateFlow<TvState> = connection.state
    val settings: StateFlow<AppSettings?> = app.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val apps: StateFlow<List<TvApp>> = app.apps.apps.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val logLines = app.log.lines
    val icons: StateFlow<Map<String, ImageBitmap>> = app.icons.icons

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun toast(text: String) {
        _messages.tryEmit(text)
    }

    init {
        viewModelScope.launch { CastService.messages.collect { toast(it) } }
        // Cada vez que cambia la lista o la app en pantalla, se buscan los íconos que falten.
        viewModelScope.launch {
            combine(apps, state.map { (it as? TvState.Conectado)?.app }.distinctUntilChanged()) { list, pkg -> list to pkg }
                .collectLatest { (list, pkg) -> app.icons.ensure(list, pkg) }
        }
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

    // Google TV abre el buscador cuando le llega texto sin un campo abierto, pero ese primer
    // texto se pierde. Entonces se espera a que avise el campo y se manda de nuevo.
    private var waitingForField: Job? = null
    private var pendingText = ""
    private var pendingEnter = false

    /**
     * En pantalla está el Inicio de CleanYourTV: junta las letras que le llegan sin campo abierto y busca el
     * texto entero en Google TV (parche local del launcher). No abre ningún campo, así que no hay que esperarlo.
     */
    private fun cleanHomeSearches(fieldOpen: Boolean) =
        !fieldOpen && (state.value as? TvState.Conectado)?.app == AppNames.CLEAN_HOME

    override fun sendText(text: String): Boolean {
        if (text.isEmpty()) return false
        val enter = settings.value?.enterAfterText == true
        val fieldOpen = connection.textFieldOpen()
        if (!connection.sendText(text)) {
            toast("Sin conexión con la tele: no se mandó el texto")
            return false
        }
        if (cleanHomeSearches(fieldOpen)) return true
        if (fieldOpen) {
            if (enter) {
                viewModelScope.launch {
                    delay(150) // que la tele termine de escribir antes de buscar
                    connection.sendKey(Keys.ENTER)
                }
            }
        } else {
            // Sin campo abierto, un Enter ahora "tocaría" lo que esté seleccionado en pantalla.
            sendWhenFieldOpens(text, enter)
        }
        return true
    }

    /** Modo en vivo: manda solo la diferencia (borra con DEL lo que sobra y agrega lo nuevo). */
    override fun liveEdit(old: String, new: String) {
        if (waitingForField?.isActive == true) {
            pendingText = new // se manda todo junto cuando la tele abra el campo
            return
        }
        val fieldOpen = connection.textFieldOpen()
        // En el Inicio de CleanYourTV cada letra suelta lanzaría una búsqueda: se manda todo con la tecla de acción.
        if (cleanHomeSearches(fieldOpen)) return
        if (!fieldOpen) {
            if (new.isNotEmpty() && connection.sendText(new)) sendWhenFieldOpens(new, enter = false)
            return
        }
        val common = old.commonPrefixWith(new).length
        repeat(old.length - common) { connection.sendKey(Keys.DEL) }
        val added = new.substring(common)
        if (added.isNotEmpty()) connection.sendText(added)
    }

    // ---- Actualizaciones ----

    val update: StateFlow<UpdateState> = app.updater.state
    val appVersion: String = app.updater.currentVersion

    fun checkUpdates() {
        viewModelScope.launch { app.updater.check(force = true) }
    }

    /** False si Android todavía no deja que Control TV instale apps: hay que mandar a la persona a permitirlo. */
    fun installUpdate(): Boolean {
        val release = (update.value as? UpdateState.Available)?.release
            ?: (update.value as? UpdateState.Failed)?.release
            ?: return true
        if (!app.updater.canInstall()) return false
        viewModelScope.launch { app.updater.install(release) }
        return true
    }

    // ---- Transmitir ----

    val casting: StateFlow<CastState> = CastService.state

    override fun castMedia(uri: Uri) {
        if (settings.value?.isPaired != true) {
            toast("Primero vinculá la tele")
            return
        }
        CastService.cast(getApplication(), uri)
    }

    override fun stopCasting() = CastService.stop(getApplication())

    override fun liveSubmit(text: String) {
        if (text.isNotBlank() && cleanHomeSearches(connection.textFieldOpen())) {
            if (!connection.sendText(text)) toast("Sin conexión con la tele: no se mandó el texto")
        } else {
            connection.sendKey(Keys.ENTER)
        }
    }

    private fun sendWhenFieldOpens(text: String, enter: Boolean) {
        pendingText = text
        pendingEnter = enter
        waitingForField?.cancel()
        waitingForField = viewModelScope.launch {
            if (!connection.awaitTextField(FIELD_WAIT_MS)) {
                toast("La tele no abrió un campo de texto: abrí el buscador y probá de nuevo")
                return@launch
            }
            delay(250) // que el buscador termine de abrirse
            if (pendingText.isNotEmpty()) connection.sendText(pendingText)
            if (pendingEnter) {
                delay(150)
                connection.sendKey(Keys.ENTER)
            }
        }
    }

    override fun retry() = connection.retryNow()

    fun onPermissionGranted() = connection.retryNow()

    fun launch(tvApp: TvApp, byPackage: Boolean = false) {
        val link = if (byPackage) tvApp.packageLink ?: tvApp.link else tvApp.link
        val wasInStore = (state.value as? TvState.Conectado)?.app == TvApp.PLAY_STORE
        if (!connection.launchApp(link)) {
            toast("Sin conexión con la tele")
            return
        }
        if (TvApp.isMarketLink(link)) pressOpenInStore(wasInStore)
    }

    private var pressingOpen: Job? = null

    /**
     * Abrir por paquete muestra la ficha de Play Store con "Abrir" seleccionado: cuando la tele
     * avisa que está en Play Store, se espera a que cargue la ficha y se aprieta OK.
     */
    private fun pressOpenInStore(wasInStore: Boolean) {
        pressingOpen?.cancel()
        pressingOpen = viewModelScope.launch {
            if (wasInStore) {
                // Ya estaba en Play Store: la tele no avisa de nuevo, así que se espera más.
                delay(STORE_SETTLE_MS * 2)
            } else {
                val opened = withTimeoutOrNull(STORE_WAIT_MS) {
                    state.first { (it as? TvState.Conectado)?.app == TvApp.PLAY_STORE }
                }
                if (opened == null) {
                    toast("La tele no abrió la app")
                    return@launch
                }
                delay(STORE_SETTLE_MS)
            }
            connection.sendKey(Keys.DPAD_CENTER)
        }
    }

    // ---- Apps ----

    fun saveApp(tvApp: TvApp) {
        viewModelScope.launch { app.apps.upsert(tvApp) }
    }

    fun deleteApp(tvApp: TvApp) {
        viewModelScope.launch { app.apps.delete(tvApp.id) }
        app.icons.removeCustom(tvApp.id)
        toast("Se borró ${tvApp.name}")
    }

    fun setAppImage(appId: String, uri: Uri) {
        viewModelScope.launch {
            if (!app.icons.setCustom(appId, uri)) toast("No se pudo usar esa imagen")
        }
    }

    fun removeAppImage(appId: String) = app.icons.removeCustom(appId)

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
        /** Cuánto se espera a que la tele abra el buscador después de recibir texto. */
        private const val FIELD_WAIT_MS = 2_500L

        /** Cuánto se espera a que la tele abra Play Store (en la prueba tardó 1,4 s). */
        private const val STORE_WAIT_MS = 5_000L

        /** Lo que se deja cargar la ficha antes de apretar OK en "Abrir". */
        private const val STORE_SETTLE_MS = 1_000L
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
