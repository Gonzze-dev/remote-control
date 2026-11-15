package com.gonzalo.controltv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLinksTest {
    @Test
    fun openingByPackageUsesThePlayStorePage() {
        // La tele rechaza market://launch; la ficha de Play Store sí la abre.
        assertEquals("market://details?id=com.limelight", TvApp.marketLink("com.limelight"))
        assertEquals("market://details?id=com.limelight", TvApp("x", "Moonlight", "netflix://", "com.limelight").packageLink)
        assertTrue(TvApp.isMarketLink(TvApp.marketLink("org.xbmc.kodi")))
        assertFalse(TvApp.isMarketLink("https://www.youtube.com"))
    }

    @Test
    fun savedLaunchLinksAreUpgraded() {
        assertEquals("market://details?id=org.xbmc.kodi", TvApp.upgradeLink("market://launch?id=org.xbmc.kodi"))
        assertEquals("netflix://", TvApp.upgradeLink("netflix://"))
        assertEquals("market://details?id=a.b", TvApp.upgradeLink("market://details?id=a.b"))
    }

    @Test
    fun savedListsMoveToTheHouseholdApps() {
        val factory10 = listOf(
            TvApp("youtube", "YouTube", "https://www.youtube.com", "com.google.android.youtube.tv"),
            TvApp("plex", "Plex", "plex://", "com.plexapp.android"),
            TvApp("spotify", "Spotify", "spotify://mi-lista", "com.spotify.tv.android"), // editada: se queda
            TvApp("x", "Moonlight", TvApp.marketLink("com.limelight"), "com.limelight"), // ya estaba
        )
        val result = AppsRepository.householdApps(factory10)
        assertEquals(listOf("youtube", "spotify", "x", "flow"), result.map { it.id })
        assertEquals(TvApp.marketLink("ar.com.flow.androidtv"), result.last().link)
    }

    @Test
    fun cleanYourTvHomeIsHomeNotAnApp() {
        // Con el Inicio de CleanYourTV en pantalla no tiene que aparecer "En pantalla → Agregar".
        assertTrue(AppNames.isSystem("com.gathin.tvhome"))
        assertEquals("Inicio", AppNames.label("com.gathin.tvhome", emptyList()))
        assertFalse(AppNames.isSystem("ar.com.flow.androidtv"))
    }
}
