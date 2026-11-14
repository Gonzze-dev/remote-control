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
}
