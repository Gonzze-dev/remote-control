package com.gonzalo.controltv.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonzalo.controltv.R
import com.gonzalo.controltv.data.AppNames
import com.gonzalo.controltv.data.TvApp
import com.gonzalo.controltv.ui.theme.Palette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    apps: List<TvApp>,
    currentPkg: String?,
    connected: Boolean,
    onOpen: (TvApp) -> Unit,
    onOpenByPackage: (TvApp) -> Unit,
    onSave: (TvApp) -> Unit,
    onDelete: (TvApp) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<TvApp?>(null) }
    var creating by remember { mutableStateOf<TvApp?>(null) }
    var menuFor by remember { mutableStateOf<TvApp?>(null) }
    val currentInList = currentPkg != null && apps.any { it.pkg == currentPkg }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Apps", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (connected) "Tocá una para abrirla en la tele" else "Sin conexión con la tele",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) Palette.TextMuted else Palette.Red,
                )
            }
            IconButton(onClick = { creating = TvApp(name = "", link = "") }) {
                Icon(painterResource(R.drawable.ic_add), "Agregar app", tint = Palette.TextMuted)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(painterResource(R.drawable.ic_settings), "Ajustes", tint = Palette.TextMuted)
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (connected && currentPkg != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    NowOnScreenCard(
                        label = AppNames.label(currentPkg, apps),
                        pkg = currentPkg,
                        inList = currentInList,
                        onAdd = {
                            creating = TvApp(
                                name = AppNames.label(currentPkg, apps),
                                link = TvApp.marketLink(currentPkg),
                                pkg = currentPkg,
                            )
                        },
                    )
                }
            }
            items(apps, key = { it.id }) { app ->
                AppCard(
                    app = app,
                    isOpen = connected && app.pkg.isNotBlank() && app.pkg == currentPkg,
                    onClick = { onOpen(app) },
                    onLongClick = { menuFor = app },
                )
            }
            item {
                AddCard(onClick = { creating = TvApp(name = "", link = "") })
            }
        }
    }

    menuFor?.let { app ->
        ModalBottomSheet(
            onDismissRequest = { menuFor = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface,
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppInitial(app.name, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(app.name, style = MaterialTheme.typography.titleMedium)
                        Text(app.link, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (app.packageLink != null && app.packageLink != app.link) {
                    SheetAction(R.drawable.ic_open_in_new, "Abrir por paquete (plan B)") { menuFor = null; onOpenByPackage(app) }
                }
                SheetAction(R.drawable.ic_edit, "Editar") { menuFor = null; editing = app }
                SheetAction(R.drawable.ic_delete, "Borrar", tint = Palette.Red) { menuFor = null; onDelete(app) }
            }
        }
    }

    (editing ?: creating)?.let { app ->
        AppDialog(
            initial = app,
            isNew = editing == null,
            onDismiss = { editing = null; creating = null },
            onSave = { onSave(it); editing = null; creating = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCard(app: TvApp, isOpen: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .background(if (isOpen) Palette.AccentContainer else Palette.SurfaceHigh)
            .border(if (isOpen) 1.5.dp else 1.dp, if (isOpen) Palette.Accent else Palette.OutlineSoft, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onLongClickLabel = "Editar o borrar",
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppInitial(app.name, 46.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                app.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        if (isOpen) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Palette.Accent),
            )
        }
    }
}

@Composable
private fun AddCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .border(1.dp, Palette.Outline, shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_add), null, tint = Palette.TextMuted, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text("Agregar", style = MaterialTheme.typography.labelLarge, color = Palette.TextMuted)
    }
}

@Composable
private fun NowOnScreenCard(label: String, pkg: String, inList: Boolean, onAdd: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Surface)
            .border(1.dp, Palette.OutlineSoft, shape)
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppInitial(label, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("EN PANTALLA", fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pkg, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (inList) {
            Text("En tu lista", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted, modifier = Modifier.padding(end = 6.dp))
        } else {
            FilledTonalButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text("Agregar", fontSize = 13.sp)
            }
        }
    }
}

/** Inicial con un color que sale del nombre (siempre el mismo para la misma app). */
@Composable
fun AppInitial(name: String, size: androidx.compose.ui.unit.Dp) {
    val colors = listOf(
        Color(0xFFE5484D), Color(0xFF3E63DD), Color(0xFF30A46C), Color(0xFFF76B15), Color(0xFF8E4EC6),
        Color(0xFF12A594), Color(0xFFD6409F), Color(0xFF0090FF), Color(0xFFBF7AF0), Color(0xFF46A758),
    )
    val trimmed = name.trim()
    val bg = colors[Math.floorMod(trimmed.lowercase().hashCode(), colors.size)]
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            trimmed.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

@Composable
private fun SheetAction(@DrawableRes icon: Int, label: String, tint: Color = Palette.Text, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
private fun AppDialog(initial: TvApp, isNew: Boolean, onDismiss: () -> Unit, onSave: (TvApp) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var link by rememberSaveable { mutableStateOf(initial.link) }
    var pkg by rememberSaveable { mutableStateOf(initial.pkg) }
    val valid = name.isNotBlank() && (link.isNotBlank() || pkg.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text(if (isNew) "Agregar app" else "Editar app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(
                    link, { link = it.trim() },
                    label = { Text("Link") },
                    placeholder = { Text("netflix:// o https://…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    pkg, { pkg = it.trim() },
                    label = { Text("Paquete (opcional)") },
                    placeholder = { Text("com.ejemplo.app") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = { Text("Sirve para marcar la app abierta y como plan B. Si no sabés el link, alcanza con el paquete.") },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            link = link.ifBlank { TvApp.marketLink(pkg) },
                            pkg = pkg,
                        ),
                    )
                },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
