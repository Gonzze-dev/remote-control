package com.gonzalo.controltv

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gonzalo.controltv.tv.Keys
import com.gonzalo.controltv.tv.TvState
import com.gonzalo.controltv.ui.RootScreen
import com.gonzalo.controltv.ui.theme.ControlTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            ControlTvTheme {
                RootScreen()
            }
        }
    }

    /**
     * Con la app en pantalla y la tele conectada, los botones de volumen del celu manejan el de la tele (Ajustes →
     * Botones de volumen del celu). Mantenerlos apretados repite: Android manda la tecla de nuevo con repeatCount.
     * Sin conexión siguen siendo los del celu.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val tvKey = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Keys.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> Keys.VOLUME_DOWN
            else -> return super.dispatchKeyEvent(event)
        }
        val app = application as App
        if (!app.volumeKeysToTv || app.connection.state.value !is TvState.Conectado) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) app.connection.sendKey(tvKey)
        return true
    }
}
