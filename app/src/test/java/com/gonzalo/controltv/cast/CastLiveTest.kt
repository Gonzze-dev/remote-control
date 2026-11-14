package com.gonzalo.controltv.cast

import android.app.Application
import com.gonzalo.controltv.tv.TvLog
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Contra el Chromecast de verdad: abre el reproductor, muestra el logo de Google, espera y lo
 * cierra. Solo corre si se pide: `CAST_LIVE_HOST=192.168.1.31 gradlew testDebugUnitTest --tests '*CastLiveTest*'`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CastLiveTest {
    @Test
    fun showsAnImageOnTheRealChromecast() {
        val host = System.getenv("CAST_LIVE_HOST")
        assumeTrue("sin CAST_LIVE_HOST", !host.isNullOrBlank())
        val states = LinkedBlockingQueue<String>()
        CastClient(host!!, TvLog { println("  [log] $it") }).use { cast ->
            cast.onMedia = { state, reason -> states += "$state/$reason".also { println("  [media] $it") } }
            cast.connect()
            cast.launch()
            cast.load(SAMPLE_IMAGE, "image/png", "Logo de Google")
            assertNotNull("el Chromecast no informó el estado", states.poll(15, TimeUnit.SECONDS))
            Thread.sleep(8_000) // para verla en la tele y ver qué estados manda con una foto
            cast.stopApp()
            Thread.sleep(1_000)
        }
    }

    private companion object {
        const val SAMPLE_IMAGE = "https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png"
    }
}
