package br.com.redesurftank.havaldock.data

import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.beantechs.intelligentvehiclecontrol.IIntelligentVehicleControlService
import com.beantechs.intelligentvehiclecontrol.sdk.IListener
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Conecta-se ao IntelligentVehicleControlService (SDK Beantechs) via Shizuku, obtendo o binder
 * de sistema por reflexão em android.os.ServiceManager#getService (igual ao Impulse e ao Haval Radio).
 *
 * Pré-requisitos no carro: Shizuku ativo + permissão concedida. Escrita = request("cmd.common.request.set",
 * key, value); leitura = fetchData(key) (mesmo mecanismo que o Impulse usa para HVAC/condução).
 *
 * AUTORRECUPERAÇÃO (corrige o "trava no boot do carro"): no boot frio a central reinicia o serviço
 * do veículo e/ou o Shizuku, e o binder em cache morria sem ninguém perceber — listener parava de
 * receber `onDataChanged` e a UI ficava com dado velho até reabrir o app. Agora:
 *   1. `linkToDeath` no binder: ao morrer, zera o cache e agenda reconexão com backoff.
 *   2. os listeners são LEMBRADOS e re-registrados a cada (re)conexão.
 *   3. `Shizuku.addBinderReceivedListenerSticky`: conecta assim que o Shizuku sobe no boot (sem
 *      depender de reabrir o app).
 *   4. callbacks de conexão avisam os consumidores p/ relerem o snapshot ao (re)conectar.
 */
object VehicleClient {
    private const val TAG = "HavalDock"
    private const val SERVICE_NAME = "com.beantechs.intelligentvehiclecontrol"
    private const val PKG = "br.com.redesurftank.havaldock"
    private const val ACTION_SET = "cmd.common.request.set"

    private val lock = Any()
    @Volatile private var service: IIntelligentVehicleControlService? = null

    /** Listeners lembrados (chave→quais propriedades) p/ re-registrar a cada reconexão. */
    private val registrations = LinkedHashMap<IListener, Array<String>>()
    /** Quais listeners já estão aplicados na conexão ATUAL (limpo quando o binder troca). */
    private val applied = HashSet<IListener>()
    /** Avisados quando a conexão é (re)estabelecida — p/ reler o estado inicial. */
    private val connectionListeners = ArrayList<() -> Unit>()

    private val io = Executors.newSingleThreadExecutor()
    private val reconnectExec = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var shizukuHooked = false

    private val deathRecipient = IBinder.DeathRecipient {
        // o binder já morreu (o link se desfaz sozinho); só zeramos o cache e reconectamos
        Log.w(TAG, "binder do veículo morreu — reconectando")
        synchronized(lock) {
            service = null
            applied.clear()
        }
        scheduleReconnect(0)
    }

    private val getServiceMethod by lazy {
        runCatching {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
        }.onFailure { Log.w(TAG, "Reflexão de ServiceManager falhou", it) }.getOrNull()
    }

    /**
     * Instala os hooks do Shizuku (uma vez). Chamar de App.onCreate p/ que no boot a conexão se
     * estabeleça assim que o Shizuku ficar disponível, sem precisar reabrir o app.
     */
    fun init() {
        if (shizukuHooked) return
        shizukuHooked = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { scheduleReconnect(0) }
            Shizuku.addBinderDeadListener {
                synchronized(lock) { service = null; applied.clear() }
            }
        }.onFailure { Log.w(TAG, "hooks do Shizuku falharam", it) }
    }

    fun isShizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun ensureConnected(): Boolean {
        var justConnected = false
        synchronized(lock) {
            if (service != null) return true
            if (!isShizukuReady()) return false
            val raw = runCatching { getServiceMethod?.invoke(null, SERVICE_NAME) as? IBinder }
                .onFailure { Log.e(TAG, "Falha ao obter o binder do veículo", it) }
                .getOrNull() ?: return false
            val svc = IIntelligentVehicleControlService.Stub.asInterface(ShizukuBinderWrapper(raw))
            runCatching { svc.asBinder().linkToDeath(deathRecipient, 0) }
                .onFailure { Log.w(TAG, "linkToDeath falhou", it) }
            service = svc
            applied.clear()
            applyRegistrationsLocked()
            justConnected = true
        }
        if (justConnected) notifyConnected()
        return true
    }

    fun getData(key: String): String? = runCatching {
        ensureConnected(); service?.fetchData(key)
    }.onFailure { Log.e(TAG, "getData $key", it) }.getOrNull()

    /** Escreve uma propriedade (ação de set padrão do SDK). */
    fun set(key: String, value: String) {
        // chaves de HVAC abririam o painel do ar (com.beantechs.hvac) -> suprime em volta da escrita
        val hvac = HvacPanel.isHvacKey(key)
        if (hvac) HvacPanel.beforeWrite()
        runCatching {
            if (ensureConnected()) service?.request(ACTION_SET, key, value)
        }.onFailure { Log.e(TAG, "set $key=$value", it) }
        if (hvac) HvacPanel.afterWrite()
    }

    fun registerListener(keys: List<String>, listener: IListener) {
        val arr = keys.toTypedArray()
        synchronized(lock) { registrations[listener] = arr }
        runCatching {
            if (ensureConnected()) synchronized(lock) { applyOneLocked(listener, arr) }
        }.onFailure { Log.e(TAG, "registerListener", it) }
    }

    fun unregisterListener(listener: IListener) {
        synchronized(lock) {
            registrations.remove(listener)
            applied.remove(listener)
            runCatching { service?.unRegisterDataChangedListener(PKG, listener) }
                .onFailure { Log.e(TAG, "unregisterListener", it) }
        }
    }

    /** Avisado (em thread de IO) quando a conexão é (re)estabelecida. Idempotente. */
    fun addConnectionListener(cb: () -> Unit) {
        synchronized(lock) { if (cb !in connectionListeners) connectionListeners.add(cb) }
    }

    fun removeConnectionListener(cb: () -> Unit) {
        synchronized(lock) { connectionListeners.remove(cb) }
    }

    // ---- internos ----

    /** Re-registra todos os listeners lembrados na conexão atual. Chamar com [lock] em mãos. */
    private fun applyRegistrationsLocked() {
        registrations.forEach { (l, k) -> applyOneLocked(l, k) }
    }

    /** Aplica um listener na conexão atual (no-op se já aplicado). Chamar com [lock] em mãos. */
    private fun applyOneLocked(listener: IListener, keys: Array<String>) {
        val svc = service ?: return
        if (listener in applied) return
        runCatching {
            svc.addListenerKey(PKG, keys)
            svc.registerDataChangedListener(PKG, listener)
        }.onSuccess { applied.add(listener) }
            .onFailure { Log.e(TAG, "applyListener", it) }
    }

    private fun notifyConnected() {
        val cbs = synchronized(lock) { connectionListeners.toList() }
        cbs.forEach { cb -> io.execute { runCatching { cb() } } }
    }

    /** Reconexão com backoff (1s,2s,4s,…,30s). Para quando conecta ou após esgotar as tentativas. */
    private fun scheduleReconnect(attempt: Int) {
        val hasWork = synchronized(lock) { registrations.isNotEmpty() || connectionListeners.isNotEmpty() }
        if (!hasWork) return
        val delay = minOf(30_000L, 1000L shl attempt.coerceIn(0, 5))
        runCatching {
            reconnectExec.schedule({
                if (!ensureConnected() && attempt < 12) scheduleReconnect(attempt + 1)
            }, delay, TimeUnit.MILLISECONDS)
        }
    }
}
