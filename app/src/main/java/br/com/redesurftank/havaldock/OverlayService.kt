package br.com.redesurftank.havaldock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.com.redesurftank.havaldock.data.Airflow
import br.com.redesurftank.havaldock.data.AirflowOption
import br.com.redesurftank.havaldock.data.Battery
import br.com.redesurftank.havaldock.data.Control
import br.com.redesurftank.havaldock.data.DockColors
import br.com.redesurftank.havaldock.data.DockControls
import br.com.redesurftank.havaldock.data.DockKeys
import br.com.redesurftank.havaldock.data.HvacPanel
import br.com.redesurftank.havaldock.data.IconToggle
import br.com.redesurftank.havaldock.data.Info
import br.com.redesurftank.havaldock.data.Level
import br.com.redesurftank.havaldock.data.MaxAc
import br.com.redesurftank.havaldock.data.Mode
import br.com.redesurftank.havaldock.data.ProjectionLauncher
import br.com.redesurftank.havaldock.data.Regen
import br.com.redesurftank.havaldock.data.RenderState
import br.com.redesurftank.havaldock.data.SettingsStore
import br.com.redesurftank.havaldock.data.Temp
import br.com.redesurftank.havaldock.data.TxtToggle
import br.com.redesurftank.havaldock.data.VehicleClient
import br.com.redesurftank.havaldock.data.Volume
import com.beantechs.intelligentvehiclecontrol.sdk.IListener
import java.util.concurrent.Executors

/**
 * PREVIEW VERSION - Dashboard HMI Clima Implementation
 * 
 * Toolbar inferior como overlay (TYPE_APPLICATION_OVERLAY), visual v2 (HMI Clima).
 * Serviço de Overlay que gerencia a Toolbar inferior e o Dashboard estendido.
 * Lê/escreve via [VehicleClient]; IPC sempre fora da main thread.
 * 
 * Este serviço é responsável por renderizar a interface de usuário por cima de outras aplicações
 * utilizando o WindowManager. Ele se comunica com o veículo através do [VehicleClient] para
 * leitura e escrita de variáveis de sistema (Climatização, Condução, Áudio, etc.).
 */
class OverlayService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: TouchFrame
    
    // Elementos da Barra Compacta
    private var bar: LinearLayout? = null
    private var topLine: View? = null
    private val sectionLayouts = ArrayList<LinearLayout>()
    private var contentLayout: FrameLayout? = null
    
    // Alça de controle de visibilidade (Mini pill)
    private lateinit var handle: View

    /** Mapa de funções de atualização de UI, indexado pelo ID do controle. */
    private val updaters = HashMap<String, (RenderState) -> Unit>()
    
    // Referências para janelas de popups ativos
    private var volWin: View? = null
    private var airflowWin: View? = null
    private var levelWin: View? = null
    private var modeWin: View? = null
    private var tempWin: View? = null
    
    /** Estado de ocultação da interface. */
    private var hidden = false

    // Gerenciamento de Projeção (CarPlay / Android Auto)
    private var projView: View? = null
    private var projIcon: ImageView? = null
    private var projConnected: String? = null   // pacote da projeção conectada
    private var projForeground = false          // projeção em foco
    private var projShownState: String? = null  // estado do ícone
    private var lastProjection: String? = null  // última projeção vista
    private var lastCentralApp: String? = null  // último app não-projeção

    private var maxEconomicLevel = 0.0f
    private var minEconomicLevel = 100.0f
    private var firstEcoValue = true

    // Medidas e Cores
    private val barHeightPx: Int get() = dp(SettingsStore.barHeight(this))
    private val handleHeightPx by lazy { dp(HANDLE_DP) }
    private val trackPx by lazy { dp(30) }

    private val cAccent = DockColors.CYAN
    private val cTxt = DockColors.ON_SURFACE
    private val cMuted = DockColors.ON_SURFACE_MUTED
    private val cCard = DockColors.SURFACE
    private val cLine = DockColors.OUTLINE
    private val cOnAccent = Color.BLACK
    private val cTrack = DockColors.TRACK
    private val cSurfaceSelected = DockColors.SURFACE_SELECTED
    private val cSurfaceRaised = DockColors.SURFACE_RAISED

    /** Fonte customizada Chakra Petch carregada em tempo de execução. */
    private val typeface by lazy { ResourcesCompat.getFont(this, R.font.font_family_clima) }

    /** Referência para o layout do Dashboard quando ativo. */
    private var dashboard: View? = null

    /** Retorna a cor de fundo da barra baseada na opacidade configurada. */
    private fun getBarColor(): Int {
        val opacity = SettingsStore.opacity(this)
        val alpha = (opacity * 255) / 100
        return Color.argb(alpha, 7, 10, 14)
    }

    /** Retorna a cor para o fundo de popups. */
    private fun getPopupColor(): Int {
        return if (SettingsStore.isItemFrameEnabled(this)) {
            Color.parseColor("#F2070A0E") // 95% fixo no modo bolha
        } else {
            getBarColor()
        }
    }

    /** Timer para auto-ocultar a barra principal. */
    private val hideRunnable = Runnable { hideBar() }
    
    /** Timer para fechar popups de controle por inatividade. */
    private val closePopupsRunnable = Runnable { closeAllPopups() }

    /** Timer para atualizar o relógio do dashboard. */
    private val clockTicker = object : Runnable {
        override fun run() {
            updaters["header_info"]?.invoke(RenderState())
            main.postDelayed(this, 10000L)
        }
    }

    /** Listener de dados do veículo: atualiza a UI quando uma variável monitorada muda. */
    private val listener = object : IListener.Stub() {
        override fun onDataChanged(key: String?, value: String?) {
            main.post {
                refreshAll()
                if (SettingsStore.visualMode.value == SettingsStore.VISUAL_BALLOONS) {
                    showBalloonForKey(key)
                }
            }
        }
    }

    /** Observa mudanças nas configurações locais e recria ou atualiza o overlay se necessário. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsStore.KEY_MODE || key == SettingsStore.KEY_SECS) applyVisibility()
        if (key == SettingsStore.KEY_OPACITY) {
            main.post { bar?.setBackgroundColor(getBarColor()) }
        }
        if (key == SettingsStore.KEY_ITEM_FRAME) {
            main.post { updateItemFrame() }
        }
        if (key != null && key.startsWith("sec") && key.endsWith("_x")) {
            main.post { updateSectionsPosition() }
        }
        if (key == SettingsStore.KEY_BAR_HEIGHT || key == SettingsStore.KEY_VISUAL_MODE) {
            if (key == SettingsStore.KEY_BAR_HEIGHT) params.height = barHeightPx
            if (!hidden) main.post {
                if (::root.isInitialized) runCatching { wm.removeView(root) }
                buildOverlay()
            }
            broadcastBarState()
        }
    }

    /** Escuta requisições de estado de outros apps do ecossistema. */
    private val requestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { broadcastBarState() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildOverlay()
        
        SettingsStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        registerRequestReceiver()
        broadcastBarState()
        
        // Conexão e sincronização com o barramento do veículo
        VehicleClient.addConnectionListener(onVehicleConnected)
        io.execute { runCatching { VehicleClient.registerListener(DockControls.MONITORED, listener) } }
        
        HvacPanel.ensureEnabled()
        refreshAll()
        main.postDelayed(projPoll, 1200)
        main.post(clockTicker)
    }

    private val onVehicleConnected: () -> Unit = { refreshAll() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyVisibility(); return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(closePopupsRunnable)
        main.removeCallbacks(projPoll)
        main.removeCallbacks(clockTicker)
        closeAllPopups()
        
        runCatching { SettingsStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener) }
        runCatching { unregisterReceiver(requestReceiver) }
        
        // Notifica o sistema de que a barra foi removida
        runCatching { sendBroadcast(Intent(ACTION_BAR_STATE).putExtra(EXTRA_VISIBLE, false).putExtra(EXTRA_HEIGHT_DP, 0)) }
        
        VehicleClient.removeConnectionListener(onVehicleConnected)
        io.execute { runCatching { VehicleClient.unregisterListener(listener) } }
        runCatching { wm.removeView(root) }
    }

    // ---- Construção da Interface ----

    /**
     * Constrói e exibe a janela principal do overlay.
     * Define as dimensões e parâmetros do WindowManager com base no modo visual.
     */
    private fun buildOverlay() {
        val visualMode = SettingsStore.visualMode.value
        if (visualMode == SettingsStore.VISUAL_BALLOONS) {
            return
        }

        val isDash = visualMode == SettingsStore.VISUAL_DASHBOARD
        val h = if (hidden) handleHeightPx else (if (isDash) 720 else barHeightPx)
        val w = if (hidden) dp(100) else WindowManager.LayoutParams.MATCH_PARENT
        val g = if (hidden) (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) else (Gravity.BOTTOM or (if (isDash) Gravity.CENTER_HORIZONTAL else Gravity.START))

        params = WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = g }

        root = TouchFrame(this, { onUserActivity() }, { hideBar(manual = true) }, { showBar() })
        updaters.clear()

        handle = View(this).apply {
            background = pill(Color.parseColor("#40FFFFFF"), dp(2))
            visibility = if (hidden) View.VISIBLE else View.GONE
            setOnClickListener { showBar() }
        }

        if (isDash) {
            buildDashboard()
            dashboard?.visibility = if (hidden) View.GONE else View.VISIBLE
        } else {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getBarColor())
                visibility = if (hidden) View.GONE else View.VISIBLE
            }
            bar = b
            buildOverlayContent()
            root.addView(b, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        root.addView(handle, FrameLayout.LayoutParams(dp(100), dp(4),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) })

        wm.addView(root, params)
        refreshAll()
        io.execute { refreshProjection() }
    }

    /** Constrói o conteúdo interno da barra compacta (seções e itens). */
    private fun buildOverlayContent() {
        // Linha decorativa de topo
        val top = View(this).apply { setBackgroundColor(cAccent) }
        topLine = top
        bar?.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)))

        // Container para as seções de controles
        val c = FrameLayout(this).apply { setPadding(0, 0, 0, 0) }
        contentLayout = c
        bar?.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        
        buildSections(c)
        updateItemFrame()
        updateSectionsPosition()
    }

    /** Posiciona as seções horizontais com base nos offsets configurados. */
    private fun updateSectionsPosition() {
        sectionLayouts.forEachIndexed { i, sec ->
            val lp = sec.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = dp(SettingsStore.sectionX(this, i))
            lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            sec.layoutParams = lp
        }
    }

    /** Aplica o visual de 'bolha/moldura' nos itens se a opção estiver ligada. */
    private fun updateItemFrame() {
        val enabled = SettingsStore.isItemFrameEnabled(this)
        topLine?.visibility = if (enabled) View.GONE else View.VISIBLE
        bar?.setBackgroundColor(if (enabled) Color.TRANSPARENT else getBarColor())
        
        if (enabled) {
            sectionLayouts.forEach { sec ->
                sec.background = pill(Color.parseColor("#F2070A0E"), dp(18))
                sec.setPadding(dp(12), 0, dp(12), 0)
            }
        } else {
            sectionLayouts.forEach { sec ->
                sec.background = null
                sec.setPadding(0, 0, 0, 0)
            }
        }
    }

    /** Organiza os controles monitorados em seções dentro da barra compacta. */
    private fun buildSections(content: FrameLayout) {
        sectionLayouts.clear()
        val secs = arrayOf(rowSection(), rowSection(), rowSection(), rowSection())
        secs.forEach { 
            sectionLayouts.add(it)
            content.addView(it, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        
        for (c in DockControls.ALL) {
            if (c.section < secs.size) secs[c.section].addView(tile(c))
        }
        secs[0].addView(projTile())

        // Adiciona atalho para Dashboard se estiver no modo barra
        if (SettingsStore.visualMode.value == SettingsStore.VISUAL_BAR) {
            val dashBtn = icon(R.drawable.ic_car, dp(24), Color.WHITE).apply {
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    SettingsStore.setVisualMode(SettingsStore.VISUAL_DASHBOARD)
                    OverlayService.stop(this@OverlayService)
                    OverlayService.start(this@OverlayService)
                }
            }
            content.addView(dashBtn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(10) })
        }
    }

    private fun rowSection() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }

    private fun tile(c: Control): View = when (c) {
        is Temp -> tileTemp(c)
        is Level -> tileLevel(c)
        is Volume -> tileVolume(c)
        is TxtToggle -> tileTxt(c)
        is MaxAc -> tileMax(c)
        is IconToggle -> tileIconToggle(c)
        is Battery -> tileBattery(c)
        is Info -> tileInfo(c)
        is Regen -> tileRegen(c)
        else -> View(this)
    }

    private fun gap(v: View, start: Int) { (v.layoutParams as LinearLayout.LayoutParams).marginStart = dp(start) }

    private fun col() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        val h = SettingsStore.barHeight(this@OverlayService) - 8
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(h)).apply { marginStart = dp(22) }
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun tileTemp(c: Temp): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            val ms = if (c.id == "tempP") 42 else 22
            val h = SettingsStore.barHeight(this@OverlayService) - 8
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(h)).apply { marginStart = dp(ms) }
            isClickable = true
        }
        val tv = TextView(this).apply {
            setTextColor(cAccent); textSize = 34f; setTypeface(typeface, Typeface.BOLD); text = "—°"
            gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
        }
        row.addView(tv)
        updaters[c.id] = { st -> 
            tv.text = st.text
            tv.setTextColor(st.color)
        }

        var startX = 0f
        val threshold = dp(40).toFloat()
        row.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startX = event.x; false }
                MotionEvent.ACTION_UP -> {
                    val diff = event.x - startX
                    if (kotlin.math.abs(diff) > threshold) {
                        onUserActivity()
                        io.execute {
                            val fan = DockControls.FAN
                            val cur = fan.value()
                            if (diff > 0) { // Direita -> Aumenta
                                fan.setLevel(cur + 1)
                            } else { // Esquerda -> Diminui
                                fan.setLevel(cur - 1)
                            }
                            main.post { refreshAll() }
                        }
                        true // Consome o gesto, impede o clique
                    } else {
                        false // Pequeno movimento ou clique, deixa o OnClickListener agir
                    }
                }
                else -> false
            }
        }
        row.setOnClickListener { onUserActivity(); openTemp(c, row) }
        return row
    }

    private fun tileLevel(c: Level): View {
        val v = col(); v.isClickable = true
        val ic = icon(c.icon, cTxt, 42) // Aumentado
        val track = makeTrack()
        v.addView(ic)
        v.addView(track.first)
        updaters[c.id] = { st ->
            ic.setColorFilter(st.color)
            setTrack(track.second, st.ratio)
        }
        v.setOnClickListener { if (c.picker) { onUserActivity(); openLevel(c, v) } else act(c) { c.cycle() } }
        return v
    }

    private fun tileVolume(c: Volume): View {
        val v = col(); v.isClickable = true
        val ic = icon(c.icon, cTxt, 42) // Aumentado
        val track = makeTrack()
        v.addView(ic)
        v.addView(track.first)
        updaters[c.id] = { st ->
            setTrack(track.second, st.ratio)
            if (st.icon != 0) ic.setImageResource(st.icon)
        }
        v.setOnClickListener { onUserActivity(); openVolume(c, v) }
        return v
    }

    private fun tileTxt(c: TxtToggle): View = textTile(c, c.label) { c.flip() }
    private fun tileMax(c: MaxAc): View = textTile(c, c.label) { c.flip() }

    private fun textTile(c: Control, label: String, onFlip: () -> Unit): View {
        val v = col(); v.isClickable = true
        val tv = TextView(this).apply {
            text = label; setTextColor(cMuted); textSize = 28f; setTypeface(typeface, Typeface.BOLD) // Aumentado
            gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(6), 0, dp(6), 0)
        }
        val ul = View(this)
        // WRAP_CONTENT explícito: em LinearLayout VERTICAL o padrão é MATCH_PARENT, o que fazia
        // o texto encolher à largura do sublinhado (28dp) e CORTAR (MAX/AUTO/SYNC).
        v.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        v.addView(ul, LinearLayout.LayoutParams(dp(28), dp(3)).apply { topMargin = dp(7) })
        updaters[c.id] = { st ->
            tv.text = label   // re-setar força re-medida (resolve o corte; igual aos modos)
            tv.setTextColor(if (st.on) cAccent else cMuted)
            ul.setBackgroundColor(if (st.on) cAccent else Color.TRANSPARENT)
        }
        v.setOnClickListener { act(c) { onFlip() } }
        return v
    }

    private fun tileIconToggle(c: IconToggle): View {
        val v = col(); v.isClickable = true
        val ic = icon(c.iconOff, cTxt, 52)   // recirc maior; ícone trocado por estado
        v.addView(ic)
        updaters[c.id] = { st ->
            if (st.icon != 0) ic.setImageResource(st.icon)
            ic.setColorFilter(if (st.on) cAccent else cTxt)
        }
        v.setOnClickListener { act(c) { c.flip() } }
        return v
    }



    private fun tileBattery(c: Battery): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val h = SettingsStore.barHeight(this@OverlayService) - 8
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(h)).apply { marginStart = dp(22) }
            setPadding(dp(8), 0, dp(8), 0); isClickable = true
        }
        val modeTv = TextView(this).apply {
            setTextColor(cAccent); textSize = 30f; setTypeface(typeface, Typeface.NORMAL)
            gravity = Gravity.CENTER; setPadding(dp(12), 0, 0, 0); text = "—"
        }
        val ic = icon(R.drawable.ic_bolt, cAccent, 34)
        val batTv = TextView(this).apply {
            setTextColor(cAccent); textSize = 30f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; setSingleLine(true); maxLines = 1; setPadding(0, 0, dp(10), 0); text = "—%"
        }
        row.addView(batTv); row.addView(ic); row.addView(modeTv)

        updaters["drive"] = { st -> 
            modeTv.text = st.text
            modeTv.setTextColor(st.color)
            ic.setColorFilter(st.color) // Raio segue a cor do modo drive
        }
        updaters[c.id] = { st ->
            batTv.text = st.text
            batTv.setTextColor(st.color)
        }
        row.setOnClickListener { onUserActivity(); openMode(DockControls.DRIVE, row) }
        return row
    }

    private fun tileInfo(c: Info): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; isClickable = false
            val h = SettingsStore.barHeight(this@OverlayService) - 8
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(h)).apply { marginStart = dp(22) }
            setPadding(dp(8), 0, dp(8), 0)
        }
        val ic = icon(c.icon, cTxt, 32)
        val tv = TextView(this).apply {
            setTextColor(cTxt); textSize = 30f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; setSingleLine(true); maxLines = 1; setPadding(dp(10), 0, 0, 0); text = "—°"
        }
        row.addView(ic); row.addView(tv)
        updaters[c.id] = { st -> 
            tv.text = st.text
            tv.setTextColor(st.color)
        }
        return row
    }

    private fun tileRegen(c: Regen): View {
        val v = col(); v.isClickable = true
        val ic = icon(c.icon, cAccent, 40) // Aumentado
        v.addView(ic)
        val barsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bars = Array(3) { View(this) }
        bars.forEachIndexed { i, b ->
            b.background = pill(cLine, dp(1))
            barsRow.addView(b, LinearLayout.LayoutParams(dp(10), dp(7)).apply { if (i > 0) marginStart = dp(4) })
        }
        v.addView(barsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        updaters[c.id] = { st ->
            ic.setColorFilter(st.color)
            bars.forEachIndexed { i, b -> b.background = pill(if (i < st.bars) st.color else cLine, dp(1)) }
        }
        v.setOnClickListener { act(c) { c.next() } }
        return v
    }

    private fun icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res); setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** track (fundo + fill) para o sublinhado de nível. retorna (container, fillView). */
    private fun makeTrack(): Pair<View, View> {
        val track = FrameLayout(this).apply {
            background = pill(cLine, dp(2))
            layoutParams = LinearLayout.LayoutParams(trackPx, dp(3)).apply { topMargin = dp(7) }
        }
        val fill = View(this).apply { setBackgroundColor(cAccent) }
        track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        return Pair(track, fill)
    }

    private fun setTrack(fill: View, ratio: Float) {
        val lp = fill.layoutParams; lp.width = (trackPx * ratio.coerceIn(0f, 1f)).toInt(); fill.layoutParams = lp
    }

    // ---- volume popup (janela vertical separada) ----

    // ---- popups (volume, ar, modo, nivel): centralizados sobre o ícone que os abriu ----

    private fun openVolume(c: Volume, anchor: View) {
        if (volWin != null) { closeVolume(); return }
        closeAirflow(); closeLevel(); closeMode(); closeTemp()
        armPopupTimer()
        val pop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(getPopupColor(), dp(18)); setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val valTv = TextView(this).apply {
            setTextColor(cAccent); textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; minWidth = dp(70)
        }
        pop.addView(valTv)

        val sliderW = dp(240); val sliderH = dp(32)
        val sliderTrack = FrameLayout(this).apply {
            background = pill(cCard, dp(16))
            layoutParams = LinearLayout.LayoutParams(sliderW, sliderH).apply { marginStart = dp(12) }
        }
        val sliderFill = View(this).apply { setBackgroundColor(cAccent) }
        sliderTrack.addView(sliderFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        pop.addView(sliderTrack)

        var canGoPast12 = false
        var currentV = 0 // cache local para verificar no DOWN

        fun updateUI(v: Int) {
            val color = if (v > 12) DockColors.RED else cAccent
            valTv.text = v.toString()
            valTv.setTextColor(color)
            val hi = c.hi().coerceAtLeast(1)
            val r = v.toFloat() / hi
            val lp = sliderFill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt()
            sliderFill.layoutParams = lp
            sliderFill.setBackgroundColor(color)
        }

        sliderTrack.setOnTouchListener { view, e ->
            armPopupTimer()
            val r = (e.x / view.width).coerceIn(0f, 1f)
            var v = (r * c.hi()).toInt()

            if (e.action == MotionEvent.ACTION_DOWN) {
                canGoPast12 = currentV >= 12
            }

            if (!canGoPast12) v = minOf(v, 12)

            updateUI(v)

            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity()
                currentV = v
                io.execute { c.set(v); main.post { refreshAll() } }
            }
            true
        }

        runCatching { 
            wm.addView(pop, createPopupParams(anchor))
            handleOutsideTouch(pop)
            volWin = pop 
        }

        io.execute {
            val initial = c.value()
            main.post { currentV = initial; updateUI(initial) }
        }
        onUserActivity()
    }

    private fun closeVolume() { volWin?.let { v -> runCatching { wm.removeView(v) } }; volWin = null }

    private fun closeMode() { modeWin?.let { v -> runCatching { wm.removeView(v) } }; modeWin = null }

    private fun closeTemp() {
        tempWin?.let { v -> runCatching { wm.removeView(v) } }
        tempWin = null
        updaters.remove("fan_popup")
        updaters.remove("vent_popup")
        updaters.remove("auto_popup")
        updaters.remove("pwr_popup")
        updaters.remove("ac_popup")
        updaters.remove("air_popup")
    }

    private fun closeAllPopups() {
        main.removeCallbacks(closePopupsRunnable)
        closeVolume(); closeLevel(); closeMode(); closeTemp()
        airflowWin?.let { v -> runCatching { wm.removeView(v) } }; airflowWin = null
    }

    private fun armPopupTimer() {
        main.removeCallbacks(closePopupsRunnable)
        val s = SettingsStore.popupSecs(this)
        if (s > 0) main.postDelayed(closePopupsRunnable, s * 1000L)
    }

    // ---- popup de fluxo de ar (linha horizontal de ícones) ----

    private fun openAirflow(c: Airflow, anchor: View) {
        if (airflowWin != null) { closeAirflow(); return }
        closeVolume(); closeLevel(); closeMode(); closeTemp()
        armPopupTimer()
        val pop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(getPopupColor(), dp(18)); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val ivs = ArrayList<Pair<AirflowOption, ImageView>>()
        c.options.forEach { opt ->
            val iv = ImageView(this).apply {
                setImageResource(opt.icon); setColorFilter(cTxt); isClickable = true
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginStart = dp(4); marginEnd = dp(4) }
                setOnClickListener {
                    onUserActivity(); armPopupTimer()
                    io.execute { c.select(opt); main.post { closeAirflow(); refreshAll() } }
                }
            }
            ivs.add(opt to iv); pop.addView(iv)
        }

        runCatching { 
            wm.addView(pop, createPopupParams(anchor))
            handleOutsideTouch(pop)
            airflowWin = pop 
        }
        // destaca o modo atual em ciano (IPC fora da main thread)
        io.execute {
            val cur = c.currentOption()
            main.post { ivs.forEach { (o, iv) -> iv.setColorFilter(if (o == cur) cAccent else cTxt) } }
        }
        onUserActivity()
    }

    // ---- popup de modo (Modos de condução + SOC HEV) ----

    private fun openMode(c: Mode, anchor: View) {
        if (modeWin != null) { closeMode(); return }
        closeVolume(); closeAirflow(); closeLevel(); closeTemp()
        armPopupTimer()
        val pop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = pill(getPopupColor(), dp(18)); setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val modeViews = ArrayList<Pair<Int, TextView>>()
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(0))
            visibility = View.GONE // Começa escondido p/ evitar flicker
        }

        // Botão Inteligente
        val intelBtn = TextView(this).apply {
            text = "INTELIGENTE"; setTextColor(cTxt); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            background = pill(cCard, dp(14)); setPadding(dp(12), dp(6), dp(12), dp(6)); isClickable = true
        }
        row2.addView(intelBtn)

        // Label do SOC
        val socLabel = TextView(this).apply {
            setTextColor(cTxt); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), 0, dp(8), 0); text = "—%"
        }
        row2.addView(socLabel)

        // Slider do SOC
        val sliderW = dp(200); val sliderH = dp(32)
        val sliderTrack = FrameLayout(this).apply {
            background = pill(cCard, dp(16))
            layoutParams = LinearLayout.LayoutParams(sliderW, sliderH)
        }
        val sliderFill = View(this).apply { setBackgroundColor(DockColors.AMBER) }
        sliderTrack.addView(sliderFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        row2.addView(sliderTrack)

        fun updateHEVUI(strategy: Int, soc: Int) {
            val isIntel = strategy == 1
            intelBtn.setTextColor(if (isIntel) cOnAccent else cTxt)
            intelBtn.background = pill(if (isIntel) DockColors.AMBER else cCard, dp(14))

            socLabel.text = "$soc%"
            socLabel.setTextColor(if (!isIntel) DockColors.AMBER else cMuted)

            val r = (soc - c.minSoc).toFloat() / (c.maxSoc - c.minSoc)
            val lp = sliderFill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt()
            sliderFill.layoutParams = lp
            sliderFill.setBackgroundColor(if (!isIntel) DockColors.AMBER else cMuted)
        }

        intelBtn.setOnClickListener {
            onUserActivity(); armPopupTimer()
            io.execute { c.select(0, strategy = 1); main.post { updateHEVUI(1, c.curHevSocInt()); refreshAll() } }
        }

        sliderTrack.setOnTouchListener { view, e ->
            armPopupTimer()
            val r = (e.x / view.width).coerceIn(0f, 1f)
            val soc = c.minSoc + (r * (c.maxSoc - c.minSoc)).toInt()
            updateHEVUI(2, soc)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity()
                io.execute { c.select(0, strategy = 2, soc = soc); main.post { refreshAll() } }
            }
            true
        }

        c.order.forEach { m ->
            val tv = TextView(this).apply {
                text = (c.labels[m] ?: "—").uppercase(); setTextColor(cTxt); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(16), dp(10), dp(16), dp(10)); isClickable = true
                setOnClickListener {
                    onUserActivity(); armPopupTimer()
                    io.execute {
                        c.select(m, strategy = if (m == 0) c.curStrategy() else null)
                        main.post {
                            if (m != 0) closeMode() else {
                                row2.visibility = View.VISIBLE
                                updateHEVUI(c.curStrategy(), c.curHevSocInt())
                            }
                            refreshAll()
                            // Update colors
                            val curM = m // Usa o modo clicado imediatamente p/ feedback visual
                            modeViews.forEach { (mo, t) -> t.setTextColor(if (mo == curM) c.colors[mo] ?: cAccent else cTxt) }
                        }
                    }
                }
            }
            modeViews.add(m to tv); row1.addView(tv)
        }
        pop.addView(row1)
        pop.addView(row2)

        runCatching { 
            wm.addView(pop, createPopupParams(anchor))
            handleOutsideTouch(pop)
            modeWin = pop 
        }

        io.execute {
            val curM = c.cur(); val curS = c.curHevSocInt(); val curSt = c.curStrategy()
            main.post {
                row2.visibility = if (curM == 0) View.VISIBLE else View.GONE
                updateHEVUI(curSt, curS)
                modeViews.forEach { (m, tv) -> tv.setTextColor(if (m == curM) c.colors[m] ?: cAccent else cTxt) }
            }
        }
    }


    // ---- popup de clima (AUTO, Temp, Ventilador, Fluxo, Banco) ----

    private fun openTemp(c: Temp, anchor: View) {
        if (tempWin != null) { closeTemp(); return }
        closeVolume(); closeAirflow(); closeLevel(); closeMode()
        armPopupTimer()
        val pop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = pill(getPopupColor(), dp(18)); setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val sliderW = dp(240); val sliderH = dp(32)

        // --- Linha 1: Controles Rápidos (Power, AC, AUTO) ---
        val auto = DockControls.AUTO_CONTROL
        val rowAuto = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }

        val btnW = dp(100); val btnH = dp(46)

        // 1. Botão de Energia (Power)
        val pwrIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_fan)
            background = pill(cCard, dp(14))
            layoutParams = LinearLayout.LayoutParams(btnW, btnH)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener {
                onUserActivity(); armPopupTimer()
                io.execute {
                    val isOn = VehicleClient.getData(DockKeys.CAR_HVAC_POWER_MODE) == "1"
                    val next = !isOn
                    if (isOn) {
                        VehicleClient.set(DockKeys.CAR_HVAC_POWER_MODE, "0")
                        VehicleClient.set(DockKeys.CAR_HVAC_FAN_SPEED, "0")
                    } else {
                        VehicleClient.set(DockKeys.CAR_HVAC_POWER_MODE, "1")
                        VehicleClient.set(DockKeys.CAR_HVAC_AUTO_ENABLE, "0")
                        VehicleClient.set(DockKeys.CAR_HVAC_FAN_SPEED, "2")
                    }
                    main.post { 
                        this@apply.setColorFilter(if (next) DockColors.GREEN else cTxt)
                        refreshAll() 
                    }
                }
            }
        }
        rowAuto.addView(pwrIcon)

        // 2. Botão de Ar-Condicionado (AC)
        val acIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_snowflake_thermometer)
            background = pill(cCard, dp(14))
            layoutParams = LinearLayout.LayoutParams(btnW, btnH).apply { marginStart = dp(16) }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener {
                onUserActivity(); armPopupTimer()
                io.execute {
                    val acOn = VehicleClient.getData(DockKeys.CAR_HVAC_AC_ENABLE) == "1"
                    val nextAc = !acOn
                    if (acOn) {
                        VehicleClient.set(DockKeys.CAR_HVAC_AC_ENABLE, "0")
                    } else {
                        VehicleClient.set(DockKeys.CAR_HVAC_AC_ENABLE, "1")
                        // Garante que a ventilação ligue junto
                        VehicleClient.set(DockKeys.CAR_HVAC_POWER_MODE, "1")
                        VehicleClient.set(DockKeys.CAR_HVAC_AUTO_ENABLE, "0")
                        VehicleClient.set(DockKeys.CAR_HVAC_FAN_SPEED, "2")
                    }
                    main.post { 
                        this@apply.setColorFilter(if (nextAc) DockColors.GREEN else cTxt)
                        if (nextAc) pwrIcon.setColorFilter(DockColors.GREEN)
                        refreshAll() 
                    }
                }
            }
        }
        rowAuto.addView(acIcon)

        // 3. Botão AUTO
        val autoBtn = TextView(this).apply {
            text = "AUTO"; setTextColor(cTxt); textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            background = pill(cCard, dp(14)); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(btnW, btnH).apply { marginStart = dp(16) }
            isClickable = true
            setOnClickListener {
                onUserActivity(); armPopupTimer()
                io.execute { auto.flip(); main.post { refreshAll() } }
            }
        }
        rowAuto.addView(autoBtn)
        pop.addView(rowAuto)

        // --- Linha 2: Temperatura ---
        val rowTemp = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tempTv = TextView(this).apply {
            setTextColor(cAccent); textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; minWidth = dp(70)
        }
        val tempTrack = FrameLayout(this).apply {
            background = pill(cCard, dp(16))
            layoutParams = LinearLayout.LayoutParams(sliderW, sliderH).apply { marginStart = dp(12) }
        }
        val tempFill = View(this).apply { setBackgroundColor(cAccent) }
        tempTrack.addView(tempFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        rowTemp.addView(tempTv); rowTemp.addView(tempTrack)
        pop.addView(rowTemp)

        fun updateTempUI(v: Double) {
            val r = ((v - c.min) / (c.hi() - c.min)).toFloat()
            val color = blend(DockColors.CYAN, DockColors.AMBER, r)
            tempTv.text = c.fmt(v) + "°"; tempTv.setTextColor(color)
            val lp = tempFill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt()
            tempFill.layoutParams = lp; tempFill.setBackgroundColor(color)
        }

        tempTrack.setOnTouchListener { view, e ->
            armPopupTimer()
            val r = (e.x / view.width).coerceIn(0f, 1f)
            val raw = c.min + r * (c.hi() - c.min)
            val v = (Math.round(raw / c.step) * c.step).coerceIn(c.min, c.hi())
            updateTempUI(v)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity()
                io.execute { c.select(v); main.post { refreshAll() } }
            }
            true
        }

        // --- Linha 3: Ventilador (Velocidade) ---
        val fan = DockControls.FAN
        val rowFan = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val fanIcon = icon(R.drawable.ic_fan, cTxt, 24)
        val fanTv = TextView(this).apply {
            setTextColor(cTxt); textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; minWidth = dp(34); setPadding(dp(8), 0, dp(8), 0)
        }
        val fanTrack = FrameLayout(this).apply {
            background = pill(cCard, dp(16))
            layoutParams = LinearLayout.LayoutParams(sliderW, sliderH)
        }
        val fanFill = View(this).apply { setBackgroundColor(cAccent) }
        fanTrack.addView(fanFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        rowFan.addView(fanIcon); rowFan.addView(fanTv); rowFan.addView(fanTrack)
        pop.addView(rowFan)

        fun updateFanUI(v: Int) {
            fanTv.text = if (v < 0) "_" else v.toString()
            val lo = fan.min; val hi = fan.hi().coerceAtLeast(lo + 1)
            val r = (v - lo).toFloat() / (hi - lo)
            val lp = fanFill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt()
            fanFill.layoutParams = lp
        }

        fanTrack.setOnTouchListener { view, e ->
            armPopupTimer()
            val r = (e.x / view.width).coerceIn(0f, 1f)
            val lo = fan.min; val hi = fan.hi().coerceAtLeast(lo + 1)
            val v = lo + (r * (hi - lo)).toInt()
            updateFanUI(v)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity()
                io.execute { fan.setLevel(v); main.post { refreshAll() } }
            }
            true
        }

        // --- Linha 4: Fluxo de Ar (Ícones) ---
        val airflow = DockControls.AIRFLOW_CONTROL
        val rowAir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        val airIcons = ArrayList<Pair<AirflowOption, ImageView>>()
        airflow.options.forEach { opt: AirflowOption ->
            val iv = ImageView(this).apply {
                setImageResource(opt.icon); setColorFilter(cTxt); isClickable = true
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginStart = dp(4); marginEnd = dp(4) }
                setOnClickListener {
                    onUserActivity(); armPopupTimer()
                    io.execute { airflow.select(opt); main.post { refreshAll() } }
                }
            }
            airIcons.add(Pair(opt, iv)); rowAir.addView(iv)
        }
        pop.addView(rowAir)

        fun updateAirflowUI(cur: AirflowOption) {
            airIcons.forEach { pair ->
                val o = pair.first; val iv = pair.second
                iv.setColorFilter(if (o == cur) cAccent else cTxt)
            }
        }

        // --- Linha 5: Ventilação do Banco ---
        val vent = if (c.id == "tempD") DockControls.VENT_D else DockControls.VENT_P
        val rowVent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val ventIcon = icon(R.drawable.ic_carseat_cooler, cTxt, 24)
        val ventTv = TextView(this).apply {
            setTextColor(cTxt); textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; minWidth = dp(34); setPadding(dp(8), 0, dp(8), 0)
        }
        val ventTrack = FrameLayout(this).apply {
            background = pill(cCard, dp(16))
            layoutParams = LinearLayout.LayoutParams(sliderW, sliderH)
        }
        val ventFill = View(this).apply { setBackgroundColor(cAccent) }
        ventTrack.addView(ventFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        rowVent.addView(ventIcon); rowVent.addView(ventTv); rowVent.addView(ventTrack)
        pop.addView(rowVent)

        fun updateVentUI(v: Int) {
            ventTv.text = if (v < 0) "_" else v.toString()
            val hi = vent.hi().coerceAtLeast(1)
            val r = v.toFloat() / hi
            val lp = ventFill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt()
            ventFill.layoutParams = lp
        }

        ventTrack.setOnTouchListener { view, e ->
            armPopupTimer()
            val r = (e.x / view.width).coerceIn(0f, 1f)
            val v = (r * vent.hi()).toInt()
            updateVentUI(v)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity()
                io.execute { vent.setLevel(v); main.post { refreshAll() } }
            }
            true
        }

        runCatching { 
            wm.addView(pop, createPopupParams(anchor))
            handleOutsideTouch(pop)
            tempWin = pop 
        }

        updaters["fan_popup"] = { _ ->
            io.execute { val v = fan.value(); main.post { updateFanUI(v) } }
        }
        updaters["vent_popup"] = { _ ->
            io.execute { val v = vent.value(); main.post { updateVentUI(v) } }
        }
        updaters["auto_popup"] = { _ ->
            io.execute {
                val on = auto.isOn()
                main.post {
                    autoBtn.setTextColor(if (on) cOnAccent else cTxt)
                    autoBtn.background = pill(if (on) cAccent else cCard, dp(14))
                }
            }
        }
        updaters["pwr_popup"] = { _ ->
            io.execute {
                val isOn = VehicleClient.getData(DockKeys.CAR_HVAC_POWER_MODE) == "1"
                main.post { pwrIcon.setColorFilter(if (isOn) DockColors.GREEN else cTxt) }
            }
        }
        updaters["ac_popup"] = { _ ->
            io.execute {
                val isOn = VehicleClient.getData(DockKeys.CAR_HVAC_AC_ENABLE) == "1"
                main.post { acIcon.setColorFilter(if (isOn) DockColors.GREEN else cTxt) }
            }
        }
        updaters["air_popup"] = { _ ->
            io.execute { val cur = airflow.currentOption(); main.post { updateAirflowUI(cur) } }
        }

        io.execute {
            val curT = c.read() ?: c.min
            val curF = fan.value()
            val curV = vent.value()
            val isAuto = auto.isOn()
            val curA = airflow.currentOption()
            val isPwrOn = VehicleClient.getData(DockKeys.CAR_HVAC_POWER_MODE) == "1"
            val isAcOn = VehicleClient.getData(DockKeys.CAR_HVAC_AC_ENABLE) == "1"
            main.post {
                updateTempUI(curT); updateFanUI(curF); updateVentUI(curV); updateAirflowUI(curA)
                autoBtn.setTextColor(if (isAuto) cOnAccent else cTxt)
                autoBtn.background = pill(if (isAuto) cAccent else cCard, dp(14))
                pwrIcon.setColorFilter(if (isPwrOn) DockColors.GREEN else cTxt)
                acIcon.setColorFilter(if (isAcOn) DockColors.GREEN else cTxt)
            }
        }
        onUserActivity()
    }

    private fun closeAirflow() { airflowWin?.let { v -> runCatching { wm.removeView(v) } }; airflowWin = null }


    // ---- popup de nível (ventilação): escolher min..max direto ----

    private fun openLevel(c: Level, anchor: View) {
        if (levelWin != null) { closeLevel(); return }
        closeVolume(); closeAirflow(); closeLevel(); closeMode(); closeTemp()
        armPopupTimer()
        val pop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(getPopupColor(), dp(18)); setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val valTv = TextView(this).apply {
            setTextColor(cAccent); textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; minWidth = dp(70)
        }
        pop.addView(valTv)

        val sliderW = dp(240); val sliderH = dp(32)
        val sliderTrack = FrameLayout(this).apply {
            background = pill(cCard, dp(16))
            layoutParams = LinearLayout.LayoutParams(sliderW, sliderH).apply { marginStart = dp(12) }
        }
        val sliderFill = View(this).apply { setBackgroundColor(cAccent) }
        sliderTrack.addView(sliderFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        pop.addView(sliderTrack)

        fun updateUI(v: Int) {
            valTv.text = if (v < 0) "_" else v.toString()
            val lo = c.min; val hi = c.hi().coerceAtLeast(lo + 1)
            val r = (v - lo).toFloat() / (hi - lo)
            val lp = sliderFill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt()
            sliderFill.layoutParams = lp
        }

        sliderTrack.setOnTouchListener { view, e ->
            armPopupTimer()
            val r = (e.x / view.width).coerceIn(0f, 1f)
            val lo = c.min; val hi = c.hi().coerceAtLeast(lo + 1)
            val v = lo + (r * (hi - lo)).toInt()
            updateUI(v)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity()
                io.execute { c.setLevel(v); main.post { refreshAll() } }
            }
            true
        }

        runCatching { 
            wm.addView(pop, createPopupParams(anchor))
            handleOutsideTouch(pop)
            levelWin = pop 
        }

        io.execute {
            val cur = c.value()
            main.post { updateUI(cur) }
        }
        onUserActivity()
    }

    private fun closeLevel() { levelWin?.let { v -> runCatching { wm.removeView(v) } }; levelWin = null }

    private fun createPopupParams(anchor: View): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = barHeightPx + dp(8)
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            @Suppress("DEPRECATION")
            x = (loc[0] + anchor.width / 2) - (wm.defaultDisplay.width / 2)
        }
    }

    private fun handleOutsideTouch(pop: View) {
        pop.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                closeAllPopups()
                true
            } else false
        }
    }

    // ---- ações / refresh ----

    private fun act(c: Control, action: () -> Unit) {
        onUserActivity()
        io.execute {
            runCatching { action() }
            val st = c.render()
            main.post { updaters[c.id]?.invoke(st) }
        }
    }

    private fun showBalloonForKey(key: String?) {
        when (key) {
            DockKeys.CAR_HVAC_DRIVER_TEMPERATURE, DockKeys.CAR_HVAC_PASS_TEMPERATURE -> {
                val c = if (key == DockKeys.CAR_HVAC_DRIVER_TEMPERATURE)
                    DockControls.ALL.find { it.id == "tempD" } as? Temp
                else
                    DockControls.ALL.find { it.id == "tempP" } as? Temp
                c?.let { openTemp(it, root /* dummy anchor */) }
            }
            DockKeys.MEDIA_VOLUME -> {
                val c = DockControls.ALL.find { it.id == "vol" } as? Volume
                c?.let { openVolume(it, root) }
            }
            DockKeys.CAR_HVAC_FAN_SPEED -> {
                openLevel(DockControls.FAN, root)
            }
        }
    }

    private fun refreshAll() {
        if (hidden) return
        io.execute {
            val standalone = listOf(DockControls.DRIVE, DockControls.FAN, DockControls.VENT_D, DockControls.VENT_P, DockControls.AUTO_CONTROL, DockControls.AIRFLOW_CONTROL)
            val controls = DockControls.ALL + standalone
            val snap = controls.map { it.id to it.render() }
            main.post {
                snap.forEach { (id, st) -> updaters[id]?.invoke(st) }
                // Garante que os popups e dashboard também atualizem
                updaters["fan_popup"]?.invoke(RenderState())
                updaters["vent_popup"]?.invoke(RenderState())
                updaters["auto_popup"]?.invoke(RenderState())
                updaters["pwr_popup"]?.invoke(RenderState())
                updaters["ac_popup"]?.invoke(RenderState())
                updaters["air_popup"]?.invoke(RenderState())
                
                // Dashboard Airflow Icons
                DockControls.AIRFLOW_OPTIONS.forEach { opt ->
                    updaters["air_${opt.label}"]?.invoke(RenderState())
                }
                updaters["proj"]?.invoke(RenderState())
                updaters["header_info"]?.invoke(RenderState())
                updaters["tempD_sync"]?.invoke(RenderState())
                
                // Dashboard dynamic updates
                updaters.filter { it.key.startsWith("quick_") }.forEach { it.value(RenderState()) }
                updaters.filter { it.key.startsWith("dash_air_") }.forEach { it.value(RenderState()) }
                updaters.filter { it.key.startsWith("hev_") }.forEach { it.value(RenderState()) }
                updaters["dash_proj"]?.invoke(RenderState())
            }
        }
    }

    // ---- atalho de projeção (CarPlay/Android Auto) ----

    private fun projTile(): View {
        val v = col(); v.isClickable = true
        val ic = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)) }
        v.addView(ic)
        v.visibility = View.GONE   // só aparece quando há projeção conectada
        v.setOnClickListener { onProjClick() }
        projView = v; projIcon = ic
        return v
    }

    // poll periódico: conexão + foco da projeção
    private val projPoll = object : Runnable {
        override fun run() { refreshProjection(); main.postDelayed(this, 2500) }
    }

    // resolve qual projeção mostrar/abrir: foco no D0 (like-matching) tem prioridade; senão a
    // última vista em foco; senão CarPlay se estiver conectado (processo rodando).
    private fun refreshProjection() {
        io.execute {
            val raw = ProjectionLauncher.topPackage()
            val fg = ProjectionLauncher.classifyProjection(raw)
            val conn: String?
            val isFg: Boolean
            if (fg != null) {
                conn = fg; isFg = true; lastProjection = fg
            } else {
                isFg = false
                // topo do D0 é um app NÃO-projeção -> é a "última tela da central" p/ voltar depois
                if (raw != null && raw != packageName) lastCentralApp = raw
                conn = lastProjection
                    ?: (if (ProjectionLauncher.carPlayConnected()) ProjectionLauncher.CARPLAY_PKG else null)
                if (conn != null) lastProjection = conn
            }
            main.post { updateProjTile(conn, isFg) }
        }
    }

    private fun updateProjTile(conn: String?, fg: Boolean) {
        projConnected = conn
        projForeground = fg

        // Sempre atualiza o atalho do Dashboard se ele existir
        updaters["dash_proj"]?.invoke(RenderState())

        val v = projView ?: return
        val ic = projIcon ?: return
        if (conn == null) {   // nada conectado -> esconde
            if (v.visibility != View.GONE) v.visibility = View.GONE
            projShownState = null
            return
        }
        if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
        val want = if (fg) "car" else conn   // na projeção = carro; fora = marca
        if (projShownState != want) {
            when {
                fg -> { ic.setImageResource(R.drawable.ic_car); ic.setColorFilter(cTxt) }
                // ícones embutidos (símbolo correto) — não dependem do getApplicationIcon, que
                // vinha como ícone padrão do Android pro pacote de projeção
                conn == ProjectionLauncher.AA_PKG -> { ic.setImageResource(R.drawable.ic_androidauto); ic.clearColorFilter() }
                else -> { ic.setImageResource(R.drawable.ic_carplay); ic.clearColorFilter() }
            }
            projShownState = want
        }
    }

    private fun onProjClick() {
        onUserActivity()
        val conn = projConnected ?: return
        val goingBack = projForeground
        io.execute {
            if (goingBack) {
                // volta pra última tela da central (não HOME); fallback HOME se não souber/lançar
                val comp = lastCentralApp?.let {
                    runCatching { packageManager.getLaunchIntentForPackage(it)?.component?.flattenToString() }.getOrNull()
                }
                if (comp != null) ProjectionLauncher.openComponent(comp) else ProjectionLauncher.goHome()
            } else {
                ProjectionLauncher.openProjection(conn)
            }
            Thread.sleep(600)
            refreshProjection()
        }
    }

    // ---- visibilidade ----

    // NÃO mostrar no toque (DOWN) quando escondida — senão a janela redimensiona no meio do
    // gesto e o deslocamento de coordenadas vira um falso swipe-down. Mostrar só via swipe-up/alça.
    private fun onUserActivity() { if (!hidden) armTimer() }
    private fun applyVisibility() { showBar() }
    private fun armTimer() {
        main.removeCallbacks(hideRunnable)
        if (SettingsStore.mode(this) == SettingsStore.MODE_AUTO)
            main.postDelayed(hideRunnable, SettingsStore.secs(this) * 1000L)
    }
    private fun buildDashboard() {
        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
        }
        dashboard = rootLayout
        root.removeAllViews()
        root.addView(rootLayout)

        val dashWidth = 1792 //- 120 // Largura levemente ajustada
        
        // Container Mestre com fundo semi-transparente
        val dashboardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // DockColors.SCREEN com 92% de opacidade (0xEB)
            val bgColor = (0xFF shl 24) or (DockColors.SCREEN and 0x00FFFFFF) // (0xEB shl 24) 92% opaco 0xFF 100%
            background = pill(bgColor, dp(40))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        rootLayout.addView(dashboardContainer, FrameLayout.LayoutParams(dashWidth, 720 - dp(80), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(20)
        })

        // Header (Data e Hora)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), dp(10), dp(40), 0)
        }
        val tvHeader = TextView(this).apply {
            textSize = 18f; setTextColor(cTxt); setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.05f
        }
        header.addView(tvHeader)

        updaters["header_info"] = {
            val sdf = java.text.SimpleDateFormat("EEEE, dd 'DE' MMMM 'DE' yyyy", java.util.Locale("pt", "BR"))
            tvHeader.text = sdf.format(java.util.Date()).uppercase()
        }
        dashboardContainer.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

        // Painel de Colunas
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = null
            setPadding(dp(20), dp(10), dp(20), dp(20))
        }
        dashboardContainer.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Colunas (Grade de 12 colunas simplificada: 4-4-4)
        val col1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        panel.addView(col1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        panel.addView(gapView(12, true)) // Gap entre colunas

        val col2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        panel.addView(col2, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        
        panel.addView(gapView(12, true)) // Gap entre colunas

        val col3 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        panel.addView(col3, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        // Coluna 1: Motorista
        col1.addView(createDashboardCard("", createHvacQuickControls("D")))
        col1.addView(gapView(12))
        col1.addView(createDashboardCard("", createTempControl(DockControls.ALL.find { it.id == "tempD" } as Temp)))
        col1.addView(gapView(12))
        col1.addView(createDashboardCard("FLUXO DE AR", createAirflowSelection("D")))
        col1.addView(gapView(12))
        col1.addView(createDashboardCard("", createLevelControl(DockControls.FAN, R.drawable.ic_fan)))
        col1.addView(gapView(12))
        col1.addView(createDashboardCard("", createLevelControl(DockControls.VENT_D, R.drawable.ic_carseat_cooler)))

        // Coluna 2: Veículo (Centro)
        col2.addView(createDashboardCard("", createBatteryCard(DockControls.ALL.find { it.id == "bat" } as Battery)))
        col2.addView(gapView(12))
        col2.addView(createDashboardCard("MODO DE CONDUÇÃO", createDriveModeSelection(DockControls.DRIVE)))
        col2.addView(gapView(12))
        col2.addView(createDashboardCard("", createAmbientTempCard(DockControls.ALL.find { it.id == "recirc" } as IconToggle)))
        col2.addView(gapView(12))
        col2.addView(createDashboardCard("VOLUME", createVolumeControl(DockControls.ALL.find { it.id == "vol" } as Volume)))

        // Coluna 3: Passageiro
        col3.addView(createDashboardCard("", createHvacQuickControls("P")))
        col3.addView(gapView(12))
        col3.addView(createDashboardCard("", createTempControl(DockControls.ALL.find { it.id == "tempP" } as Temp)))
        col3.addView(gapView(12))
        col3.addView(createDashboardCard("FLUXO DE AR", createAirflowSelection("P")))
        col3.addView(gapView(12))
        col3.addView(createDashboardCard("", createLevelControl(DockControls.FAN, R.drawable.ic_fan)))
        col3.addView(gapView(12))
        col3.addView(createDashboardCard("", createLevelControl(DockControls.VENT_P, R.drawable.ic_carseat_cooler)))
    }

    private fun createDashboardCard(title: String, content: View, active: Boolean = false): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val bg = if (active) cSurfaceSelected else cCard
            background = pill(bg, dp(28), stroke = if (active) cAccent else cLine)
            val topP = if (title.isEmpty()) dp(16) else dp(12) //altura do card default
            setPadding(dp(24), topP, dp(24), dp(20))
        }
        if (title.isNotEmpty()) {
            val tvTitle = TextView(this).apply {
                text = title.uppercase(); textSize = 13f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.2f
                setPadding(0, 0, 0, dp(12))
            }
            card.addView(tvTitle)
        }
        card.addView(content)
        return card
    }

    private fun createTempControl(c: Temp): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }

        val sliderW = dp(280); val sliderH = dp(44)
        val totalW = dp(380)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(totalW, dp(44))
        }

        fun btn(txt: String, dir: Int) = TextView(this).apply {
            text = txt; textSize = 24f; setTextColor(cTxt); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.NORMAL)
            background = pill(cSurfaceRaised, dp(22), stroke = cLine)
            isClickable = true
            setOnClickListener { onUserActivity(); io.execute { c.nudge(dir); main.post { refreshAll() } } }
        }

        container.addView(btn("−", -1), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))

        val track = FrameLayout(this).apply {
            background = pill(cTrack, dp(22))
        }
        container.addView(track, FrameLayout.LayoutParams(sliderW, sliderH, Gravity.CENTER))

        val fill = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(DockColors.CYAN, DockColors.WHITE, DockColors.ORANGE)).apply {
                cornerRadius = dp(22).toFloat()
            }
        }
        track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))

        val tv = TextView(this).apply {
            textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; includeFontPadding = false
            setShadowLayer(dp(2).toFloat(), 0f, 1f, Color.BLACK)
        }
        container.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        container.addView(btn("+", 1), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL))

        layout.addView(container)

        fun updateUI(v: Double) {
            val r = ((v - c.min) / (c.hi() - c.min)).toFloat()
            val text = "${c.fmt(v)}°C"
            val sb = SpannableStringBuilder(text)
            if (text.endsWith("°C")) {
                sb.setSpan(RelativeSizeSpan(0.8f), text.length - 2, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            tv.text = sb
            val lp = fill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt()
            fill.layoutParams = lp
        }

        val touchArea = View(this).apply { isClickable = true }
        container.addView(touchArea, FrameLayout.LayoutParams(sliderW, dp(44), Gravity.CENTER))

        touchArea.setOnTouchListener { _, e ->
            val r = (e.x / sliderW).coerceIn(0f, 1f)
            val v = (Math.round((c.min + r * (c.hi() - c.min)) / c.step) * c.step).coerceIn(c.min, c.hi())
            updateUI(v)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity(); io.execute { c.select(v); main.post { refreshAll() } }
            }
            true
        }
        updaters[c.id] = { _ -> updateUI(c.read() ?: c.min) }
        return layout
    }

    private fun createLevelControl(c: Level, iconRes: Int? = null): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(380), dp(44))
        }

        if (iconRes != null) {
            container.addView(icon(iconRes, dp(24), cMuted))
            container.addView(gapView(8, true))
        }

        val (indicator, updateVisual) = createLevelIndicator(c)

        fun btn(txt: String, action: Int) = TextView(this).apply {
            text = txt; textSize = 24f; setTextColor(cTxt); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.NORMAL)
            background = pill(cSurfaceRaised, dp(22), stroke = cLine)
            isClickable = true
            setOnClickListener {
                onUserActivity()
                val next = (c.value() + action).coerceIn(c.min, c.hi())
                updateVisual(next)
                io.execute { c.setLevel(next); main.post { refreshAll() } }
            }
        }

        container.addView(btn("−", -1), LinearLayout.LayoutParams(dp(44), dp(44)))
        container.addView(gapView(12, true))

        container.addView(indicator, LinearLayout.LayoutParams(0, dp(20), 1f))

        container.addView(gapView(12, true))
        container.addView(btn("+", 1), LinearLayout.LayoutParams(dp(44), dp(44)))

        container.setOnTouchListener { v, e ->
            val r = (e.x / v.width).coerceIn(0f, 1f)
            val vLevel = (Math.round(r * c.hi())).toInt().coerceIn(c.min, c.hi())
            updateVisual(vLevel)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity(); io.execute { c.setLevel(vLevel); main.post { refreshAll() } }
            }
            true
        }

        return container
    }

    private fun createLevelIndicator(c: Level): Pair<View, (Int) -> Unit> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val hi = c.hi().coerceAtLeast(1)
        val bars = Array(hi) { View(this) }

        fun updateVisual(v: Int) {
            bars.forEachIndexed { i, b -> b.background = pill(if (i < v) DockColors.CYAN else cTrack, dp(4)) }
        }

        bars.forEachIndexed { i, b ->
            b.background = pill(cTrack, dp(4))
            row.addView(b, LinearLayout.LayoutParams(0, dp(14), 1f).apply { if (i > 0) marginStart = dp(8) })
        }

        updaters[c.id] = { _ -> updateVisual(c.value()) }
        updateVisual(c.value())

        return Pair(row, ::updateVisual)
    }


    private fun createHvacQuickControls(side: String): View {
        val totalW = dp(380)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(totalW, dp(44))
        }

        fun quickBtn(label: String, key: String, onV: String = "1", offV: String = "0") = TextView(this).apply {
            text = label; textSize = 11f; setTextColor(cMuted); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.1f
            background = pill(cSurfaceRaised, dp(18), stroke = cLine)

            fun update(forcedState: Boolean? = null) {
                val isOn = forcedState ?: (VehicleClient.getData(key) == onV)
                background = pill(if (isOn) cSurfaceSelected else cSurfaceRaised, dp(18), stroke = if (isOn) cAccent else cLine)
                setTextColor(if (isOn) DockColors.CYAN else cMuted)
            }

            setOnClickListener {
                onUserActivity()
                val isOn = VehicleClient.getData(key) == onV
                update(!isOn)
                val next = if (isOn) offV else onV
                io.execute { VehicleClient.set(key, next); main.post { refreshAll() } }
            }

            updaters["quick_${side}_$key"] = { update(null) }
            update()
        }

        layout.addView(quickBtn("POWER", DockKeys.CAR_HVAC_POWER_MODE), LinearLayout.LayoutParams(0, dp(38), 1f))
        layout.addView(quickBtn("A/C", DockKeys.CAR_HVAC_AC_ENABLE), LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(8) })
        layout.addView(quickBtn("AUTO", DockKeys.CAR_HVAC_AUTO_ENABLE), LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(8) })
        layout.addView(quickBtn("SYNC", DockKeys.CAR_HVAC_SYNC_ENABLE), LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(8) })

        return layout
    }

    private fun createBatteryCard(c: Battery): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val batteryIcon = icon(R.drawable.battery_charging_medium, dp(20), DockColors.GREEN)
        topRow.addView(batteryIcon)
        
        val label = TextView(this).apply {
            text = "BATERIA"; textSize = 18f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.2f; setPadding(dp(8), 0, 0, 0)
        }
        topRow.addView(label)

        val valueTxt = TextView(this).apply {
            textSize = 22f; setTextColor(cTxt); setTypeface(typeface, Typeface.BOLD); text = "0%"
            setPadding(dp(8), 0, 0, 0)
        }
        topRow.addView(valueTxt)

        val infoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(12), 0, 0, 0)
        }
        
        val autonomiaTv = TextView(this).apply {
            text = "AUTONOMIA -- KM"; textSize = 13f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD)
        }
        infoContainer.addView(autonomiaTv)
        
        val ecoLabel = TextView(this).apply {
            text = "ECONOMIA: --"; textSize = 13f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        }
        infoContainer.addView(ecoLabel)
        
        topRow.addView(infoContainer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        layout.addView(topRow)

        val track = FrameLayout(this).apply {
            background = pill(cTrack, dp(15))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(15)).apply { topMargin = dp(12) }
        }
        val fill = View(this).apply {
            background = pill(DockColors.GREEN, dp(15))
        }
        track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        layout.addView(track)

        // Seção de Consumo (Simplificada com Views)
        val consumptionCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(cSurfaceRaised, dp(12), stroke = cLine)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }

        fun consumptionItem(label: String, color: Int) = TextView(this).apply {
            text = "$label: --"; textSize = 11f; setTextColor(color)
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }

        val energyTv = consumptionItem("ENERGIA", DockColors.CYAN)
        val fuelTv = consumptionItem("GASOLINA", DockColors.AMBER)

        consumptionCard.addView(energyTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        consumptionCard.addView(View(this).apply { background = pill(cLine, 1) }, LinearLayout.LayoutParams(dp(1), dp(16)))
        consumptionCard.addView(fuelTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(consumptionCard)

        updaters[c.id] = { st ->
            val v = (st.text?.toString()?.replace("%", "")?.toIntOrNull() ?: 0).coerceIn(0, 100)
            valueTxt.text = "$v%"

            batteryIcon.setImageResource(st.icon)
            batteryIcon.setColorFilter(st.color)
            fill.background = pill(st.color, dp(15))

            val range = VehicleClient.getData(DockKeys.CAR_EV_INFO_ELECTRIC_MODE_REMAIN_ODOMETER) ?: "--"
            val autoSb = android.text.SpannableStringBuilder("AUTONOMIA $range KM")
            if (range != "--") {
                val start = 10 // "AUTONOMIA "
                val end = start + range.length
                autoSb.setSpan(android.text.style.ForegroundColorSpan(cTxt), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            autonomiaTv.text = autoSb

            // Atualiza Economic Level
            val rawEco = VehicleClient.getData(DockKeys.CAR_EV_INFO_ECONOMIC_GUIDE_LEVEL)?.toFloatOrNull() ?: 0f
            if (rawEco > 0) {
                if (firstEcoValue) {
                    minEconomicLevel = rawEco
                    maxEconomicLevel = rawEco
                    firstEcoValue = false
                } else {
                    if (rawEco > maxEconomicLevel) maxEconomicLevel = rawEco
                    if (rawEco < minEconomicLevel) minEconomicLevel = rawEco
                }
                
                val ecoColor = when {
                    rawEco >= 70f -> DockColors.CYAN
                    rawEco >= 40f -> DockColors.GREEN
                    else -> DockColors.AMBER
                }
                
                val sb = android.text.SpannableStringBuilder("ECONOMIA: ${String.format("%.0f", rawEco)} (${String.format("%.0f", minEconomicLevel)} / ${String.format("%.0f", maxEconomicLevel)})")
                sb.setSpan(android.text.style.ForegroundColorSpan(ecoColor), 10, sb.indexOf(" ("), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ecoLabel.text = sb
            } else {
                ecoLabel.text = "ECONOMIA: --"
            }

            // Atualiza Consumo
            val energy = VehicleClient.getData(DockKeys.CAR_EV_INFO_CYCLE_ENERGY_CONSUME_INFO) ?: "--"
            val fuel = VehicleClient.getData(DockKeys.CAR_EV_INFO_CYCLE_FUEL_CONSUME_INFO) ?: "--"
            energyTv.text = "ENERGIA: $energy KW"
            fuelTv.text = "GASOLINA: $fuel L"

            val lp = fill.layoutParams as FrameLayout.LayoutParams
            track.post {
                lp.width = (track.width * (v / 100f)).toInt()
                fill.layoutParams = lp
            }
        }
        return layout
    }

    private fun createDriveModeSelection(c: Mode): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val options = listOf(0 to "HEV", 1 to "Prioridade EV", 3 to "EV")
        
        val tiles = options.map { (_, label) ->
            createModeTile(label)
        }

        val hevSub = createHevSubCard(c)

        val updateUI = { targetMode: Int? ->
            val curMode = targetMode ?: c.cur()
            options.forEachIndexed { i, (mode, _) ->
                val active = curMode == mode
                val tile = tiles[i] as LinearLayout
                val icon = tile.getChildAt(0) as ImageView
                val tvLabel = tile.getChildAt(1) as TextView
                val modeColor = c.colors[mode] ?: DockColors.GREEN

                tile.background = pill(if (active) cSurfaceSelected else cSurfaceRaised, dp(16), stroke = if (active) modeColor else cLine)
                icon.setColorFilter(if (active) modeColor else cMuted)
                tvLabel.setTextColor(if (active) cTxt else cMuted)
            }
            val isHev = curMode == 0
            hevSub.alpha = if (isHev) 1f else 0.4f
            hevSub.isEnabled = isHev
        }

        tiles.forEachIndexed { i, tile ->
            modeRow.addView(tile, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
            tile.setOnClickListener {
                onUserActivity()
                val mode = options[i].first
                updateUI(mode)
                io.execute { c.select(mode); main.post { refreshAll() } }
            }
        }

        layout.addView(modeRow)
        layout.addView(gapView(16))
        layout.addView(hevSub)

        updaters[c.id] = { _ -> updateUI(null) }
        updateUI(null)

        return layout
    }

    private fun createHevSubCard(c: Mode): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(cCard, dp(16), stroke = cLine)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        var updateIntelBtn: (Boolean?) -> Unit = {}
        var updateSliderUI: (Int, Boolean?) -> Unit = { _, _ -> }

        val intelBtn = TextView(this).apply {
            text = "INTELIGENTE"; textSize = 10f; setTextColor(cMuted); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.05f
            background = pill(cCard, dp(12), stroke = cLine)
            isClickable = true

            updateIntelBtn = { forcedActive ->
                val active = forcedActive ?: (c.curStrategy() == 1)
                background = pill(if (active) cSurfaceSelected else cCard, dp(12), stroke = if (active) DockColors.AMBER else cLine)
                setTextColor(if (active) DockColors.AMBER else cMuted)
            }

            setOnClickListener {
                if (!layout.isEnabled) return@setOnClickListener
                onUserActivity()
                updateIntelBtn(true)
                updateSliderUI(c.curHevSocInt(), false)
                io.execute { c.select(0, strategy = 1); main.post { refreshAll() } }
            }

            updaters["hev_strategy_1"] = { updateIntelBtn(null) }
            updateIntelBtn(null)
        }
        layout.addView(intelBtn, LinearLayout.LayoutParams(dp(110), dp(32)))

        val sliderArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        val socLabel = TextView(this).apply {
            textSize = 16f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD)
            text = "SAVE -%"; minWidth = dp(75)
        }
        sliderArea.addView(socLabel)

        val sliderW = dp(220); val sliderH = dp(20) //barra do save% tamanho e especura
        val track = FrameLayout(this).apply {
            background = pill(cTrack, dp(5))
            layoutParams = LinearLayout.LayoutParams(sliderW, sliderH).apply { marginStart = dp(8) }
        }
        val fill = View(this).apply {
            background = pill(DockColors.AMBER, dp(5))
        }
        track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        sliderArea.addView(track)

        updateSliderUI = { soc, forcedActive ->
            val isSave = forcedActive ?: (c.curStrategy() == 2)
            socLabel.text = "SAVE $soc%"
            socLabel.setTextColor(if (isSave) cTxt else cMuted)

            val r = (soc - c.minSoc).toFloat() / (c.maxSoc - c.minSoc)
            val lp = fill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt()
            fill.layoutParams = lp
            fill.background = pill(if (isSave) DockColors.AMBER else cMuted, dp(5))
            sliderArea.alpha = if (isSave) 1f else 0.4f
        }

        track.setOnTouchListener { _, e ->
            if (!layout.isEnabled) return@setOnTouchListener true
            val r = (e.x / sliderW).coerceIn(0f, 1f)
            val soc = c.minSoc + (r * (c.maxSoc - c.minSoc)).toInt()
            
            if (e.action == MotionEvent.ACTION_MOVE) {
                updateSliderUI(soc, c.curStrategy() == 2)
            }
            
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity()
                updateSliderUI(soc, true)
                updateIntelBtn(false)
                io.execute { c.select(0, strategy = 2, soc = soc); main.post { refreshAll() } }
            }
            true
        }

        layout.addView(sliderArea)

        updaters["hev_sub_card"] = {
            updateSliderUI(c.curHevSocInt(), null)
        }

        return layout
    }

    private fun createModeTile(label: String): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            isClickable = true
        }
        val icon = icon(R.drawable.ic_bolt, dp(24), cMuted)
        layout.addView(icon)
        val tvLabel = TextView(this).apply {
            text = label.uppercase(); textSize = 11f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        layout.addView(tvLabel)
        return layout
    }

    /** Card de Clima Ambiente: Interna, Externa e Recirculação integrada como botão de card. */
    private fun createAmbientTempCard(c: IconToggle): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }

        val internal = createTempInfo(R.drawable.ic_thermo, DockColors.ORANGE, "INTERNA", "tempIn")
        layout.addView(internal, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val img = icon(R.drawable.ic_recirc_closed, dp(28), cMuted)
        layout.addView(img, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(12); marginEnd = dp(12) })

        val external = createTempInfo(R.drawable.ic_external_thermo, DockColors.CYAN, "EXTERNA", "tempOut")
        layout.addView(external, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        layout.setOnClickListener { onUserActivity(); io.execute { c.flip(); main.post { refreshAll() } } }

        updaters[c.id] = { st ->
            val on = st.on
            img.setImageResource(if (on) R.drawable.ic_recirc_closed else R.drawable.ic_recirc_open)
            img.setColorFilter(if (on) DockColors.CYAN else cMuted)
        }

        return layout
    }


    private fun createTempInfo(res: Int, color: Int, label: String, id: String): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val img = icon(res, dp(24), color)
        layout.addView(img)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        val tvVal = TextView(this).apply {
            text = "--,−°"; textSize = 22f; setTextColor(cTxt); setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
        }
        val tvLabel = TextView(this).apply {
            text = label; textSize = 9f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
        }
        col.addView(tvVal); col.addView(tvLabel)
        layout.addView(col)

        updaters[id] = { st ->
            tvVal.text = st.text?.toString() ?: "--°"
        }
        return layout
    }


    private fun createAirflowSelection(side: String): View {
        val totalW = dp(380)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(totalW, dp(54))
        }

        val options = DockControls.AIRFLOW_OPTIONS
        val icons = ArrayList<ImageView>()

        val updateUI = { targetOpt: AirflowOption? ->
            options.forEachIndexed { i, opt ->
                val iv = icons.getOrNull(i) ?: return@forEachIndexed
                val isCur = if (targetOpt != null) opt == targetOpt else DockControls.AIRFLOW_CONTROL.currentOption() == opt
                iv.background = pill(if (isCur) cSurfaceSelected else cSurfaceRaised, dp(14), stroke = if (isCur) cAccent else cLine)
                iv.setColorFilter(if (isCur) DockColors.CYAN else cMuted)
            }
        }

        options.forEachIndexed { i, opt ->
            val iv = icon(opt.icon, cMuted, 28).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = pill(cSurfaceRaised, dp(14), stroke = cLine)
                isClickable = true

                setOnClickListener {
                    onUserActivity()
                    updateUI(opt)
                    io.execute { DockControls.AIRFLOW_CONTROL.select(opt); main.post { refreshAll() } }
                }
            }
            icons.add(iv)
            layout.addView(iv, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (i > 0) marginStart = dp(8)
            })

            updaters["dash_air_${side}_${opt.label}"] = { updateUI(null) }
        }
        updateUI(null)
        return layout
    }

    private fun createVolumeControl(c: Volume): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        // Atalho de Projeção Dinâmico no Dashboard
        val projArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) }
            visibility = View.GONE
            isClickable = true
            setOnClickListener { onProjClick() }
        }
        val projImg = ImageView(this).apply { layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER) }
        projArea.addView(projImg)
        layout.addView(projArea)

        updaters["dash_proj"] = { _ ->
            val conn = projConnected
            val fg = projForeground
            if (conn == null) {
                projArea.visibility = View.GONE
            } else {
                projArea.visibility = View.VISIBLE
                when {
                    fg -> { projImg.setImageResource(R.drawable.ic_car); projImg.setColorFilter(cTxt) }
                    conn == ProjectionLauncher.AA_PKG -> { projImg.setImageResource(R.drawable.ic_androidauto); projImg.clearColorFilter() }
                    else -> { projImg.setImageResource(R.drawable.ic_carplay); projImg.clearColorFilter() }
                }
            }
        }

//        layout.addView(icon(c.icon, cMuted, 36))
        val volumeIcon = icon(c.icon, cMuted, 28)

        layout.addView(
            volumeIcon,
            LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginStart = dp(16)
            }
        )
        val sliderW = dp(330); val sliderH = dp(20) //Barra de volume
        val track = FrameLayout(this).apply {
            background = pill(cTrack, dp(7))
            layoutParams = LinearLayout.LayoutParams(sliderW, sliderH).apply { marginStart = dp(12) }
        }
        val fill = View(this).apply { background = pill(DockColors.CYAN, dp(7)) }
        track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        layout.addView(track)

        var canGoPast12 = false
        var currentV = 0

        fun updateUI(v: Int) {
            val color = if (v > 12) DockColors.RED else DockColors.CYAN
            val r = v.toFloat() / c.hi()
            val lp = fill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt(); fill.layoutParams = lp
            fill.background = pill(color, dp(7))
        }

        track.setOnTouchListener { _, e ->
            val r = (e.x / sliderW).coerceIn(0f, 1f)
            var v = (r * c.hi()).toInt()

            if (e.action == MotionEvent.ACTION_DOWN) {
                canGoPast12 = currentV >= 12
            }

            if (!canGoPast12) v = minOf(v, 12)

            updateUI(v)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity()
                currentV = v
                io.execute { c.set(v); main.post { refreshAll() } }
            }
            true
        }
        updaters[c.id] = { st ->
            val v = c.value()
            currentV = v
            updateUI(v)
            if (st.icon != 0) volumeIcon.setImageResource(st.icon)
        }
        return layout
    }

    private fun gapView(size: Int, horizontal: Boolean = false): View = View(this).apply {
        layoutParams = if (horizontal) LinearLayout.LayoutParams(dp(size), 1) else LinearLayout.LayoutParams(1, dp(size))
    }

    private fun showBar() {
        main.removeCallbacks(hideRunnable)
        if (hidden) {
            hidden = false
            bar?.visibility = View.VISIBLE
            dashboard?.visibility = View.VISIBLE
            handle.visibility = View.GONE
            
            val visualMode = SettingsStore.visualMode.value
            val isDash = visualMode == SettingsStore.VISUAL_DASHBOARD
            params.width = if (isDash) (1792 - 160) else WindowManager.LayoutParams.MATCH_PARENT
            params.height = if (isDash) 720 else barHeightPx
            params.gravity = Gravity.BOTTOM or (if (isDash) Gravity.END else Gravity.START)
            
            runCatching { wm.updateViewLayout(root, params) }
            broadcastBarState()
            refreshAll()
        }
        armTimer()
    }
    private fun hideBar(manual: Boolean = false) {
        // gesto (manual) esconde em qualquer modo; o timer só esconde no modo auto
        if (!manual && SettingsStore.mode(this) != SettingsStore.MODE_AUTO) return
        closeAllPopups()
        hidden = true
        bar?.visibility = View.GONE
        dashboard?.visibility = View.GONE
        handle.visibility = View.VISIBLE
        
        params.width = dp(100)
        params.height = handleHeightPx
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        
        runCatching { wm.updateViewLayout(root, params) }
        broadcastBarState()
    }

    // Avisa apps que reservam o rodapé (haval-radio) qual a altura ocupada agora.
    private fun broadcastBarState() {
        val h = if (hidden) HANDLE_DP else SettingsStore.barHeight(this)
        runCatching {
            sendBroadcast(
                Intent(ACTION_BAR_STATE)
                    .putExtra(EXTRA_VISIBLE, !hidden)
                    .putExtra(EXTRA_HEIGHT_DP, h)
            )
        }
    }

    private fun registerRequestReceiver() {
        val filter = IntentFilter(ACTION_REQUEST_STATE)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(requestReceiver, filter, Context.RECEIVER_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(requestReceiver, filter)
    }

    // ---- utils ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun blend(c1: Int, c2: Int, ratio: Float): Int {
        val ir = 1f - ratio
        val a = (Color.alpha(c1) * ir + Color.alpha(c2) * ratio).toInt()
        val r = (Color.red(c1) * ir + Color.red(c2) * ratio).toInt()
        val g = (Color.green(c1) * ir + Color.green(c2) * ratio).toInt()
        val b = (Color.blue(c1) * ir + Color.blue(c2) * ratio).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun pill(fill: Int, radius: Int, topOnly: Boolean = false, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            if (topOnly) cornerRadii = floatArrayOf(
                radius.toFloat(), radius.toFloat(), radius.toFloat(), radius.toFloat(), 0f, 0f, 0f, 0f)
            else cornerRadius = radius.toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun buildNotification(): Notification {
        val channelId = "haval_dock_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(channelId, "Haval Dock", NotificationManager.IMPORTANCE_MIN)
                nm.createNotificationChannel(channel)
            }
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, channelId) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Haval Dock").setContentText("Barra inferior ativa")
            .setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build()
    }

    private class TouchFrame(
        context: Context,
        val onTouch: () -> Unit,
        val onSwipeDown: () -> Unit,
        val onSwipeUp: () -> Unit,
    ) : FrameLayout(context) {
        private val threshold = 20 * context.resources.displayMetrics.density
        private var downY = 0f
        private var downX = 0f
        private var fired = false
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
            if (ev == null) return super.dispatchTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.y; downX = ev.x; fired = false; onTouch() }
                MotionEvent.ACTION_MOVE -> {
                    if (fired) return true
                    val dy = ev.y - downY; val dx = ev.x - downX
                    if (kotlin.math.abs(dy) > threshold && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        fired = true
                        // cancela o toque nos filhos (não aciona botão) e dispara o gesto
                        val cancel = MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel); cancel.recycle()
                        if (dy > 0) onSwipeDown() else onSwipeUp()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (fired) { fired = false; return true }
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    companion object {
        private const val NOTIF_ID = 42

        /** Altura da alça (oculta), em dp. */
        const val HANDLE_DP = 22

        /** Broadcast do estado da barra p/ outros apps (ex.: haval-radio) reservarem o rodapé. */
        const val ACTION_BAR_STATE = "br.com.redesurftank.havaldock.BAR_STATE"
        /** Outro app pode pedir o estado atual; respondemos com ACTION_BAR_STATE. */
        const val ACTION_REQUEST_STATE = "br.com.redesurftank.havaldock.REQUEST_BAR_STATE"
        const val EXTRA_VISIBLE = "visible"
        const val EXTRA_HEIGHT_DP = "height_dp"

        fun start(context: Context) {
            val i = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }
        fun stop(context: Context) { context.stopService(Intent(context, OverlayService::class.java)) }
    }
}
