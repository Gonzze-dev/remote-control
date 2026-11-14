package com.gonzalo.controltv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Una app de la grilla. [link] es lo que se manda a la tele (link directo o
 * `market://launch?id=<paquete>`). [pkg] sirve para marcar la app abierta y como plan B.
 */
data class TvApp(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val link: String,
    val pkg: String = "",
) {
    val packageLink: String? get() = pkg.takeIf { it.isNotBlank() }?.let { marketLink(it) }

    companion object {
        fun marketLink(pkg: String) = "market://launch?id=$pkg"
    }
}

private val Context.appsStore: DataStore<Preferences> by preferencesDataStore(name = "apps")

/** La lista de apps, guardada como JSON. */
class AppsRepository(context: Context) {
    private val store = context.applicationContext.appsStore

    val apps: Flow<List<TvApp>> = store.data.map { p ->
        p[APPS_JSON]?.let { runCatching { fromJson(it) }.getOrNull() } ?: DEFAULT_APPS
    }

    suspend fun upsert(app: TvApp) = edit { list ->
        if (list.any { it.id == app.id }) list.map { if (it.id == app.id) app else it } else list + app
    }

    suspend fun delete(id: String) = edit { list -> list.filterNot { it.id == id } }

    private suspend fun edit(change: (List<TvApp>) -> List<TvApp>) {
        store.edit { p ->
            val current = p[APPS_JSON]?.let { runCatching { fromJson(it) }.getOrNull() } ?: DEFAULT_APPS
            p[APPS_JSON] = toJson(change(current))
        }
    }

    companion object {
        private val APPS_JSON = stringPreferencesKey("apps_json")

        /** Las apps de la sección "Apps para abrir de un toque" del plan. */
        val DEFAULT_APPS = listOf(
            TvApp("youtube", "YouTube", "https://www.youtube.com", "com.google.android.youtube.tv"),
            TvApp("netflix", "Netflix", "netflix://", "com.netflix.ninja"),
            TvApp("prime", "Prime Video", "https://app.primevideo.com", "com.amazon.amazonvideo.livingroom"),
            TvApp("disney", "Disney+", "https://www.disneyplus.com", "com.disney.disneyplus"),
            TvApp("plex", "Plex", "plex://", "com.plexapp.android"),
            TvApp("twitch", "Twitch", "twitch://home", "tv.twitch.android.app"),
            TvApp("spotify", "Spotify", "spotify://", "com.spotify.tv.android"),
            TvApp("crunchyroll", "Crunchyroll", "crunchyroll://", "com.crunchyroll.crunchyroid"),
            TvApp("kodi", "Kodi", TvApp.marketLink("org.xbmc.kodi"), "org.xbmc.kodi"),
        )

        private fun toJson(list: List<TvApp>): String = JSONArray().apply {
            list.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("link", it.link).put("pkg", it.pkg))
            }
        }.toString()

        private fun fromJson(json: String): List<TvApp> {
            val array = JSONArray(json)
            return List(array.length()) { i ->
                val o = array.getJSONObject(i)
                TvApp(o.getString("id"), o.getString("name"), o.getString("link"), o.optString("pkg"))
            }
        }
    }
}

/** Nombre lindo para un paquete: primero tu lista, después algunos conocidos, si no el paquete. */
object AppNames {
    private val known = mapOf(
        "com.google.android.youtube.tv" to "YouTube",
        "com.google.android.youtube.tvmusic" to "YouTube Music",
        "com.google.android.youtube.tvkids" to "YouTube Kids",
        "com.netflix.ninja" to "Netflix",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.disney.disneyplus" to "Disney+",
        "com.plexapp.android" to "Plex",
        "tv.twitch.android.app" to "Twitch",
        "com.spotify.tv.android" to "Spotify",
        "org.xbmc.kodi" to "Kodi",
        "com.google.android.apps.tv.launcherx" to "Inicio",
        "com.google.android.tvlauncher" to "Inicio",
        "com.android.tv.settings" to "Configuración",
        "com.google.android.katniss" to "Asistente",
        "com.google.android.apps.tv.dreamx" to "Protector de pantalla",
        "com.google.android.videos" to "Google TV",
    )

    fun label(pkg: String, apps: List<TvApp>): String =
        apps.firstOrNull { it.pkg == pkg }?.name ?: known[pkg] ?: fromPackage(pkg)

    /** "com.example.mercadoplay.tv" → "Mercadoplay". */
    fun fromPackage(pkg: String): String {
        val skip = setOf("com", "org", "tv", "android", "app", "apps", "ninja", "livingroom", "atv", "androidtv", "leanback")
        val part = pkg.split('.').lastOrNull { it.lowercase() !in skip && it.length > 1 } ?: pkg
        return part.replaceFirstChar { it.uppercase() }
    }
}
