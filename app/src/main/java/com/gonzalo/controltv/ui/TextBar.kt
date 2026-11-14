package com.gonzalo.controltv.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonzalo.controltv.R
import com.gonzalo.controltv.ui.theme.Palette

/**
 * Barra de texto: campo + borrar + micrófono + enviar. En modo "en vivo" cada letra se
 * manda al toque y el botón de enviar pasa a ser Enter.
 */
@Composable
fun TextBar(
    text: String,
    onTextChange: (String) -> Unit,
    live: Boolean,
    placeholder: String,
    onSend: () -> Unit,
    onBackspace: () -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(Palette.SurfaceHigh)
            .border(1.dp, Palette.OutlineSoft, shape)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            Text(
                "EN VIVO",
                color = Palette.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.AccentContainer)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text),
            cursorBrush = SolidColor(Palette.Accent),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                autoCorrectEnabled = !live, // en vivo, el autocorrector reescribiría lo que ya se mandó
            ),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Palette.TextFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        BarKey(R.drawable.ic_backspace, "Borrar en la tele", Hold.Repeat, onBackspace)
        BarKey(R.drawable.ic_mic, "Dictar por voz", Hold.None, onMic)
        val canSend = live || text.isNotBlank()
        BarKey(
            icon = if (live) R.drawable.ic_keyboard_return else R.drawable.ic_send,
            label = if (live) "Enter" else "Mandar texto",
            hold = Hold.None,
            onTap = { if (canSend) onSend() },
            container = if (canSend) Palette.Accent else Color.Transparent,
            tint = if (canSend) Palette.OnAccent else Palette.TextFaint,
        )
    }
}

@Composable
private fun BarKey(
    @DrawableRes icon: Int,
    label: String,
    hold: Hold,
    onTap: () -> Unit,
    container: Color = Color.Transparent,
    tint: Color = Palette.TextMuted,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (pressed) Palette.SurfacePressed else container)
            .remoteKey(label, hold, { pressed = it }, onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
