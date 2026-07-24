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
import br.com.redesurftank.havaldock.data.SettingsStore
import br.com.redesurftank.havaldock.data.VehicleClient
import br.com.redesurftank.havaldock.update.UpdateManager
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
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
        val mode by SettingsStore.visibilityMode
        val secs by SettingsStore.autoHideSecs
        val boot by SettingsStore.launchOnBoot


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
                Text("Visibilidade", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Segmented(
                    options = listOf("Sempre visível" to SettingsStore.MODE_ALWAYS, "Auto-ocultar" to SettingsStore.MODE_AUTO),
                    selected = mode
                ) { SettingsStore.setVisibilityMode(it) }

                if (mode == SettingsStore.MODE_AUTO) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ocultar após", color = Color.White, fontSize = 16.sp)
                            Text("Reinicia a cada toque na barra.", color = Muted, fontSize = 13.sp)
                        }
                        Stepper("$secs s") { d -> SettingsStore.setAutoHideSecs(secs + d) }
                    }
                }
            }

            // ---- boot ----
            SectionCard("Inicialização") {
                RowSwitch("Religar ao ligar o carro", "Mostra a barra automaticamente no boot.", boot) {
                    SettingsStore.setLaunchOnBoot(it)
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
                        Text("Versão ${UpdateManager.currentVersion}", color = Color.White, fontSize = 16.sp)
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
    private fun RowSwitch(name: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontSize = 16.sp)
                Text(desc, color = Muted, fontSize = 13.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange)
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
    private fun Stepper(value: String, onStep: (Int) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onStep(-1) }, modifier = Modifier.size(44.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−", fontSize = 20.sp) }
            Text(value, color = AccentSoft, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedButton(onClick = { onStep(1) }, modifier = Modifier.size(44.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+", fontSize = 20.sp) }
        }
    }
}
