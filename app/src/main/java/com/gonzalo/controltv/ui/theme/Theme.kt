package com.gonzalo.controltv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Paleta del control: grafito oscuro (se usa de noche, frente a la tele) con un
 * acento ámbar, como la luz de un control remoto.
 */
object Palette {
    val Bg = Color(0xFF0D0F13)
    val Surface = Color(0xFF15181E)
    val SurfaceHigh = Color(0xFF1D2129)
    val SurfaceHigher = Color(0xFF272C36)
    val SurfacePressed = Color(0xFF323845)
    val Outline = Color(0xFF2E3440)
    val OutlineSoft = Color(0xFF222731)

    val Text = Color(0xFFEDEFF3)
    val TextMuted = Color(0xFF8D94A1)
    val TextFaint = Color(0xFF5D6472)

    val Accent = Color(0xFFFF9F45)
    val AccentBright = Color(0xFFFFB46B)
    val AccentDeep = Color(0xFFF07F22)
    val OnAccent = Color(0xFF241304)
    val AccentContainer = Color(0xFF3A2614)

    val Green = Color(0xFF3DDC97)
    val Red = Color(0xFFFF5E5E)
    val RedContainer = Color(0xFF3A1B1D)
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentContainer,
    onPrimaryContainer = Palette.AccentBright,
    secondary = Palette.Accent,
    onSecondary = Palette.OnAccent,
    secondaryContainer = Palette.AccentContainer,
    onSecondaryContainer = Palette.AccentBright,
    tertiary = Palette.Green,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Bg,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextMuted,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Palette.Bg,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigher,
    surfaceBright = Palette.SurfaceHigher,
    surfaceDim = Palette.Bg,
    outline = Palette.Outline,
    outlineVariant = Palette.OutlineSoft,
    error = Palette.Red,
    onError = Color.White,
    errorContainer = Palette.RedContainer,
    onErrorContainer = Palette.Red,
    inverseSurface = Palette.Text,
    inverseOnSurface = Palette.Bg,
    inversePrimary = Palette.AccentDeep,
    scrim = Color.Black,
)

private val base = Typography()

private val typography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Estilo para el log y el código de vinculación. */
val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)

@Composable
fun ControlTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
        // Sin esto, el texto fuera de un Scaffold o Surface sale negro.
        CompositionLocalProvider(LocalContentColor provides Palette.Text, content = content)
    }
}
