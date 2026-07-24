package br.com.redesurftank.havaldock.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Preferências do app (persistidas localmente). Estados observáveis pelo Compose (tela de Configs).
 *
 * - overlayEnabled: a barra está ligada (deve ser mostrada).
 * - visibilityMode: "always" (sempre visível) ou "auto" (auto-ocultar após inatividade).
 * - autoHideSecs: segundos de inatividade até ocultar (modo "auto").
 * - launchOnBoot: religar a barra quando o carro liga (via BootReceiver).
 */
object SettingsStore {
    const val PREFS = "settings"
    const val KEY_OVERLAY = "overlay_enabled"
    const val KEY_MODE = "visibility_mode"
    const val KEY_SECS = "auto_hide_secs"
    const val KEY_BOOT = "launch_on_boot"

    const val MODE_ALWAYS = "always"
    const val MODE_AUTO = "auto"
    const val DEFAULT_SECS = 10
    const val MIN_SECS = 3
    const val MAX_SECS = 30

    private lateinit var appCtx: Context

    val overlayEnabled = mutableStateOf(false)
    val visibilityMode = mutableStateOf(MODE_AUTO)
    val autoHideSecs = mutableIntStateOf(DEFAULT_SECS)
    val launchOnBoot = mutableStateOf(true)

    fun init(context: Context) {
        appCtx = context.applicationContext
        val p = prefs(appCtx)
        overlayEnabled.value = p.getBoolean(KEY_OVERLAY, false)
        visibilityMode.value = p.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO
        autoHideSecs.intValue = p.getInt(KEY_SECS, DEFAULT_SECS)
        launchOnBoot.value = p.getBoolean(KEY_BOOT, true)
    }

    fun setOverlayEnabled(v: Boolean) {
        overlayEnabled.value = v
        prefs(appCtx).edit().putBoolean(KEY_OVERLAY, v).apply()
    }

    fun setVisibilityMode(v: String) {
        visibilityMode.value = v
        prefs(appCtx).edit().putString(KEY_MODE, v).apply()
    }

    fun setAutoHideSecs(v: Int) {
        val c = v.coerceIn(MIN_SECS, MAX_SECS)
        autoHideSecs.intValue = c
        prefs(appCtx).edit().putInt(KEY_SECS, c).apply()
    }

    fun setLaunchOnBoot(v: Boolean) {
        launchOnBoot.value = v
        prefs(appCtx).edit().putBoolean(KEY_BOOT, v).apply()
    }

    fun isLaunchOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOOT, true)

    fun isOverlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY, false)

    fun mode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO

    fun secs(context: Context): Int =
        prefs(context).getInt(KEY_SECS, DEFAULT_SECS)

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
