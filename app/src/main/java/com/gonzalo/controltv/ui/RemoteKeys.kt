package com.gonzalo.controltv.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonzalo.controltv.R
import com.gonzalo.controltv.tv.Keys
import com.gonzalo.controltv.ui.theme.Palette
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Vibración corta al tocar un botón (se apaga desde Ajustes). */
val LocalRemoteHaptics = staticCompositionLocalOf<() -> Unit> { {} }

/** Qué pasa si se mantiene apretado un botón. */
enum class Hold {
    /** Nada especial: al soltar cuenta como un toque. */
    None,

    /** Se repite mientras está apretado (flechas, volumen, borrar). */
    Repeat,

    /** Pulsación larga en la tele: START_LONG al pasar el umbral, END_LONG al soltar. */
    LongPress,
}

private const val LONG_PRESS_MS = 400L
private const val REPEAT_MS = 110L

private enum class Release { Up, Cancel, Timeout }

private suspend fun AwaitPointerEventScope.awaitRelease(timeoutMs: Long?): Release {
    if (timeoutMs == null) return if (waitForUpOrCancellation() != null) Release.Up else Release.Cancel
    return withTimeoutOrNull(timeoutMs) {
        if (waitForUpOrCancellation() != null) Release.Up else Release.Cancel
    } ?: Release.Timeout
}

/** Lo que pasa después de apoyar el dedo, según [hold]. */
private suspend fun AwaitPointerEventScope.handleHold(
    hold: Hold,
    haptic: () -> Unit,
    onTap: () -> Unit,
    onLongStart: () -> Unit,
    onLongEnd: () -> Unit,
) {
    when (awaitRelease(LONG_PRESS_MS)) {
        Release.Up -> onTap()
        Release.Cancel -> Unit
        Release.Timeout -> when (hold) {
            Hold.None -> if (awaitRelease(null) == Release.Up) onTap()
            Hold.Repeat -> {
                onTap()
                while (awaitRelease(REPEAT_MS) == Release.Timeout) onTap()
            }
            Hold.LongPress -> {
                haptic()
                onLongStart()
                awaitRelease(null)
                onLongEnd()
            }
        }
    }
}

/** Botón del control: vibra al apoyar, y repite o hace pulsación larga según [hold]. */
@Composable
fun Modifier.remoteKey(
    label: String,
    hold: Hold = Hold.None,
    onPressedChange: (Boolean) -> Unit = {},
    onTap: () -> Unit,
    onLongStart: () -> Unit = {},
    onLongEnd: () -> Unit = {},
): Modifier {
    val haptic = LocalRemoteHaptics.current
    val tap by rememberUpdatedState(onTap)
    val longStart by rememberUpdatedState(onLongStart)
    val longEnd by rememberUpdatedState(onLongEnd)
    val pressedChange by rememberUpdatedState(onPressedChange)
    return this
        .semantics {
            role = Role.Button
            contentDescription = label
            onClick { tap(); true }
        }
        .pointerInput(hold) {
            awaitEachGesture {
                awaitFirstDown().consume()
                pressedChange(true)
                haptic()
                try {
                    handleHold(hold, haptic, { tap() }, { longStart() }, { longEnd() })
                } finally {
                    pressedChange(false)
                }
            }
        }
}

/** Botón redondo con ícono. */
@Composable
fun RoundKey(
    @DrawableRes icon: Int,
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    hold: Hold = Hold.LongPress,
    onLongStart: () -> Unit = {},
    onLongEnd: () -> Unit = {},
    tint: Color = Palette.Text,
    container: Color = Palette.SurfaceHigh,
    border: Color = Palette.OutlineSoft,
) {
    var pressed by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (pressed) Palette.SurfacePressed else container, tween(90), label = "bg")
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(90), label = "scale")
    Box(
        modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .remoteKey(label, hold, { pressed = it }, onTap, onLongStart, onLongEnd),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.42f))
    }
}

/**
 * Balancín de volumen: − a la izquierda, + a la derecha. Mantener apretado repite.
 * El nivel se ve en la línea de estado, así que acá no se repite.
 */
@Composable
fun VolumeRocker(
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    width: Dp = 168.dp,
) {
    var downPressed by remember { mutableStateOf(false) }
    var upPressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(Palette.SurfaceHigh)
            .border(1.dp, Palette.OutlineSoft, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(if (downPressed) Palette.SurfacePressed else Color.Transparent)
                .remoteKey("Bajar volumen", Hold.Repeat, { downPressed = it }, onDown),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_volume_down), null, tint = Palette.Text, modifier = Modifier.size(24.dp))
        }
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            Text("VOL", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(if (upPressed) Palette.SurfacePressed else Color.Transparent)
                .remoteKey("Subir volumen", Hold.Repeat, { upPressed = it }, onUp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_volume_up), null, tint = Palette.Text, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * Pad direccional circular con OK en el medio. Las flechas se repiten al mantenerlas;
 * OK hace pulsación larga (menú contextual en la tele).
 */
@Composable
fun DPad(
    size: Dp,
    onKey: (Int) -> Unit,
    onLongStart: (Int) -> Unit,
    onLongEnd: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressedKey by remember { mutableStateOf<Int?>(null) }
    val haptic = LocalRemoteHaptics.current
    val key by rememberUpdatedState(onKey)
    val longStart by rememberUpdatedState(onLongStart)
    val longEnd by rememberUpdatedState(onLongEnd)
    val okFraction = 0.38f

    Box(
        modifier
            .size(size)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val code = zoneAt(down.position, Size(this.size.width.toFloat(), this.size.height.toFloat()), okFraction)
                        ?: return@awaitEachGesture
                    down.consume()
                    pressedKey = code
                    haptic()
                    try {
                        handleHold(
                            hold = if (code == Keys.DPAD_CENTER) Hold.LongPress else Hold.Repeat,
                            haptic = haptic,
                            onTap = { key(code) },
                            onLongStart = { longStart(code) },
                            onLongEnd = { longEnd(code) },
                        )
                    } finally {
                        pressedKey = null
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            val c = center
            // Aro con un leve degradé, como un botón de goma.
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Palette.SurfaceHigher,
                    1f to Palette.SurfaceHigh,
                    center = c,
                    radius = r,
                ),
                radius = r,
            )
            // Sector apretado.
            pressedKey?.takeIf { it != Keys.DPAD_CENTER }?.let { k ->
                val start = when (k) {
                    Keys.DPAD_RIGHT -> -45f
                    Keys.DPAD_DOWN -> 45f
                    Keys.DPAD_LEFT -> 135f
                    else -> 225f
                }
                drawArc(
                    brush = Brush.radialGradient(
                        0.3f to Palette.Accent.copy(alpha = 0.05f),
                        1f to Palette.Accent.copy(alpha = 0.28f),
                        center = c,
                        radius = r,
                    ),
                    startAngle = start,
                    sweepAngle = 90f,
                    useCenter = true,
                )
            }
            // Divisiones en diagonal entre las flechas.
            val inner = r * (okFraction + 0.07f)
            for (deg in listOf(45f, 135f, 225f, 315f)) {
                val rad = Math.toRadians(deg.toDouble())
                drawLine(
                    color = Palette.Bg.copy(alpha = 0.55f),
                    start = Offset(c.x + inner * cos(rad).toFloat(), c.y + inner * sin(rad).toFloat()),
                    end = Offset(c.x + (r - 10.dp.toPx()) * cos(rad).toFloat(), c.y + (r - 10.dp.toPx()) * sin(rad).toFloat()),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            drawCircle(Palette.OutlineSoft, radius = r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            // Hueco alrededor del OK.
            drawCircle(Palette.Bg.copy(alpha = 0.45f), radius = r * okFraction + 6.dp.toPx())
        }

        val arrowSize = size * 0.14f
        val edge = size * 0.07f
        Arrow(R.drawable.ic_keyboard_arrow_up, "Arriba", pressedKey == Keys.DPAD_UP, arrowSize, { key(Keys.DPAD_UP) },
            Modifier.align(Alignment.TopCenter).padding(top = edge))
        Arrow(R.drawable.ic_keyboard_arrow_down, "Abajo", pressedKey == Keys.DPAD_DOWN, arrowSize, { key(Keys.DPAD_DOWN) },
            Modifier.align(Alignment.BottomCenter).padding(bottom = edge))
        Arrow(R.drawable.ic_keyboard_arrow_left, "Izquierda", pressedKey == Keys.DPAD_LEFT, arrowSize, { key(Keys.DPAD_LEFT) },
            Modifier.align(Alignment.CenterStart).padding(start = edge))
        Arrow(R.drawable.ic_keyboard_arrow_right, "Derecha", pressedKey == Keys.DPAD_RIGHT, arrowSize, { key(Keys.DPAD_RIGHT) },
            Modifier.align(Alignment.CenterEnd).padding(end = edge))

        val okPressed = pressedKey == Keys.DPAD_CENTER
        val okScale by animateFloatAsState(if (okPressed) 0.95f else 1f, tween(90), label = "ok")
        Box(
            Modifier
                .size(size * okFraction)
                .scale(okScale)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        if (okPressed) listOf(Palette.AccentDeep, Palette.AccentDeep)
                        else listOf(Palette.AccentBright, Palette.AccentDeep),
                    ),
                )
                .semantics {
                    role = Role.Button
                    contentDescription = "OK"
                    onClick { key(Keys.DPAD_CENTER); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("OK", color = Palette.OnAccent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.075f).sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun Arrow(@DrawableRes icon: Int, label: String, pressed: Boolean, size: Dp, onTap: () -> Unit, modifier: Modifier) {
    Box(
        modifier.semantics {
            role = Role.Button
            contentDescription = label
            onClick { onTap(); true }
        },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (pressed) Palette.AccentBright else Palette.TextMuted, modifier = Modifier.size(size))
    }
}

/** Qué tecla corresponde a un punto del pad, o null si cae fuera del círculo. */
private fun zoneAt(p: Offset, size: Size, okFraction: Float): Int? {
    val r = size.minDimension / 2
    val dx = p.x - size.width / 2
    val dy = p.y - size.height / 2
    val dist = hypot(dx, dy)
    if (dist > r) return null
    if (dist < r * (okFraction + 0.02f)) return Keys.DPAD_CENTER
    val deg = Math.toDegrees(atan2(dy, dx).toDouble())
    return when {
        deg >= -45 && deg < 45 -> Keys.DPAD_RIGHT
        deg >= 45 && deg < 135 -> Keys.DPAD_DOWN
        deg >= -135 && deg < -45 -> Keys.DPAD_UP
        else -> Keys.DPAD_LEFT
    }
}
