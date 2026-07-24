package br.com.redesurftank.havaldock.data

/**
 * Atalho de projeção (CarPlay / Android Auto).
 *
 * Detecção robusta lendo `am stack list` via [ShizukuShell]:
 *  - [foregroundProjection]: QUAL projeção está no topo do Display 0 (foco), por like-matching
 *    (carplay/zlink -> CarPlay; androidauto/gearhead -> AA). NÃO usa mera presença do pacote,
 *    porque o app do Android Auto fica sempre rodando (tarefa "fantasma") e dava falso-positivo.
 *  - [carPlayConnected]: CarPlay conectado mas em background (processo rodando) — p/ o botão
 *    aparecer mesmo fora da projeção.
 *
 * Botão (OverlayService): fora da projeção mostra o ícone da marca e ABRE; na projeção mostra o
 * carro e volta pra TELA DA CENTRAL (HOME).
 */
object ProjectionLauncher {
    const val CARPLAY_PKG = "com.ts.carplay.app"
    private const val CARPLAY_ACTIVITY = "com.ts.carplay.app.ui.display.view.CarPlayDisplayActivity"
    const val AA_PKG = "com.ts.androidauto.app"
    private const val AA_ACTIVITY = "com.ts.androidauto.app.display.AapActivity"

    private val taskRe = Regex("""taskId=\d+:\s*([a-zA-Z0-9._]+)/""")
    private val dispRe = Regex("""displayId=(\d+)""")

    private fun stackList(): String? = ShizukuShell.exec("am", "stack", "list")

    /** Pacote no topo do Display 0 (string crua) ou null. */
    private fun topOnDisplay0(out: String): String? {
        var cur: Int? = null
        for (line in out.lines()) {
            dispRe.find(line)?.let { cur = it.groupValues[1].toIntOrNull() }
            if (cur == 0) taskRe.find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** Pacote no topo do Display 0 agora (cru), ou null. */
    fun topPackage(): String? {
        val out = stackList() ?: return null
        return topOnDisplay0(out)
    }

    /** Classifica um pacote como projeção (canônico) por like-matching, ou null. */
    fun classifyProjection(pkg: String?): String? {
        val t = (pkg ?: return null).lowercase()
        return when {
            t.contains("carplay") || t.contains("zlink") -> CARPLAY_PKG
            t.contains("androidauto") || t.contains("gearhead") -> AA_PKG
            else -> null
        }
    }

    /** Traz um app pro foco pelo seu componente de launcher (volta pra última tela dele).
     *  Usa `am start` (confiável via Shizuku) — o `monkey` não roda bem pelo newProcess. */
    fun openComponent(component: String) {
        ShizukuShell.exec("am", "start", "-f", "0x10000000", "-n", component)
    }

    /** CarPlay conectado (processo rodando), mesmo em background. */
    fun carPlayConnected(): Boolean = !ShizukuShell.exec("pidof", CARPLAY_PKG).isNullOrBlank()

    /**
     * Traz a projeção pro foco no Display 0. Flag de cold-start aceito pelo contrato do Impulse
     * (0x14000000); NÃO usa `--display 0` (que caía em instância stale no cluster).
     */
    fun openProjection(pkg: String) {
        val act = if (pkg == AA_PKG) AA_ACTIVITY else CARPLAY_ACTIVITY
        ShizukuShell.exec("am", "start", "-f", "0x14000000", "-n", "$pkg/$act")
    }

    /** Volta pra tela da central (launcher/home). */
    fun goHome() {
        ShizukuShell.exec("am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    }
}
