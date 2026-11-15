package com.gonzalo.controltv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Una tele vinculada: su nombre, dónde está y la huella SHA-256 de su certificado (guardada al vincular). */
data class SavedTv(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    val fingerprint: String,
)

data class AppSettings(
    val tvs: List<SavedTv> = emptyList(),
    val activeTvId: String = "",
    val clientName: String = DEFAULT_CLIENT_NAME,
    val enterAfterText: Boolean = true,
    val vibration: Boolean = true,
    val liveText: Boolean = false,
    /** Los botones de volumen del celu manejan el volumen de la tele mientras la app está en pantalla. */
    val volumeKeys: Boolean = true,
    /** Superficie para deslizar en vez del pad de flechas. */
    val touchpad: Boolean = false,
) {
    /** La tele que se controla ahora (la primera si la elegida ya no está). */
    val activeTv: SavedTv? get() = tvs.firstOrNull { it.id == activeTvId } ?: tvs.firstOrNull()

    val ip: String get() = activeTv?.ip.orEmpty()
    val tvFingerprint: String get() = activeTv?.fingerprint.orEmpty()
    val tvName: String get() = activeTv?.name.orEmpty()
    val isPaired: Boolean get() = ip.isNotBlank() && tvFingerprint.isNotBlank()

    companion object {
        const val DEFAULT_CLIENT_NAME = "Mi control"
        const val DEFAULT_TV_NAME = "Tele"
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "ajustes")

/** Las teles vinculadas, cuál se controla y las opciones. */
class Settings(context: Context) {
    private val store = context.applicationContext.settingsStore

    val flow: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            tvs = readTvs(p),
            activeTvId = p[ACTIVE_TV].orEmpty(),
            clientName = p[CLIENT_NAME] ?: AppSettings.DEFAULT_CLIENT_NAME,
            enterAfterText = p[ENTER_AFTER_TEXT] ?: true,
            vibration = p[VIBRATION] ?: true,
            liveText = p[LIVE_TEXT] ?: false,
            volumeKeys = p[VOLUME_KEYS] ?: true,
            touchpad = p[TOUCHPAD] ?: false,
        )
    }

    /**
     * Guarda una vinculación y deja esa tele como la que se controla. Si ya estaba (misma huella, o misma IP:
     * una tele reseteada cambia de certificado), se actualiza en vez de agregarse otra vez.
     */
    suspend fun savePairing(ip: String, fingerprint: String, clientName: String, tvName: String?) = store.edit { p ->
        val tvs = readTvs(p)
        val existing = tvs.firstOrNull { it.fingerprint == fingerprint } ?: tvs.firstOrNull { it.ip == ip }
        val name = tvName?.takeIf { it.isNotBlank() } ?: existing?.name ?: AppSettings.DEFAULT_TV_NAME
        val saved = existing?.copy(name = name, ip = ip, fingerprint = fingerprint) ?: SavedTv(name = name, ip = ip, fingerprint = fingerprint)
        writeTvs(p, if (existing != null) tvs.map { if (it.id == existing.id) saved else it } else tvs + saved)
        p[ACTIVE_TV] = saved.id
        p[CLIENT_NAME] = clientName
    }

    /** La tele que se controla, con otra IP: la huella sigue valiendo. */
    suspend fun setIp(ip: String) = editActive { it.copy(ip = ip) }

    /** La tele con esa huella apareció en otra IP (la encontró [com.gonzalo.controltv.tv.TvLocator]). */
    suspend fun moveTv(fingerprint: String, ip: String) = store.edit { p ->
        writeTvs(p, readTvs(p).map { if (it.fingerprint == fingerprint) it.copy(ip = ip) else it })
    }

    suspend fun setActiveTv(id: String) = store.edit { it[ACTIVE_TV] = id }

    suspend fun renameTv(id: String, name: String) = store.edit { p ->
        writeTvs(p, readTvs(p).map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
    }

    suspend fun removeTv(id: String) = store.edit { p ->
        val left = readTvs(p).filterNot { it.id == id }
        writeTvs(p, left)
        if (p[ACTIVE_TV] == id) p[ACTIVE_TV] = left.firstOrNull()?.id.orEmpty()
    }

    suspend fun setClientName(name: String) = store.edit { it[CLIENT_NAME] = name }

    suspend fun setEnterAfterText(value: Boolean) = store.edit { it[ENTER_AFTER_TEXT] = value }

    suspend fun setVibration(value: Boolean) = store.edit { it[VIBRATION] = value }

    suspend fun setLiveText(value: Boolean) = store.edit { it[LIVE_TEXT] = value }

    suspend fun setVolumeKeys(value: Boolean) = store.edit { it[VOLUME_KEYS] = value }

    suspend fun setTouchpad(value: Boolean) = store.edit { it[TOUCHPAD] = value }

    private suspend fun editActive(change: (SavedTv) -> SavedTv) = store.edit { p ->
        val tvs = readTvs(p)
        val active = tvs.firstOrNull { it.id == p[ACTIVE_TV] } ?: tvs.firstOrNull() ?: return@edit
        writeTvs(p, tvs.map { if (it.id == active.id) change(it) else it })
    }

    private companion object {
        val TVS = stringPreferencesKey("teles_json")
        val ACTIVE_TV = stringPreferencesKey("tele_activa")
        val CLIENT_NAME = stringPreferencesKey("nombre_cliente")
        val ENTER_AFTER_TEXT = booleanPreferencesKey("enter_despues_del_texto")
        val VIBRATION = booleanPreferencesKey("vibracion")
        val LIVE_TEXT = booleanPreferencesKey("texto_en_vivo")
        val VOLUME_KEYS = booleanPreferencesKey("botones_de_volumen")
        val TOUCHPAD = booleanPreferencesKey("touchpad")

        // Hasta la 1.1.1 había una sola tele: su IP y su huella sueltas. Se leen como la primera de la lista.
        val LEGACY_IP = stringPreferencesKey("ip")
        val LEGACY_FINGERPRINT = stringPreferencesKey("huella_tele")
        const val LEGACY_ID = "tele-1"

        fun readTvs(p: Preferences): List<SavedTv> {
            p[TVS]?.let { json -> runCatching { return fromJson(json) } }
            val ip = p[LEGACY_IP].orEmpty()
            val fp = p[LEGACY_FINGERPRINT].orEmpty()
            return if (ip.isNotBlank() && fp.isNotBlank()) listOf(SavedTv(LEGACY_ID, AppSettings.DEFAULT_TV_NAME, ip, fp)) else emptyList()
        }

        fun writeTvs(p: MutablePreferences, tvs: List<SavedTv>) {
            p[TVS] = toJson(tvs)
            p.remove(LEGACY_IP)
            p.remove(LEGACY_FINGERPRINT)
        }

        fun toJson(tvs: List<SavedTv>): String = JSONArray().apply {
            tvs.forEach { put(JSONObject().put("id", it.id).put("nombre", it.name).put("ip", it.ip).put("huella", it.fingerprint)) }
        }.toString()

        fun fromJson(json: String): List<SavedTv> {
            val a = JSONArray(json)
            return List(a.length()) { i ->
                val o = a.getJSONObject(i)
                SavedTv(o.getString("id"), o.getString("nombre"), o.getString("ip"), o.getString("huella"))
            }
        }
    }
}
