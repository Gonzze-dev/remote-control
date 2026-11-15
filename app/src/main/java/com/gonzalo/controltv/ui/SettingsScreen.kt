package com.gonzalo.controltv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonzalo.controltv.R
import com.gonzalo.controltv.data.AppSettings
import com.gonzalo.controltv.data.ConnectionLog
import com.gonzalo.controltv.data.SavedTv
import com.gonzalo.controltv.tv.Features
import com.gonzalo.controltv.tv.TvState
import com.gonzalo.controltv.ui.theme.MonoStyle
import com.gonzalo.controltv.ui.theme.Palette
import com.gonzalo.controltv.update.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    state: TvState,
    onBack: () -> Unit,
    onSetIp: (String) -> Unit,
    onSetClientName: (String) -> Unit,
    onRepair: () -> Unit,
    onEnterAfterText: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit,
    onLiveText: (Boolean) -> Unit,
    onOpenLog: () -> Unit,
    version: String = "",
    update: UpdateState = UpdateState.Idle,
    onCheckUpdates: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    onShareApp: () -> Unit = {},
    onSelectTv: (String) -> Unit = {},
    onRenameTv: (id: String, name: String) -> Unit = { _, _ -> },
    onRemoveTv: (String) -> Unit = {},
    onAddTv: () -> Unit = {},
    onVolumeKeys: (Boolean) -> Unit = {},
    onTouchpad: (Boolean) -> Unit = {},
) {
    var tvMenu by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTv by rememberSaveable { mutableStateOf<String?>(null) }
    var editIp by rememberSaveable { mutableStateOf(false) }
    var editName by rememberSaveable { mutableStateOf(false) }
    var confirmRepair by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), "Volver") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Teles de la casa")
            Group {
                val active = settings.activeTv
                settings.tvs.forEach { tv ->
                    TvRow(tv, isActive = tv.id == active?.id) { tvMenu = tv.id }
                    Divider()
                }
                NavRow("Agregar otra tele", "Busca las teles en tu red y la vincula", accent = true) { onAddTv() }
            }

            Section(settings.tvName.ifBlank { "Tele" })
            Group {
                NavRow("IP", settings.ip.ifBlank { "Sin configurar" } + " · si cambia, la app la busca sola") { editIp = true }
                Divider()
                NavRow("Nombre que ve la tele", settings.clientName) { editName = true }
                Divider()
                NavRow("Volver a vincular", "Si la tele dejó de reconocer el celu", accent = true) { confirmRepair = true }
                (state as? TvState.Conectado)?.tele?.let { tele ->
                    Divider()
                    InfoRow("Conectado a", "${tele.vendor} ${tele.model}".trim().ifBlank { "Tele" })
                    Divider()
                    InfoRow("Funciones activas", Features.describeForPeople(tele.features))
                }
            }

            Section("Texto")
            Group {
                SwitchRow("Mandar Enter después del texto", "Así la tele busca sola", settings.enterAfterText, onEnterAfterText)
                Divider()
                SwitchRow("Texto en vivo (experimental)", "Cada letra se manda al toque, sin apretar enviar", settings.liveText, onLiveText)
            }

            Section("Control")
            Group {
                SwitchRow("Botones de volumen del celu", "Manejan el volumen de la tele mientras la app está abierta", settings.volumeKeys, onVolumeKeys)
                Divider()
                SwitchRow("Touchpad en vez de flechas", "Deslizá para moverte por la tele y tocá para OK", settings.touchpad, onTouchpad)
                Divider()
                SwitchRow("Vibrar al tocar", null, settings.vibration, onVibration)
            }

            Section("Control TV")
            Group {
                UpdateRow(version, update, onCheckUpdates, onInstallUpdate)
                Divider()
                NavRow("Compartir la app", "Manda el link para bajarla e instalarla") { onShareApp() }
            }

            Section("Diagnóstico")
            Group {
                NavRow("Log de conexión", "Cada mensaje que va y viene, para ver dónde se traba") { onOpenLog() }
            }

            Text(
                "Control TV $version · protocolo Android TV Remote v2",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextFaint,
                modifier = Modifier.padding(top = 16.dp, start = 8.dp, bottom = 24.dp),
            )
        }
    }

    tvMenu?.let { id ->
        val tv = settings.tvs.firstOrNull { it.id == id }
        if (tv == null) {
            tvMenu = null
        } else {
            AlertDialog(
                onDismissRequest = { tvMenu = null },
                containerColor = Palette.Surface,
                title = { Text(tv.name) },
                text = { Text(tv.ip) },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        if (tv.id != settings.activeTv?.id) {
                            TextButton(onClick = { tvMenu = null; onSelectTv(tv.id) }) { Text("Usar esta tele") }
                        }
                        TextButton(onClick = { tvMenu = null; renameTv = tv.id }) { Text("Cambiar el nombre") }
                        TextButton(onClick = { tvMenu = null; onRemoveTv(tv.id) }) { Text("Quitar", color = Palette.Red) }
                        TextButton(onClick = { tvMenu = null }) { Text("Cerrar", color = Palette.TextMuted) }
                    }
                },
            )
        }
    }
    renameTv?.let { id ->
        val tv = settings.tvs.firstOrNull { it.id == id }
        if (tv != null) {
            TextInputDialog(
                title = "Nombre de la tele",
                initial = tv.name,
                hint = "Por ejemplo: Comedor, Dormitorio",
                keyboardType = KeyboardType.Text,
                onDismiss = { renameTv = null },
                onSave = { onRenameTv(id, it); renameTv = null },
            )
        }
    }
    if (editIp) {
        TextInputDialog(
            title = "IP del Chromecast",
            initial = settings.ip,
            hint = "En la tele: Configuración → Red e Internet → tu red",
            keyboardType = KeyboardType.Uri,
            onDismiss = { editIp = false },
            onSave = { onSetIp(it); editIp = false },
        )
    }
    if (editName) {
        TextInputDialog(
            title = "Nombre que ve la tele",
            initial = settings.clientName,
            hint = "Se aplica la próxima vez que vincules",
            keyboardType = KeyboardType.Text,
            onDismiss = { editName = false },
            onSave = { onSetClientName(it); editName = false },
        )
    }
    if (confirmRepair) {
        AlertDialog(
            onDismissRequest = { confirmRepair = false },
            containerColor = Palette.Surface,
            title = { Text("¿Volver a vincular?") },
            text = { Text("La tele va a mostrar un código nuevo. Lleva 30 segundos.") },
            confirmButton = { TextButton(onClick = { confirmRepair = false; onRepair() }) { Text("Vincular") } },
            dismissButton = { TextButton(onClick = { confirmRepair = false }) { Text("Cancelar") } },
        )
    }
}

/** La versión instalada y, si hay una nueva en GitHub, el botón para actualizar. */
@Composable
private fun UpdateRow(version: String, update: UpdateState, onCheck: () -> Unit, onInstall: () -> Unit) {
    when (update) {
        is UpdateState.Available -> NavRow(
            "Actualizar a la versión ${update.release.version}",
            update.release.notes.lineSequence().map { it.trim().removePrefix("- ") }.firstOrNull { it.isNotBlank() }
                ?: "Tocá para bajarla con el navegador",
            accent = true,
            onClick = onInstall,
        )
        is UpdateState.Failed -> NavRow("No se pudo buscar actualizaciones", update.message, accent = true, onClick = onCheck)
        UpdateState.Checking -> InfoRow("Versión $version", "Buscando actualizaciones…")
        UpdateState.UpToDate -> NavRow("Versión $version", "Es la última · tocá para buscar de nuevo", onClick = onCheck)
        UpdateState.Idle -> NavRow("Versión $version", "Tocá para buscar actualizaciones", onClick = onCheck)
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
        color = Palette.TextMuted,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 2.dp),
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface),
    ) { content() }
}

@Composable
private fun Divider() = HorizontalDivider(color = Palette.OutlineSoft, modifier = Modifier.padding(start = 18.dp))

@Composable
private fun NavRow(title: String, subtitle: String?, accent: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (accent) Palette.Accent else Palette.Text)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
    }
}

/** Una tele de la lista: la que se controla ahora va marcada. */
@Composable
private fun TvRow(tv: SavedTv, isActive: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_tv), null, tint = if (isActive) Palette.Accent else Palette.TextMuted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tv.name, style = MaterialTheme.typography.bodyLarge)
            Text(tv.ip, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
        if (isActive) Text("En uso", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.OnAccent,
                checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.TextMuted,
                uncheckedTrackColor = Palette.SurfaceHigher,
                uncheckedBorderColor = Palette.Outline,
            ),
        )
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    hint: String,
    keyboardType: KeyboardType,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value, { value = it },
                singleLine = true,
                supportingText = { Text(hint) },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onSave(value.trim()) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    lines: List<ConnectionLog.Line>,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.lastOrNull()?.time, lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Log de conexión") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), "Volver") }
                },
                actions = {
                    IconButton(onClick = onCopy) { Icon(painterResource(R.drawable.ic_content_copy), "Copiar todo") }
                    IconButton(onClick = onClear) { Icon(painterResource(R.drawable.ic_delete_sweep), "Borrar log") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg),
            )
        },
    ) { padding ->
        if (lines.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Todavía no hay nada anotado", color = Palette.TextMuted)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(lines) { line ->
                    val color = when {
                        line.text.startsWith("✗") || line.text.startsWith("⚠") -> Palette.Red
                        line.text.startsWith("✓") -> Palette.Green
                        line.text.startsWith("→") -> Palette.AccentBright
                        line.text.startsWith("·") -> Palette.TextFaint
                        else -> Palette.Text
                    }
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(ConnectionLog.format(line.time), style = MonoStyle, fontSize = 11.sp, color = Palette.TextFaint)
                        Spacer(Modifier.width(10.dp))
                        Text(line.text, style = MonoStyle, fontSize = 11.sp, color = color, lineHeight = 15.sp)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
