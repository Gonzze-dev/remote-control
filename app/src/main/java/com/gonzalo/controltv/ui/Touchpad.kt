package com.gonzalo.controltv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gonzalo.controltv.tv.Keys
import com.gonzalo.controltv.ui.theme.Palette
import kotlin.math.abs

/**
 * Superficie para deslizar en lugar del pad de flechas (Ajustes → Touchpad). La tele solo entiende teclas: cada
 * [STEP] recorrido en una dirección manda una flecha, así que un deslizamiento largo mueve varios lugares.
 * Un toque es OK; mantenerlo sin mover hace la pulsación larga de OK, como el pad.
 */
@Composable
fun Touchpad(
    size: Dp,
    onKey: (Int) -> Unit,
    onOkLongStart: () -> Unit,
    onOkLongEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalRemoteHaptics.current
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(36.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.OutlineSoft, RoundedCornerShape(36.dp))
            .semantics { contentDescription = "Touchpad: deslizá para moverte, tocá para OK" }
            .pointerInput(Unit) {
                val step = STEP.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var totalX = 0f
                    var totalY = 0f
                    // Primero: ¿suelta (OK), se mueve (deslizar) o se queda quieto (pulsación larga)?
                    val start = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull RELEASED
                            if (!change.pressed) return@withTimeoutOrNull RELEASED
                            val d = change.positionChange()
                            change.consume()
                            totalX += d.x
                            totalY += d.y
                            if (abs(totalX) > slop || abs(totalY) > slop) return@withTimeoutOrNull MOVED
                        }
                        @Suppress("UNREACHABLE_CODE") RELEASED
                    }
                    when (start) {
                        RELEASED -> {
                            haptic()
                            onKey(Keys.DPAD_CENTER)
                        }
                        null -> {
                            haptic()
                            onOkLongStart()
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                            }
                            onOkLongEnd()
                        }
                        else -> {
                            // Una vez elegido el eje, el gesto no cambia: así no se mueve en diagonal.
                            val horizontal = abs(totalX) >= abs(totalY)
                            var acc = if (horizontal) totalX else totalY
                            while (true) {
                                while (abs(acc) >= step) {
                                    val forward = acc > 0
                                    onKey(
                                        if (horizontal) (if (forward) Keys.DPAD_RIGHT else Keys.DPAD_LEFT)
                                        else (if (forward) Keys.DPAD_DOWN else Keys.DPAD_UP),
                                    )
                                    haptic()
                                    acc -= if (forward) step else -step
                                }
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val d = change.positionChange()
                                change.consume()
                                acc += if (horizontal) d.x else d.y
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Deslizá para moverte\ntocá para OK",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextFaint,
            textAlign = TextAlign.Center,
        )
    }
}

/** Cuánto hay que deslizar para mover un lugar en la tele. */
private val STEP = 40.dp

private const val RELEASED = 0
private const val MOVED = 1
