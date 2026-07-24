package br.com.redesurftank.havaldock.data

import android.content.Context
import androidx.annotation.DrawableRes
import br.com.redesurftank.havaldock.R
import java.util.Locale

/** Chaves do IntelligentVehicleControlService (do CarConstants do Impulse / nota do vault). */
object DockKeys {
    const val DRIVER_TEMP = "car.hvac.driver_temperature"
    const val PASS_TEMP = "car.hvac.pass_temperature"
    const val FRONT_TEMP_RANGE = "car.hvac.front_temperature_range"
    const val FAN_SPEED = "car.hvac.fan_speed"
    const val FAN_RANGE = "car.hvac.fan_speed_range"
    const val AUTO = "car.hvac.auto_enable"
    const val SYNC = "car.hvac.sync_enable"
    const val CYCLE_MODE = "car.hvac.cycle_mode"
    const val BLOWER_MODE = "car.hvac.blower_mode"
    const val FRONT_DEFROST = "car.hvac.front_defrost_enable"
    const val POWER_MODE = "car.hvac.power_mode"
    const val AC_ENABLE = "car.hvac.ac_enable"
    const val DRIVER_SEAT_VENT = "car.comfort_setting.driver_seat_ventilation_level"
    const val PASS_SEAT_VENT = "car.comfort_setting.passenger_seat_ventilation_level"
    const val SEAT_VENT_MAX = "car.comfort_setting.seat_ventilation_max_level"
    const val DRIVE_MODE = "car.drive_setting.drive_mode"
    const val STEER_MODE = "car.drive_setting.steering_wheel_assist_mode"
    const val REGEN_LEVEL = "car.ev_setting.energy_recovery_level"
    const val MEDIA_VOLUME = "sys.settings.audio.media_volume"
    const val MEDIA_VOLUME_RANGE = "sys.settings.audio.media_volume_range"

    const val CAR_BASIC_INSIDE_TEMP = "car.basic.inside_temp"
    const val CAR_BASIC_OUTSIDE_TEMP = "car.basic.outside_temp"
    const val CAR_CONFIGURE_OUTSIDE_TEMP_DISPLAY = "car.configure.outside_temp_display"
    const val CAR_EV_SETTING_POWER_MODEL_CONFIG = "car.ev_setting.power_model_config" //0=HEV, 1=Prior.EV, 3=EV
    const val HEV_SOC_TARGET = "car.ev_setting.power_reserve_config"
}

/** Cores do tema v2 (ARGB int — sem dependência de android no data layer). */
object DockColors {
    const val CYAN = 0xFF2DE0F0.toInt()
    const val GREEN = 0xFF36E05A.toInt()
    const val RED = 0xFFFF4D4D.toInt()
    const val AMBER = 0xFFFFC23C.toInt()
    const val WHITE = 0xFFEEF4F8.toInt()
}

private fun parseMax(s: String?): Double? {
    if (s == null) return null
    return Regex("-?\\d+(\\.\\d+)?").findAll(s).mapNotNull { it.value.toDoubleOrNull() }.maxOrNull()
}

/** Estado de render lido do veículo (campos usados variam por tipo de controle). */
data class RenderState(
    val text: String? = null,
    val ratio: Float = 0f,
    val on: Boolean = false,
    val color: Int = DockColors.CYAN,
    val bars: Int = 0,
    @DrawableRes val icon: Int = 0,
)

sealed class Control(val id: String, val section: Int, val label: String) {
    abstract fun render(): RenderState
}

/** Temperatura: exibida com setas ‹ ›; escreve o valor float direto (ex.: "22.5"). */
class Temp(id: String, section: Int, label: String, val key: String,
          val min: Double, val max: Double, val step: Double, val rangeKey: String?) :
    Control(id, section, label) {
    override fun render() = RenderState(text = read()?.let { fmt(it) + "°" } ?: "—°")
    private fun read() = VehicleClient.getData(key)?.trim()?.toDoubleOrNull()
    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    fun nudge(dir: Int) {
        val cur = read() ?: return
        val hi = rangeKey?.let { parseMax(VehicleClient.getData(it)) } ?: max
        VehicleClient.set(key, fmt((cur + dir * step).coerceIn(min, hi)))
    }
}

/**
 * Banco/ventilador: ícone + sublinhado de nível. Toque incrementa e dá a volta; quando [picker]=true
 * o toque abre um popup pra escolher o nível direto (min..max) em vez de ciclar.
 */
class Level(id: String, section: Int, label: String, @DrawableRes val icon: Int,
           val key: String, val max: Int, val rangeKey: String?, val min: Int = 0,
           val picker: Boolean = false) :
    Control(id, section, label) {
    fun value() = VehicleClient.getData(key)?.trim()?.toIntOrNull() ?: 0
    fun hi() = rangeKey?.let { parseMax(VehicleClient.getData(it))?.toInt() } ?: max
    override fun render(): RenderState {
        val m = hi().coerceAtLeast(1)
        return RenderState(ratio = value().coerceIn(0, m).toFloat() / m)
    }
    /** Toque incrementa; ao chegar no máximo, volta pro mínimo (fan: 1..7→1; banco: 0..3→0). */
    fun cycle() {
        val m = hi().coerceAtLeast(1)
        val v = value()
        val next = if (v >= m) min else (v + 1).coerceAtLeast(min)
        VehicleClient.set(key, next.toString())
    }
    /** Escolha direta de nível (usada pelo popup). */
    fun setLevel(v: Int) = VehicleClient.set(key, v.coerceIn(min, hi().coerceAtLeast(min)).toString())
}

/** Volume: ícone + sublinhado; abre popup vertical com −/+ e arraste. */
class Volume(id: String, section: Int, label: String, @DrawableRes val icon: Int,
            val key: String, val max: Int, val rangeKey: String?) :
    Control(id, section, label) {
    fun value() = VehicleClient.getData(key)?.trim()?.toIntOrNull() ?: 0
    fun hi() = (rangeKey?.let { parseMax(VehicleClient.getData(it))?.toInt() } ?: max).coerceAtLeast(1)
    override fun render() = RenderState(ratio = value().coerceIn(0, hi()).toFloat() / hi(), text = value().toString())
    fun set(v: Int) = VehicleClient.set(key, v.coerceIn(0, hi()).toString())
}

/**
 * Uma opção de fluxo de ar: ícone + o que escrever. Quase todas escrevem `blower_mode` (0..3);
 * o desembaçador dianteiro (`defrost=true`) é uma propriedade SEPARADA (`front_defrost_enable` 0/1).
 */
data class AirflowOption(val value: String, val label: String, @DrawableRes val icon: Int,
                        val defrost: Boolean = false)

/**
 * Fluxo de ar: o dock mostra o ícone do modo ATUAL; o toque abre um popup com os modos e o
 * escolhido vira o visível. Mistura duas propriedades — direção do ar (`blower_mode`: 0=Rosto,
 * 1=Rosto/Pés, 2=Pés, 3=Vidro/Pés, do Impulse/HAVAL_6984) e o desembaçador dianteiro
 * (`front_defrost_enable` 0/1). Seleção é exclusiva: escolher uma direção desliga o desembaçador;
 * escolher o desembaçador o liga. ⚠️ valores e a escrita do defrost: confirmar AO VIVO neste carro.
 */
class Airflow(id: String, section: Int, label: String, val key: String, val defrostKey: String,
             val options: List<AirflowOption>) : Control(id, section, label) {
    private fun blower(): String? = VehicleClient.getData(key)?.trim()
    private fun defrostOn(): Boolean = VehicleClient.getData(defrostKey)?.trim() == "1"
    /** Opção atualmente ativa: se o desembaçador está ligado, ele vence; senão, o blower_mode. */
    fun currentOption(): AirflowOption {
        if (defrostOn()) return options.firstOrNull { it.defrost } ?: options.first()
        val b = blower()
        return options.firstOrNull { !it.defrost && it.value == b } ?: options.first()
    }
    override fun render() = RenderState(icon = currentOption().icon)
    fun select(opt: AirflowOption) {
        if (opt.defrost) {
            VehicleClient.set(defrostKey, "1")
        } else {
            VehicleClient.set(defrostKey, "0")
            VehicleClient.set(key, opt.value)
        }
    }
}

/** Toggle de texto (MAX / AUTO / SYNC): on em ciano + sublinhado. */
class TxtToggle(id: String, section: Int, label: String, val key: String) :
    Control(id, section, label) {
    fun isOn() = VehicleClient.getData(key)?.trim() == "1"
    override fun render() = RenderState(on = isOn())
    fun flip() = VehicleClient.set(key, if (isOn()) "0" else "1")
}

/** Toggle de ícone (recirculador): troca o ícone por estado. Neste carro cycle_mode 0=recirc, 1=externo. */
class IconToggle(id: String, section: Int, label: String, @DrawableRes val iconOn: Int,
                @DrawableRes val iconOff: Int, val key: String, val onV: String, val offV: String) :
    Control(id, section, label) {
    fun isOn() = VehicleClient.getData(key)?.trim() == onV
    override fun render() = RenderState(on = isOn(), icon = if (isOn()) iconOn else iconOff)
    fun flip() = VehicleClient.set(key, if (isOn()) offV else onV)
}

/** Modo (condução/direção): ícone + label colorido por estado; toque abre seleção. */
class Mode(id: String, section: Int, label: String, @DrawableRes val icon: Int,
          val key: String, val order: List<Int>, val labels: Map<Int, String>, val colors: Map<Int, Int>,
          val hevKey: String? = null, val hevOptions: List<HevSaveOption> = emptyList()) :
    Control(id, section, label) {
    fun cur() = VehicleClient.getData(key)?.trim()?.toIntOrNull()
    fun curHevSoc() = hevKey?.let { VehicleClient.getData(it)?.trim() }

    override fun render(): RenderState {
        val v = cur()
        var txt = (labels[v] ?: "—").uppercase()
        if (v == 0) { // HEV
            curHevSoc()?.let { txt += " $it%" }
        }
        return RenderState(text = txt, color = colors[v] ?: DockColors.CYAN)
    }

    fun select(mode: Int, soc: String? = null) {
        VehicleClient.set(key, mode.toString())
        if (mode == 0 && soc != null && hevKey != null) {
            VehicleClient.set(hevKey, soc)
        }
    }

    fun next() {
        val idx = order.indexOf(cur())
        select(order[(idx + 1).mod(order.size)])
    }
}

data class HevSaveOption(val value: String, val label: String)

/** Informação simples (apenas leitura): ícone + valor formatado. */
class Info(id: String, section: Int, label: String, @DrawableRes val icon: Int, val key: String) :
    Control(id, section, label) {
    override fun render(): RenderState {
        val raw = VehicleClient.getData(key)?.trim() ?: "—"
        // Tenta extrair apenas o número caso venha com sufixos tipo "25C"
        val clean = Regex("-?\\d+(\\.\\d+)?").find(raw)?.value ?: raw
        return RenderState(text = clean + "°", color = DockColors.WHITE, icon = icon)
    }
}

/** Regeneração: raio colorido por nível (verde=Baixo, amarelo=Normal, vermelho=Alto) + barras. */
class Regen(id: String, section: Int, label: String, @DrawableRes val icon: Int,
           val key: String, val order: List<Int>) :
    Control(id, section, label) {
    // value -> (barras, cor): 2=Baixo(1,verde), 0=Normal(2,amarelo), 1=Alto(3,vermelho)
    private val map = mapOf(
        2 to Pair(1, DockColors.GREEN),
        0 to Pair(2, DockColors.AMBER),
        1 to Pair(3, DockColors.RED),
    )
    private fun cur() = VehicleClient.getData(key)?.trim()?.toIntOrNull()
    override fun render(): RenderState {
        val (bars, color) = map[cur()] ?: Pair(0, DockColors.GREEN)
        return RenderState(bars = bars, color = color)
    }
    fun next() {
        val idx = order.indexOf(cur())
        VehicleClient.set(key, order[(idx + 1).mod(order.size)].toString())
    }
}

/** Estado persistente do Max A/C (não há flag nativa nesse carro — guardamos local). */
object MaxAcStore {
    private const val PREFS = "maxac"
    private lateinit var appCtx: Context
    fun init(c: Context) { appCtx = c.applicationContext }
    private fun prefs() = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isOn() = prefs().getBoolean("on", false)
    fun setOn(v: Boolean) = prefs().edit().putBoolean("on", v).apply()
    fun saveValues(m: Map<String, String>) {
        val e = prefs().edit(); m.forEach { (k, v) -> e.putString("s_$k", v) }; e.apply()
    }
    fun savedValue(k: String): String? = prefs().getString("s_$k", null)
}

/**
 * MAX A/C — não existe flag nativa (`acmax_enable` não tem efeito nesse carro), então replicamos
 * a rotina do Impulse: ao ligar, SALVA o estado e força resfriamento máximo; ao desligar, RESTAURA.
 */
class MaxAc(id: String, section: Int, label: String) : Control(id, section, label) {
    private val keys = listOf(
        DockKeys.POWER_MODE, DockKeys.AC_ENABLE, DockKeys.FAN_SPEED,
        DockKeys.DRIVER_TEMP, DockKeys.PASS_TEMP, DockKeys.AUTO, DockKeys.SYNC, DockKeys.CYCLE_MODE,
    )
    override fun render() = RenderState(on = MaxAcStore.isOn())
    fun flip() { if (MaxAcStore.isOn()) restore() else apply() }

    private fun apply() {
        MaxAcStore.saveValues(keys.associateWith { VehicleClient.getData(it) ?: "" })
        VehicleClient.set(DockKeys.POWER_MODE, "1")
        VehicleClient.set(DockKeys.AUTO, "0")
        VehicleClient.set(DockKeys.FAN_SPEED, "7")
        VehicleClient.set(DockKeys.DRIVER_TEMP, "16.0")
        VehicleClient.set(DockKeys.PASS_TEMP, "16.0")
        VehicleClient.set(DockKeys.SYNC, "1")
        VehicleClient.set(DockKeys.CYCLE_MODE, "0")   // recirc ON (este carro: 0=recirc)
        VehicleClient.set(DockKeys.AC_ENABLE, "1")
        MaxAcStore.setOn(true)
    }

    private fun restore() {
        keys.forEach { k -> MaxAcStore.savedValue(k)?.takeIf { it.isNotEmpty() }?.let { VehicleClient.set(k, it) } }
        MaxAcStore.setOn(false)
    }
}

object DockControls {
    /** Modos de fluxo de ar, na ordem em que aparecem no popup (o último é o desembaçador). */
    val AIRFLOW_OPTIONS = listOf(
        AirflowOption("0", "Rosto", R.drawable.ic_hvac_blower_face),
        AirflowOption("1", "Rosto/Pés", R.drawable.ic_hvac_blower_feet_and_face),
        AirflowOption("2", "Pés", R.drawable.ic_hvac_blower_feet),
        AirflowOption("3", "Vidro/Pés", R.drawable.ic_hvac_blower_feet_and_defrost),
        AirflowOption("1", "Desembaçador", R.drawable.ic_hvac_blower_defrost, defrost = true),
    )

    val HEVSAVE_OPTIONS = listOf(
        HevSaveOption("20", "20%"),
        HevSaveOption("25", "25%"),
        HevSaveOption("35", "35%"),
        HevSaveOption("45", "45%"),
        HevSaveOption("55", "55%"),
    )

    val ALL: List<Control> = listOf(
        // ----- ESQUERDA (clima) -----
        Level("ventD", 0, "Ventil. motorista", R.drawable.ic_seat, DockKeys.DRIVER_SEAT_VENT, 3, DockKeys.SEAT_VENT_MAX),
        Temp("tempD", 0, "Temp. motorista", DockKeys.DRIVER_TEMP, 16.0, 32.0, 0.5, DockKeys.FRONT_TEMP_RANGE),

        //---------Espacado
        Mode("drive", 1, "Modo", R.drawable.ic_car, DockKeys.CAR_EV_SETTING_POWER_MODEL_CONFIG,
            listOf(1, 3, 0),
            mapOf(0 to "HEV", 1 to "PriorEv",3 to "EV"), //0=HEV, 1=Prior.EV, 3=EV
            mapOf(0 to DockColors.AMBER, 1 to DockColors.GREEN, 3 to DockColors.CYAN),
            hevKey = DockKeys.HEV_SOC_TARGET,
            hevOptions = HEVSAVE_OPTIONS
        ),

        // ----- CENTRO (condução) -----
        IconToggle("recirc", 2, "Recirculador", R.drawable.ic_recirc_closed, R.drawable.ic_recirc_open, DockKeys.CYCLE_MODE, "0", "1"),
        Info("tempIn", 2, "Interna", R.drawable.ic_thermo, DockKeys.CAR_BASIC_INSIDE_TEMP),
        //MaxAc("max", 0, "MAX"),
        //TxtToggle("sync", 0, "SYNC", DockKeys.SYNC),
        Level("fan", 2, "Veloc. ar-cond.", R.drawable.ic_fan, DockKeys.FAN_SPEED, 7, DockKeys.FAN_RANGE, min = 1, picker = true),
        Airflow("airflow", 2, "Fluxo de ar", DockKeys.BLOWER_MODE, DockKeys.FRONT_DEFROST, AIRFLOW_OPTIONS),
        Info("tempOut", 2, "Externa", R.drawable.ic_thermo, DockKeys.CAR_BASIC_OUTSIDE_TEMP),
        TxtToggle("auto", 2, "AUTO", DockKeys.AUTO),
        /* Mode("steer", 1, "Modo direção", R.drawable.ic_steer, DockKeys.STEER_MODE,
            listOf(2, 0, 1),
            mapOf(2 to "Conforto", 0 to "Normal", 1 to "Esportiva"),
            mapOf(2 to DockColors.CYAN, 0 to DockColors.WHITE, 1 to DockColors.RED)),
        */
        //Regen("regen", 1, "Regeneração", R.drawable.ic_bolt, DockKeys.REGEN_LEVEL, listOf(2, 0, 1)),
        // ----- DIREITA (passageiro + volume) -----
        Volume("vol", 3, "Volume rádio", R.drawable.ic_volume, DockKeys.MEDIA_VOLUME, 30, DockKeys.MEDIA_VOLUME_RANGE),
        Temp("tempP", 3, "Temp. passageiro", DockKeys.PASS_TEMP, 16.0, 32.0, 0.5, DockKeys.FRONT_TEMP_RANGE),
        Level("ventP", 3, "Ventil. passageiro", R.drawable.ic_seat, DockKeys.PASS_SEAT_VENT, 3, DockKeys.SEAT_VENT_MAX),
    )

    val MONITORED: List<String> = listOf(
        DockKeys.DRIVER_TEMP, DockKeys.PASS_TEMP, DockKeys.FAN_SPEED, DockKeys.DRIVER_SEAT_VENT,
        DockKeys.PASS_SEAT_VENT, DockKeys.AUTO, DockKeys.SYNC, DockKeys.CYCLE_MODE,
        DockKeys.BLOWER_MODE, DockKeys.FRONT_DEFROST,
        DockKeys.CAR_EV_SETTING_POWER_MODEL_CONFIG,
        DockKeys.CAR_BASIC_INSIDE_TEMP, DockKeys.CAR_BASIC_OUTSIDE_TEMP,
        //DockKeys.STEER_MODE,
        //DockKeys.REGEN_LEVEL,
        DockKeys.MEDIA_VOLUME,
    )
}
