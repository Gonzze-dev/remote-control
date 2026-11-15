package com.gonzalo.controltv.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.gonzalo.controltv.data.AppSettings
import com.gonzalo.controltv.data.AppsRepository
import com.gonzalo.controltv.data.ConnectionLog
import com.gonzalo.controltv.data.SavedTv
import com.gonzalo.controltv.tv.Features
import com.gonzalo.controltv.tv.TvInfo
import com.gonzalo.controltv.tv.TvState
import com.gonzalo.controltv.tv.VolumeInfo
import com.gonzalo.controltv.ui.theme.ControlTvTheme
import com.gonzalo.controltv.ui.theme.Palette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Capturas de cada pantalla, para revisar el diseño sin celu.
 * Quedan en app/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val settings = AppSettings(
        tvs = listOf(SavedTv("1", "Comedor", "192.168.0.50", "AB"), SavedTv("2", "Dormitorio", "192.168.0.51", "CD")),
        activeTvId = "1",
        clientName = "Mi control",
    )
    private val tele = TvInfo("Google", "Chromecast HD", "6.0", Features.REQUESTED)
    private val connected = TvState.Conectado(
        app = "com.netflix.ninja",
        volumen = VolumeInfo(12, 100, false),
        prendida = true,
        tele = tele,
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            ControlTvTheme {
                Box(Modifier.fillMaxSize().background(Palette.Bg)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable
    private fun Main(tab: Tab, content: @Composable () -> Unit) {
        Scaffold(containerColor = Palette.Bg, bottomBar = { BottomBar(tab) {} }) { p ->
            Box(Modifier.padding(p)) { content() }
        }
    }

    @Test
    fun control() = shot("01_control") {
        Main(Tab.Control) {
            ControlScreen(connected, "Netflix", settings, RemoteActions.Noop, {}, {}, {})
        }
    }

    @Test
    fun controlSinConexion() = shot("02_control_sin_conexion") {
        Main(Tab.Control) {
            ControlScreen(
                TvState.Desconectado("No encuentro la tele en 192.168.0.50", 4000),
                null, settings, RemoteActions.Noop, {}, {}, {},
            )
        }
    }

    @Test
    fun controlApagadaEnVivo() = shot("03_control_tele_apagada_texto_en_vivo") {
        Main(Tab.Control) {
            ControlScreen(
                connected.copy(prendida = false, volumen = VolumeInfo(0, 100, true)),
                "Netflix", settings.copy(liveText = true), RemoteActions.Noop, {}, {}, {},
            )
        }
    }

    @Test
    fun controlHayQueVincular() = shot("04_control_hay_que_vincular") {
        Main(Tab.Control) {
            ControlScreen(TvState.HayQueVincular("La tele no acepta este celu"), null, settings, RemoteActions.Noop, {}, {}, {})
        }
    }

    @Test
    fun apps() = shot("05_apps") {
        Main(Tab.Apps) {
            AppsScreen(AppsRepository.DEFAULT_APPS, "com.netflix.ninja", true, {}, {}, {}, {}, {})
        }
    }

    @Test
    fun appsAppNueva() = shot("06_apps_agregar_la_abierta") {
        Main(Tab.Apps) {
            AppsScreen(AppsRepository.DEFAULT_APPS, "ar.com.mercadolibre.play.tv", true, {}, {}, {}, {}, {})
        }
    }

    @Test
    fun ajustes() = shot("07_ajustes") {
        SettingsScreen(settings, connected, {}, {}, {}, {}, {}, {}, {}, {}, version = "1.1.0")
    }

    @Test
    fun log() = shot("08_log") {
        val t = 1_700_000_000_000L
        LogScreen(
            listOf(
                "Conectando a 192.168.0.50:6466…",
                "← remote_configure: Google Chromecast HD, soporta PING+KEY+IME+VOICE+POWER+VOLUME+APP_LINK",
                "→ remote_configure (pido PING+KEY+IME+POWER+VOLUME+APP_LINK = 615)",
                "← remote_set_active",
                "→ remote_set_active (615)",
                "← remote_start (prendida: sí)",
                "✓ Conectado",
                "← ime_key_inject: app com.netflix.ninja, ime 5, campo 7",
                "← volumen 12/100",
                "→ tecla DPAD_DOWN SHORT",
                "→ texto \"hola\" (ime 5, campo 7)",
                "✗ Pasaron 16 s sin noticias de la tele. Reintento en 100 ms",
            ).mapIndexed { i, s -> ConnectionLog.Line(t + i * 137L, s) },
            {}, {}, {},
        )
    }

    @Test
    fun vincularIp() = shot("09_vincular_ip") {
        PairingScreen(PairingUi.EnterIp(), "", "Mi control", false, { _, _ -> }, {}, {}, {}, {})
    }

    @Test
    fun vincularIpError() = shot("10_vincular_ip_error") {
        PairingScreen(
            PairingUi.EnterIp("No encuentro la tele en 192.168.0.50. ¿Está prendida y en la misma red Wi‑Fi?"),
            "192.168.0.50", "Mi control", false, { _, _ -> }, {}, {}, {}, {},
        )
    }

    @Test
    fun vincularCodigo() = shot("11_vincular_codigo") {
        PairingScreen(
            PairingUi.EnterCode("Ese código no coincide con el de la tele. Revisalo y escribilo de nuevo.", 1),
            "192.168.0.50", "Mi control", false, { _, _ -> }, {}, {}, {}, {},
        )
    }

    @Test
    fun vincularListo() = shot("12_vincular_listo") {
        PairingScreen(PairingUi.Done, "192.168.0.50", "Mi control", false, { _, _ -> }, {}, {}, {}, {})
    }

    @Test
    fun permiso() = shot("13_permiso_red_local") {
        PermissionScreen(permanentlyDenied = false, onRequest = {}, onOpenSettings = {})
    }
}
