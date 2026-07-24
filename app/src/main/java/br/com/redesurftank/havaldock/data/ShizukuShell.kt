package br.com.redesurftank.havaldock.data

import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.FileInputStream

/**
 * Executa comandos de shell pelo Shizuku (uid shell), via IShizukuService#newProcess — mesmo
 * mecanismo do Impulse (ShizukuUtils). As interfaces moe.shizuku.server.* vêm da lib dev.rikka.shizuku:api
 * que o dock já usa. Usado para `pm`/`am` (suprimir o pop-up do painel de HVAC).
 */
object ShizukuShell {
    private const val TAG = "HavalDock"

    private fun service(): IShizukuService? =
        runCatching { Shizuku.getBinder()?.let { IShizukuService.Stub.asInterface(it) } }.getOrNull()

    /** Roda o comando, espera terminar e devolve o stdout (trim). null em erro. */
    fun exec(vararg cmd: String): String? = runCatching {
        val svc = service() ?: return null
        val proc: IRemoteProcess = svc.newProcess(cmd, null, null) ?: return null
        try {
            // fecha stdin imediatamente (evita vazar FD no servidor do Shizuku)
            runCatching { proc.outputStream?.close() }
            // drena stderr numa thread pra não travar
            val errPfd = proc.errorStream
            val errThread = errPfd?.let { pfd ->
                Thread {
                    runCatching { FileInputStream(pfd.fileDescriptor).use { it.readBytes() } }
                    runCatching { pfd.close() }
                }.also { it.start() }
            }
            val out = proc.inputStream?.let { pfd ->
                try { FileInputStream(pfd.fileDescriptor).use { String(it.readBytes()) } }
                finally { runCatching { pfd.close() } }
            } ?: ""
            errThread?.join(2000)
            proc.waitFor()
            out.trim()
        } finally {
            runCatching { proc.destroy() }
        }
    }.onFailure { Log.e(TAG, "shell ${cmd.joinToString(" ")}", it) }.getOrNull()
}
