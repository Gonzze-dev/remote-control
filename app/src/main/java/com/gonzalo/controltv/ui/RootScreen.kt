package com.gonzalo.controltv.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gonzalo.controltv.R
import com.gonzalo.controltv.cast.CastState
import com.gonzalo.controltv.data.AppNames
import com.gonzalo.controltv.data.AppSettings
import com.gonzalo.controltv.tv.TvState
import com.gonzalo.controltv.ui.theme.Palette
import kotlinx.coroutines.launch

/** Arranque: permiso de red local → vinculación (la primera vez) → control. */
@Composable
fun RootScreen(vm: RemoteViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Palette.Bg)) // cargando los ajustes, es un instante
        return
    }

    val view = LocalView.current
    val haptics: () -> Unit = remember(s.vibration, view) {
        if (s.vibration) {
            { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        } else {
            {}
        }
    }

    CompositionLocalProvider(LocalRemoteHaptics provides haptics) {
        val permission = rememberLocalNetworkPermission(onGranted = vm::onPermissionGranted)
        val pairingOpen by vm.pairingOpen.collectAsStateWithLifecycle()
        when {
            !permission.granted -> PermissionScreen(permission.permanentlyDenied, permission.request, permission.openSettings)
            !s.isPaired || pairingOpen -> PairingRoute(vm, s, canCancel = s.isPaired)
            else -> MainRoute(vm, s)
        }
    }
}

@Composable
private fun PairingRoute(vm: RemoteViewModel, s: AppSettings, canCancel: Boolean) {
    val ui by vm.pairing.collectAsStateWithLifecycle()
    BackHandler(enabled = ui is PairingUi.EnterCode || (canCancel && ui is PairingUi.EnterIp)) {
        if (ui is PairingUi.EnterCode) vm.cancelCode() else vm.closePairing()
    }
    PairingScreen(
        ui = ui,
        initialIp = s.ip,
        initialName = s.clientName,
        canCancel = canCancel,
        onStart = vm::startPairing,
        onSubmitCode = vm::submitCode,
        onCancelCode = vm::cancelCode,
        onClose = vm::closePairing,
        onFinish = vm::finishPairing,
    )
}

internal enum class Tab { Control, Apps }

private enum class Overlay { None, Settings, Log }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainRoute(vm: RemoteViewModel, s: AppSettings) {
    val state by vm.state.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val icons by vm.icons.collectAsStateWithLifecycle()
    val casting by vm.casting.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Control) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.None) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.messages.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }

    val currentPkg = (state as? TvState.Conectado)?.app
    val appLabel = currentPkg?.let { AppNames.label(it, apps) }

    BackHandler(enabled = overlay != Overlay.None) {
        overlay = if (overlay == Overlay.Log) Overlay.Settings else Overlay.None
    }
    BackHandler(enabled = overlay == Overlay.None && tab == Tab.Apps) { tab = Tab.Control }

    when (overlay) {
        Overlay.Settings -> SettingsScreen(
            settings = s,
            state = state,
            onBack = { overlay = Overlay.None },
            onSetIp = vm::setIp,
            onSetClientName = vm::setClientName,
            onRepair = {
                overlay = Overlay.None
                vm.openPairing()
            },
            onEnterAfterText = vm::setEnterAfterText,
            onVibration = vm::setVibration,
            onLiveText = vm::setLiveText,
            onOpenLog = { overlay = Overlay.Log },
        )
        Overlay.Log -> {
            val lines by vm.logLines.collectAsStateWithLifecycle()
            LogScreen(
                lines = lines,
                onBack = { overlay = Overlay.Settings },
                onCopy = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Log de Control TV", vm.logText()))
                },
                onClear = vm::clearLog,
            )
        }
        Overlay.None -> Scaffold(
            containerColor = Palette.Bg,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!WindowInsets.isImeVisible) BottomBar(tab) { tab = it }
            },
        ) { padding ->
            Box(
                Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                when (tab) {
                    Tab.Control -> ControlScreen(
                        state = state,
                        appLabel = appLabel,
                        settings = s,
                        actions = vm,
                        onOpenSettings = { overlay = Overlay.Settings },
                        onRepair = vm::openPairing,
                        onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                        casting = casting != CastState.Idle,
                    )
                    Tab.Apps -> AppsScreen(
                        apps = apps,
                        currentPkg = currentPkg,
                        connected = state is TvState.Conectado,
                        onOpen = { vm.launch(it) },
                        onOpenByPackage = { vm.launch(it, byPackage = true) },
                        onSave = vm::saveApp,
                        onDelete = vm::deleteApp,
                        onOpenSettings = { overlay = Overlay.Settings },
                        icons = icons,
                        onSetImage = vm::setAppImage,
                        onRemoveImage = vm::removeAppImage,
                    )
                }
            }
        }
    }
}

@Composable
internal fun BottomBar(tab: Tab, onSelect: (Tab) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = Palette.Accent,
        selectedTextColor = Palette.Accent,
        indicatorColor = Palette.AccentContainer,
        unselectedIconColor = Palette.TextMuted,
        unselectedTextColor = Palette.TextMuted,
    )
    NavigationBar(containerColor = Palette.Surface, tonalElevation = 0.dp) {
        NavigationBarItem(
            selected = tab == Tab.Control,
            onClick = { onSelect(Tab.Control) },
            icon = { Icon(painterResource(R.drawable.ic_settings_remote), null) },
            label = { Text("Control") },
            colors = colors,
        )
        NavigationBarItem(
            selected = tab == Tab.Apps,
            onClick = { onSelect(Tab.Apps) },
            icon = { Icon(painterResource(R.drawable.ic_apps), null) },
            label = { Text("Apps") },
            colors = colors,
        )
    }
}

/** API en la que aparece el permiso de red local (Android 17). */
private const val LOCAL_NETWORK_API = 37
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

class LocalNetworkPermissionState(
    val granted: Boolean,
    val permanentlyDenied: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

@Composable
private fun rememberLocalNetworkPermission(onGranted: () -> Unit): LocalNetworkPermissionState {
    if (Build.VERSION.SDK_INT < LOCAL_NETWORK_API) {
        return remember { LocalNetworkPermissionState(granted = true, permanentlyDenied = false, request = {}, openSettings = {}) }
    }
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun check() = ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(check()) }
    var denials by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) onGranted() else denials++
    }
    // Si lo dio (o lo sacó) desde los ajustes del sistema, se nota al volver.
    LifecycleResumeEffect(Unit) {
        val now = check()
        if (now && !granted) onGranted()
        granted = now
        onPauseOrDispose { }
    }
    val permanentlyDenied = denials > 0 && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, LOCAL_NETWORK_PERMISSION)
    return LocalNetworkPermissionState(
        granted = granted,
        permanentlyDenied = permanentlyDenied,
        request = { launcher.launch(LOCAL_NETWORK_PERMISSION) },
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}
