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
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.utils.MPPointD
import br.com.redesurftank.havaldock.DockKeys
import br.com.redesurftank.havaldock.data.AirflowOption
import br.com.redesurftank.havaldock.data.Battery
import br.com.redesurftank.havaldock.data.Control
import br.com.redesurftank.havaldock.data.DockColors
import br.com.redesurftank.havaldock.data.DockControls
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
 * OverlayService gerencia a Toolbar inferior e os Dashboards (Normal e Light).
 * Serviço responsável por exibir a interface de usuário sobreposta à multimídia.
 */
class OverlayService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: TouchFrame
    
    private var bar: LinearLayout? = null
    private var topLine: View? = null
    private val sectionLayouts = ArrayList<LinearLayout>()
    private var contentLayout: FrameLayout? = null
    private lateinit var handle: View

    private val updaters = HashMap<String, (RenderState) -> Unit>()
    
    private var volWin: View? = null
    private var levelWin: View? = null
    private var modeWin: View? = null
    private var tempWin: View? = null
    private var hidden = false

    private var projView: View? = null
    private var projIcon: ImageView? = null
    private var projConnected: String? = null
    private var projForeground = false
    private var projShownState: String? = null
    private var lastProjection: String? = null
    private var lastCentralApp: String? = null

    // Proteção Anti-Flicker e Estado Manual
    private var lastManualSoc: Int = -1
    private var lastManualSocTime: Long = 0
    private var lastManualMode: Int = -1
    private var lastManualStrategy: Int = -1

    private var lastManualVol: Int = -1
    private var lastManualVolTime: Long = 0

    private var lastManualTempD: Double = -1.0
    private var lastManualTempDTime: Long = 0
    private var lastManualTempP: Double = -1.0
    private var lastManualTempPTime: Long = 0

    private var sessionStartOdo: Double = 0.0

    private var flashView: TextView? = null
    private val flashHideRunnable = Runnable {
        flashView?.let { runCatching { wm.removeView(it) } }
        flashView = null
    }

    private fun showFlash(text: String) {
        main.removeCallbacks(flashHideRunnable)
        if (flashView == null) {
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 42f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = pill(Color.parseColor("#CC091017"), dp(24))
                setPadding(dp(60), dp(30), dp(60), dp(30))
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            runCatching { wm.addView(tv, lp); flashView = tv }
        } else {
            flashView?.text = text
        }
        main.postDelayed(flashHideRunnable, 5000L)
    }

    private var currentDashPage = 0

    private data class PowerPoint(val consumption: Float, val regen: Float, val outputPct: Float)

    // Histórico para o Gráfico de Energia
    private val powerHistory = ArrayList<PowerPoint>() //consumo, regeneracao e percentual de acelerador eletrico
    private var chartLimit = 30 // 1min=30, 3min=90, 5min=150
    private val CHART_MAX_POINTS = 150
    private var mockIndex = 0
    private val mockSequence = listOf(-1.0, -1.0, 10.0, 20.0, 35.0, -20.0, -18.0, -10.0, 0.0, 1.0, 1.2, 1.0, 30.0, 35.0, 5.0, 4.0, 6.0, 5.0)

    private val chartTicker = object : Runnable {
        override fun run() {
            if (!hidden && (SettingsStore.visualMode.value == SettingsStore.VISUAL_DASHBOARD_LIGHT)) {
                updateChartData()
            }
            main.postDelayed(this, 2000L)
        }
    }

    private fun updateChartData() {
        val isDash = SettingsStore.visualMode.value == SettingsStore.VISUAL_DASHBOARD || SettingsStore.visualMode.value == SettingsStore.VISUAL_DASHBOARD_LIGHT
        if (!isDash) return

        val isSim = SettingsStore.simulationEnabled.value
        val volt = if (isSim) 328.0 else (VehicleClient.getData(DockKeys.CAR_EV_INFO_POWER_BATTERY_VOLTAGE)?.toDoubleOrNull() ?: 0.0) //CAR_EV_INFO_POWER_BATTERY_VOLTAGE voltagem da bateria de tracao
        val curr = if (isSim) {
            val v = mockSequence[mockIndex % mockSequence.size]
            mockIndex++
            v
        } else {
            VehicleClient.getData(DockKeys.CAR_EV_INFO_CUR_CHARGE_CURRENT )?.toDoubleOrNull() ?: 0.0 //CAR_EV_INFO_CUR_CHARGE_CURRENT corrente da bateria de tracao

        }
        
        if (curr == 0.0 && !isSim) return 

        // Ambos em kW para manter a mesma escala visual
        val pKw = (kotlin.math.abs(volt * curr) / 1000.0).toFloat()
        val consumption = if (curr > 0) pKw else 0f
        val regen = if (curr < 0) pKw else 0f
        val outputPct = VehicleClient.getData(DockKeys.CAR_EV_INFO_ENERGY_OUTPUT_PERCENTAGE)?.toFloatOrNull() ?: 0f
        
        powerHistory.add(PowerPoint(consumption, regen, outputPct))
        if (powerHistory.size > CHART_MAX_POINTS) powerHistory.removeAt(0)
        
        if (currentDashPage == 1) {
            main.post { updaters["power_chart"]?.invoke(RenderState()) }
        }
    }

    private val barHeightPx: Int get() = dp(SettingsStore.barHeight(this))
    private val handleHeightPx by lazy { dp(HANDLE_DP) }
    private val trackPx by lazy { dp(30) }

    private val cAccent = DockColors.CYAN
    private val cEmerald = DockColors.EMERALD
    private val cTxt = DockColors.ON_SURFACE
    private val cMuted = DockColors.ON_SURFACE_MUTED
    private val cCard = DockColors.SURFACE
    private val cLine = DockColors.OUTLINE
    private val cOnAccent = Color.BLACK
    private val cTrack = DockColors.TRACK
    private val cSurfaceSelected = DockColors.SURFACE_SELECTED
    private val cSurfaceRaised = DockColors.SURFACE_RAISED

    private val typeface by lazy { ResourcesCompat.getFont(this, R.font.font_family_clima) }
    private var dashboard: View? = null

    private fun getBarColor(): Int {
        val opacity = SettingsStore.opacity(this)
        val alpha = (opacity * 255) / 100
        return Color.argb(alpha, 7, 10, 14)
    }

    private fun getPopupColor(): Int {
        return if (SettingsStore.isItemFrameEnabled(this)) Color.parseColor("#F2070A0E") else getBarColor()
    }

    private val hideRunnable = Runnable { hideBar() }
    private val closePopupsRunnable = Runnable { closeAllPopups() }

    private val listener = object : IListener.Stub() {
        override fun onDataChanged(key: String?, value: String?) {
            main.post {
                refreshAll()
                if (SettingsStore.visualMode.value == SettingsStore.VISUAL_BALLOONS) showBalloonForKey(key)
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsStore.KEY_MODE) applyVisibility()
        if (key == SettingsStore.KEY_SECS && !hidden) armTimer()
        if (key == SettingsStore.KEY_OPACITY) main.post { bar?.setBackgroundColor(getBarColor()) }
        if (key == SettingsStore.KEY_ITEM_FRAME) main.post { updateItemFrame() }
        if (key != null && key.startsWith("sec") && key.endsWith("_x")) main.post { updateSectionsPosition() }
        if (key == SettingsStore.KEY_BAR_HEIGHT || key == SettingsStore.KEY_VISUAL_MODE || key == SettingsStore.KEY_LIGHT_FLOATING) {
            if (key == SettingsStore.KEY_BAR_HEIGHT) params.height = barHeightPx
            if (!hidden) main.post {
                if (::root.isInitialized) runCatching { wm.removeView(root) }
                buildOverlay()
            }
            broadcastBarState()
        }
    }

    private val requestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { broadcastBarState() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Ponto de entrada do serviço. Configura o WindowManager, cria a interface inicial,
     * registra listeners de configurações e inicia a comunicação com o veículo.
     */
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        SettingsStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        registerRequestReceiver()
        broadcastBarState()
        VehicleClient.addConnectionListener(onVehicleConnected)
        io.execute { runCatching { VehicleClient.registerListener(DockControls.MONITORED, listener) } }
        HvacPanel.ensureEnabled()
        refreshAll()
        main.postDelayed(projPoll, 1200)
        main.post(chartTicker)
    }

    private val onVehicleConnected: () -> Unit = { refreshAll() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { applyVisibility(); return START_STICKY }

    /**
     * Encerra o serviço, removendo a interface da tela, cancelando timers e
     * desregistrando todos os listeners de rádio e veículo para poupar recursos.
     */
    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(hideRunnable); main.removeCallbacks(closePopupsRunnable); main.removeCallbacks(projPoll)
        main.removeCallbacks(chartTicker); main.removeCallbacks(flashHideRunnable)
        flashView?.let { runCatching { wm.removeView(it) } }
        closeAllPopups()
        runCatching { SettingsStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener) }
        runCatching { unregisterReceiver(requestReceiver) }
        runCatching { sendBroadcast(Intent(ACTION_BAR_STATE).putExtra(EXTRA_VISIBLE, false).putExtra(EXTRA_HEIGHT_DP, 0)) }
        VehicleClient.removeConnectionListener(onVehicleConnected)
        io.execute { runCatching { VehicleClient.unregisterListener(listener) } }
        runCatching { wm.removeView(root) }
    }

    /**
     * Constrói a janela principal do overlay (WindowManager). Define se será exibida
     * a barra compacta (Toolbar) ou o Dashboard (Normal/Light) com base nas configurações.
     */
    private fun buildOverlay() {
        val visualMode = SettingsStore.visualMode.value
        if (visualMode == SettingsStore.VISUAL_BALLOONS) return

        val isDash = visualMode == SettingsStore.VISUAL_DASHBOARD || visualMode == SettingsStore.VISUAL_DASHBOARD_LIGHT
        val h = if (hidden) handleHeightPx else (if (isDash) 720 else barHeightPx)
        val w = if (hidden) dp(100) else (if (isDash) 1770 else WindowManager.LayoutParams.MATCH_PARENT)
        val g = if (hidden) (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) else (Gravity.BOTTOM or (if (isDash) Gravity.END else Gravity.START))

        params = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = g }

        root = TouchFrame(this, { onUserActivity() }, { hideBar(manual = true) }, { showBar() })
        updaters.clear()
        handle = View(this).apply {
            background = pill(Color.parseColor("#40FFFFFF"), dp(2))
            visibility = if (hidden) View.VISIBLE else View.GONE
            setOnClickListener { showBar() }
        }

        if (isDash) {
            if (visualMode == SettingsStore.VISUAL_DASHBOARD_LIGHT) buildDashboardLight() else buildDashboard()
            dashboard?.visibility = if (hidden) View.GONE else View.VISIBLE
        } else {
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getBarColor()); visibility = if (hidden) View.GONE else View.VISIBLE }
            bar = b
            buildOverlayContent()
            root.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root.addView(handle, FrameLayout.LayoutParams(dp(100), dp(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) })
        wm.addView(root, params)
        refreshAll()
        io.execute { refreshProjection() }
    }

    private fun buildOverlayContent() {
        val top = View(this).apply { setBackgroundColor(cAccent) }
        topLine = top
        bar?.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)))
        val c = FrameLayout(this).apply { setPadding(0, 0, 0, 0) }
        contentLayout = c
        bar?.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        buildSections(c); updateItemFrame(); updateSectionsPosition()
    }

    private fun updateSectionsPosition() {
        sectionLayouts.forEachIndexed { i, sec ->
            val lp = sec.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = dp(SettingsStore.sectionX(this, i))
            lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            sec.layoutParams = lp
        }
    }

    private fun updateItemFrame() {
        val enabled = SettingsStore.isItemFrameEnabled(this)
        topLine?.visibility = if (enabled) View.GONE else View.VISIBLE
        bar?.setBackgroundColor(if (enabled) Color.TRANSPARENT else getBarColor())
        sectionLayouts.forEach { sec ->
            sec.background = if (enabled) pill(Color.parseColor("#F2070A0E"), dp(18)) else null
            sec.setPadding(if (enabled) dp(12) else 0, 0, if (enabled) dp(12) else 0, 0)
        }
    }

    private fun buildSections(content: FrameLayout) {
        sectionLayouts.clear()
        val secs = Array(4) { rowSection() }
        secs.forEach { sectionLayouts.add(it); content.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)) }
        for (c in DockControls.ALL) { if (c.section < secs.size) secs[c.section].addView(tile(c)) }
        secs[0].addView(projTile())
    }

    private fun rowSection() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun tile(c: Control): View = when (c) {
        is Temp -> tileTemp(c); is Level -> tileLevel(c); is Volume -> tileVolume(c); is TxtToggle -> tileTxt(c)
        is MaxAc -> tileMax(c); is IconToggle -> tileIconToggle(c); is Battery -> tileBattery(c)
        is Info -> tileInfo(c); is Regen -> tileRegen(c); else -> View(this)
    }

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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(SettingsStore.barHeight(this@OverlayService) - 8)).apply { marginStart = dp(ms) }
            isClickable = true
        }
        val tv = TextView(this).apply { setTextColor(cAccent); textSize = 34f; setTypeface(typeface, Typeface.BOLD); text = "—°"; gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0) }
        row.addView(tv)
        updaters[c.id] = { st ->
            val now = System.currentTimeMillis()
            val cur = c.read() ?: c.min
            val manualVal = if (c.id == "tempD") lastManualTempD else lastManualTempP
            val manualTime = if (c.id == "tempD") lastManualTempDTime else lastManualTempPTime
            if (now - manualTime > 2000 || cur == manualVal) { tv.text = st.text; tv.setTextColor(st.color) }
        }
        var startX = 0f
        row.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startX = event.x; false }
                MotionEvent.ACTION_UP -> {
                    val diff = event.x - startX
                    if (kotlin.math.abs(diff) > dp(40)) {
                        onUserActivity(); io.execute { val fan = DockControls.FAN; fan.setLevel(fan.value() + if (diff > 0) 1 else -1); main.post { refreshAll() } }
                        true
                    } else false
                }
                else -> false
            }
        }
        row.setOnClickListener { onUserActivity(); openTemp(c, row) }
        return row
    }

    private fun tileLevel(c: Level): View {
        val v = col(); v.isClickable = true; val ic = icon(c.icon, cTxt, 42); val track = makeTrack()
        v.addView(ic); v.addView(track.first)
        updaters[c.id] = { st -> ic.setColorFilter(st.color); setTrack(track.second, st.ratio) }
        v.setOnClickListener { if (c.picker) { onUserActivity(); openLevel(c, v) } else act(c) { c.cycle() } }
        return v
    }

    private fun tileVolume(c: Volume): View {
        val v = col(); v.isClickable = true; val ic = icon(c.icon, cTxt, 42); val track = makeTrack()
        v.addView(ic); v.addView(track.first)
        updaters[c.id] = { st ->
            val now = System.currentTimeMillis(); val curV = c.value()
            if (now - lastManualVolTime > 2000 || curV == lastManualVol) { setTrack(track.second, st.ratio); if (st.icon != 0) ic.setImageResource(st.icon) }
        }
        v.setOnClickListener { onUserActivity(); openVolume(c, v) }
        return v
    }

    private fun tileTxt(c: TxtToggle): View = textTile(c, c.label) { c.flip() }
    private fun tileMax(c: MaxAc): View = textTile(c, c.label) { c.flip() }

    private fun textTile(c: Control, label: String, onFlip: () -> Unit): View {
        val v = col(); v.isClickable = true
        val tv = TextView(this).apply { text = label; setTextColor(cMuted); textSize = 28f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(6), 0, dp(6), 0) }
        val ul = View(this)
        v.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        v.addView(ul, LinearLayout.LayoutParams(dp(28), dp(3)).apply { topMargin = dp(7) })
        updaters[c.id] = { st -> tv.text = label; tv.setTextColor(if (st.on) cAccent else cMuted); ul.setBackgroundColor(if (st.on) cAccent else Color.TRANSPARENT) }
        v.setOnClickListener { act(c) { onFlip() } }
        return v
    }

    private fun tileIconToggle(c: IconToggle): View {
        val v = col(); v.isClickable = true; val ic = icon(c.iconOff, cTxt, 52)
        v.addView(ic)
        updaters[c.id] = { st -> if (st.icon != 0) ic.setImageResource(st.icon); ic.setColorFilter(if (st.on) cAccent else cTxt) }
        v.setOnClickListener { act(c) { c.flip() } }
        return v
    }

    private fun tileBattery(c: Battery): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(SettingsStore.barHeight(this@OverlayService) - 8)).apply { marginStart = dp(22) }
            setPadding(dp(8), 0, dp(8), 0); isClickable = true
        }
        val modeTv = TextView(this).apply { setTextColor(cAccent); textSize = 30f; setTypeface(typeface, Typeface.NORMAL); gravity = Gravity.CENTER; setPadding(dp(12), 0, 0, 0); text = "—" }
        val ic = icon(R.drawable.ic_bolt, cAccent, 34)
        val batTv = TextView(this).apply { setTextColor(cAccent); textSize = 30f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setSingleLine(true); maxLines = 1; setPadding(0, 0, dp(10), 0); text = "—%" }
        row.addView(batTv); row.addView(ic); row.addView(modeTv)

        updaters["drive"] = { st -> 
            val now = System.currentTimeMillis(); val isRecent = now - lastManualSocTime < 2000
            val text = if (isRecent) (if (lastManualMode == 0) "HEV ${if (lastManualStrategy == 1) "INT" else "${lastManualSoc}%"}" else st.text) else st.text
            val color = if (isRecent) (if (lastManualMode == 0) DockColors.AMBER else if (lastManualMode == 1) DockColors.GREEN else DockColors.CYAN) else st.color
            modeTv.text = text; modeTv.setTextColor(color); ic.setColorFilter(color)
        }
        updaters[c.id] = { st -> batTv.text = st.text; batTv.setTextColor(st.color) }
        row.setOnClickListener { onUserActivity(); openMode(DockControls.DRIVE, row) }
        return row
    }

    private fun tileInfo(c: Info): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; isClickable = false; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(SettingsStore.barHeight(this@OverlayService) - 8)).apply { marginStart = dp(22) }; setPadding(dp(8), 0, dp(8), 0) }
        val ic = icon(c.icon, cTxt, 32); val tv = TextView(this).apply { setTextColor(cTxt); textSize = 30f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setSingleLine(true); maxLines = 1; setPadding(dp(10), 0, 0, 0); text = "—°" }
        row.addView(ic); row.addView(tv)
        updaters[c.id] = { st -> tv.text = st.text; tv.setTextColor(st.color) }
        return row
    }

    private fun tileRegen(c: Regen): View {
        val v = col(); v.isClickable = true; val ic = icon(c.icon, cAccent, 40); v.addView(ic)
        val barsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bars = Array(3) { View(this).apply { background = pill(cLine, dp(1)) } }
        bars.forEachIndexed { i, b -> barsRow.addView(b, LinearLayout.LayoutParams(dp(10), dp(7)).apply { if (i > 0) marginStart = dp(4) }) }
        v.addView(barsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        updaters[c.id] = { st -> ic.setColorFilter(st.color); bars.forEachIndexed { i, b -> b.background = pill(if (i < st.bars) st.color else cLine, dp(1)) } }
        v.setOnClickListener { act(c) { c.next() } }
        return v
    }

    private fun icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply { setImageResource(res); setColorFilter(tint); layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)) }

    private fun makeTrack(): Pair<View, View> {
        val track = FrameLayout(this).apply { background = pill(cLine, dp(2)); layoutParams = LinearLayout.LayoutParams(trackPx, dp(3)).apply { topMargin = dp(7) } }
        val fill = View(this).apply { setBackgroundColor(cAccent) }
        track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        return Pair(track, fill)
    }

    private fun setTrack(fill: View, ratio: Float) { val lp = fill.layoutParams; lp.width = (trackPx * ratio.coerceIn(0f, 1f)).toInt(); fill.layoutParams = lp }

    private fun openVolume(c: Volume, anchor: View) {
        if (volWin != null) { closeVolume(); return }; closePopups(); armPopupTimer()
        val pop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = pill(getPopupColor(), dp(18)); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val valTv = TextView(this).apply { setTextColor(cAccent); textSize = 22f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; minWidth = dp(70) }
        pop.addView(valTv)
        val sliderW = dp(240); val sliderH = dp(32); val sliderTrack = FrameLayout(this).apply { background = pill(cCard, dp(16)); layoutParams = LinearLayout.LayoutParams(sliderW, sliderH).apply { marginStart = dp(12) } }
        val sliderFill = View(this).apply { setBackgroundColor(cAccent) }; sliderTrack.addView(sliderFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)); pop.addView(sliderTrack)
        var canGoPast12 = false; var currentV = 0
        fun updateUI(v: Int) {
            val color = if (v > 12) DockColors.RED else cAccent
            valTv.text = v.toString(); valTv.setTextColor(color); val lp = sliderFill.layoutParams; lp.width = (sliderW * (v.toFloat() / c.hi().coerceAtLeast(1)).coerceIn(0f, 1f)).toInt(); sliderFill.layoutParams = lp; sliderFill.setBackgroundColor(color)
        }
        sliderTrack.setOnTouchListener { view, e ->
            armPopupTimer(); val v = ((e.x / view.width).coerceIn(0f, 1f) * c.hi()).toInt()
            if (e.action == MotionEvent.ACTION_DOWN) canGoPast12 = currentV >= 12
            val finalV = if (canGoPast12) v else minOf(v, 12)
            updateUI(finalV)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) { onUserActivity(); currentV = finalV; lastManualVol = finalV; lastManualVolTime = System.currentTimeMillis(); io.execute { c.set(finalV); main.post { refreshAll() } } }
            true
        }
        runCatching { wm.addView(pop, createPopupParams(anchor)); handleOutsideTouch(pop); volWin = pop }
        io.execute { val initial = c.value(); main.post { currentV = initial; updateUI(initial) } }
        onUserActivity()
    }

    private fun closeVolume() { volWin?.let { v -> runCatching { wm.removeView(v) } }; volWin = null }
    private fun closeMode() { 
        modeWin?.let { v -> runCatching { wm.removeView(v) } }
        modeWin = null
        updaters.remove("mode_popup")
    }
    private fun closeTemp() { tempWin?.let { v -> runCatching { wm.removeView(v) } }; tempWin = null; updaters.remove("fan_popup"); updaters.remove("vent_popup"); updaters.remove("auto_popup"); updaters.remove("pwr_popup"); updaters.remove("ac_popup"); updaters.remove("air_popup") }
    /**
     * Fecha todos os menus flutuantes ativos (Volume, Temperatura, Modos, etc.).
     */
    private fun closePopups() { closeVolume(); closeMode(); closeTemp() }
    
    /**
     * Fecha todos os popups e cancela o timer de auto-fechamento.
     */
    private fun closeAllPopups() { main.removeCallbacks(closePopupsRunnable); closePopups() }
    
    /**
     * Inicia o timer para fechar menus abertos por inatividade do usuário.
     */
    private fun armPopupTimer() { main.removeCallbacks(closePopupsRunnable); val s = SettingsStore.popupSecs(this); if (s > 0) main.postDelayed(closePopupsRunnable, s * 1000L) }

    /**
     * Abre o menu de seleção de Modos de Condução (HEV, EV, Prioridade EV).
     * Se o modo selecionado for HEV, exibe opções adicionais para estratégia Inteligente
     * ou Save SOC (com slider de ajuste de percentual).
     */
    private fun openMode(c: Mode, anchor: View) {
        if (modeWin != null) { closeMode(); return }; closePopups(); armPopupTimer()
        val pop = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = pill(getPopupColor(), dp(18)); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val modeViews = ArrayList<Pair<Int, TextView>>()
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(0)); visibility = View.GONE }
        val intelBtn = TextView(this).apply { text = "INTELIGENTE"; setTextColor(cTxt); textSize = 13f; setTypeface(typeface, Typeface.BOLD); background = pill(cCard, dp(14)); setPadding(dp(12), dp(6), dp(12), dp(6)); isClickable = true }
        row2.addView(intelBtn)
        val socLabel = TextView(this).apply { setTextColor(cTxt); textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(12), 0, dp(8), 0); text = "—%" }
        row2.addView(socLabel)
        val sliderW = dp(200); val sliderH = dp(32); val sliderTrack = FrameLayout(this).apply { background = pill(cCard, dp(16)); layoutParams = LinearLayout.LayoutParams(sliderW, sliderH) }
        val sliderFill = View(this).apply { setBackgroundColor(DockColors.AMBER) }; sliderTrack.addView(sliderFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)); row2.addView(sliderTrack)

        fun updateHEVUI(strategy: Int, soc: Int) {
            val isIntel = strategy == 1; intelBtn.setTextColor(if (isIntel) cOnAccent else cTxt); intelBtn.background = pill(if (isIntel) DockColors.AMBER else cCard, dp(14))
            socLabel.text = "$soc%"; socLabel.setTextColor(if (!isIntel) DockColors.AMBER else cMuted)
            val lp = sliderFill.layoutParams; lp.width = (sliderW * ((soc - c.minSoc).toFloat() / (c.maxSoc - c.minSoc)).coerceIn(0f, 1f)).toInt(); sliderFill.layoutParams = lp; sliderFill.setBackgroundColor(if (!isIntel) DockColors.AMBER else cMuted)
        }
        intelBtn.setOnClickListener { changeDriveMode(c, 0, strategy = 1) }
        sliderTrack.setOnTouchListener { view, e ->
            if (modeWin != null) armPopupTimer(); val soc = c.minSoc + ((e.x / view.width).coerceIn(0f, 1f) * (c.maxSoc - c.minSoc)).toInt()
            updateHEVUI(2, soc); if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) changeDriveMode(c, 0, strategy = 2, soc = soc)
            true
        }
        c.order.forEach { m ->
            val tv = TextView(this).apply { text = (c.labels[m] ?: "—").uppercase(); setTextColor(cTxt); textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(16), dp(10), dp(16), dp(10)); isClickable = true; setOnClickListener { changeDriveMode(c, m) } }
            modeViews.add(m to tv); row1.addView(tv)
        }
        pop.addView(row1); pop.addView(row2)
        runCatching { wm.addView(pop, createPopupParams(anchor)); handleOutsideTouch(pop); modeWin = pop }
        
        val updateModePopupUI = {
            io.execute {
                val now = System.currentTimeMillis()
                val isRecent = now - lastManualSocTime < 2000
                val curM = if (isRecent) lastManualMode else c.cur()
                val curSt = if (isRecent && lastManualStrategy != -1) lastManualStrategy else c.curStrategy()
                val curS = if (isRecent && lastManualSoc != -1) lastManualSoc else c.curHevSocInt()
                
                main.post {
                    row2.visibility = if (curM == 0) View.VISIBLE else View.GONE
                    updateHEVUI(curSt, curS)
                    modeViews.forEach { (m, tv) -> tv.setTextColor(if (m == curM) c.colors[m] ?: cAccent else cTxt) }
                }
            }
        }
        updaters["mode_popup"] = { updateModePopupUI() }
        updateModePopupUI()
    }

    /**
     * Abre o menu completo de controle de Climatização (Temperatura, AC, AUTO, Ventilador, Bancos).
     * Centraliza todos os controles de HVAC em um único popup contextual.
     */
    private fun openTemp(c: Temp, anchor: View) {
        if (tempWin != null) { closeTemp(); return }; closePopups(); armPopupTimer()
        val pop = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = pill(getPopupColor(), dp(18)); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val rowAuto = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(14)) }
        val pwrIcon = ImageView(this).apply { setImageResource(R.drawable.ic_fan); background = pill(cCard, dp(14)); layoutParams = LinearLayout.LayoutParams(dp(100), dp(46)); setPadding(dp(12), dp(8), dp(12), dp(8)); isClickable = true }
        pwrIcon.setOnClickListener { onUserActivity(); armPopupTimer(); io.execute { val isOn = VehicleClient.getData(DockKeys.CAR_HVAC_POWER_MODE) == "1"; if (isOn) { VehicleClient.set(DockKeys.CAR_HVAC_POWER_MODE, "0"); VehicleClient.set(DockKeys.CAR_HVAC_FAN_SPEED, "0") } else { VehicleClient.set(DockKeys.CAR_HVAC_POWER_MODE, "1"); VehicleClient.set(DockKeys.CAR_HVAC_AUTO_ENABLE, "0"); VehicleClient.set(DockKeys.CAR_HVAC_FAN_SPEED, "2") }; main.post { refreshAll() } } }
        rowAuto.addView(pwrIcon)
        val acIcon = ImageView(this).apply { setImageResource(R.drawable.ic_snowflake_thermometer); background = pill(cCard, dp(14)); layoutParams = LinearLayout.LayoutParams(dp(100), dp(46)).apply { marginStart = dp(16) }; setPadding(dp(12), dp(8), dp(12), dp(8)); isClickable = true }
        acIcon.setOnClickListener { onUserActivity(); armPopupTimer(); io.execute { val acOn = VehicleClient.getData(DockKeys.CAR_HVAC_AC_ENABLE) == "1"; if (acOn) VehicleClient.set(DockKeys.CAR_HVAC_AC_ENABLE, "0") else { VehicleClient.set(DockKeys.CAR_HVAC_AC_ENABLE, "1"); VehicleClient.set(DockKeys.CAR_HVAC_POWER_MODE, "1"); VehicleClient.set(DockKeys.CAR_HVAC_AUTO_ENABLE, "0"); VehicleClient.set(DockKeys.CAR_HVAC_FAN_SPEED, "2") }; main.post { refreshAll() } } }
        rowAuto.addView(acIcon)
        val autoBtn = TextView(this).apply { text = "AUTO"; setTextColor(cTxt); textSize = 18f; setTypeface(typeface, Typeface.BOLD); background = pill(cCard, dp(14)); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(100), dp(46)).apply { marginStart = dp(16) }; isClickable = true; setOnClickListener { onUserActivity(); armPopupTimer(); io.execute { DockControls.AUTO_CONTROL.flip(); main.post { refreshAll() } } } }
        rowAuto.addView(autoBtn); pop.addView(rowAuto)

        val sliderW = dp(240); val sliderH = dp(32); val rowTemp = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tempTv = TextView(this).apply { setTextColor(cAccent); textSize = 22f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; minWidth = dp(70) }
        val tempTrack = FrameLayout(this).apply { background = pill(cCard, dp(16)); layoutParams = LinearLayout.LayoutParams(sliderW, sliderH).apply { marginStart = dp(12) } }
        val tempFill = View(this).apply { setBackgroundColor(cAccent) }; tempTrack.addView(tempFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)); rowTemp.addView(tempTv); rowTemp.addView(tempTrack); pop.addView(rowTemp)

        fun updateTempUI(v: Double) { val r = ((v - c.min) / (c.hi() - c.min)).toFloat(); val color = blend(DockColors.CYAN, DockColors.AMBER, r); tempTv.text = c.fmt(v) + "°"; tempTv.setTextColor(color); val lp = tempFill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt(); tempFill.layoutParams = lp; tempFill.setBackgroundColor(color) }
        tempTrack.setOnTouchListener { view, e -> armPopupTimer(); val v = (kotlin.math.round((c.min + (e.x / view.width).coerceIn(0f, 1f) * (c.hi() - c.min)) / c.step) * c.step).coerceIn(c.min, c.hi()); updateTempUI(v); if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) { onUserActivity(); if (c.id == "tempD") { lastManualTempD = v; lastManualTempDTime = System.currentTimeMillis() } else { lastManualTempP = v; lastManualTempPTime = System.currentTimeMillis() }; io.execute { c.select(v); main.post { refreshAll() } } }; true }

        val fan = DockControls.FAN; val rowFan = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        val fanTv = TextView(this).apply { setTextColor(cTxt); textSize = 20f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; minWidth = dp(34); setPadding(dp(8), 0, dp(8), 0) }
        val fanTrack = FrameLayout(this).apply { background = pill(cCard, dp(16)); layoutParams = LinearLayout.LayoutParams(sliderW, sliderH) }
        val fanFill = View(this).apply { setBackgroundColor(cAccent) }; fanTrack.addView(fanFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)); rowFan.addView(icon(R.drawable.ic_fan, cTxt, 24)); rowFan.addView(fanTv); rowFan.addView(fanTrack); pop.addView(rowFan)
        fun updateFanUI(v: Int) { val r = (v - fan.min).toFloat() / (fan.hi().coerceAtLeast(fan.min + 1) - fan.min); fanTv.text = if (v < 0) "_" else v.toString(); val lp = fanFill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt(); fanFill.layoutParams = lp }
        fanTrack.setOnTouchListener { view, e -> armPopupTimer(); val v = fan.min + ((e.x / view.width).coerceIn(0f, 1f) * (fan.hi() - fan.min)).toInt(); updateFanUI(v); if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) { onUserActivity(); io.execute { fan.setLevel(v); main.post { refreshAll() } } }; true }

        val rowAir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) }
        val airIcons = ArrayList<Pair<AirflowOption, ImageView>>()
        DockControls.AIRFLOW_CONTROL.options.forEach { opt -> val iv = ImageView(this).apply { setImageResource(opt.icon); setColorFilter(cTxt); isClickable = true; setPadding(dp(8), dp(8), dp(8), dp(8)); layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginStart = dp(4); marginEnd = dp(4) }; setOnClickListener { onUserActivity(); armPopupTimer(); io.execute { DockControls.AIRFLOW_CONTROL.select(opt); main.post { refreshAll() } } } }; airIcons.add(opt to iv); rowAir.addView(iv) }
        pop.addView(rowAir)
        fun updateAirflowUI(cur: AirflowOption) { airIcons.forEach { it.second.setColorFilter(if (it.first == cur) cAccent else cTxt) } }

        val vent = if (c.id == "tempD") DockControls.VENT_D else DockControls.VENT_P; val rowVent = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        val ventTv = TextView(this).apply { setTextColor(cTxt); textSize = 20f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; minWidth = dp(34); setPadding(dp(8), 0, dp(8), 0) }
        val ventTrack = FrameLayout(this).apply { background = pill(cCard, dp(16)); layoutParams = LinearLayout.LayoutParams(sliderW, sliderH) }
        val ventFill = View(this).apply { setBackgroundColor(cAccent) }; ventTrack.addView(ventFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)); rowVent.addView(icon(R.drawable.ic_carseat_cooler, cTxt, 24)); rowVent.addView(ventTv); rowVent.addView(ventTrack); pop.addView(rowVent)
        fun updateVentUI(v: Int) { ventTv.text = if (v < 0) "_" else v.toString(); val lp = ventFill.layoutParams; lp.width = (sliderW * (v.toFloat() / vent.hi().coerceAtLeast(1)).coerceIn(0f, 1f)).toInt(); ventFill.layoutParams = lp }
        ventTrack.setOnTouchListener { view, e -> armPopupTimer(); val v = ((e.x / view.width).coerceIn(0f, 1f) * vent.hi()).toInt(); updateVentUI(v); if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) { onUserActivity(); io.execute { vent.setLevel(v); main.post { refreshAll() } } }; true }

        runCatching { wm.addView(pop, createPopupParams(anchor)); handleOutsideTouch(pop); tempWin = pop }
        updaters["fan_popup"] = { _ -> io.execute { val v = fan.value(); main.post { updateFanUI(v) } } }
        updaters["vent_popup"] = { _ -> io.execute { val v = vent.value(); main.post { updateVentUI(v) } } }
        updaters["auto_popup"] = { _ -> io.execute { val on = DockControls.AUTO_CONTROL.isOn(); main.post { autoBtn.setTextColor(if (on) cOnAccent else cTxt); autoBtn.background = pill(if (on) cAccent else cCard, dp(14)) } } }
        updaters["pwr_popup"] = { _ -> io.execute { val isOn = VehicleClient.getData(DockKeys.CAR_HVAC_POWER_MODE) == "1"; main.post { pwrIcon.setColorFilter(if (isOn) DockColors.GREEN else cTxt) } } }
        updaters["ac_popup"] = { _ -> io.execute { val isOn = VehicleClient.getData(DockKeys.CAR_HVAC_AC_ENABLE) == "1"; main.post { acIcon.setColorFilter(if (isOn) DockColors.GREEN else cTxt) } } }
        updaters["air_popup"] = { _ -> io.execute { val cur = DockControls.AIRFLOW_CONTROL.currentOption(); main.post { updateAirflowUI(cur) } } }
        io.execute { val curT = c.read() ?: c.min; val curF = fan.value(); val curV = vent.value(); val isAuto = DockControls.AUTO_CONTROL.isOn(); val curA = DockControls.AIRFLOW_CONTROL.currentOption(); val isPwrOn = VehicleClient.getData(DockKeys.CAR_HVAC_POWER_MODE) == "1"; val isAcOn = VehicleClient.getData(DockKeys.CAR_HVAC_AC_ENABLE) == "1"; main.post { updateTempUI(curT); updateFanUI(curF); updateVentUI(curV); updateAirflowUI(curA); autoBtn.setTextColor(if (isAuto) cOnAccent else cTxt); autoBtn.background = pill(if (isAuto) cAccent else cCard, dp(14)); pwrIcon.setColorFilter(if (isPwrOn) DockColors.GREEN else cTxt); acIcon.setColorFilter(if (isAcOn) DockColors.GREEN else cTxt) } }
        onUserActivity()
    }

    private fun openLevel(c: Level, anchor: View) {
        if (levelWin != null) { closePopups(); return }; closePopups(); armPopupTimer()
        val pop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = pill(getPopupColor(), dp(18)); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val valTv = TextView(this).apply { setTextColor(cAccent); textSize = 22f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; minWidth = dp(70) }
        pop.addView(valTv)
        val sliderW = dp(240); val sliderH = dp(32); val sliderTrack = FrameLayout(this).apply { background = pill(cCard, dp(16)); layoutParams = LinearLayout.LayoutParams(sliderW, sliderH).apply { marginStart = dp(12) } }
        val sliderFill = View(this).apply { setBackgroundColor(cAccent) }; sliderTrack.addView(sliderFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)); pop.addView(sliderTrack)
        fun updateUI(v: Int) { valTv.text = if (v < 0) "_" else v.toString(); val lp = sliderFill.layoutParams; lp.width = (sliderW * ((v - c.min).toFloat() / (c.hi().coerceAtLeast(c.min + 1) - c.min)).coerceIn(0f, 1f)).toInt(); sliderFill.layoutParams = lp }
        sliderTrack.setOnTouchListener { view, e -> armPopupTimer(); val v = c.min + ((e.x / view.width).coerceIn(0f, 1f) * (c.hi() - c.min)).toInt(); updateUI(v); if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) { onUserActivity(); io.execute { c.setLevel(v); main.post { refreshAll() } } }; true }
        runCatching { wm.addView(pop, createPopupParams(anchor)); handleOutsideTouch(pop); levelWin = pop }
        io.execute { val cur = c.value(); main.post { updateUI(cur) } }; onUserActivity()
    }

    private fun createPopupParams(anchor: View): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = barHeightPx + dp(8)
            val loc = IntArray(2); anchor.getLocationOnScreen(loc); x = (loc[0] + anchor.width / 2) - (wm.defaultDisplay.width / 2)
        }
    }

    private fun handleOutsideTouch(pop: View) { pop.setOnTouchListener { _, event -> if (event.action == MotionEvent.ACTION_OUTSIDE) { closeAllPopups(); true } else false } }

    /**
     * Executa a troca de modo de condução e estratégia de energia no veículo.
     * Implementa 'Optimistic UI' para redesenhar a tela instantaneamente e
     * proteção anti-flicker para ignorar leituras instáveis do carro por 2 segundos.
     */
    private fun changeDriveMode(c: Mode, mode: Int, strategy: Int? = null, soc: Int? = null) {
        onUserActivity(); if (modeWin != null) armPopupTimer()
        lastManualSocTime = System.currentTimeMillis(); lastManualMode = mode
        io.execute {
            val targetStrategy = if (mode == 0) (strategy ?: c.curStrategy()) else null
            val targetSoc = if (mode == 0 && targetStrategy == 2) (soc ?: c.curHevSocInt()) else null
            lastManualStrategy = targetStrategy ?: -1; if (targetSoc != null) lastManualSoc = targetSoc
            c.select(mode, strategy = targetStrategy, soc = targetSoc)
            main.post { refreshAll(); if (mode != 0 && modeWin != null) closeMode() }
        }
    }

    private fun act(c: Control, action: () -> Unit) { onUserActivity(); io.execute { runCatching { action() }; val st = c.render(); main.post { updaters[c.id]?.invoke(st) } } }

    private fun showBalloonForKey(key: String?) {
        when (key) {
            DockKeys.CAR_HVAC_DRIVER_TEMPERATURE, DockKeys.CAR_HVAC_PASS_TEMPERATURE -> (if (key == DockKeys.CAR_HVAC_DRIVER_TEMPERATURE) DockControls.ALL.find { it.id == "tempD" } as? Temp else DockControls.ALL.find { it.id == "tempP" } as? Temp)?.let { openTemp(it, root) }
            DockKeys.MEDIA_VOLUME -> (DockControls.ALL.find { it.id == "vol" } as? Volume)?.let { openVolume(it, root) }
            DockKeys.CAR_HVAC_FAN_SPEED -> openLevel(DockControls.FAN, root)
        }
    }

    /**
     * Atualiza todos os elementos visuais da interface (Barra e Dashboard) com os
     * dados mais recentes lidos do barramento CAN do veículo.
     */
    private fun refreshAll() {
        if (hidden) return
        val visual = SettingsStore.visualMode.value
        val isDash = visual == SettingsStore.VISUAL_DASHBOARD || visual == SettingsStore.VISUAL_DASHBOARD_LIGHT
        
        io.execute {
            val controls = DockControls.ALL + listOf(DockControls.DRIVE, DockControls.FAN, DockControls.VENT_D, DockControls.VENT_P, DockControls.AUTO_CONTROL, DockControls.AIRFLOW_CONTROL)
            val snap = controls.map { it.id to it.render() }
            main.post {
                // Se estiver no Dashboard e na página do Gráfico, atualiza apenas o gráfico e itens globais (projeção)
                if (isDash && currentDashPage == 1) {
                    updaters["energy_info"]?.invoke(RenderState())
                    updaters["power_chart"]?.invoke(RenderState())
                    updaters["proj"]?.invoke(RenderState())
                    updaters["dash_proj"]?.invoke(RenderState())
                    updaters["telemetry"]?.invoke(RenderState())
                    return@post
                }

                // Atualiza controles normais (Página 1 ou Modo Barra)
                snap.forEach { (id, st) -> updaters[id]?.invoke(st) }
                updaters["telemetry"]?.invoke(RenderState())
                updaters["fan_popup"]?.invoke(RenderState()); updaters["vent_popup"]?.invoke(RenderState()); updaters["auto_popup"]?.invoke(RenderState()); updaters["pwr_popup"]?.invoke(RenderState()); updaters["ac_popup"]?.invoke(RenderState()); updaters["air_popup"]?.invoke(RenderState())
                DockControls.AIRFLOW_OPTIONS.forEach { opt -> updaters["air_${opt.label}"]?.invoke(RenderState()) }
                updaters["proj"]?.invoke(RenderState()); updaters["header_info"]?.invoke(RenderState()); updaters["tempD_sync"]?.invoke(RenderState())
                updaters["mode_popup"]?.invoke(RenderState())
                updaters.filter { it.key.startsWith("quick_") }.forEach { it.value(RenderState()) }
                updaters.filter { it.key.startsWith("dash_air_") }.forEach { it.value(RenderState()) }
                updaters.filter { it.key.startsWith("hev_") }.forEach { it.value(RenderState()) }
                updaters["dash_proj"]?.invoke(RenderState())
            }
        }
    }

    private fun projTile(): View { val v = col(); v.isClickable = true; val ic = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)) }; v.addView(ic); v.visibility = View.GONE; v.setOnClickListener { onProjClick() }; projView = v; projIcon = ic; return v }
    private val projPoll = object : Runnable { override fun run() { refreshProjection(); main.postDelayed(this, 2500) } }
    private fun refreshProjection() { io.execute { val raw = ProjectionLauncher.topPackage(); val fg = ProjectionLauncher.classifyProjection(raw); val conn: String?; val isFg: Boolean; if (fg != null) { conn = fg; isFg = true; lastProjection = fg } else { isFg = false; if (raw != null && raw != packageName) lastCentralApp = raw; conn = lastProjection ?: (if (ProjectionLauncher.carPlayConnected()) ProjectionLauncher.CARPLAY_PKG else null); if (conn != null) lastProjection = conn }; main.post { updateProjTile(conn, isFg) } } }
    private fun updateProjTile(conn: String?, fg: Boolean) { projConnected = conn; projForeground = fg; updaters["dash_proj"]?.invoke(RenderState()); val v = projView ?: return; val ic = projIcon ?: return; if (conn == null) { if (v.visibility != View.GONE) v.visibility = View.GONE; projShownState = null; return }; if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE; val want = if (fg) "car" else conn; if (projShownState != want) { when { fg -> { ic.setImageResource(R.drawable.ic_car); ic.setColorFilter(cTxt) }; conn == ProjectionLauncher.AA_PKG -> { ic.setImageResource(R.drawable.ic_androidauto); ic.clearColorFilter() }; else -> { ic.setImageResource(R.drawable.ic_carplay); ic.clearColorFilter() } }; projShownState = want } }
    /**
     * Gerencia o clique nos ícones de projeção (CarPlay/Android Auto).
     * Alterna entre abrir o app de projeção ou voltar para a última tela da central.
     * Minimiza o overlay automaticamente ao navegar para estas telas.
     */
    private fun onProjClick() { onUserActivity(); val conn = projConnected ?: return; val goingBack = projForeground; hideBar(manual = true); io.execute { if (goingBack) { val comp = lastCentralApp?.let { runCatching { packageManager.getLaunchIntentForPackage(it)?.component?.flattenToString() }.getOrNull() }; if (comp != null) ProjectionLauncher.openComponent(comp) else ProjectionLauncher.goHome() } else { ProjectionLauncher.openProjection(conn) }; Thread.sleep(600); refreshProjection() } }
    private fun onUserActivity() { if (!hidden) armTimer() }
    private fun applyVisibility() { showBar() }
    private fun armTimer() { main.removeCallbacks(hideRunnable); if (SettingsStore.mode(this) == SettingsStore.MODE_AUTO) main.postDelayed(hideRunnable, SettingsStore.secs(this) * 1000L) }

    /**
     * Constrói o layout do Dashboard Normal (Painel Completo).
     * Organiza os cards em 3 colunas (Motorista, Veículo, Passageiro) com paginação.
     */
    private fun buildDashboard() {
        val rootLayout = FrameLayout(this).apply { layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT); setBackgroundColor(Color.TRANSPARENT) }
        dashboard = rootLayout; root.removeAllViews(); root.addView(rootLayout)
        val dashboardContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = pill((0xFF shl 24) or (DockColors.SCREEN and 0x00FFFFFF), dp(40)); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        rootLayout.addView(dashboardContainer, FrameLayout.LayoutParams(1770, 720 - dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(2) })
        
        // Header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(40), dp(10), dp(40), 0) }
        val tvHeader = TextView(this).apply { textSize = 18f; setTextColor(cTxt); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.05f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }; header.addView(tvHeader)
        updaters["header_info"] = { val sdf = java.text.SimpleDateFormat("EEEE, dd 'DE' MMMM 'DE' yyyy", java.util.Locale("pt", "BR")); tvHeader.text = sdf.format(java.util.Date()).uppercase() }
        dashboardContainer.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))
        
        // Página 1: Controles
        val page1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = null; setPadding(dp(20), dp(10), dp(20), dp(20)); gravity = Gravity.BOTTOM }
        val col1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }; page1.addView(col1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        page1.addView(gapView(12, true)); val col2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }; page1.addView(col2, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        page1.addView(gapView(12, true)); val col3 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }; page1.addView(col3, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        
        col1.addView(createDashboardCard("", createHvacQuickControls("D"))); col1.addView(gapView(12)); col1.addView(createDashboardCard("", createTempControl(DockControls.ALL.find { it.id == "tempD" } as Temp))); col1.addView(gapView(12)); col1.addView(createDashboardCard("", createAirflowSelection("D"))); col1.addView(gapView(12)); col1.addView(createDashboardCard("", createLevelControl(DockControls.FAN, R.drawable.ic_fan))); col1.addView(gapView(12)); col1.addView(createDashboardCard("", createLevelControl(DockControls.VENT_D, R.drawable.ic_carseat_cooler)))
        col2.addView(createDashboardCard("", createBatteryCard(DockControls.ALL.find { it.id == "bat" } as Battery, segmented = false))); col2.addView(gapView(12)); col2.addView(createDashboardCard("MODO DE CONDUÇÃO", createDriveModeSelectionLight(DockControls.DRIVE), iconRes = R.drawable.ic_bolt, titleSize = 18f)); col2.addView(gapView(12)); col2.addView(createDashboardCard("", createAmbientTempCard(DockControls.ALL.find { it.id == "recirc" } as IconToggle)))
        col3.addView(createDashboardCard("", createHvacQuickControls("P"))); col3.addView(gapView(12)); col3.addView(createDashboardCard("", createTempControl(DockControls.ALL.find { it.id == "tempP" } as Temp))); col3.addView(gapView(12)); col3.addView(createDashboardCard("", createAirflowSelection("P"))); col3.addView(gapView(12)); col3.addView(createDashboardCard("", createLevelControl(DockControls.FAN, R.drawable.ic_fan))); col3.addView(gapView(12)); col3.addView(createDashboardCard("", createLevelControl(DockControls.VENT_P, R.drawable.ic_carseat_cooler)))

        dashboardContainer.addView(page1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * Constrói o layout do Dashboard Light (Minimalista) com suporte a páginas.
     */
    private fun buildDashboardLight() {
        val rootLayout = FrameLayout(this).apply { layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT); setBackgroundColor(Color.TRANSPARENT) }
        dashboard = rootLayout; root.removeAllViews(); root.addView(rootLayout)
        val dashboardContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = pill((0xFF shl 24) or (DockColors.SCREEN and 0x00FFFFFF), dp(40)); setPadding(dp(10), dp(5), dp(10), dp(10)) }
        rootLayout.addView(dashboardContainer, FrameLayout.LayoutParams(1770, 720 - dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(2) })
        
        // Header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(40), dp(4), dp(40), 0) }
        val tvHeader = TextView(this).apply { textSize = 18f; setTextColor(cTxt); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.05f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }; header.addView(tvHeader)
        updaters["header_info"] = { val sdf = java.text.SimpleDateFormat("EEEE, dd 'DE' MMMM 'DE' yyyy", java.util.Locale("pt", "BR")); tvHeader.text = sdf.format(java.util.Date()).uppercase() }
        
        // ViewPager
        val viewPager = NonSwipeViewPager(this).apply { id = View.generateViewId() }
        
        // Botões de Paginação no Header
        header.addView(createPaginationButtons(viewPager, 2))
        dashboardContainer.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(45)))
        val isFloating = SettingsStore.isLightFloatingEnabled(this); val cardBg = if (isFloating) DockColors.SCREEN else null; val cardStroke = if (isFloating) DockColors.SCREEN else null

        // Página 1 Light: Controles
        val page1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = null; setPadding(dp(20), dp(5), dp(20), dp(10)); gravity = Gravity.BOTTOM }
        val col1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }; page1.addView(col1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        page1.addView(gapView(12, true)); val col2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }; page1.addView(col2, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        page1.addView(gapView(12, true)); val col3 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }; page1.addView(col3, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        
        col1.addView(createDashboardCard("", createHvacQuickControls("D"), radius = 8, bgColor = cardBg, strokeColor = cardStroke)); col1.addView(gapView(4)); col1.addView(createDashboardCard("", createAirflowSelection("D"), radius = 8, bgColor = cardBg, strokeColor = cardStroke)); col1.addView(gapView(4)); col1.addView(createDashboardCard("", createLevelControl(DockControls.FAN, R.drawable.ic_fan, iconSize = 42), radius = 8, bgColor = cardBg, strokeColor = cardStroke)); col1.addView(gapView(4)); col1.addView(createDashboardCard("", createTempControl(DockControls.ALL.find { it.id == "tempD" } as Temp), radius = 8, bgColor = cardBg, strokeColor = cardStroke)); col1.addView(gapView(4)); col1.addView(createDashboardCard("", createLevelControl(DockControls.VENT_D, R.drawable.ic_carseat_cooler), radius = 8, bgColor = cardBg, strokeColor = cardStroke))
        col2.addView(createDashboardCard("", createBatteryCard(DockControls.ALL.find { it.id == "bat" } as Battery, segmented = true), radius = 8, bgColor = cardBg, strokeColor = cardStroke)); col2.addView(gapView(4)); col2.addView(createDashboardCard("MODO DE CONDUÇÃO", createDriveModeSelectionLight(DockControls.DRIVE), iconRes = R.drawable.ic_bolt, titleSize = 18f, radius = 8, bgColor = cardBg, strokeColor = cardStroke)); col2.addView(gapView(4)); col2.addView(createDashboardCard("", createAmbientTempCard(DockControls.ALL.find { it.id == "recirc" } as IconToggle), radius = 8, bgColor = cardBg, strokeColor = cardStroke))
        col3.addView(createDashboardCard("TELEMETRIA", createTelemetryCardContent(), iconRes = R.drawable.ic_bolt, radius = 8, bgColor = cardBg, strokeColor = cardStroke)); col3.addView(gapView(4)); col3.addView(createDashboardCard("", createVolumeControl(DockControls.ALL.find { it.id == "vol" } as Volume), radius = 8, bgColor = cardBg, strokeColor = cardStroke)); col3.addView(gapView(4)); col3.addView(createDashboardCard("", createTempControl(DockControls.ALL.find { it.id == "tempP" } as Temp), radius = 8, bgColor = cardBg, strokeColor = cardStroke)); col3.addView(gapView(4)); col3.addView(createDashboardCard("", createLevelControl(DockControls.VENT_P, R.drawable.ic_carseat_cooler), radius = 8, bgColor = cardBg, strokeColor = cardStroke))

        // Página 2 Light: Gráfico ampliado (Unificado)
        val page2 = createEnergyAnalysisPage(isLight = true, cardBg = cardBg, cardStroke = cardStroke)

        val pages = listOf(page1, page2)
        viewPager.adapter = object : PagerAdapter() {
            override fun getCount() = pages.size
            override fun isViewFromObject(v: View, obj: Any) = v == obj
            override fun instantiateItem(container: android.view.ViewGroup, pos: Int): Any { container.addView(pages[pos]); return pages[pos] }
            override fun destroyItem(container: android.view.ViewGroup, pos: Int, obj: Any) { container.removeView(obj as View) }
        }
        
        dashboardContainer.addView(viewPager, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        
        val dots = createDotsIndicator(pages.size, viewPager)
        dashboardContainer.addView(dots)
        
        viewPager.post { 
            viewPager.currentItem = currentDashPage
            if (currentDashPage == 1) main.postDelayed({ 
                updaters["energy_info"]?.invoke(RenderState())
                updaters["power_chart"]?.invoke(RenderState()) 
            }, 500)
        }
    }

    private fun createDotsIndicator(count: Int, pager: ViewPager): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10)) }
        val dots = Array(count) { View(this) }
        dots.forEachIndexed { i, d ->
            d.background = pill(if (i == 0) cAccent else cLine, dp(4))
            row.addView(d, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(6); marginEnd = dp(6) })
        }
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(pos: Int) {
                currentDashPage = pos
                dots.forEachIndexed { i, d -> d.background = pill(if (i == pos) cAccent else cLine, dp(4)) }
                if (pos == 0) refreshAll()
                else if (pos == 1) {
                    main.postDelayed({ 
                        updaters["energy_info"]?.invoke(RenderState())
                        updaters["power_chart"]?.invoke(RenderState()) 
                    }, 300)
                    main.postDelayed({ 
                        updaters["energy_info"]?.invoke(RenderState())
                        updaters["power_chart"]?.invoke(RenderState()) 
                    }, 1000)
                }
            }
        })
        pager.currentItem = currentDashPage
        return row
    }

    private fun createEnergyAnalysisPage(isLight: Boolean, cardBg: Int?, cardStroke: Int?): View {
        val padding = if (isLight) dp(10) else dp(16)
        val bottomPadding = if (isLight) dp(20) else dp(30)
        val radius = if (isLight) 16 else 28
        
        val page = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(60), padding, dp(60), bottomPadding)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        
        // Linha de Informações (Fora do card do gráfico)
        val infoTv = TextView(this).apply {
            textSize = 22f; setTextColor(cMuted); setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        page.addView(infoTv)
        
        updaters["energy_info"] = {
            val battery = VehicleClient.getData(DockKeys.CAR_EV_INFO_CUR_BATTERY_POWER_PERCENTAGE) ?: "—"
            val range = VehicleClient.getData(DockKeys.CAR_EV_INFO_ELECTRIC_MODE_REMAIN_ODOMETER) ?: "—"
            val time = VehicleClient.getData(DockKeys.CAR_EV_INFO_CHARGE_REMAINING_TIME)?.toIntOrNull() ?: 0
            val isCharging = VehicleClient.getData(DockKeys.CAR_EV_INFO_CHARGING_GUN_CONN_STATE) == "1"
            
            val timeText = if (time > 0 && isCharging) " - Tempo de Recarga $time minutos." else "."
            var baseText = "$battery% - $range km$timeText"
            
            if (isCharging) {
                val lastChargeRaw = VehicleClient.getData(DockKeys.CAR_EV_INFO_LAST_CHARGE_TIME_ODOMETER)
                val lastInfo = parseLastCharge(lastChargeRaw)
                if (lastInfo != null) {
                    val totalOdo = VehicleClient.getData(DockKeys.CAR_EV_INFO_TOTAL_ODOMETER)?.toDoubleOrNull() ?: 0.0
                    val diff = (totalOdo - lastInfo.second).coerceAtLeast(0.0)
                    baseText += String.format(java.util.Locale.US, "\nÚltima recarga %s - %.1f Km rodados", lastInfo.first, diff)
                }
            }
            infoTv.text = baseText
        }
        
        val card = createDashboardCard(
            "Consumo vs Regeneração (kW)", 
            createPowerChart(heightDp = 0), 
            radius = radius, 
            bgColor = cardBg, 
            strokeColor = cardStroke
        )
        
        val cardLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        page.addView(card, cardLp)
        
        if (card is LinearLayout && card.childCount > 0) {
            val chartContainer = card.getChildAt(card.childCount - 1)
            chartContainer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        
        return page
    }

    private fun createTelemetryCardContent(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        
        val autonomyTv = TextView(this).apply {
            textSize = 28f; setTextColor(cAccent); setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val statsTv = TextView(this).apply {
            textSize = 14f; setTextColor(cMuted); setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        
        layout.addView(autonomyTv)
        layout.addView(statsTv)
        
        updaters["telemetry"] = {
            val totalOdo = VehicleClient.getData(DockKeys.CAR_EV_INFO_TOTAL_ODOMETER)?.toDoubleOrNull() ?: 0.0
            if (sessionStartOdo <= 0.0 && totalOdo > 0.0) {
                // Se em simulação, finge que já rodamos 3km para o card aparecer com dados
                if (SettingsStore.simulationEnabled.value) {
                    sessionStartOdo = totalOdo - 3.0
                } else {
                    sessionStartOdo = totalOdo
                }
            }
            //CAR_EV_INFO_POWER_BATTERY_VOLTAGE voltagem da bateria de tracao
            //CAR_EV_INFO_CUR_CHARGE_CURRENT corrente da bateria de tracao

            val batteryPct = VehicleClient.getData(DockKeys.CAR_EV_INFO_CUR_BATTERY_POWER_PERCENTAGE)?.toDoubleOrNull() ?: 0.0
            val consumed = VehicleClient.getData(DockKeys.CAR_EV_INFO_CYCLE_ENERGY_CONSUME_INFO)?.toDoubleOrNull() ?: 0.0
            val recovered = VehicleClient.getData(DockKeys.CAR_EV_INFO_ENERGY_RECOVERY_INFO)?.toDoubleOrNull() ?: 0.0
            
            val distance = totalOdo - sessionStartOdo
            val netEnergy = consumed - recovered
            
            if (distance >= 0.1 && netEnergy > 0.01) {
                val efficiency = netEnergy / distance // kWh / km
                val kmPerKwh = distance / netEnergy
                val remainingEnergy = (batteryPct / 100.0) * 19.0
                val autonomy = if (efficiency > 0) remainingEnergy / efficiency else 0.0
                
                val text = String.format(java.util.Locale.US, "Autonomia %.0f km / %.1f km/kWh", autonomy, kmPerKwh)
                val ssb = SpannableStringBuilder(text)
                
                // Diminui "km" e "km/kWh"
                val units = listOf("Autonomia", "km", "km/kWh")
                units.forEach { unit ->
                    var start = text.indexOf(unit)
                    while (start != -1) {
                        ssb.setSpan(RelativeSizeSpan(0.5f), start, start + unit.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        start = text.indexOf(unit, start + unit.length)
                    }
                }
                autonomyTv.text = ssb
                
                statsTv.text = String.format(java.util.Locale.US, "Distância %.1f km | %.1f kWh", distance, netEnergy)
            } else {
                autonomyTv.text = "-- km / -- km/kWh"
                statsTv.text = "Calculando..."
            }
        }
        
        return layout
    }

    private fun createPaginationButtons(pager: ViewPager, count: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnPrev = ImageView(this).apply {
            setImageResource(R.drawable.page_previous)
            setColorFilter(cTxt)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            setOnClickListener {
                onUserActivity()
                pager.currentItem = (pager.currentItem - 1).coerceAtLeast(0)
            }
        }

        val btnNext = ImageView(this).apply {
            setImageResource(R.drawable.page_next)
            setColorFilter(cTxt)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            setOnClickListener {
                onUserActivity()
                pager.currentItem = (pager.currentItem + 1).coerceAtMost(count - 1)
            }
        }

        row.addView(btnPrev)
        row.addView(gapView(10, true))
        row.addView(btnNext)

        val updateVisibility = { pos: Int ->
            btnPrev.visibility = if (pos > 0) View.VISIBLE else View.GONE
            btnNext.visibility = if (pos < count - 1) View.VISIBLE else View.GONE
        }

        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                updateVisibility(position)
            }
        })

        updateVisibility(pager.currentItem)
        return row
    }

    private fun createDashboardCard(title: String, content: View, active: Boolean = false, iconRes: Int? = null, titleSize: Float = 13f, radius: Int = 28, bgColor: Int? = null, strokeColor: Int? = null): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = pill(bgColor ?: (if (active) cSurfaceSelected else cCard), dp(radius), stroke = strokeColor ?: (if (active) cAccent else cLine)); setPadding(dp(24), if (title.isEmpty()) dp(16) else dp(12), dp(24), dp(20)) }
        if (title.isNotEmpty()) { val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12)) }; if (iconRes != null) { titleRow.addView(icon(iconRes, dp(18), cMuted)); titleRow.addView(gapView(8, true)) }; titleRow.addView(TextView(this).apply { text = title.uppercase(); textSize = titleSize; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.2f }); card.addView(titleRow) }
        card.addView(content); return card
    }

    private fun createTempControl(c: Temp): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val container = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(380), dp(44)) }
        container.addView(View(this).apply { isClickable = true }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val track = FrameLayout(this).apply { background = pill(cTrack, dp(22)); isClickable = false }
        container.addView(track, FrameLayout.LayoutParams(dp(380), dp(44), Gravity.CENTER))
        val fill = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(DockColors.CYAN, DockColors.WHITE, DockColors.ORANGE)).apply { cornerRadius = dp(22).toFloat() } }
        track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        val tv = TextView(this).apply { textSize = 26f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; includeFontPadding = false; setShadowLayer(dp(6).toFloat(), 0f, 1f, Color.BLACK) }
        container.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        fun updateUI(v: Double) { val r = ((v - c.min) / (c.hi() - c.min)).toFloat(); val text = "${c.fmt(v)}°C"; val sb = SpannableStringBuilder(text); if (text.endsWith("°C")) sb.setSpan(RelativeSizeSpan(0.8f), text.length - 2, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); tv.text = sb; val (s, e) = when { v <= 23.0 -> DockColors.CYAN to DockColors.CYAN; v <= 25.0 -> DockColors.CYAN to DockColors.GREEN; v <= 27.0 -> DockColors.GREEN to DockColors.AMBER; else -> DockColors.AMBER to DockColors.ORANGE }; fill.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(s, e)).apply { cornerRadius = dp(22).toFloat() }; tv.setTextColor(blend(Color.WHITE, e, 0.3f)); val lp = fill.layoutParams; lp.width = (dp(380) * r.coerceIn(0f, 1f)).toInt(); fill.layoutParams = lp }
        fun btn(txt: String, dir: Int) = TextView(this).apply { text = txt; textSize = 32f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); isClickable = true; setPadding(dp(20), 0, dp(20), 0); setOnClickListener { onUserActivity(); val next = ((c.read() ?: c.min) + dir * c.step).coerceIn(c.min, c.hi()); updateUI(next); if (c.id == "tempD") { lastManualTempD = next; lastManualTempDTime = System.currentTimeMillis() } else { lastManualTempP = next; lastManualTempPTime = System.currentTimeMillis() }; io.execute { c.select(next); main.post { refreshAll() } } } }
        container.addView(btn("−", -1), FrameLayout.LayoutParams(dp(70), dp(44), Gravity.START or Gravity.CENTER_VERTICAL)); container.addView(btn("+", 1), FrameLayout.LayoutParams(dp(70), dp(44), Gravity.END or Gravity.CENTER_VERTICAL)); layout.addView(container)
        var canGo23 = false
        container.getChildAt(0).setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) canGo23 = (c.read() ?: c.min) >= 23.0; val v = (kotlin.math.round(((c.min + (e.x / dp(380)).coerceIn(0f, 1f) * (c.hi() - c.min))) / c.step) * c.step).coerceIn(c.min, c.hi()); val finalV = if (canGo23) v else minOf(v, 23.0); updateUI(finalV); if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) { onUserActivity(); if (c.id == "tempD") { lastManualTempD = finalV; lastManualTempDTime = System.currentTimeMillis() } else { lastManualTempP = finalV; lastManualTempPTime = System.currentTimeMillis() }; io.execute { c.select(finalV); main.post { refreshAll() } } }; true }
        updaters[c.id] = { val cur = c.read() ?: c.min; val now = System.currentTimeMillis(); if (c.id == "tempD") { if (now - lastManualTempDTime > 2000 || cur == lastManualTempD) updateUI(cur) } else if (c.id == "tempP") { if (now - lastManualTempPTime > 2000 || cur == lastManualTempP) updateUI(cur) } else updateUI(cur) }
        return layout
    }

    private fun createLevelControl(c: Level, iconRes: Int? = null, iconSize: Int = 35): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(dp(420), dp(44)) }
        if (iconRes != null) { layout.addView(icon(iconRes, dp(iconSize), cMuted)); layout.addView(gapView(8, true)) }
        val sW = dp(420) - (if (iconRes != null) dp(iconSize + 8) else 0); val container = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(sW, dp(44)); background = pill(cTrack, dp(22)) }
        val (indicator, updateVis) = createLevelIndicator(c); indicator.setPadding(dp(60), 0, dp(60), 0); container.addView(indicator, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        fun btn(txt: String, act: Int) = TextView(this).apply { text = txt; textSize = 32f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); isClickable = true; setPadding(dp(15), 0, dp(15), 0); setOnClickListener { onUserActivity(); val next = (c.value() + act).coerceIn(c.min, c.hi()); updateVis(next); io.execute { c.setLevel(next); main.post { refreshAll() } } } }
        container.addView(btn("−", -1), FrameLayout.LayoutParams(dp(65), dp(44), Gravity.START or Gravity.CENTER_VERTICAL)); container.addView(btn("+", 1), FrameLayout.LayoutParams(dp(65), dp(44), Gravity.END or Gravity.CENTER_VERTICAL)); layout.addView(container)
        container.setOnTouchListener { _, e -> val v = (kotlin.math.round((e.x / sW).coerceIn(0f, 1f) * c.hi())).toInt().coerceIn(c.min, c.hi()); updateVis(v); if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) { onUserActivity(); io.execute { c.setLevel(v); main.post { refreshAll() } } }; true }
        return layout
    }

    private fun createLevelIndicator(c: Level): Pair<View, (Int) -> Unit> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; val hi = c.hi().coerceAtLeast(1); val bars = Array(hi) { View(this).apply { background = pill(cTrack, dp(4)) } }
        bars.forEachIndexed { i, b -> row.addView(b, LinearLayout.LayoutParams(0, dp(14), 1f).apply { if (i > 0) marginStart = dp(8) }) }
        val updateVis = { v: Int -> bars.forEachIndexed { i, b -> b.background = pill(if (i < v) DockColors.CYAN else cTrack, dp(4)) } }
        updaters[c.id] = { _ -> updateVis(c.value()) }; updateVis(c.value()); return Pair(row, updateVis)
    }

    private fun createHvacQuickControls(side: String): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(380), dp(74)) }
        fun quickBtn(label: String, key: String, onV: String = "1", offV: String = "0") = TextView(this).apply {
            text = label; textSize = 14f; setTextColor(cMuted); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.1f; background = pill(cSurfaceRaised, dp(14), stroke = cLine)
            fun update(st: Boolean? = null) { val isOn = st ?: (VehicleClient.getData(key) == onV); background = pill(if (isOn) cSurfaceSelected else cSurfaceRaised, dp(14), stroke = if (isOn) cAccent else cLine); setTextColor(if (isOn) DockColors.CYAN else cMuted) }
            setOnClickListener { onUserActivity(); val isOn = VehicleClient.getData(key) == onV; update(!isOn); io.execute { VehicleClient.set(key, if (isOn) offV else onV); main.post { refreshAll() } } }
            updaters["quick_${side}_$key"] = { update(null) }; update()
        }
        layout.addView(quickBtn("POWER", DockKeys.CAR_HVAC_POWER_MODE), LinearLayout.LayoutParams(0, dp(64), 1f)); layout.addView(quickBtn("A/C", DockKeys.CAR_HVAC_AC_ENABLE), LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(16) }); layout.addView(quickBtn("AUTO", DockKeys.CAR_HVAC_AUTO_ENABLE), LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(16) }); layout.addView(quickBtn("SYNC", DockKeys.CAR_HVAC_SYNC_ENABLE), LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(16) })
        return layout
    }

    private fun createBatteryCard(c: Battery, segmented: Boolean = false): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, 0) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ic = icon(R.drawable.battery_charging_medium, dp(35), DockColors.GREEN)
        top.addView(ic)
        
        val titleTv = TextView(this).apply { text = "BATERIA"; textSize = 20f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.2f; setPadding(dp(8), 0, 0, 0) }
        top.addView(titleTv)
        
        val rangeTv = TextView(this).apply { textSize = 20f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD); setPadding(dp(4), 0, 0, 0) }
        top.addView(rangeTv)
        
        layout.addView(top)
        
        val infoTv = TextView(this).apply { textSize = 15f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD); setPadding(dp(43), dp(2), 0, 0) }
        layout.addView(infoTv)

        val track = FrameLayout(this).apply { background = if (segmented) null else pill(cTrack, dp(48)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) } }
        if (segmented) { val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }; for (i in 0 until 20) r.addView(View(this).apply { background = pill(cTrack, dp(4)) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = dp(4) }); track.addView(r, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
        else track.addView(View(this).apply { background = pill(DockColors.GREEN, dp(48)) }, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        val tv = TextView(this).apply { textSize = 26f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); text = "0%"; gravity = Gravity.CENTER; includeFontPadding = false; setShadowLayer(dp(6).toFloat(), 0f, 1f, Color.BLACK) }; track.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)); layout.addView(track)
        updaters[c.id] = { st ->
            val v = (st.text?.toString()?.replace("%", "")?.toIntOrNull() ?: 0).coerceIn(0, 100); tv.text = "$v%"; ic.setImageResource(st.icon); ic.setColorFilter(st.color)
            val (s, e) = when { v > 75 -> Color.parseColor("#00838F") to DockColors.CYAN; v >= 31 -> Color.parseColor("#2E7D32") to DockColors.GREEN; v > 15 -> Color.parseColor("#FF8F00") to Color.parseColor("#ffcf00"); else -> Color.parseColor("#C62828") to DockColors.RED }
            if (segmented) { val r = track.getChildAt(0) as LinearLayout; val count = (v / 5).coerceIn(0, 20); for (i in 0 until 20) r.getChildAt(i).background = if (i < count) pill(blend(s, e, i / 19f), dp(4)) else pill(cTrack, dp(4)) }
            else { val f = track.getChildAt(0); f.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(s, e)).apply { cornerRadius = dp(48).toFloat() }; val lp = f.layoutParams as FrameLayout.LayoutParams; track.post { lp.width = (track.width * (v / 100f)).toInt(); f.layoutParams = lp } }
            
            val range = VehicleClient.getData(DockKeys.CAR_EV_INFO_ELECTRIC_MODE_REMAIN_ODOMETER) ?: "—"
            val time = VehicleClient.getData(DockKeys.CAR_EV_INFO_CHARGE_REMAINING_TIME)?.toIntOrNull() ?: 0
            val isCharging = VehicleClient.getData(DockKeys.CAR_EV_INFO_CHARGING_GUN_CONN_STATE) == "1"
            
            rangeTv.text = "- $range Km"
            val showTime = time > 0 && isCharging
            
            if (showTime) {
                var text = "Tempo de Recarga $time minutos."
                val lastChargeRaw = VehicleClient.getData(DockKeys.CAR_EV_INFO_LAST_CHARGE_TIME_ODOMETER)
                val lastInfo = parseLastCharge(lastChargeRaw)
                if (lastInfo != null) {
                    val totalOdo = VehicleClient.getData(DockKeys.CAR_EV_INFO_TOTAL_ODOMETER)?.toDoubleOrNull() ?: 0.0
                    val diff = (totalOdo - lastInfo.second).coerceAtLeast(0.0)
                    text += String.format(java.util.Locale.US, "\nÚltima recarga %s - %.1f Km rodados", lastInfo.first, diff)
                }
                infoTv.text = text
                infoTv.visibility = View.VISIBLE
            } else {
                infoTv.visibility = View.GONE
            }
        }; layout.addView(gapView(6)); return layout
    }

    private fun createDriveModeSelectionLight(c: Mode): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val hevTile = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; layoutParams = LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginStart = dp(4); marginEnd = dp(4) } }
        val hevText = TextView(this).apply { text = "HEV"; textSize = 18f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER_HORIZONTAL }
        val strategyText = TextView(this).apply { text = "INTELIGENTE"; textSize = 14f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(2), 0, 0); gravity = Gravity.CENTER_HORIZONTAL }
        hevTile.addView(hevText); hevTile.addView(strategyText)
        val options = listOf(1 to "Prioridade EV", 3 to "EV")
        val otherTiles = options.map { opt -> LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; isClickable = true; addView(TextView(this@OverlayService).apply { text = opt.second.uppercase(); textSize = 18f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER }) } }
        val hevSub = createHevSubCardLight(c)
        val updateUI = { targetMode: Int? ->
            val now = System.currentTimeMillis(); val isRecent = now - lastManualSocTime < 2000
            val curM = targetMode ?: (if (isRecent) lastManualMode else c.cur()); val curS = if (isRecent && lastManualStrategy != -1) lastManualStrategy else c.curStrategy(); val isHev = curM == 0
            hevTile.background = pill(if (isHev) cSurfaceSelected else cSurfaceRaised, dp(16), stroke = if (isHev) DockColors.AMBER else cLine); hevText.setTextColor(if (isHev) DockColors.AMBER else cMuted)
            if (isHev) { strategyText.text = "INTELIGENTE"; strategyText.setTextColor(if (curS == 1) DockColors.AMBER else cMuted) } else { strategyText.text = "INTELIGENTE"; strategyText.setTextColor(cMuted) }
            options.forEachIndexed { i, (m, _) -> val active = curM == m; val t = otherTiles[i]; val tv = t.getChildAt(0) as TextView; val color = c.colors[m] ?: DockColors.GREEN; t.background = pill(if (active) cSurfaceSelected else cSurfaceRaised, dp(16), stroke = if (active) color else cLine); tv.setTextColor(if (active) cTxt else cMuted) }
            hevSub.alpha = if (isHev) 1f else 0.4f; hevSub.isEnabled = isHev
        }
        hevTile.setOnClickListener { changeDriveMode(c, 0, strategy = 1) }
        modeRow.addView(hevTile); otherTiles.forEachIndexed { i, t -> modeRow.addView(t, LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginStart = dp(4); marginEnd = dp(4) }); t.setOnClickListener { changeDriveMode(c, options[i].first) } }
        layout.addView(modeRow); layout.addView(gapView(16)); layout.addView(hevSub); layout.addView(gapView(4))
        updaters[c.id] = { updateUI(null) }; updateUI(null); return layout
    }

    private fun createHevSubCardLight(c: Mode): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = pill(cCard, dp(16), stroke = cLine); setPadding(dp(20), dp(10), dp(20), dp(10)); isClickable = true }
        val sliderArea = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val socLabel = TextView(this).apply { textSize = 16f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD); text = "SAVE -%"; minWidth = dp(85) }; sliderArea.addView(socLabel)
        val sW = dp(380); val track = FrameLayout(this).apply { background = pill(cTrack, dp(18)); layoutParams = LinearLayout.LayoutParams(sW, dp(36)).apply { marginStart = dp(12) } }
        val fill = View(this).apply { background = pill(DockColors.AMBER, dp(18)) }; track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)); sliderArea.addView(track)
        fun updateSliderUI(soc: Int, forced: Boolean? = null) {
            val now = System.currentTimeMillis(); val isRecent = now - lastManualSocTime < 2000; val isSave = forced ?: (if (isRecent && lastManualStrategy != -1) lastManualStrategy == 2 else c.curStrategy() == 2); val dispSoc = if (isRecent && lastManualSoc != -1) lastManualSoc else soc
            socLabel.text = "SAVE $dispSoc%"; socLabel.setTextColor(if (isSave) cTxt else cMuted); val lp = fill.layoutParams; lp.width = (sW * ((dispSoc - c.minSoc).toFloat() / (c.maxSoc - c.minSoc)).coerceIn(0f, 1f)).toInt(); fill.layoutParams = lp; fill.background = pill(if (isSave) DockColors.AMBER else cMuted, dp(18)); sliderArea.alpha = if (isSave) 1f else 0.4f; layout.background = pill(if (isSave) cSurfaceSelected else cCard, dp(16), stroke = if (isSave) DockColors.AMBER else cLine)
        }
        layout.setOnClickListener { if (layout.isEnabled) changeDriveMode(c, 0, strategy = 2) }
        track.setOnTouchListener { _, e -> if (!layout.isEnabled) return@setOnTouchListener true; val soc = c.minSoc + ((e.x / sW).coerceIn(0f, 1f) * (c.maxSoc - c.minSoc)).toInt(); if (e.action == MotionEvent.ACTION_MOVE) updateSliderUI(soc, true); if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) changeDriveMode(c, 0, strategy = 2, soc = soc); true }
        layout.addView(sliderArea); updaters["hev_sub_card_light"] = { updateSliderUI(c.curHevSocInt(), null) }; return layout
    }

    private fun createAmbientTempCard(c: IconToggle): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true }; val internal = createTempInfo(R.drawable.ic_thermo, DockColors.ORANGE, "INTERNA", "tempIn"); layout.addView(internal, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); val img = icon(R.drawable.ic_recirc_closed, dp(52), cMuted); layout.addView(img, LinearLayout.LayoutParams(dp(68), dp(68)).apply { marginStart = dp(12); marginEnd = dp(12) }); val external = createTempInfo(R.drawable.ic_external_thermo, DockColors.CYAN, "EXTERNA", "tempOut"); layout.addView(external, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); layout.setOnClickListener { onUserActivity(); io.execute { c.flip(); main.post { refreshAll() } } }
        updaters[c.id] = { val on = it.on; img.setImageResource(if (on) R.drawable.ic_recirc_closed else R.drawable.ic_recirc_open); img.setColorFilter(if (on) DockColors.CYAN else cMuted) }; return layout
    }

    private fun createTempInfo(res: Int, color: Int, label: String, id: String): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; layout.addView(icon(res, dp(35), color))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }; val tvVal = TextView(this).apply { text = "--,−°"; textSize = 32f; setTextColor(cTxt); setTypeface(typeface, Typeface.BOLD); includeFontPadding = false }; val tvLabel = TextView(this).apply { text = label; textSize = 12f; setTextColor(cMuted); setTypeface(typeface, Typeface.BOLD); includeFontPadding = false }; col.addView(tvVal); col.addView(tvLabel); layout.addView(col)
        updaters[id] = { tvVal.text = it.text?.toString() ?: "--°" }; return layout
    }

    private fun createAirflowSelection(side: String): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(380), dp(74)) }; val options = DockControls.AIRFLOW_OPTIONS; val icons = ArrayList<ImageView>()
        val updateUI = { targetOpt: AirflowOption? -> options.forEachIndexed { i, opt -> val iv = icons.getOrNull(i) ?: return@forEachIndexed; val isCur = if (targetOpt != null) opt == targetOpt else DockControls.AIRFLOW_CONTROL.currentOption() == opt; iv.background = pill(if (isCur) cSurfaceSelected else cSurfaceRaised, dp(14), stroke = if (isCur) cAccent else cLine); iv.setColorFilter(if (isCur) DockColors.CYAN else cMuted) } }
        options.forEachIndexed { i, opt -> val iv = icon(opt.icon, cMuted, 56).apply { setPadding(dp(8), dp(8), dp(8), dp(8)); background = pill(cSurfaceRaised, dp(14), stroke = cLine); isClickable = true; setOnClickListener { onUserActivity(); updateUI(opt); io.execute { DockControls.AIRFLOW_CONTROL.select(opt); main.post { refreshAll() } } } }; icons.add(iv); layout.addView(iv, LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (i > 0) marginStart = dp(8) }); updaters["dash_air_${side}_${opt.label}"] = { updateUI(null) } }
        updateUI(null); return layout
    }

    private fun createVolumeControl(c: Volume): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }; val projArea = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) }; visibility = View.GONE; isClickable = true; setOnClickListener { onProjClick() } }; val projImg = ImageView(this).apply { layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER) }; projArea.addView(projImg); layout.addView(projArea)
        updaters["dash_proj"] = { val conn = projConnected; val fg = projForeground; if (conn == null) projArea.visibility = View.GONE else { projArea.visibility = View.VISIBLE; when { fg -> { projImg.setImageResource(R.drawable.ic_car); projImg.setColorFilter(cTxt) }; conn == ProjectionLauncher.AA_PKG -> { projImg.setImageResource(R.drawable.ic_androidauto); projImg.clearColorFilter() }; else -> { projImg.setImageResource(R.drawable.ic_carplay); projImg.clearColorFilter() } } } }
        val volIc = icon(c.icon, cMuted, 49); layout.addView(volIc, LinearLayout.LayoutParams(dp(49), dp(49)).apply { marginStart = dp(16) })
        val sW = dp(330); val track = FrameLayout(this).apply { background = pill(cTrack, dp(18)); layoutParams = LinearLayout.LayoutParams(sW, dp(36)).apply { marginStart = dp(12) } }; val fill = View(this).apply { background = pill(DockColors.CYAN, dp(18)) }; track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)); layout.addView(track)
        var canGo12 = false; var curV = 0
        fun updateUI(v: Int) { val color = if (v > 12) DockColors.RED else DockColors.CYAN; val lp = fill.layoutParams; lp.width = (sW * (v.toFloat() / c.hi()).coerceIn(0f, 1f)).toInt(); fill.layoutParams = lp; fill.background = pill(color, dp(18)) }
        track.setOnTouchListener { _, e -> var v = ((e.x / sW).coerceIn(0f, 1f) * c.hi()).toInt(); if (e.action == MotionEvent.ACTION_DOWN) canGo12 = curV >= 12; if (!canGo12) v = minOf(v, 12); updateUI(v); if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) { onUserActivity(); curV = v; lastManualVol = v; lastManualVolTime = System.currentTimeMillis(); io.execute { c.set(v); main.post { refreshAll() } } }; true }
        updaters[c.id] = { val v = c.value(); val now = System.currentTimeMillis(); if (now - lastManualVolTime > 2000 || v == lastManualVol) { curV = v; updateUI(v); if (it.icon != 0) volIc.setImageResource(it.icon) } }; return layout
    }

    private fun createPowerChart(heightDp: Int = 165): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Se heightDp for 0, usa peso para ocupar o máximo possível, caso contrário usa altura fixa
            layoutParams = if (heightDp <= 0) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
            }
        }

        // 1. Barra de Acumulados (Estrutura Manual para Centralização Perfeita)
        val accumContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(105), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(12) }
            background = pill(cTrack, dp(8))
            clipToOutline = true
        }
        
        // Segmento de Regeneração (Topo)
        val regenBar = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(Color.BLACK); setTypeface(null, Typeface.BOLD); textSize = 26f
            background = pill(DockColors.CYAN, 0)
        }
        // Segmento de Consumo (Base)
        val consBar = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(Color.BLACK); setTypeface(null, Typeface.BOLD); textSize = 26f
            background = pill(cEmerald, 0)
        }
        
        accumContainer.addView(regenBar)
        accumContainer.addView(consBar)
        container.addView(accumContainer)

        // 2. Gráfico de Linha + Valores à Direita (Instantâneo)
        val chartWrapper = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        val lineChart = LineChart(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            description.isEnabled = false; legend.isEnabled = false; setTouchEnabled(false)
            xAxis.isEnabled = false; axisRight.isEnabled = false; setDrawGridBackground(false)
            axisLeft.apply {
                setDrawGridLines(true); gridColor = Color.parseColor("#1AFFFFFF")
                textColor = cMuted; textSize = 22f; setLabelCount(3, true); setDrawAxisLine(false)
            }
        }
        chartWrapper.addView(lineChart)

        // Labels flutuantes na direita
        val currConsText = TextView(this).apply {
            setTextColor(cEmerald); setTypeface(null, Typeface.BOLD); textSize = 28f
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                rightMargin = dp(8)
            }
        }
        val currRegenText = TextView(this).apply {
            setTextColor(cAccent); setTypeface(null, Typeface.BOLD); textSize = 28f
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                rightMargin = dp(8)
            }
        }
        chartWrapper.addView(currConsText)
        chartWrapper.addView(currRegenText)
        
        container.addView(chartWrapper)

        // Interação: Clique Duplo para alternar histórico
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val (limit, label) = when (chartLimit) {
                    30 -> 90 to "3 min"
                    90 -> 150 to "5 min"
                    else -> 30 to "1 min"
                }
                chartLimit = limit
                showFlash(label)
                updaters["power_chart"]?.invoke(RenderState())
                return true
            }
        })
        container.setOnTouchListener { _, event -> gd.onTouchEvent(event); true }

        updaters["power_chart"] = {
            // Atualiza Barra Acumulada
            val cycleEnergy = VehicleClient.getData(DockKeys.CAR_EV_INFO_CYCLE_ENERGY_CONSUME_INFO)?.toFloatOrNull() ?: 0f //rever acho que esta com a variavel errada
            val energyRecovery = VehicleClient.getData(DockKeys.CAR_EV_INFO_ENERGY_RECOVERY_INFO)?.toFloatOrNull() ?: 0f
            val total = cycleEnergy + energyRecovery

            if (total > 0) {
                regenBar.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, energyRecovery)
                consBar.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, cycleEnergy)
                
                regenBar.text = if (energyRecovery > 0.01) String.format(java.util.Locale.US, "%.2f", energyRecovery) else ""
                consBar.text = if (cycleEnergy > 0.01) String.format(java.util.Locale.US, "%.2f", cycleEnergy) else ""
                
                regenBar.visibility = if (energyRecovery > 0) View.VISIBLE else View.GONE
                consBar.visibility = if (cycleEnergy > 0) View.VISIBLE else View.GONE
            } else {
                regenBar.visibility = View.GONE; consBar.visibility = View.GONE
            }

            // Filtra o histórico para o limite atual
            val history = powerHistory.takeLast(chartLimit)
            
            // Atualiza Gráfico de Linha
            val consumptionEntries = ArrayList<Entry>()
            val regenEntries = ArrayList<Entry>()
            history.forEachIndexed { i, p ->
                consumptionEntries.add(Entry(i.toFloat(), p.consumption, p.outputPct))
                regenEntries.add(Entry(i.toFloat(), p.regen, p.outputPct))
            }

            fun setupDataSet(entries: List<Entry>, label: String, colorRes: Int): LineDataSet {
                return LineDataSet(entries, label).apply {
                    color = colorRes; setDrawCircles(false); lineWidth = 3f
                    mode = LineDataSet.Mode.CUBIC_BEZIER; setDrawFilled(true); fillColor = colorRes; fillAlpha = 50
                    valueTextSize = 22f; valueTextColor = colorRes
                    valueFormatter = object : ValueFormatter() {
                        override fun getPointLabel(entry: Entry?): String {
                            if (entry == null) return ""
                            val idx = entries.indexOf(entry)
                            if (idx <= 0 || idx >= entries.size - 1) return ""
                            val prev = entries[idx - 1].y
                            val next = entries[idx + 1].y
                            val cur = entry.y
                            // Detecta topo local (maior que vizinhos e maior que um limiar mínimo de ruído)
                            if (cur > prev && cur > next && cur > 1.0f) {
                                val pct = entry.data as? Float ?: 0f
                                return String.format(java.util.Locale.US, "%.1f\n [%.0f%%]", cur, pct)
                            }
                            return ""
                        }
                    }
                }
            }

            val ds1 = setupDataSet(consumptionEntries, "Consumo", cEmerald)
            val ds2 = setupDataSet(regenEntries, "Regeneração", cAccent)
            
            lineChart.data = LineData(ds1, ds2)
            lineChart.invalidate()

            // Atualiza Valores Atuais à Direita (acompanhando a linha)
            val lastPoint = history.lastOrNull() ?: PowerPoint(0f, 0f, 0f)
            currConsText.text = if (lastPoint.consumption > 0.05) String.format(java.util.Locale.US, "%.1f\n [%.0f%%]", lastPoint.consumption, lastPoint.outputPct) else ""
            currRegenText.text = if (lastPoint.regen > 0.05) String.format(java.util.Locale.US, "%.1f\n [%.0f%%]", lastPoint.regen, lastPoint.outputPct) else ""

            lineChart.post {
                val transformer = lineChart.getTransformer(YAxis.AxisDependency.LEFT)
                val xPos = (history.size - 1).coerceAtLeast(0).toFloat()
                
                val p1 = transformer.getPixelForValues(xPos, lastPoint.consumption)
                val p2 = transformer.getPixelForValues(xPos, lastPoint.regen)
                
                currConsText.translationY = p1.y.toFloat() - currConsText.height / 2f
                currRegenText.translationY = p2.y.toFloat() - currRegenText.height / 2f

                MPPointD.recycleInstance(p1)
                MPPointD.recycleInstance(p2)

                // Evita sobreposição se estiverem muito próximos
                if (currConsText.text.isNotEmpty() && currRegenText.text.isNotEmpty()) {
                    val diff = kotlin.math.abs(currConsText.translationY - currRegenText.translationY)
                    if (diff < dp(25)) {
                        if (currConsText.translationY < currRegenText.translationY) {
                            currConsText.translationY -= dp(12)
                            currRegenText.translationY += dp(12)
                        } else {
                            currConsText.translationY += dp(12)
                            currRegenText.translationY -= dp(12)
                        }
                    }
                }
            }
        }

        return container
    }

    private fun gapView(size: Int, horizontal: Boolean = false): View = View(this).apply { layoutParams = if (horizontal) LinearLayout.LayoutParams(dp(size), 1) else LinearLayout.LayoutParams(1, dp(size)) }

    private fun parseLastCharge(raw: String?): Pair<String, Double>? {
        if (raw == null || raw.length < 5) return null
        return runCatching {
            // Remove chaves se existirem e separa por vírgula
            val clean = raw.replace("{", "").replace("}", "").trim()
            val parts = clean.split(",").map { it.trim() }
            if (parts.size >= 2) {
                val ts = parts[0].toLongOrNull() ?: 0L
                val odo = parts[1].toDoubleOrNull() ?: 0.0
                
                // Timestamp pode estar em segundos ou milissegundos
                val millis = if (ts < 1000000000000L) ts * 1000 else ts
                val date = java.util.Date(millis)
                val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US)
                fmt.format(date) to odo
            } else null
        }.getOrNull()
    }

    /**
     * Restaura a visibilidade da interface, expandindo a janela do WindowManager
     * e notificando outros apps sobre o espaço ocupado.
     */
    private fun showBar() {
        main.removeCallbacks(hideRunnable); if (hidden) { hidden = false; bar?.visibility = View.VISIBLE; dashboard?.visibility = View.VISIBLE; handle.visibility = View.GONE; val isDash = SettingsStore.visualMode.value != SettingsStore.VISUAL_BAR; params.width = if (isDash) 1770 else WindowManager.LayoutParams.MATCH_PARENT; params.height = if (isDash) 720 else barHeightPx; params.gravity = Gravity.BOTTOM or (if (isDash) Gravity.END else Gravity.START); runCatching { wm.updateViewLayout(root, params) }; broadcastBarState(); refreshAll() }; armTimer()
    }
    
    /**
     * Minimiza a interface para uma alça compacta (mini pill).
     * Ocorre automaticamente pelo timer (Modo Auto) ou por gesto do usuário.
     */
    private fun hideBar(manual: Boolean = false) { if (!manual && SettingsStore.mode(this) != SettingsStore.MODE_AUTO) return; closeAllPopups(); hidden = true; bar?.visibility = View.GONE; dashboard?.visibility = View.GONE; handle.visibility = View.VISIBLE; params.width = dp(100); params.height = handleHeightPx; params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; runCatching { wm.updateViewLayout(root, params) }; broadcastBarState() }
    private fun broadcastBarState() { runCatching { sendBroadcast(Intent(ACTION_BAR_STATE).putExtra(EXTRA_VISIBLE, !hidden).putExtra(EXTRA_HEIGHT_DP, if (hidden) HANDLE_DP else SettingsStore.barHeight(this))) } }
    private fun registerRequestReceiver() { val f = IntentFilter(ACTION_REQUEST_STATE); if (Build.VERSION.SDK_INT >= 33) registerReceiver(requestReceiver, f, RECEIVER_EXPORTED) else registerReceiver(requestReceiver, f) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun blend(c1: Int, c2: Int, ratio: Float): Int { val ir = 1f - ratio; return Color.argb((Color.alpha(c1) * ir + Color.alpha(c2) * ratio).toInt(), (Color.red(c1) * ir + Color.red(c2) * ratio).toInt(), (Color.green(c1) * ir + Color.green(c2) * ratio).toInt(), (Color.blue(c1) * ir + Color.blue(c2) * ratio).toInt()) }
    private fun pill(fill: Int, radius: Int, topOnly: Boolean = false, stroke: Int? = null): GradientDrawable = GradientDrawable().apply { setColor(fill); if (topOnly) cornerRadii = floatArrayOf(radius.toFloat(), radius.toFloat(), radius.toFloat(), radius.toFloat(), 0f, 0f, 0f, 0f) else cornerRadius = radius.toFloat(); stroke?.let { setStroke(dp(1), it) } }
    private fun buildNotification(): Notification { val cId = "haval_dock_overlay"; val nm = getSystemService(NotificationManager::class.java); if (nm.getNotificationChannel(cId) == null) nm.createNotificationChannel(NotificationChannel(cId, "Haval Dock", NotificationManager.IMPORTANCE_MIN)); return Notification.Builder(this, cId).setContentTitle("Haval Dock").setContentText("Barra inferior ativa").setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build() }

    private class TouchFrame(context: Context, val onTouch: () -> Unit, val onSwipeDown: () -> Unit, val onSwipeUp: () -> Unit) : FrameLayout(context) {
        private val threshold = 30 * context.resources.displayMetrics.density; private val hLockThreshold = 10 * context.resources.displayMetrics.density; private var downY = 0f; private var downX = 0f; private var fired = false; private var hLocked = false
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean { if (ev == null) return super.dispatchTouchEvent(ev); when (ev.actionMasked) { MotionEvent.ACTION_DOWN -> { downY = ev.y; downX = ev.x; fired = false; hLocked = false; onTouch() }; MotionEvent.ACTION_MOVE -> { if (fired) return true; val dy = ev.y - downY; val dx = ev.x - downX; val absDy = kotlin.math.abs(dy); val absDx = kotlin.math.abs(dx); if (!hLocked && absDx > hLockThreshold && absDx > absDy) hLocked = true; if (!hLocked && absDy > threshold && absDy > absDx) { fired = true; val cancel = MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL }; super.dispatchTouchEvent(cancel); cancel.recycle(); if (dy > 0) onSwipeDown() else onSwipeUp(); return true } }; MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (fired) { fired = false; return true } }; MotionEvent.ACTION_OUTSIDE -> { onSwipeDown(); return true } }; return super.dispatchTouchEvent(ev) }
    }

    /**
     * ViewPager customizado que desabilita o gesto de deslizar (swipe).
     * A navegação entre páginas passa a ser feita exclusivamente pelos botões.
     */
    private class NonSwipeViewPager(context: Context) : ViewPager(context) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false
        override fun onTouchEvent(ev: MotionEvent): Boolean = false
    }

    companion object {
        private const val NOTIF_ID = 42; const val HANDLE_DP = 22; const val ACTION_BAR_STATE = "br.com.redesurftank.havaldock.BAR_STATE"; const val ACTION_REQUEST_STATE = "br.com.redesurftank.havaldock.REQUEST_BAR_STATE"; const val EXTRA_VISIBLE = "visible"; const val EXTRA_HEIGHT_DP = "height_dp"
        fun start(context: Context) { context.startForegroundService(Intent(context, OverlayService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, OverlayService::class.java)) }
    }
}
