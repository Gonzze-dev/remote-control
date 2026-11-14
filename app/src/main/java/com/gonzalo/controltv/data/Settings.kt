package com.gonzalo.controltv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val ip: String = "",
    /** Huella SHA-256 del certificado de la tele, guardada al vincular. */
    val tvFingerprint: String = "",
    val clientName: String = DEFAULT_CLIENT_NAME,
    val enterAfterText: Boolean = true,
    val vibration: Boolean = true,
    val liveText: Boolean = false,
) {
    val isPaired: Boolean get() = ip.isNotBlank() && tvFingerprint.isNotBlank()

    companion object {
        const val DEFAULT_CLIENT_NAME = "Mi control"
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "ajustes")

/** IP de la tele, huella de su certificado y opciones. */
class Settings(context: Context) {
    private val store = context.applicationContext.settingsStore

    val flow: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            ip = p[IP].orEmpty(),
            tvFingerprint = p[FINGERPRINT].orEmpty(),
            clientName = p[CLIENT_NAME] ?: AppSettings.DEFAULT_CLIENT_NAME,
            enterAfterText = p[ENTER_AFTER_TEXT] ?: true,
            vibration = p[VIBRATION] ?: true,
            liveText = p[LIVE_TEXT] ?: false,
        )
    }

    suspend fun savePairing(ip: String, fingerprint: String, clientName: String) = store.edit {
        it[IP] = ip
        it[FINGERPRINT] = fingerprint
        it[CLIENT_NAME] = clientName
    }

    /** Misma tele con otra IP: la huella sigue valiendo. */
    suspend fun setIp(ip: String) = store.edit { it[IP] = ip }

    suspend fun setClientName(name: String) = store.edit { it[CLIENT_NAME] = name }

    suspend fun setEnterAfterText(value: Boolean) = store.edit { it[ENTER_AFTER_TEXT] = value }

    suspend fun setVibration(value: Boolean) = store.edit { it[VIBRATION] = value }

    suspend fun setLiveText(value: Boolean) = store.edit { it[LIVE_TEXT] = value }

    private companion object {
        val IP = stringPreferencesKey("ip")
        val FINGERPRINT = stringPreferencesKey("huella_tele")
        val CLIENT_NAME = stringPreferencesKey("nombre_cliente")
        val ENTER_AFTER_TEXT = booleanPreferencesKey("enter_despues_del_texto")
        val VIBRATION = booleanPreferencesKey("vibracion")
        val LIVE_TEXT = booleanPreferencesKey("texto_en_vivo")
    }
}
