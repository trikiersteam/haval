package br.com.redesurftank.havaldock

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havaldock.data.DockControls
import br.com.redesurftank.havaldock.data.SettingsStore
import br.com.redesurftank.havaldock.data.VehicleClient
import br.com.redesurftank.havaldock.update.UpdateManager
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import rikka.shizuku.Shizuku

private val Accent = Color(0xFF19E3B1)
private val AccentSoft = Color(0xFF5FF0CF)
private val Bg = Color(0xFF0B0E14)
private val CardBg = Color(0xFF161B24)
private val Muted = Color(0xFF9099A8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = CardBg, background = Bg)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { SettingsScreen() }
            }
        }
    }

    private fun requestOverlay() {
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun requestShizuku() {
        runCatching { if (!Shizuku.isPreV11()) Shizuku.requestPermission(1001) }
    }

    @Composable
    private fun SettingsScreen() {
        var tick by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { while (true) { tick++; delay(1500) } }

        @Suppress("UNUSED_EXPRESSION") tick // recompõe periodicamente p/ refletir permissões
        val overlayGranted = Settings.canDrawOverlays(this)
        val shizukuReady = VehicleClient.isShizukuReady()

        val overlayEnabled by SettingsStore.overlayEnabled
        val visualMode by SettingsStore.visualMode
        val mode by SettingsStore.visibilityMode
        val secs by SettingsStore.autoHideSecs
        val boot by SettingsStore.launchOnBoot

        var monitorEnabled by remember { mutableStateOf(false) }
        val debugValues = remember { mutableStateMapOf<String, MonitorValue>() }

        LaunchedEffect(monitorEnabled) {
            if (monitorEnabled) {
                while (true) {
                    DockControls.DEBUG_VARIABLES.values.forEach { vars ->
                        vars.forEach { (label, key) ->
                            val newVal = VehicleClient.getData(key) ?: "—"
                            val old = debugValues[label]
                            if (old == null || old.current != newVal) {
                                debugValues[label] = MonitorValue(
                                    current = newVal,
                                    previous = old?.current,
                                    lastChanged = System.currentTimeMillis()
                                )
                            }
                        }
                    }
                    delay(1500)
                }
            }
        }


        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("Haval Dock", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Configurações da barra inferior", color = Muted, fontSize = 15.sp)

            // ---- permissões ----
            SectionCard("Permissões") {
                StatusRow("Shizuku", if (shizukuReady) "OK" else "Pendente", shizukuReady) {
                    if (!shizukuReady) requestShizuku()
                }
                Spacer(Modifier.height(10.dp))
                StatusRow("Sobrepor à tela", if (overlayGranted) "OK" else "Pendente", overlayGranted) {
                    if (!overlayGranted) requestOverlay()
                }
            }

            // ---- barra ---- 
            SectionCard("Interface Visual") {
                Text("Tipo de Visualização", color = Color.White, fontSize = 16.sp)
                Text("Escolha entre a barra compacta ou o painel completo.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Segmented(
                    options = listOf(
                        "Barra" to SettingsStore.VISUAL_BAR,
                        "Dashboard" to SettingsStore.VISUAL_DASHBOARD,
                        "Light" to SettingsStore.VISUAL_DASHBOARD_LIGHT
                    ),
                    selected = visualMode
                ) {
                    SettingsStore.setVisualMode(it)
                    // Reinicia o serviço para aplicar a mudança de layout se necessário
                    if (SettingsStore.overlayEnabled.value) {
                        OverlayService.stop(this@MainActivity)
                        OverlayService.start(this@MainActivity)
                    }
                }

                if (visualMode == SettingsStore.VISUAL_DASHBOARD_LIGHT) {
                    Spacer(Modifier.height(14.dp))
                    val lightFloating by SettingsStore.lightFloatingEnabled
                    RowSwitch("Flutuante sem borda", "Remove fundo e bordas dos cards no modo Light.", lightFloating) {
                        SettingsStore.setLightFloatingEnabled(it)
                        if (SettingsStore.overlayEnabled.value) {
                            OverlayService.stop(this@MainActivity)
                            OverlayService.start(this@MainActivity)
                        }
                    }
                }
            }

            SectionCard("Barra inferior") {
                RowSwitch("Barra ligada", "Mostra a toolbar por cima da central.", overlayEnabled) { on ->
                    if (on) {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            SettingsStore.setOverlayEnabled(true); OverlayService.start(this@MainActivity)
                        } else requestOverlay()
                    } else {
                        SettingsStore.setOverlayEnabled(false); OverlayService.stop(this@MainActivity)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Visibilidade da Barra", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Segmented(
                    options = listOf("Sempre visível" to SettingsStore.MODE_ALWAYS, "Auto-ocultar" to SettingsStore.MODE_AUTO),
                    selected = mode
                ) { SettingsStore.setVisibilityMode(it) }

                val isBarMode = visualMode == SettingsStore.VISUAL_BAR
                val isAutoMode = mode == SettingsStore.MODE_AUTO

                if (isAutoMode) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ocultar após", color = Color.White, fontSize = 16.sp)
                            Text("Reinicia a cada toque na barra.", color = Muted, fontSize = 13.sp)
                        }
                        Stepper("$secs s", enabled = true) { d -> SettingsStore.setAutoHideSecs(secs + d) }
                    }

                    Spacer(Modifier.height(14.dp))
                    val pSecs by SettingsStore.popupSecs
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Tempo do menu", color = Color.White, fontSize = 16.sp)
                            Text(if (pSecs == 0) "Menu ficará visível até o clique." else "Auto-ocultar menus após inatividade.", color = Muted, fontSize = 13.sp)
                        }
                        Stepper("$pSecs s", enabled = true) { d -> SettingsStore.setPopupSecs(pSecs + d) }
                    }
                }

                Spacer(Modifier.height(14.dp))
                val bHeight by SettingsStore.barHeight
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Altura da barra", color = if (isBarMode) Color.White else Muted, fontSize = 16.sp)
                        Text("Ajuste a altura da barra inferior.", color = Muted, fontSize = 13.sp)
                    }
                    Stepper("$bHeight dp", enabled = isBarMode) { d -> SettingsStore.setBarHeight(bHeight + d) }
                }

                Spacer(Modifier.height(14.dp))
                val bOpacity by SettingsStore.barOpacity
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Opacidade da barra", color = if (isBarMode) Color.White else Muted, fontSize = 16.sp)
                        Text("Muda a transparência do fundo.", color = Muted, fontSize = 13.sp)
                    }
                    Stepper("$bOpacity %", enabled = isBarMode) { d -> SettingsStore.setBarOpacity(bOpacity + d) }
                }

                Spacer(Modifier.height(14.dp))
                val itemFrame by SettingsStore.itemFrameEnabled
                RowSwitch("Moldura nos itens da barra inferior", "Agrupa os ícones em um balão arredondado.", itemFrame, enabled = isBarMode) {
                    SettingsStore.setItemFrameEnabled(it)
                }
                
                Spacer(Modifier.height(14.dp))
                val simulation by SettingsStore.simulationEnabled
                RowSwitch("Modo Simulação", "Ativado automaticamente em ambiente de desenvolvimento.", simulation, enabled = false) {
                    SettingsStore.setSimulationEnabled(it)
                }
            }

            // ---- posicionamento ----
            if (visualMode == SettingsStore.VISUAL_BAR) {
                SectionCard("Posicionamento das Seções") {
                    val dm = resources.displayMetrics
                    val screenWidthDp = dm.widthPixels / dm.density

                    val sec0 by SettingsStore.sec0X
                    val sec1 by SettingsStore.sec1X
                    val sec2 by SettingsStore.sec2X
                    val sec3 by SettingsStore.sec3X

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionPosRow("Motorista", sec0, screenWidthDp) { SettingsStore.setSectionX(0, it) }
                        SectionPosRow("Centro", sec1, screenWidthDp) { SettingsStore.setSectionX(1, it) }
                        SectionPosRow("Bateria", sec2, screenWidthDp) { SettingsStore.setSectionX(2, it) }
                        SectionPosRow("Passageiro", sec3, screenWidthDp) { SettingsStore.setSectionX(3, it) }
                    }
                }
            }

            // ---- boot ----
            SectionCard("Inicialização") {
                RowSwitch("Religar ao ligar o carro", "Mostra a barra automaticamente no boot.", boot) {
                    SettingsStore.setLaunchOnBoot(it)
                }
            }

            // ---- monitor ----
            SectionCard("Monitor de Variáveis") {
                RowSwitch("Monitorar variáveis", "Lê valores em tempo real do sistema.", monitorEnabled) {
                    monitorEnabled = it
                }
                if (monitorEnabled) {
                    Spacer(Modifier.height(14.dp))
                    Column {
                        DockControls.DEBUG_VARIABLES.forEach { (category, vars) ->
                            Text(
                                category,
                                color = Accent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                            val items = vars.toList()
                            val chunk = (items.size + 2) / 3
                            val col1 = items.take(chunk)
                            val col2 = items.drop(chunk).take(chunk)
                            val col3 = items.drop(chunk * 2)

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f)) {
                                    col1.forEach { (label, _) ->
                                        MonitorRow(label, debugValues[label])
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    col2.forEach { (label, _) ->
                                        MonitorRow(label, debugValues[label])
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    col3.forEach { (label, _) ->
                                        MonitorRow(label, debugValues[label])
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- dimensões da tela ----
            SectionCard("Dimensões da Tela") {
                val dm = resources.displayMetrics
                val wPx = dm.widthPixels
                val hPx = dm.heightPixels
                val density = dm.density
                val wDp = (wPx / density).toInt()
                val hDp = (hPx / density).toInt()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DimensionRow("Resolução (Pixels)", "${wPx}px x ${hPx}px")
                    DimensionRow("Resolução (DP)", "${wDp}dp x ${hDp}dp")
                    DimensionRow("Densidade", "${density}x (${dm.densityDpi} dpi)")
                }
            }

            // ---- atualização ----
            SectionCard("Sobre e atualização") {
                val checking by UpdateManager.checking
                val message by UpdateManager.message
                val available by UpdateManager.available
                val downloading by UpdateManager.downloading
                val progress by UpdateManager.progress

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Versão atual ${UpdateManager.currentVersion}", color = Color.White, fontSize = 16.sp)
                        message?.let { Text(it, color = AccentSoft, fontSize = 13.sp) }
                            ?: Text("Verifica a última release no GitHub.", color = Muted, fontSize = 13.sp)
                    }
                    if (available == null) {
                        OutlinedButton(onClick = { UpdateManager.checkForUpdate() }, enabled = !checking) {
                            Text(if (checking) "…" else "Verificar")
                        }
                    } else {
                        Button(onClick = { UpdateManager.downloadAndInstall(this@MainActivity) }, enabled = !downloading) {
                            Text("Instalar")
                        }
                    }
                }
                if (downloading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    @Composable
    private fun SectionCard(title: String, content: @Composable () -> Unit) {
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text(title.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }

    @Composable
    private fun StatusRow(name: String, status: String, ok: Boolean, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(status, color = if (ok) AccentSoft else Color(0xFFE0556A), fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
            if (!ok) {
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onClick) { Text("Conceder") }
            }
        }
    }

    @Composable
    private fun RowSwitch(name: String, desc: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = if (enabled) Color.White else Muted, fontSize = 16.sp)
                Text(desc, color = Muted, fontSize = 13.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }

    @Composable
    private fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
        Row(
            Modifier.background(Color(0xFF121722), RoundedCornerShape(14.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (label, value) ->
                val on = value == selected
                Text(
                    label,
                    color = if (on) Color.White else Muted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) Accent.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }
    }

    @Composable
    private fun Stepper(value: String, enabled: Boolean = true, onStep: (Int) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onStep(-1) }, enabled = enabled, modifier = Modifier.size(44.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−", fontSize = 20.sp) }
            Text(value, color = if (enabled) AccentSoft else Muted, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedButton(onClick = { onStep(1) }, enabled = enabled, modifier = Modifier.size(44.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+", fontSize = 20.sp) }
        }
    }

    @Composable
    private fun SectionPosRow(label: String, value: Int, max: Float, onValueChange: (Int) -> Unit) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${value}dp", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = 0f..max,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent.copy(alpha = 0.4f))
            )
        }
    }

    @Composable
    private fun MonitorRow(label: String, monitor: MonitorValue?) {
        val value = monitor?.current ?: "—"
        val previous = monitor?.previous
        val lastChanged = monitor?.lastChanged ?: 0
        val isRecent = System.currentTimeMillis() - lastChanged < 5000
        val displayValue = if (isRecent && previous != null && previous != value) "$previous -> $value" else value
        val color = if (isRecent && previous != null && previous != value) Color(0xFFFFC23C) else Color.White

        Column(Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(0.5f), maxLines = 1)
                Text(
                    displayValue,
                    color = color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.5f),
                    maxLines = 1
                )
            }
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.05f)))
        }
    }

    @Composable
    private fun DimensionRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }

    private data class MonitorValue(
        val current: String,
        val previous: String? = null,
        val lastChanged: Long = 0
    )
}
