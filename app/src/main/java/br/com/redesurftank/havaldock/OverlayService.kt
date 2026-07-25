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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * Toolbar inferior como overlay (TYPE_APPLICATION_OVERLAY), só na faixa de baixo, visual v2
 * (estilo do app de referência). Lê/escreve via [VehicleClient]; IPC sempre fora da main thread.
 */
class OverlayService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: TouchFrame
    private lateinit var bar: LinearLayout
    private lateinit var handle: TextView

    private val updaters = HashMap<String, (RenderState) -> Unit>()
    private var volWin: View? = null
    private var airflowWin: View? = null
    private var levelWin: View? = null
    private var modeWin: View? = null
    private var tempWin: View? = null
    private var hidden = false

    // botão de atalho de projeção (CarPlay/AA): aparece quando há projeção conectada
    private var projView: View? = null
    private var projIcon: ImageView? = null
    private var projConnected: String? = null   // pacote da projeção conectada (ou null)
    private var projForeground = false          // projeção está em foco no Display 0
    private var projShownState: String? = null  // estado do ícone atual ("car" ou o pacote)
    private var lastProjection: String? = null  // última projeção vista em foco (p/ mostrar o logo certo na central)
    private var lastCentralApp: String? = null  // último app NÃO-projeção no topo do D0 (p/ voltar a ele)

    private val barHeightPx by lazy { dp(BAR_DP) }
    private val handleHeightPx by lazy { dp(HANDLE_DP) }
    private val trackPx by lazy { dp(30) }

    private val cAccent = DockColors.CYAN
    private val cTxt = DockColors.WHITE
    private val cMuted = Color.parseColor("#828C9C")
    private val cCard = Color.parseColor("#121722")
    private val cLine = Color.parseColor("#23FFFFFF")
    private val cBarBg = Color.parseColor("#F2070A0E")
    private val cOnAccent = Color.parseColor("#04161A")

    private val hideRunnable = Runnable { hideBar() }

    private val listener = object : IListener.Stub() {
        override fun onDataChanged(key: String?, value: String?) { main.post { refreshAll() } }
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsStore.KEY_MODE || key == SettingsStore.KEY_SECS) applyVisibility()
    }
    // Outro app (ex.: haval-radio) pede o estado atual da barra; respondemos com um broadcast.
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
        // re-lê o snapshot toda vez que a conexão com o veículo (re)estabelece — ex.: o Shizuku/serviço
        // sobe depois da barra no boot, ou o binder morre e reconecta. Substitui o antigo hack de
        // refreshAll() com postDelayed, que só mascarava a corrida.
        VehicleClient.addConnectionListener(onVehicleConnected)
        io.execute { runCatching { VehicleClient.registerListener(DockControls.MONITORED, listener) } }
        HvacPanel.ensureEnabled()   // rede de segurança: garante o painel do ar habilitado
        refreshAll()
        main.postDelayed(projPoll, 1200)   // detecção da projeção (CarPlay/AA)
    }

    private val onVehicleConnected: () -> Unit = { refreshAll() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyVisibility(); return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(projPoll)
        closeVolume()
        closeAirflow()
        closeLevel()
        runCatching { SettingsStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener) }
        runCatching { unregisterReceiver(requestReceiver) }
        // barra saiu de cena: avisa quem reserva o rodapé p/ liberar o espaço
        runCatching { sendBroadcast(Intent(ACTION_BAR_STATE).putExtra(EXTRA_VISIBLE, false).putExtra(EXTRA_HEIGHT_DP, 0)) }
        VehicleClient.removeConnectionListener(onVehicleConnected)
        io.execute { runCatching { VehicleClient.unregisterListener(listener) } }
        runCatching { wm.removeView(root) }
    }

    // ---- overlay ----

    private fun buildOverlay() {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, barHeightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }

        root = TouchFrame(this, { onUserActivity() }, { hideBar(manual = true) }, { showBar() })

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBarBg)
        }
        // linha ciano no topo
        bar.addView(View(this).apply { setBackgroundColor(cAccent) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), 0, dp(40), 0)
        }
        bar.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        buildSections(content)

        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        handle = TextView(this).apply {
            text = "▴ Haval Dock"; setTextColor(cAccent); textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(22), dp(5), dp(22), dp(6)); background = pill(cBarBg, dp(12), topOnly = true)
            visibility = View.GONE; setOnClickListener { showBar() }
        }
        root.addView(handle, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        wm.addView(root, params)
    }

    private fun buildSections(content: LinearLayout) {
        val secs = arrayOf(rowSection(), rowSection(), rowSection(), rowSection())
        for (c in DockControls.ALL) {
            if (c.section < secs.size) secs[c.section].addView(tile(c))
        }
        secs[0].addView(projTile())
        content.addView(secs[0])
        content.addView(fixedSpacer(80))
        content.addView(secs[1])
        content.addView(fixedSpacer(90))
        content.addView(secs[2])
        content.addView(spacer())
        content.addView(secs[3])
    }

    private fun rowSection() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private fun fixedSpacer(w: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(w), 1)
    }

    private fun tile(c: Control): View = when (c) {
        is Temp -> tileTemp(c)
        is Level -> tileLevel(c)
        is Volume -> tileVolume(c)
        is TxtToggle -> tileTxt(c)
        is MaxAc -> tileMax(c)
        is IconToggle -> tileIconToggle(c)
        is Mode -> tileMode(c)
        is Info -> tileInfo(c)
        is Regen -> tileRegen(c)
        is Airflow -> tileAirflow(c)
    }

    private fun gap(v: View, start: Int) { (v.layoutParams as LinearLayout.LayoutParams).marginStart = dp(start) }

    private fun col() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(22) }
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun tileTemp(c: Temp): View {
        val v = col(); v.isClickable = true
        val tv = TextView(this).apply {
            setTextColor(cAccent); textSize = 25f; setTypeface(typeface, Typeface.BOLD); text = "—°"
            gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
        }
        v.addView(tv)
        updaters[c.id] = { st -> tv.text = st.text }
        v.setOnClickListener { onUserActivity(); openTemp(c, v) }
        return v
    }

    private fun tileLevel(c: Level): View {
        val v = col(); v.isClickable = true
        v.addView(icon(c.icon, cTxt, 26))
        val track = makeTrack()
        v.addView(track.first)
        updaters[c.id] = { st -> setTrack(track.second, st.ratio) }
        v.setOnClickListener { if (c.picker) { onUserActivity(); openLevel(c, v) } else act(c) { c.cycle() } }
        return v
    }

    private fun tileVolume(c: Volume): View {
        val v = col(); v.isClickable = true
        v.addView(icon(c.icon, cTxt, 26))
        val track = makeTrack()
        v.addView(track.first)
        updaters[c.id] = { st -> setTrack(track.second, st.ratio) }
        v.setOnClickListener { onUserActivity(); openVolume(c, v) }
        return v
    }

    private fun tileTxt(c: TxtToggle): View = textTile(c, c.label) { c.flip() }
    private fun tileMax(c: MaxAc): View = textTile(c, c.label) { c.flip() }

    private fun textTile(c: Control, label: String, onFlip: () -> Unit): View {
        val v = col(); v.isClickable = true
        val tv = TextView(this).apply {
            text = label; setTextColor(cMuted); textSize = 20f; setTypeface(typeface, Typeface.BOLD)
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
        val ic = icon(c.iconOff, cTxt, 46)   // recirc maior; ícone trocado por estado
        v.addView(ic)
        val track = makeTrack()
        v.addView(track.first)
        updaters[c.id] = { st ->
            if (st.icon != 0) ic.setImageResource(st.icon)
            ic.setColorFilter(if (st.on) cAccent else cTxt)
            setTrack(track.second, if (st.on) 1f else 0f)
        }
        v.setOnClickListener { act(c) { c.flip() } }
        return v
    }

    private fun tileAirflow(c: Airflow): View {
        val v = col(); v.isClickable = true
        val ic = icon(c.options.first().icon, cTxt, 34)
        v.addView(ic)
        updaters[c.id] = { st ->
            if (st.icon != 0) ic.setImageResource(st.icon)
            ic.setColorFilter(cTxt)
        }
        v.setOnClickListener { onUserActivity(); openAirflow(c, v) }
        return v
    }

    private fun tileMode(c: Mode): View {
        val v = col(); v.isClickable = true
        val ic = icon(c.icon, cAccent, 20)
        val tv = TextView(this).apply {
            setTextColor(cAccent); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; setSingleLine(true); maxLines = 1; setPadding(dp(4), 0, dp(4), 0); text = "—"
        }
        v.addView(ic)
        v.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        updaters[c.id] = { st -> ic.setColorFilter(st.color); tv.text = st.text; tv.setTextColor(st.color) }
        v.setOnClickListener { onUserActivity(); openMode(c, v) }
        return v
    }

    private fun tileInfo(c: Info): View {
        val v = col()
        val ic = icon(c.icon, cTxt, 18)
        val tv = TextView(this).apply {
            setTextColor(cTxt); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; setSingleLine(true); maxLines = 1; setPadding(dp(4), 0, dp(4), 0); text = "—°"
        }
        v.addView(ic)
        v.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        updaters[c.id] = { st -> tv.text = st.text }
        return v
    }

    private fun tileRegen(c: Regen): View {
        val v = col(); v.isClickable = true
        val ic = icon(c.icon, cAccent, 24)
        v.addView(ic)
        val barsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bars = Array(3) { View(this) }
        bars.forEachIndexed { i, b ->
            b.background = pill(cLine, dp(1))
            barsRow.addView(b, LinearLayout.LayoutParams(dp(7), dp(5)).apply { if (i > 0) marginStart = dp(3) })
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
        val pop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(cBarBg, dp(18)); setPadding(dp(16), dp(12), dp(16), dp(12))
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
            valTv.text = v.toString()
            val hi = c.hi().coerceAtLeast(1)
            val r = v.toFloat() / hi
            val lp = sliderFill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt()
            sliderFill.layoutParams = lp
        }

        sliderTrack.setOnTouchListener { view, e ->
            val r = (e.x / view.width).coerceIn(0f, 1f)
            val v = (r * c.hi()).toInt()
            updateUI(v)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity()
                io.execute { c.set(v); main.post { refreshAll() } }
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = barHeightPx + dp(8)
            val loc = IntArray(2); anchor.getLocationOnScreen(loc)
            @Suppress("DEPRECATION")
            x = (loc[0] + anchor.width / 2) - (wm.defaultDisplay.width / 2)
        }
        runCatching { wm.addView(pop, lp); volWin = pop }

        io.execute {
            val cur = c.value()
            main.post { updateUI(cur) }
        }
        onUserActivity()
    }

    private fun closeVolume() { volWin?.let { v -> runCatching { wm.removeView(v) } }; volWin = null }

    private fun closeMode() { modeWin?.let { v -> runCatching { wm.removeView(v) } }; modeWin = null }

    private fun closeTemp() { tempWin?.let { v -> runCatching { wm.removeView(v) } }; tempWin = null }

    // ---- popup de fluxo de ar (linha horizontal de ícones) ----

    private fun openAirflow(c: Airflow, anchor: View) {
        if (airflowWin != null) { closeAirflow(); return }
        closeVolume(); closeLevel(); closeMode(); closeTemp()
        val pop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(cBarBg, dp(18)); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val ivs = ArrayList<Pair<AirflowOption, ImageView>>()
        c.options.forEach { opt ->
            val iv = ImageView(this).apply {
                setImageResource(opt.icon); setColorFilter(cTxt); isClickable = true
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginStart = dp(4); marginEnd = dp(4) }
                setOnClickListener {
                    onUserActivity()
                    io.execute { c.select(opt); main.post { closeAirflow(); refreshAll() } }
                }
            }
            ivs.add(opt to iv); pop.addView(iv)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = barHeightPx + dp(8)
            val loc = IntArray(2); anchor.getLocationOnScreen(loc)
            @Suppress("DEPRECATION")
            x = (loc[0] + anchor.width / 2) - (wm.defaultDisplay.width / 2)
        }
        runCatching { wm.addView(pop, lp); airflowWin = pop }
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
        val pop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = pill(cBarBg, dp(18)); setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val modeViews = ArrayList<Pair<Int, TextView>>()

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(0))
        }

        // Label do SOC à esquerda
        val socLabel = TextView(this).apply {
            setTextColor(cTxt); textSize = 15f; setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, dp(12), 0); text = "—%"
        }
        row2.addView(socLabel)

        // Slider do SOC
        val sliderW = dp(240); val sliderH = dp(32)
        val sliderTrack = FrameLayout(this).apply {
            background = pill(cCard, dp(16))
            layoutParams = LinearLayout.LayoutParams(sliderW, sliderH)
        }
        val sliderFill = View(this).apply { setBackgroundColor(DockColors.AMBER) }
        sliderTrack.addView(sliderFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        row2.addView(sliderTrack)

        fun updateSliderUI(soc: Int) {
            socLabel.text = "$soc%"
            val r = (soc - c.minSoc).toFloat() / (c.maxSoc - c.minSoc)
            val lp = sliderFill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt()
            sliderFill.layoutParams = lp
        }

        sliderTrack.setOnTouchListener { view, e ->
            val r = (e.x / view.width).coerceIn(0f, 1f)
            val soc = c.minSoc + (r * (c.maxSoc - c.minSoc)).toInt()
            updateSliderUI(soc)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity()
                io.execute { c.select(0, soc); main.post { refreshAll() } }
            }
            true
        }

        c.order.forEach { m ->
            val tv = TextView(this).apply {
                text = (c.labels[m] ?: "—").uppercase(); setTextColor(cTxt); textSize = 15f; setTypeface(null, Typeface.BOLD)
                setPadding(dp(16), dp(10), dp(16), dp(10)); isClickable = true
                setOnClickListener {
                    onUserActivity()
                    io.execute {
                        c.select(m)
                        main.post {
                            if (m != 0) closeMode() else {
                                row2.visibility = View.VISIBLE
                                updateSliderUI(c.curHevSocInt())
                            }
                            refreshAll()
                            // Update colors
                            val curM = c.cur()
                            modeViews.forEach { (mo, t) -> t.setTextColor(if (mo == curM) c.colors[mo] ?: cAccent else cTxt) }
                        }
                    }
                }
            }
            modeViews.add(m to tv); row1.addView(tv)
        }
        pop.addView(row1)
        pop.addView(row2)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = barHeightPx + dp(8)
            val loc = IntArray(2); anchor.getLocationOnScreen(loc)
            @Suppress("DEPRECATION")
            x = (loc[0] + anchor.width / 2) - (wm.defaultDisplay.width / 2)
        }
        runCatching { wm.addView(pop, lp); modeWin = pop }

        io.execute {
            val curM = c.cur(); val curS = c.curHevSocInt()
            main.post {
                row2.visibility = if (curM == 0) View.VISIBLE else View.GONE
                updateSliderUI(curS)
                modeViews.forEach { (m, tv) -> tv.setTextColor(if (m == curM) c.colors[m] ?: cAccent else cTxt) }
            }
        }
    }

    private fun closeAirflow() { airflowWin?.let { v -> runCatching { wm.removeView(v) } }; airflowWin = null }

    // ---- popup de temperatura (slider horizontal) ----

    private fun openTemp(c: Temp, anchor: View) {
        if (tempWin != null) { closeTemp(); return }
        closeVolume(); closeAirflow(); closeLevel(); closeMode()
        val pop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(cBarBg, dp(18)); setPadding(dp(16), dp(12), dp(16), dp(12))
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

        fun updateUI(v: Double) {
            val r = ((v - c.min) / (c.hi() - c.min)).toFloat()
            val color = blend(DockColors.CYAN, DockColors.AMBER, r)
            valTv.text = c.fmt(v) + "°"
            valTv.setTextColor(color)
            val lp = sliderFill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt()
            sliderFill.layoutParams = lp
            sliderFill.setBackgroundColor(color)
        }

        sliderTrack.setOnTouchListener { view, e ->
            val r = (e.x / view.width).coerceIn(0f, 1f)
            val raw = c.min + r * (c.hi() - c.min)
            val v = (Math.round(raw / c.step) * c.step).coerceIn(c.min, c.hi())
            updateUI(v)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                onUserActivity()
                io.execute { c.select(v); main.post { refreshAll() } }
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = barHeightPx + dp(8)
            val loc = IntArray(2); anchor.getLocationOnScreen(loc)
            @Suppress("DEPRECATION")
            x = (loc[0] + anchor.width / 2) - (wm.defaultDisplay.width / 2)
        }
        runCatching { wm.addView(pop, lp); tempWin = pop }

        io.execute {
            val cur = c.read() ?: c.min
            main.post { updateUI(cur) }
        }
        onUserActivity()
    }


    // ---- popup de nível (ventilação): escolher min..max direto ----

    private fun openLevel(c: Level, anchor: View) {
        if (levelWin != null) { closeLevel(); return }
        closeVolume(); closeAirflow(); closeMode(); closeTemp()
        val pop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(cBarBg, dp(18)); setPadding(dp(16), dp(12), dp(16), dp(12))
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
            valTv.text = v.toString()
            val lo = c.min; val hi = c.hi().coerceAtLeast(lo + 1)
            val r = (v - lo).toFloat() / (hi - lo)
            val lp = sliderFill.layoutParams; lp.width = (sliderW * r.coerceIn(0f, 1f)).toInt()
            sliderFill.layoutParams = lp
        }

        sliderTrack.setOnTouchListener { view, e ->
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

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = barHeightPx + dp(8)
            val loc = IntArray(2); anchor.getLocationOnScreen(loc)
            @Suppress("DEPRECATION")
            x = (loc[0] + anchor.width / 2) - (wm.defaultDisplay.width / 2)
        }
        runCatching { wm.addView(pop, lp); levelWin = pop }

        io.execute {
            val cur = c.value()
            main.post { updateUI(cur) }
        }
        onUserActivity()
    }

    private fun closeLevel() { levelWin?.let { v -> runCatching { wm.removeView(v) } }; levelWin = null }

    // ---- ações / refresh ----

    private fun act(c: Control, action: () -> Unit) {
        onUserActivity()
        io.execute {
            runCatching { action() }
            val st = c.render()
            main.post { updaters[c.id]?.invoke(st) }
        }
    }

    private fun refreshAll() {
        if (hidden) return
        io.execute {
            val snap = DockControls.ALL.map { it.id to it.render() }
            main.post { snap.forEach { (id, st) -> updaters[id]?.invoke(st) } }
        }
    }

    // ---- atalho de projeção (CarPlay/Android Auto) ----

    private fun projTile(): View {
        val v = col(); v.isClickable = true
        val ic = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)) }
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
    private fun showBar() {
        main.removeCallbacks(hideRunnable)
        if (hidden) {
            hidden = false; bar.visibility = View.VISIBLE; handle.visibility = View.GONE
            params.height = barHeightPx; runCatching { wm.updateViewLayout(root, params) }
            broadcastBarState()
            refreshAll()
        }
        armTimer()
    }
    private fun hideBar(manual: Boolean = false) {
        // gesto (manual) esconde em qualquer modo; o timer só esconde no modo auto
        if (!manual && SettingsStore.mode(this) != SettingsStore.MODE_AUTO) return
        closeVolume()
        closeAirflow()
        closeLevel()
        hidden = true; bar.visibility = View.GONE; handle.visibility = View.VISIBLE
        params.height = handleHeightPx; runCatching { wm.updateViewLayout(root, params) }
        broadcastBarState()
    }

    // Avisa apps que reservam o rodapé (haval-radio) qual a altura ocupada agora.
    private fun broadcastBarState() {
        runCatching {
            sendBroadcast(
                Intent(ACTION_BAR_STATE)
                    .putExtra(EXTRA_VISIBLE, !hidden)
                    .putExtra(EXTRA_HEIGHT_DP, if (hidden) HANDLE_DP else BAR_DP)
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

    private fun pill(fill: Int, radius: Int, topOnly: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            if (topOnly) cornerRadii = floatArrayOf(
                radius.toFloat(), radius.toFloat(), radius.toFloat(), radius.toFloat(), 0f, 0f, 0f, 0f)
            else cornerRadius = radius.toFloat()
        }

    private fun buildNotification(): Notification {
        val channelId = "haval_dock_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null)
                nm.createNotificationChannel(NotificationChannel(channelId, "Haval Dock", NotificationManager.IMPORTANCE_MIN))
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

        /** Altura da barra (visível) e da alça (oculta), em dp. */
        const val BAR_DP = 84
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
