package com.gonzalo.controltv.update

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Leer la última release de GitHub y comparar versiones. Robolectric solo pone el org.json real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdaterTest {

    @Test
    fun versionCodeIsTheSameMathAsGradle() {
        // app/build.gradle.kts: major * 10000 + minor * 100 + patch
        assertEquals(10100, Updater.versionCode("v1.1.0"))
        assertEquals(10203, Updater.versionCode("1.2.3"))
        assertEquals(20000, Updater.versionCode("v2.0.0"))
        assertNull(Updater.versionCode("v1.2"))
        assertNull(Updater.versionCode("v1.100.0"))
        assertNull(Updater.versionCode("prueba"))
    }

    @Test
    fun readsTheLatestRelease() {
        val json = """
            {"tag_name":"v1.2.0","name":"Control TV 1.2.0","body":"- Transmitir fotos\n- Arreglos",
             "assets":[
               {"name":"otra-cosa.zip","browser_download_url":"https://x/otra.zip","size":10},
               {"name":"control-tv.apk","browser_download_url":"https://github.com/Gonzze-dev/remote-control/releases/download/v1.2.0/control-tv.apk","size":16594065}
             ]}
        """.trimIndent()
        val release = Updater.parseRelease(json)!!
        assertEquals("1.2.0", release.version)
        assertEquals(10200, release.versionCode)
        assertEquals("https://github.com/Gonzze-dev/remote-control/releases/download/v1.2.0/control-tv.apk", release.apkUrl)
        assertEquals(16594065L, release.size)
        assertEquals("- Transmitir fotos\n- Arreglos", release.notes)
    }

    @Test
    fun aReleaseWithoutTheApkOrWithAnOddTagIsIgnored() {
        assertNull(Updater.parseRelease("""{"tag_name":"v1.2.0","assets":[]}"""))
        assertNull(Updater.parseRelease("""{"tag_name":"nightly","assets":[{"name":"control-tv.apk","browser_download_url":"u"}]}"""))
    }
}
