package br.com.redesurftank.havaldock.data

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Suprime o pop-up do painel de ar quando o dock escreve uma propriedade de HVAC.
 *
 * Ao escrever, a central abre o app `com.beantechs.hvac` (a tela do ar) como "feedback". Para evitar
 * isso, replicamos o truque do Impulse: antes da escrita, DESABILITA + força-para o app do painel;
 * depois da escrita, RELIGA com um pequeno atraso. Se a tela do ar já estiver em primeiro plano
 * (o usuário a abriu), NÃO mexe — senão fecharia na cara dele.
 *
 * Observação: o controle de hardware do ar passa pelo IntelligentVehicleControlService (binder), não
 * por esse app — então desabilitar o painel não afeta o funcionamento do ar.
 */
object HvacPanel {
    private const val TAG = "HavalDock"
    private const val PKG = "com.beantechs.hvac"
    private const val RESUME_DELAY_MS = 300L

    /** Chaves cuja escrita abre o painel — só essas disparam a supressão (espelha o Impulse). */
    private val SUSPEND_KEYS = setOf(
        DockKeys.DRIVER_TEMP, DockKeys.PASS_TEMP, DockKeys.FAN_SPEED, DockKeys.AUTO, DockKeys.SYNC,
        DockKeys.CYCLE_MODE, DockKeys.BLOWER_MODE, DockKeys.FRONT_DEFROST, DockKeys.POWER_MODE, DockKeys.AC_ENABLE,
    )

    fun isHvacKey(key: String): Boolean = key in SUSPEND_KEYS

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private var suspended = false
    private var resumeFuture: ScheduledFuture<*>? = null

    /** Antes de escrever uma chave de HVAC. */
    fun beforeWrite() {
        synchronized(lock) {
            resumeFuture?.cancel(false); resumeFuture = null
            if (suspended) return
            if (isHvacForeground()) {
                Log.w(TAG, "painel de ar em foreground — não suprime")
                return
            }
            ShizukuShell.exec("pm", "disable-user", "--user", "0", PKG)
            ShizukuShell.exec("am", "force-stop", PKG)
            suspended = true
            SystemClock.sleep(150)   // garante que parou antes de mandar o comando do carro
        }
    }

    /** Depois de escrever: agenda a religação do painel. */
    fun afterWrite() {
        synchronized(lock) {
            resumeFuture?.cancel(false)
            resumeFuture = exec.schedule({ resumeNow() }, RESUME_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun resumeNow() {
        synchronized(lock) {
            if (!suspended) return
            ShizukuShell.exec("pm", "enable", PKG)
            suspended = false
            resumeFuture = null
        }
    }

    /** Rede de segurança: garante o painel habilitado (ex.: ao subir o serviço, caso tenha ficado off). */
    fun ensureEnabled() {
        exec.execute {
            synchronized(lock) {
                if (suspended) return@execute
                ShizukuShell.exec("pm", "enable", PKG)
            }
        }
    }

    private fun isHvacForeground(): Boolean {
        val out = ShizukuShell.exec(
            "sh", "-c",
            "dumpsys activity activities | grep -E 'mResumedActivity|mFocusedApp' | grep $PKG"
        ) ?: return false
        return out.contains(PKG)
    }
}
