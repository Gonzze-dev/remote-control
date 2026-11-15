package com.gonzalo.controltv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.gonzalo.controltv.App

/**
 * Lo que contesta Android a la sesión de instalación de [Updater]: si hace falta que la persona confirme, abre
 * la pantalla de Android para eso; si terminó o falló, se lo avisa al [Updater]. Al instalarse bien, Android
 * reinicia la app con la versión nueva.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> app.updater.installFinished(ok = true, message = null)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                app.log.log("✗ Android no instaló la actualización ($status): $message")
                app.updater.installFinished(
                    ok = false,
                    message = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Se canceló la instalación"
                    else "Android no la instaló: ${message ?: "error $status"}",
                )
            }
        }
    }
}
