package com.gonzalo.controltv.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.gonzalo.controltv.R
import com.gonzalo.controltv.data.AppSettings
import com.gonzalo.controltv.tv.Keys
import com.gonzalo.controltv.tv.TvState
import com.gonzalo.controltv.ui.theme.Palette

/** Lo que pueden hacer los botones. El ViewModel lo implementa; las pruebas usan [Noop]. */
interface RemoteActions {
    fun key(code: Int)
    fun keyLongStart(code: Int)
    fun keyLongEnd(code: Int)
    /** Devuelve false si no se pudo mandar (sin conexión). */
    fun sendText(text: String): Boolean
    fun liveEdit(old: String, new: String)
    fun retry()

    object Noop : RemoteActions {
        override fun key(code: Int) = Unit
        override fun keyLongStart(code: Int) = Unit
        override fun keyLongEnd(code: Int) = Unit
        override fun sendText(text: String) = true
        override fun liveEdit(old: String, new: String) = Unit
        override fun retry() = Unit
    }
}

@Composable
fun ControlScreen(
    state: TvState,
    appLabel: String?,
    settings: AppSettings,
    actions: RemoteActions,
    onOpenSettings: () -> Unit,
    onRepair: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var lastSent by rememberSaveable { mutableStateOf<String?>(null) }

    fun send(value: String) {
        if (value.isBlank()) return
        if (actions.sendText(value)) {
            lastSent = value
            text = ""
        }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                text = spoken
                send(spoken)
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        StatusHeader(state, appLabel, settings.ip, onOpenSettings, actions::retry, onRepair)

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val rowsHeight = 64.dp + 56.dp + 28.dp * 2
            val showRows = maxHeight >= rowsHeight + 170.dp
            val padSize = if (showRows) {
                min(min(maxWidth * 0.84f, maxHeight - rowsHeight - 12.dp), 300.dp)
            } else {
                min(min(maxWidth * 0.7f, maxHeight - 12.dp), 300.dp)
            }.coerceAtLeast(120.dp)
            val clusterWidth = maxOf(padSize, 280.dp).coerceAtMost(maxWidth)
            // En pantallas altas, el aire sobrante se reparte entre las filas en vez de quedar todo arriba y abajo.
            val gap = ((maxHeight - padSize - 64.dp - 56.dp) / 4).coerceIn(28.dp, 44.dp)
            val poweredOff = state is TvState.Conectado && !state.prendida
            val volume = (state as? TvState.Conectado)?.volumen

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                DPad(padSize, actions::key, actions::keyLongStart, actions::keyLongEnd)
                if (showRows) {
                    Row(
                        Modifier.width(clusterWidth),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundKey(R.drawable.ic_arrow_back, "Atrás", { actions.key(Keys.BACK) },
                            onLongStart = { actions.keyLongStart(Keys.BACK) }, onLongEnd = { actions.keyLongEnd(Keys.BACK) })
                        RoundKey(R.drawable.ic_home, "Inicio", { actions.key(Keys.HOME) },
                            onLongStart = { actions.keyLongStart(Keys.HOME) }, onLongEnd = { actions.keyLongEnd(Keys.HOME) })
                        RoundKey(
                            R.drawable.ic_power_settings_new, "Encendido", { actions.key(Keys.POWER) },
                            hold = Hold.None,
                            tint = Palette.Red,
                            container = if (poweredOff) Palette.RedContainer else Palette.SurfaceHigh,
                            border = if (poweredOff) Palette.Red.copy(alpha = 0.5f) else Palette.OutlineSoft,
                        )
                    }
                    Row(
                        Modifier.width(clusterWidth),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VolumeRocker(
                            onDown = { actions.key(Keys.VOLUME_DOWN) },
                            onUp = { actions.key(Keys.VOLUME_UP) },
                            width = clusterWidth - 56.dp * 2 - 24.dp,
                        )
                        RoundKey(
                            R.drawable.ic_volume_off, "Silencio", { actions.key(Keys.VOLUME_MUTE) },
                            size = 56.dp, hold = Hold.None,
                            tint = if (volume?.muted == true) Palette.Red else Palette.Text,
                        )
                        RoundKey(R.drawable.ic_play_pause, "Play o pausa", { actions.key(Keys.MEDIA_PLAY_PAUSE) }, size = 56.dp, hold = Hold.None)
                    }
                }
            }
        }

        TextBar(
            text = text,
            onTextChange = { new ->
                if (settings.liveText) actions.liveEdit(text, new)
                text = new
            },
            live = settings.liveText,
            placeholder = lastSent?.let { "Mandado: «$it»" } ?: "Escribir en la tele…",
            onSend = {
                if (settings.liveText) {
                    actions.key(Keys.ENTER)
                    if (text.isNotBlank()) lastSent = text
                    text = "" // en vivo la tele ya tiene el texto: solo se limpia el campo
                } else {
                    send(text)
                }
            },
            onBackspace = { actions.key(Keys.DEL) },
            onMic = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Decí lo que querés buscar")
                try {
                    voice.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    onMessage("Este celu no tiene dictado por voz")
                }
            },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        )
    }
}

/** Línea de estado: punto de conexión, app en pantalla, volumen y Ajustes. */
@Composable
fun StatusHeader(
    state: TvState,
    appLabel: String?,
    host: String,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
) {
    val (dot, title, subtitle) = when (state) {
        is TvState.Conectado ->
            if (state.prendida) Triple(Palette.Green, appLabel ?: "Conectado", "Conectado · $host")
            else Triple(Palette.TextMuted, "La tele está apagada", "Conectado · tocá encendido")
        is TvState.Conectando -> Triple(
            Palette.Accent,
            "Conectando…",
            if (state.intento > 1) "$host · intento ${state.intento}" else host,
        )
        is TvState.Desconectado -> Triple(Palette.Red, "Sin conexión", state.motivo ?: host)
        is TvState.HayQueVincular -> Triple(Palette.Red, "Hay que vincular de nuevo", state.motivo)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(dot, pulsing = state is TvState.Conectando)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (state) {
            is TvState.Conectado -> state.volumen?.let { VolumeChip(it.level, it.muted) }
            is TvState.Desconectado -> TextButton(onClick = onRetry) { Text("Reintentar") }
            is TvState.HayQueVincular -> Button(
                onClick = onRepair,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.height(36.dp),
            ) { Text("Vincular") }
            is TvState.Conectando -> Unit
        }
        IconButton(onClick = onOpenSettings) {
            Icon(painterResource(R.drawable.ic_settings), "Ajustes", tint = Palette.TextMuted)
        }
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "dot")
        transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha").value
    } else {
        1f
    }
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(22.dp)
                .alpha(alpha * 0.18f)
                .clip(CircleShape)
                .background(color),
        )
        Box(
            Modifier
                .size(10.dp)
                .alpha(alpha)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun VolumeChip(level: Int, muted: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
            contentDescription = if (muted) "Silenciado" else "Volumen",
            tint = if (muted) Palette.Red else Palette.TextMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(level.toString(), style = MaterialTheme.typography.labelLarge, color = Palette.Text)
    }
}
