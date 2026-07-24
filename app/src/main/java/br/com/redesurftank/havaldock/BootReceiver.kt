package br.com.redesurftank.havaldock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import br.com.redesurftank.havaldock.data.SettingsStore

/** Religa a barra quando o carro liga, se habilitado e com permissão de overlay. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SettingsStore.isLaunchOnBoot(context)) return
        if (!SettingsStore.isOverlayEnabled(context)) return
        if (!Settings.canDrawOverlays(context)) return
        OverlayService.start(context)
    }
}
