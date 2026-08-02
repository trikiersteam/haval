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
    const val KEY_POPUP_SECS = "popup_secs"
    const val KEY_BAR_HEIGHT = "bar_height"
    const val KEY_OPACITY = "bar_opacity"
    const val KEY_ITEM_FRAME = "item_frame"
    const val KEY_SIMULATION = "simulation_mode"
    const val KEY_SEC0_X = "sec0_x"
    const val KEY_SEC1_X = "sec1_x"
    const val KEY_SEC2_X = "sec2_x"
    const val KEY_SEC3_X = "sec3_x"
    const val KEY_BOOT = "launch_on_boot"
    const val KEY_VISUAL_MODE = "visual_mode"

    const val MODE_ALWAYS = "always"
    const val MODE_AUTO = "auto"
    const val DEFAULT_SECS = 10
    const val MIN_SECS = 3
    const val MAX_SECS = 30

    const val DEFAULT_POPUP_SECS = 5
    const val MIN_POPUP_SECS = 0
    const val MAX_POPUP_SECS = 60

    const val DEFAULT_BAR_HEIGHT = 75
    const val MIN_BAR_HEIGHT = 50
    const val MAX_BAR_HEIGHT = 120

    const val DEFAULT_OPACITY = 95
    const val MIN_OPACITY = 0
    const val MAX_OPACITY = 100

    const val VISUAL_BAR = "bar"
    const val VISUAL_DASHBOARD = "dashboard"
    const val VISUAL_BALLOONS = "balloons"

    private lateinit var appCtx: Context

    val overlayEnabled = mutableStateOf(false)
    val visibilityMode = mutableStateOf(MODE_AUTO)
    val autoHideSecs = mutableIntStateOf(DEFAULT_SECS)
    val popupSecs = mutableIntStateOf(DEFAULT_POPUP_SECS)
    val barHeight = mutableIntStateOf(DEFAULT_BAR_HEIGHT)
    val barOpacity = mutableIntStateOf(DEFAULT_OPACITY)
    val itemFrameEnabled = mutableStateOf(false)
    val simulationEnabled = mutableStateOf(false)
    val sec0X = mutableIntStateOf(20)
    val sec1X = mutableIntStateOf(320)
    val sec2X = mutableIntStateOf(620)
    val sec3X = mutableIntStateOf(920)
    val launchOnBoot = mutableStateOf(true)
    val visualMode = mutableStateOf(VISUAL_BAR)

    fun init(context: Context) {
        appCtx = context.applicationContext
        val p = prefs(appCtx)
        overlayEnabled.value = p.getBoolean(KEY_OVERLAY, false)
        visibilityMode.value = p.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO
        autoHideSecs.intValue = p.getInt(KEY_SECS, DEFAULT_SECS)
        popupSecs.intValue = p.getInt(KEY_POPUP_SECS, DEFAULT_POPUP_SECS)
        barHeight.intValue = p.getInt(KEY_BAR_HEIGHT, DEFAULT_BAR_HEIGHT)
        barOpacity.intValue = p.getInt(KEY_OPACITY, DEFAULT_OPACITY)
        itemFrameEnabled.value = p.getBoolean(KEY_ITEM_FRAME, false)
        simulationEnabled.value = p.getBoolean(KEY_SIMULATION, isEmulator())
        sec0X.intValue = p.getInt(KEY_SEC0_X, 20)
        sec1X.intValue = p.getInt(KEY_SEC1_X, 320)
        sec2X.intValue = p.getInt(KEY_SEC2_X, 620)
        sec3X.intValue = p.getInt(KEY_SEC3_X, 920)
        launchOnBoot.value = p.getBoolean(KEY_BOOT, true)
        val mode = p.getString(KEY_VISUAL_MODE, VISUAL_BAR) ?: VISUAL_BAR
        visualMode.value = if (mode == VISUAL_BALLOONS) VISUAL_BAR else mode
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

    fun setPopupSecs(v: Int) {
        val c = v.coerceIn(MIN_POPUP_SECS, MAX_POPUP_SECS)
        popupSecs.intValue = c
        prefs(appCtx).edit().putInt(KEY_POPUP_SECS, c).apply()
    }

    fun setBarHeight(v: Int) {
        val c = v.coerceIn(MIN_BAR_HEIGHT, MAX_BAR_HEIGHT)
        barHeight.intValue = c
        prefs(appCtx).edit().putInt(KEY_BAR_HEIGHT, c).apply()
    }

    fun setBarOpacity(v: Int) {
        val c = v.coerceIn(MIN_OPACITY, MAX_OPACITY)
        barOpacity.intValue = c
        prefs(appCtx).edit().putInt(KEY_OPACITY, c).apply()
    }

    fun setItemFrameEnabled(v: Boolean) {
        itemFrameEnabled.value = v
        prefs(appCtx).edit().putBoolean(KEY_ITEM_FRAME, v).apply()
    }

    fun setSimulationEnabled(v: Boolean) {
        simulationEnabled.value = v
        prefs(appCtx).edit().putBoolean(KEY_SIMULATION, v).apply()
    }

    fun setSectionX(index: Int, v: Int) {
        val key = when(index) {
            0 -> KEY_SEC0_X
            1 -> KEY_SEC1_X
            2 -> KEY_SEC2_X
            3 -> KEY_SEC3_X
            else -> return
        }
        when(index) {
            0 -> sec0X.intValue = v
            1 -> sec1X.intValue = v
            2 -> sec2X.intValue = v
            3 -> sec3X.intValue = v
        }
        prefs(appCtx).edit().putInt(key, v).apply()
    }

    fun setLaunchOnBoot(v: Boolean) {
        launchOnBoot.value = v
        prefs(appCtx).edit().putBoolean(KEY_BOOT, v).apply()
    }

    fun setVisualMode(v: String) {
        visualMode.value = v
        prefs(appCtx).edit().putString(KEY_VISUAL_MODE, v).apply()
    }

    fun isLaunchOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOOT, true)

    fun isOverlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY, false)

    fun mode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO

    fun secs(context: Context): Int =
        prefs(context).getInt(KEY_SECS, DEFAULT_SECS)

    fun popupSecs(context: Context): Int =
        prefs(context).getInt(KEY_POPUP_SECS, DEFAULT_POPUP_SECS)

    fun barHeight(context: Context): Int =
        prefs(context).getInt(KEY_BAR_HEIGHT, DEFAULT_BAR_HEIGHT)

    fun opacity(context: Context): Int =
        prefs(context).getInt(KEY_OPACITY, DEFAULT_OPACITY)

    fun isItemFrameEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ITEM_FRAME, false)

    fun isSimulationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SIMULATION, isEmulator())

    private fun isEmulator(): Boolean =
        android.os.Build.FINGERPRINT.startsWith("generic")
                || android.os.Build.FINGERPRINT.startsWith("unknown")
                || android.os.Build.MODEL.contains("google_sdk")
                || android.os.Build.MODEL.contains("Emulator")
                || android.os.Build.MODEL.contains("Android SDK built for x86")
                || android.os.Build.MANUFACTURER.contains("Genymotion")
                || (android.os.Build.BRAND.startsWith("generic") && android.os.Build.DEVICE.startsWith("generic"))
                || "google_sdk" == android.os.Build.PRODUCT

    fun sectionX(context: Context, index: Int): Int {
        val key = when(index) {
            0 -> KEY_SEC0_X
            1 -> KEY_SEC1_X
            2 -> KEY_SEC2_X
            3 -> KEY_SEC3_X
            else -> return 0
        }
        val def = when(index) {
            0 -> 20; 1 -> 320; 2 -> 620; 3 -> 920; else -> 0
        }
        return prefs(context).getInt(key, def)
    }

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
