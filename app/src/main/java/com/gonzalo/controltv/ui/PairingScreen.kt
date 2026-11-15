package com.gonzalo.controltv.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonzalo.controltv.R
import com.gonzalo.controltv.tv.DiscoveredTv
import com.gonzalo.controltv.ui.theme.MonoStyle
import com.gonzalo.controltv.ui.theme.Palette

/** En qué paso está la vinculación. */
sealed interface PairingUi {
    data class EnterIp(val error: String? = null) : PairingUi
    data object Connecting : PairingUi
    data class EnterCode(val error: String? = null, val attempt: Int = 0) : PairingUi
    data object Verifying : PairingUi
    data object Done : PairingUi
}

@Composable
fun PairingScreen(
    ui: PairingUi,
    initialIp: String,
    initialName: String,
    canCancel: Boolean,
    onStart: (ip: String, name: String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancelCode: () -> Unit,
    onClose: () -> Unit,
    onFinish: () -> Unit,
    discovered: List<DiscoveredTv> = emptyList(),
    onPickTv: (tv: DiscoveredTv, name: String) -> Unit = { _, _ -> },
) {
    WizardFrame {
        when (ui) {
            is PairingUi.EnterIp, PairingUi.Connecting -> IpStep(
                initialIp = initialIp,
                initialName = initialName,
                error = (ui as? PairingUi.EnterIp)?.error,
                busy = ui is PairingUi.Connecting,
                canCancel = canCancel,
                onStart = onStart,
                onClose = onClose,
                discovered = discovered,
                onPickTv = onPickTv,
            )
            is PairingUi.EnterCode, PairingUi.Verifying -> CodeStep(
                error = (ui as? PairingUi.EnterCode)?.error,
                attempt = (ui as? PairingUi.EnterCode)?.attempt ?: 0,
                busy = ui is PairingUi.Verifying,
                onSubmit = onSubmitCode,
                onCancel = onCancelCode,
            )
            PairingUi.Done -> DoneStep(onFinish)
        }
    }
}

@Composable
private fun WizardFrame(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) { content() }
    }
}

@Composable
private fun Hero(@DrawableRes icon: Int, step: String?, title: String, body: String) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Palette.AccentContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, tint = Palette.Accent, modifier = Modifier.size(32.dp))
    }
    Spacer(Modifier.height(24.dp))
    if (step != null) {
        Text(step.uppercase(), fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
        Spacer(Modifier.height(6.dp))
    }
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(10.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = Palette.TextMuted)
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun IpStep(
    initialIp: String,
    initialName: String,
    error: String?,
    busy: Boolean,
    canCancel: Boolean,
    onStart: (String, String) -> Unit,
    onClose: () -> Unit,
    discovered: List<DiscoveredTv>,
    onPickTv: (DiscoveredTv, String) -> Unit,
) {
    var ip by rememberSaveable { mutableStateOf(initialIp) }
    var name by rememberSaveable { mutableStateOf(initialName) }

    Hero(
        R.drawable.ic_tv, "Paso 1 de 2", "Vinculá tu tele",
        "Se hace una sola vez por tele. El celu y la tele tienen que estar en la misma red Wi‑Fi.",
    )
    // Las teles que anuncian el control en la red: tocar una es lo mismo que escribir su IP.
    Text("TELES EN TU RED", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Palette.TextMuted)
    Spacer(Modifier.height(8.dp))
    if (discovered.isEmpty()) {
        Text("Buscando…", style = MaterialTheme.typography.bodyMedium, color = Palette.TextFaint)
    }
    discovered.forEach { tv ->
        TvChoice(tv, enabled = !busy) { onPickTv(tv, name) }
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(16.dp))
    Text("O ESCRIBÍ LA IP", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Palette.TextMuted)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = ip,
        onValueChange = { ip = it.trim() },
        label = { Text("IP del Chromecast") },
        placeholder = { Text("192.168.0.50") },
        singleLine = true,
        isError = error != null,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    HelpCard(
        R.drawable.ic_wifi,
        "¿Dónde la veo?",
        "En la tele: Configuración → Red e Internet → tu red. Conviene reservarla en el router para que no cambie.",
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Nombre que va a ver la tele") },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (ip.isNotBlank()) onStart(ip, name) }),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (error != null) {
        Spacer(Modifier.height(16.dp))
        ErrorText(error)
    }
    Spacer(Modifier.height(28.dp))
    PrimaryButton(if (busy) "Conectando…" else "Vincular", enabled = ip.isNotBlank() && !busy, busy = busy) { onStart(ip, name) }
    if (canCancel) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancelar", color = Palette.TextMuted) }
    }
}

@Composable
private fun CodeStep(error: String?, attempt: Int, busy: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(attempt) { if (attempt > 0) code = "" }

    Hero(
        R.drawable.ic_tv, "Paso 2 de 2", "Escribí el código",
        "La tele muestra un código de 6 caracteres (números y letras de la A a la F).",
    )
    BasicTextField(
        value = code,
        onValueChange = { new ->
            val clean = new.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
            code = clean
            if (clean.length == 6 && !busy) onSubmit(clean)
        },
        enabled = !busy,
        singleLine = true,
        cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
        textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (code.length == 6) onSubmit(code) }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
        decorationBox = { inner ->
            Box {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until 6) {
                        val ch = code.getOrNull(i)?.toString() ?: ""
                        val active = i == code.length && !busy
                        val shape = RoundedCornerShape(14.dp)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(60.dp)
                                .clip(shape)
                                .background(Palette.SurfaceHigh)
                                .border(
                                    if (active) 2.dp else 1.dp,
                                    when {
                                        error != null && code.isEmpty() -> Palette.Red.copy(alpha = 0.6f)
                                        active -> Palette.Accent
                                        else -> Palette.OutlineSoft
                                    },
                                    shape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(ch, style = MonoStyle, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                        }
                    }
                }
                Box(Modifier.size(1.dp)) { inner() }
            }
        },
    )
    Spacer(Modifier.height(16.dp))
    when {
        busy -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Verificando con la tele…", color = Palette.TextMuted)
        }
        error != null -> ErrorText(error)
        else -> Text("Si te equivocás, te avisa antes de mandarlo.", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
    }
    Spacer(Modifier.height(28.dp))
    PrimaryButton("Confirmar", enabled = code.length == 6 && !busy, busy = false) { onSubmit(code) }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Volver", color = Palette.TextMuted)
    }
}

@Composable
private fun DoneStep(onFinish: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Palette.Green.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_check), null, tint = Palette.Green, modifier = Modifier.size(36.dp))
    }
    Spacer(Modifier.height(24.dp))
    Text("¡Listo!", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(10.dp))
    Text(
        "La tele ya reconoce este celu. No hace falta volver a vincular, salvo que borres los datos de la app.",
        style = MaterialTheme.typography.bodyLarge,
        color = Palette.TextMuted,
    )
    Spacer(Modifier.height(32.dp))
    PrimaryButton("Ir al control", enabled = true, busy = false, onClick = onFinish)
}

/** Pide el permiso de red local (Android 17 en adelante). */
@Composable
fun PermissionScreen(permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    WizardFrame {
        Hero(
            R.drawable.ic_wifi, null, "Permiso de red local",
            "Para hablar con la tele por Wi‑Fi, Android pide permiso para usar la red local. " +
                "La app no lo usa para nada más: solo se conecta a la IP de tu Chromecast.",
        )
        if (permanentlyDenied) {
            HelpCard(
                R.drawable.ic_info,
                "El permiso quedó bloqueado",
                "Abrí los ajustes de la app → Permisos → Dispositivos cercanos → Permitir.",
            )
            Spacer(Modifier.height(28.dp))
            PrimaryButton("Abrir ajustes de la app", enabled = true, busy = false, onClick = onOpenSettings)
        } else {
            PrimaryButton("Dar permiso", enabled = true, busy = false, onClick = onRequest)
        }
    }
}

@Composable
private fun TvChoice(tv: DiscoveredTv, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_tv), null, tint = Palette.Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tv.name, style = MaterialTheme.typography.bodyLarge)
            Text(tv.host, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
        Text("Vincular", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
    }
}

@Composable
private fun HelpCard(@DrawableRes icon: Int, title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .padding(14.dp),
    ) {
        Icon(painterResource(icon), null, tint = Palette.TextMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.RedContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_info), null, tint = Palette.Red, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Accent,
            contentColor = Palette.OnAccent,
            disabledContainerColor = Palette.SurfaceHigher,
            disabledContentColor = Palette.TextFaint,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Palette.TextMuted, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Accent,
    unfocusedBorderColor = Palette.Outline,
    focusedLabelColor = Palette.Accent,
    cursorColor = Palette.Accent,
    unfocusedContainerColor = Palette.Surface,
    focusedContainerColor = Palette.Surface,
    disabledContainerColor = Palette.Surface,
    errorContainerColor = Palette.Surface,
)
