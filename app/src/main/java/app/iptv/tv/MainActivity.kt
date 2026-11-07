package app.iptv.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class Row(val title: String, val sub: String = "", val tag: Any? = null, val dim: Boolean = false, val icon: String = "", val short: String = "")

class RowAdapter(private val ctx: Context) : BaseAdapter() {
    private var rows: List<Row> = emptyList()
    var icons = false
    var compact = false
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }
    var active = ""
    fun set(r: List<Row>) { rows = r; notifyDataSetChanged() }
    fun row(i: Int): Row = rows[i]
    override fun getCount() = rows.size
    override fun getItem(i: Int): Any = rows[i]
    override fun getItemId(i: Int) = i.toLong()
    override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
        val d = ctx.resources.displayMetrics.density
        fun px(x: Int) = (x * d).toInt()
        val box = (cv as? LinearLayout) ?: LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(8), px(12), px(8))
            addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(px(60), px(40)))
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.leftMargin = px(12)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply { textSize = 20f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(TextView(ctx).apply { textSize = 15f; maxLines = 1; setTextColor(0xFF8A9AA5.toInt()) })
            }, lp)
        }
        val r = rows[i]
        val img = box.getChildAt(0) as ImageView
        img.visibility = if (icons) View.VISIBLE else View.GONE
        Logos.load(img, r.icon)
        val tx = box.getChildAt(1) as LinearLayout
        val comp = compact
        box.gravity = Gravity.CENTER_VERTICAL
        (tx.getChildAt(0) as TextView).apply {
            text = if (comp) r.short.ifEmpty { r.title.take(1).uppercase() } else r.title
            textSize = if (comp) 24f else 20f
            gravity = if (comp) Gravity.CENTER_HORIZONTAL else Gravity.START
            setTextColor(if (r.title == active && active.isNotEmpty()) 0xFF6FE3C1.toInt() else if (r.dim) 0xFF8A9AA5.toInt() else Color.WHITE)
        }
        (tx.getChildAt(1) as TextView).apply { text = r.sub; visibility = if (r.sub.isEmpty() || comp) View.GONE else View.VISIBLE }
        return box
    }
}

class MainActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var pv: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var hudBox: LinearLayout
    private lateinit var hudT: TextView
    private lateinit var hudP: TextView
    private lateinit var hudN: TextView
    private var lastHoldSeek = 0L
    private lateinit var toastTv: TextView
    private lateinit var chPanel: LinearLayout
    private lateinit var arPanel: LinearLayout
    private lateinit var setup: ScrollView
    private lateinit var grpList: ListView
    private lateinit var chList: ListView
    private lateinit var dayList: ListView
    private lateinit var progList: ListView
    private lateinit var arTitle: TextView
    private lateinit var etPl: EditText
    private lateinit var etEpg: EditText
    private lateinit var etTz: EditText
    private lateinit var btType: Button
    private lateinit var btSort: Button
    private lateinit var httpFactory: DefaultHttpDataSource.Factory
    private lateinit var etUa: EditText
    private lateinit var srvPanel: LinearLayout
    private lateinit var srvList: ListView
    private lateinit var tvStat: TextView
    private lateinit var hudLogo: ImageView
    private lateinit var seekBox: LinearLayout
    private lateinit var seekBar: ProgressBar
    private lateinit var seekT: TextView
    private lateinit var seekL: TextView
    private lateinit var seekR: TextView
    private lateinit var gmPanel: LinearLayout
    private lateinit var gmList: ListView
    private lateinit var chTitle: TextView
    private var epgStatus = "не загружена"
        set(v) { field = v; if (::chTitle.isInitialized) chTitle.text = "Каналы   ·   программа: $v" }
    private var plEpgUrl = ""   // адрес программы из заголовка плейлиста (url-tvg)

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private val types = listOf("flussonic", "shift", "append")
    private val sorts = listOf("list", "az", "fav", "custom")
    private val sortNames = mapOf("list" to "как в плейлисте", "az" to "по алфавиту", "fav" to "избранные сверху", "custom" to "свой порядок")
    private var sortMode = "list"
    private var customOrder: MutableList<String> = ArrayList()   // свой порядок: названия каналов
    private var moving = false                                    // режим перемещения канала в списке
    private var chans: List<Channel> = emptyList()
    private var groups: List<String> = emptyList()
    private var epg: Map<String, List<Prog>> = emptyMap()
    private var shown: List<Channel> = emptyList()
    private val G_ALL = "Все"
    private val G_FAV = "★ Избранное"
    private var grp = G_ALL
    private var favs: MutableSet<String> = HashSet()          // избранные каналы (по названию), общие для всех плейлистов
    private var hiddenGroups: MutableSet<String> = HashSet()  // скрытые группы (по названию)
    private var zap: List<Channel> = emptyList()              // список, по которому листают ↑/↓ (группа, выбранная в панели)
    private var cur: Channel? = null
    private var arch: Prog? = null
    private var dayOff = 0
    private var lastUrl = ""
    private var arcCands: List<Pair<Int, String>> = emptyList()   // варианты ссылки на архив
    private var arcIdx = 0
    private var guide: Channel? = null   // канал, чья телепрограмма открыта в панели архива
    private lateinit var btStart: Button
    private var triedHls = false
    private var ctype = "flussonic"
    // автовосстановление потока
    private var retries = 0
    private var retryHls = false
    private var readyMode: Boolean? = null   // null = поток ещё не заработал; true/false = с принудительным HLS или без
    private val maxRetries = 5
    private val retryRun = Runnable { if (lastUrl.isNotEmpty()) playUrl(lastUrl, retryHls) }
    private val stallRun = Runnable {
        if (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && !scheduleRetry()) toast("Поток не отвечает")
    }
    private val hideSeek = Runnable { seekBox.visibility = View.GONE; ui.removeCallbacks(seekTick) }
    private val seekTick: Runnable = object : Runnable {
        override fun run() { if (seekBox.visibility == View.VISIBLE && arch != null) { updateSeek(); ui.postDelayed(this, 500) } }
    }
    private val hideHud = Runnable { hudBox.visibility = View.GONE }
    private val hideToast = Runnable { toastTv.visibility = View.GONE }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun now() = System.currentTimeMillis() / 1000
    private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t * 1000))
    private fun ad(l: ListView) = l.adapter as RowAdapter
    private fun nowProg(c: Channel): Prog? { val n = now(); return epg[c.eid]?.firstOrNull { it.s <= n && n < it.e } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        Iptv.customUa = prefs.getString("ua", "") ?: ""
        Iptv.goodUa = prefs.getString("gua", "")?.ifEmpty { null }
        httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Iptv.customUa.ifEmpty { Iptv.goodUa ?: Iptv.DEFAULT_UA })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        // Плеер получает тот же User-Agent, с которым сервер ответил на запрос плейлиста
        Iptv.onUa = { ua ->
            prefs.edit().putString("gua", ua).apply()
            ui.post { if (Iptv.customUa.isEmpty()) httpFactory.setUserAgent(ua) }
        }
        // DefaultDataSource поверх HTTP-фабрики: кроме http(s) работают udp://, rtp://, file:, content:
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                ui.removeCallbacks(stallRun)
                when (state) {
                    Player.STATE_BUFFERING -> ui.postDelayed(stallRun, 20000)
                    Player.STATE_READY -> {
                        readyMode = triedHls; retries = 0
                        // запоминаем рабочий формат архива для этого сервера
                        val cc = cur
                        if (arch != null && cc != null && arcIdx in arcCands.indices) prefs.edit().putInt("arcv_" + Iptv.hostOf(cc.url), arcCands[arcIdx].first).apply()
                    }
                    // сервер закрыл соединение с прямым эфиром — переподключаемся
                    Player.STATE_ENDED -> if (arch == null && lastUrl.isNotEmpty()) scheduleRetry()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                // фолбэк на HLS только пока поток ни разу не заработал
                if (readyMode == null && !triedHls && lastUrl.startsWith("http") && !lastUrl.contains(".m3u8")) { playUrl(lastUrl, true); return }
                // архив повторяем только при проблемах сети: ошибка адреса/типа сама не пройдёт
                val netErr = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
                // архив не открылся — пробуем следующий формат ссылки
                if (arch != null && !netErr && arcIdx + 1 < arcCands.size) {
                    arcIdx++
                    ui.removeCallbacks(retryRun); retries = 0; readyMode = null
                    toast("Архив: пробую другой формат ($arcIdx из ${arcCands.size - 1})…")
                    playUrl(arcCands[arcIdx].second)
                    return
                }
                if ((arch == null || netErr) && scheduleRetry()) return
                toast(if (arch != null) "Архив не воспроизводится (тип: ${archType()}) — смените тип в настройках. " + error.errorCodeName else "Ошибка воспроизведения: " + error.errorCodeName)
            }
        })
        pv = PlayerView(this)
        pv.useController = false
        pv.isFocusable = false
        pv.player = player
        pv.setOnClickListener { if (!panelOpen()) openChans() }
        root.addView(pv, FrameLayout.LayoutParams(-1, -1))

        favs = HashSet(prefs.getStringSet("fav", emptySet()) ?: emptySet())
        hiddenGroups = HashSet(prefs.getStringSet("hg", emptySet()) ?: emptySet())
        buildHud(); buildSeek(); buildPanels(); buildSetup(); buildServers(); buildGroupMgr(); buildToast()
        // тип архива по умолчанию — flussonic (один раз переводим и ранее сохранённое значение по умолчанию)
        if (prefs.getInt("ctv", 0) < 1) prefs.edit().putString("ct", "flussonic").putInt("ctv", 1).apply()
        ctype = prefs.getString("ct", "flussonic") ?: "flussonic"
        sortMode = prefs.getString("sort", "list") ?: "list"
        customOrder = (prefs.getString("order", "") ?: "").split("\n").filter { it.isNotEmpty() }.toMutableList()
        // по умолчанию — сетевой плейлист, а не локальный файл
        if (prefs.getString("pl", "") == "local" && getString(R.string.default_playlist).isNotEmpty())
            prefs.edit().putString("pl", getString(R.string.default_playlist)).putString("epg", getString(R.string.default_epg)).apply()
        if (prefs.getString("pl", "").isNullOrEmpty()) {
            val d = getString(R.string.default_playlist)
            if (d.isNotEmpty()) {
                prefs.edit().putString("pl", d).putString("epg", getString(R.string.default_epg)).apply()
                loadAll()
            } else if (allServers().isNotEmpty()) openServers() else openSetup()
        } else loadAll()
    }

    /* ---------- интерфейс ---------- */
    private fun roundBg(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(16).toFloat() }

    private fun buildHud() {
        hudBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(0xEE101519.toInt())
            setPadding(dp(28), dp(16), dp(28), dp(16))
            visibility = View.GONE
        }
        // значок канала слева от названия; полупрозрачная подложка, чтобы читались и тёмные, и светлые логотипы
        hudLogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0x44FFFFFF)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            visibility = View.GONE
        }
        val lpLogo = LinearLayout.LayoutParams(dp(104), dp(64))
        lpLogo.rightMargin = dp(18)
        hudBox.addView(hudLogo, lpLogo)
        hudT = TextView(this).apply { textSize = 26f; setTextColor(Color.WHITE) }
        hudP = TextView(this).apply { textSize = 18f; setTextColor(0xFF6FE3C1.toInt()) }
        hudN = TextView(this).apply { textSize = 16f; setTextColor(0xFF8A9AA5.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        hudP.maxLines = 1; hudP.ellipsize = TextUtils.TruncateAt.END
        val hudCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(hudT); addView(hudP); addView(hudN) }
        hudBox.addView(hudCol, LinearLayout.LayoutParams(0, -2, 1f))
        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        lp.setMargins(dp(20), 0, dp(20), dp(28))
        root.addView(hudBox, lp)
    }

    /** Панель перемотки архива: название передачи, полоса прогресса, время. */
    private fun buildSeek() {
        seekBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(0xEE101519.toInt())
            setPadding(dp(28), dp(16), dp(28), dp(16))
            visibility = View.GONE
        }
        seekT = TextView(this).apply { textSize = 18f; setTextColor(0xFF6FE3C1.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        seekBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(0xFF6FE3C1.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0xFF3A4A54.toInt())
        }
        seekL = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        seekR = TextView(this).apply { textSize = 16f; setTextColor(0xFF8A9AA5.toInt()); gravity = Gravity.END }
        val times = LinearLayout(this).apply {
            addView(seekL, LinearLayout.LayoutParams(0, -2, 1f))
            addView(seekR, LinearLayout.LayoutParams(0, -2, 1f))
        }
        seekBox.addView(seekT)
        seekBox.addView(seekBar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8); bottomMargin = dp(6) })
        seekBox.addView(times)
        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        lp.setMargins(dp(20), 0, dp(20), dp(28))
        root.addView(seekBox, lp)
    }

    private fun fmtT(ms: Long): String {
        val t = ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
    }

    private fun updateSeek() {
        val a = arch ?: return
        val total = (a.e - a.s) * 1000
        if (total <= 0) return
        val pos = player.currentPosition.coerceIn(0L, total)
        seekT.text = "⏪ " + a.title + "   " + hm(a.s) + "–" + hm(a.e)
        seekBar.progress = (pos * 1000 / total).toInt()
        seekL.text = fmtT(pos) + "   (" + hm(a.s + pos / 1000) + ")"
        seekR.text = fmtT(total)
    }

    private fun showSeek() {
        if (arch == null) return
        hudBox.visibility = View.GONE
        updateSeek()
        seekBox.visibility = View.VISIBLE
        ui.removeCallbacks(hideSeek); ui.postDelayed(hideSeek, 5000)
        ui.removeCallbacks(seekTick); ui.postDelayed(seekTick, 500)
    }

    /** Перемотка в архиве на [d] мс с показом панели перемотки. */
    private fun seekArch(d: Long) {
        val a = arch ?: return
        val total = (a.e - a.s) * 1000
        player.seekTo((player.currentPosition + d).coerceIn(0L, maxOf(0L, total - 1000)))
        showSeek()
    }

    private fun buildToast() {
        toastTv = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE)
            background = roundBg(0xEE26323B.toInt())
            setPadding(dp(20), dp(10), dp(20), dp(10)); visibility = View.GONE
        }
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        lp.topMargin = dp(24)
        root.addView(toastTv, lp)
    }

    private fun lv(): ListView = ListView(this).apply {
        divider = null
        selector = ColorDrawable(0x886FE3C1.toInt())
        isFocusable = true
        adapter = RowAdapter(this@MainActivity)
    }

    private fun panel(title: TextView, a: ListView, b: ListView, frac: Float, g: Int): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2101519.toInt())
            setPadding(dp(20), dp(20), dp(20), dp(20))
            visibility = View.GONE
        }
        title.textSize = 24f; title.setTextColor(Color.WHITE); title.setPadding(0, 0, 0, dp(10))
        p.addView(title)
        val row = LinearLayout(this)
        row.addView(a, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(b, LinearLayout.LayoutParams(0, -1, 2f))
        p.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(p, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * frac).toInt(), -1, g))
        return p
    }

    private fun buildPanels() {
        grpList = lv(); chList = lv(); dayList = lv(); progList = lv()
        (chList.adapter as RowAdapter).icons = true
        chTitle = TextView(this).apply { textSize = 20f; maxLines = 2; text = "Каналы   ·   программа: $epgStatus" }
        chPanel = panel(chTitle, grpList, chList, 0.43f, Gravity.START)
        arTitle = TextView(this)
        arPanel = panel(arTitle, dayList, progList, 0.57f, Gravity.END)

        // левая колонка (настройки и группы): узкая, со значками; раскрывается, когда в неё переходит фокус
        setGroupsExpanded(false)
        grpList.onFocusChangeListener = View.OnFocusChangeListener { _, has -> setGroupsExpanded(has) }
        grpList.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val r = ad(grpList).row(pos)
            when (r.tag) {
                "set" -> openSetup()
                "srv" -> openServers()
                "mgr" -> openGroupMgr()
                "arc" -> if (cur != null) openArch() else toast("Сначала выберите канал")
                else -> {
                    grp = r.title
                    ad(grpList).active = grp
                    ad(grpList).notifyDataSetChanged()
                    renderChans()
                    if (shown.isEmpty()) toast(if (grp == G_FAV) "Избранное пусто. Удерживайте OK на канале" else "В группе нет каналов")
                    else chList.requestFocus()
                }
            }
        }
        // удержание OK на группе — скрыть её
        grpList.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            val r = ad(grpList).row(pos)
            if (r.tag == "g") {
                toggleGroup(r.title)
                toast("Группа скрыта: ${r.title}. Вернуть: ☰ Группы…")
                ad(grpList).set(groupRows())
                renderChans()
                grpList.setSelection(minOf(pos, ad(grpList).getCount() - 1))
            }
            true
        }
        chList.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            if (pos < shown.size) { zap = shown; playChan(shown[pos]); closePanels() }
        }
        // удержание OK на канале — в избранное / из избранного
        chList.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            if (pos in shown.indices) {
                val c = shown[pos]
                android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(c.name)
                    .setItems(arrayOf(if (isFav(c)) "★ Убрать из избранного" else "★ В избранное", "⇅ Переместить (↑/↓)", "↺ Архив канала")) { _, w ->
                        if (w == 0) favAt(pos) else if (w == 1) startMove(pos) else { zap = shown; playChan(c); closePanels(); openArch() }
                    }.show()
            }
            true
        }
        dayList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { dayOff = pos; renderProgs() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        dayList.onItemClickListener = AdapterView.OnItemClickListener { _, _, _, _ -> progList.requestFocus() }
        progList.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val p = ad(progList).row(pos).tag as? Prog
            val c = guide ?: cur
            if (p != null && c != null) {
                val n = now()
                when {
                    p.s > n -> toast("Эта передача ещё не вышла")
                    p.s <= n && n < p.e -> { playChan(c); closePanels() }
                    else -> { if (cur !== c) cur = c; playArch(p); closePanels() }
                }
            }
        }
    }

    /** Фон с заметной рамкой и цветом в фокусе (для навигации пультом). */
    private fun focusBg(normal: Int, stroke: Int, fill: Int): StateListDrawable {
        fun shape(f: Int, st: Int, w: Int) = GradientDrawable().apply { setColor(f); setStroke(dp(w), st); cornerRadius = dp(8).toFloat() }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(fill, 0xFF6FE3C1.toInt(), 3))
            addState(intArrayOf(), shape(normal, stroke, 1))
        }
    }

    /** Кнопка для ТВ: тёмная, а в фокусе — яркая бирюзовая с тёмным текстом и белой рамкой. */
    private fun tvButton(t: String): Button = Button(this).apply {
        text = t; textSize = 18f; isAllCaps = false
        stateListAnimator = null
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        minHeight = dp(52); minimumHeight = dp(52)
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(0xFF06231B.toInt(), 0xFF06231B.toInt(), Color.WHITE)))
        fun shape(f: Int, st: Int, w: Int) = GradientDrawable().apply { setColor(f); setStroke(dp(w), st); cornerRadius = dp(8).toFloat() }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0xFF6FE3C1.toInt(), Color.WHITE, 4))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF6FE3C1.toInt(), Color.WHITE, 4))
            addState(intArrayOf(), shape(0xFF26323B.toInt(), 0xFF3A4A54.toInt(), 1))
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private fun setGroupsExpanded(on: Boolean) {
        val lp = grpList.layoutParams as LinearLayout.LayoutParams
        if (on) { lp.width = 0; lp.weight = 1f } else { lp.width = dp(72); lp.weight = 0f }
        grpList.layoutParams = lp
        ad(grpList).compact = !on
    }

    private fun buildSetup() {
        fun field(h: String) = EditText(this).apply {
            setTextColor(Color.WHITE); setHintTextColor(0xFF667780.toInt()); hint = h
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = focusBg(0xFF1B252C.toInt(), 0xFF3A4A54.toInt(), 0xFF24343D.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setTextColor(0xFF8A9AA5.toInt()); setPadding(0, dp(14), 0, 0) }
        etPl = field("http://…/playlist.m3u")
        etEpg = field("http://…/epg.xml.gz (необязательно)")
        etTz = field("0").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED }
        btType = tvButton("").apply {
            setOnClickListener { ctype = types[(types.indexOf(ctype) + 1) % types.size]; text = "Тип архива: $ctype" }
        }
        btStart = tvButton("").apply {
            setOnClickListener {
                prefs.edit().putString("start", if (prefs.getString("start", "fav") == "fav") "all" else "fav").apply()
                text = startLabel()
            }
        }
        btSort = tvButton("").apply {
            setOnClickListener { setSort(sorts[(sorts.indexOf(sortMode) + 1) % sorts.size]) }
        }
        val save = tvButton("Сохранить и загрузить").apply { setOnClickListener { saveSetup() } }
        val cancel = tvButton("Отмена").apply { setOnClickListener { if (chans.isNotEmpty()) closePanels() } }
        etUa = field("пусто = автоподбор (VLC, okhttp, Mozilla…)")
        tvStat = TextView(this).apply { textSize = 16f; setTextColor(0xFF6FE3C1.toInt()); setPadding(0, dp(14), 0, dp(6)) }
        val btEpg = tvButton("Обновить программу передач").apply {
            setOnClickListener {
                if (chans.isEmpty()) { toast("Сначала загрузите плейлист") } else {
                    toast("Загрузка программы передач…")
                    val tzv = etTz.text.toString().toIntOrNull() ?: 0
                    prefs.edit().putString("epg", etEpg.text.toString().trim()).putInt("tz", tzv).apply()
                    Thread { loadEpgNow(chans, plEpgUrl, true, tzv) }.start()
                }
            }
        }
        val btSrv = tvButton("Выбрать сервер из списка").apply { setOnClickListener { openServers() } }
        val btFile = tvButton("Загрузить список / плейлист из файла…").apply { setOnClickListener { pickFile() } }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(60), dp(30), dp(60), dp(30))
            addView(TextView(this@MainActivity).apply { text = "Настройка IPTV"; textSize = 30f; setTextColor(Color.WHITE) })
            addView(label("Ссылка на плейлист M3U")); addView(etPl)
            addView(label("Ссылка на программу передач XMLTV")); addView(etEpg)
            addView(label("Часовой пояс программы, часы от UTC (если не указан в EPG)")); addView(etTz)
            addView(label("User-Agent (если сервер отвечает 403)")); addView(etUa)
            addView(label("Тип архива, если в плейлисте не указан")); addView(btType)
            addView(label("Порядок каналов")); addView(btSort)
            addView(label("Список при запуске")); addView(btStart)
            addView(tvStat); addView(btEpg); addView(btSrv); addView(btFile); addView(save); addView(cancel)
        }
        setup = ScrollView(this).apply { setBackgroundColor(0xFF0F1519.toInt()); visibility = View.GONE; addView(col) }
        root.addView(setup, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showPanel(p: View?) {
        chPanel.visibility = View.GONE; arPanel.visibility = View.GONE; setup.visibility = View.GONE; srvPanel.visibility = View.GONE
        gmPanel.visibility = View.GONE
        hudBox.visibility = View.GONE
        seekBox.visibility = View.GONE
        p?.visibility = View.VISIBLE
    }
    private fun closePanels() = showPanel(null)
    private fun panelOpen() = chPanel.visibility == View.VISIBLE || arPanel.visibility == View.VISIBLE || setup.visibility == View.VISIBLE || srvPanel.visibility == View.VISIBLE || gmPanel.visibility == View.VISIBLE

    private fun toast(t: String) {
        toastTv.text = t; toastTv.visibility = View.VISIBLE
        ui.removeCallbacks(hideToast); ui.postDelayed(hideToast, 3000)
    }

    private fun hud() {
        val c = cur ?: return
        val a = arch
        val p = a ?: nowProg(c)
        hudT.text = (if (isFav(c)) "★ " else "") + c.name + if (a != null) "  ·  архив" else ""
        Logos.load(hudLogo, c.logo, true)
        hudP.text = if (p != null) "${hm(p.s)}–${hm(p.e)}  ${p.title}" else ""
        val nx = if (p != null) epg[c.eid]?.firstOrNull { it.s >= p.e } else null
        hudN.text = if (nx != null) "Далее: ${hm(nx.s)}–${hm(nx.e)}  ${nx.title}" else ""
        hudN.visibility = if (nx != null) View.VISIBLE else View.GONE
        hudBox.visibility = View.VISIBLE
        ui.removeCallbacks(hideHud); ui.postDelayed(hideHud, 4500)
    }

    /* ---------- панели ---------- */
    private fun openChans() {
        ad(grpList).active = grp
        ad(grpList).set(groupRows())
        renderChans()
        showPanel(chPanel)
        chList.setSelection(shown.indexOfFirst { it === cur }.coerceAtLeast(0))
        chList.requestFocus()
    }

    private fun groupRows(): List<Row> {
        val hid = groups.count { it in hiddenGroups }
        val l = ArrayList<Row>()
        l.add(Row("⚙ Настройки", tag = "set", short = "⚙"))
        l.add(Row("↺ Архив", tag = "arc", short = "↺"))
        l.add(Row("☰ Группы…", if (hid > 0) "скрыто: $hid" else "", "mgr", short = "☰"))
        l.add(Row(G_FAV, tag = "fav", short = "★"))
        l.add(Row(G_ALL, tag = "all", short = "▤"))
        for (g in groups) if (g !in hiddenGroups) l.add(Row(g, tag = "g"))
        return l
    }

    /** Каналы в выбранном порядке (сортировка устойчивая: равные остаются в порядке плейлиста). */
    private fun ordered(): List<Channel> = when (sortMode) {
        "az" -> chans.sortedBy { it.name.lowercase() }
        "fav" -> chans.sortedBy { if (isFav(it)) 0 else 1 }
        "custom" -> { val pos = HashMap<String, Int>(); customOrder.forEachIndexed { i, n -> pos.putIfAbsent(n, i) }; chans.sortedBy { pos[it.name] ?: Int.MAX_VALUE } }
        else -> chans
    }

    private fun setSort(m: String) {
        sortMode = m
        if (m == "custom" && customOrder.isEmpty()) { customOrder = chans.map { it.name }.toMutableList(); saveOrder() }
        prefs.edit().putString("sort", m).apply()
        zap = emptyList()
        btSort.text = "Порядок каналов: ${sortNames[m]}"
    }

    private fun startLabel() = "При запуске открывать: " + (if (prefs.getString("start", "fav") == "fav") "★ Избранное" else "Все каналы")

    private fun saveOrder() = prefs.edit().putString("order", customOrder.joinToString("\n")).apply()

    /** Зелёная кнопка / «#»: включает режим перемещения выбранного канала (↑/↓ — двигать, OK — готово). */
    private fun startMove(pos: Int = chList.selectedItemPosition) {
        if (pos < 0 || pos >= shown.size) return
        if (sortMode != "custom") {
            customOrder = ordered().map { it.name }.toMutableList(); saveOrder()
            setSort("custom")
        } else {
            // добавляем в свой порядок новые каналы, которых там ещё нет
            val known = customOrder.toHashSet(); chans.forEach { if (known.add(it.name)) customOrder.add(it.name) }
        }
        moving = true
        renderChans(); chList.setSelection(pos)
        toast("Перемещение: ↑/↓ — двигать, OK — готово")
    }

    private fun moveBy(d: Int) {
        val pos = chList.selectedItemPosition; val t = pos + d
        if (pos < 0 || t < 0 || t >= shown.size) return
        val a = customOrder.indexOf(shown[pos].name); val b = customOrder.indexOf(shown[t].name)
        if (a < 0 || b < 0) return
        val x = customOrder[a]; customOrder[a] = customOrder[b]; customOrder[b] = x
        saveOrder(); zap = emptyList()
        renderChans(); chList.setSelection(t)
    }

    private fun stopMove() { moving = false; zap = emptyList(); renderChans(); toast("Порядок сохранён") }

    private fun renderChans() {
        val all = ordered()
        shown = if (grp == G_FAV) all.filter { isFav(it) }
        else if (grp == G_ALL) all.filter { it.group !in hiddenGroups }
        else all.filter { it.group == grp }
        val sel = if (moving) chList.selectedItemPosition else -1
        ad(chList).set(shown.mapIndexed { i, it -> Row((if (i == sel) "⇅ " else if (it === cur) "▶ " else "") + (if (isFav(it)) "★ " else "") + it.name, nowProg(it)?.title ?: "", it, false, it.logo) })
    }

    /* ---------- избранное и скрытые группы ---------- */
    private fun isFav(c: Channel) = c.name in favs

    private fun toggleFav(c: Channel) {
        if (!favs.remove(c.name)) favs.add(c.name)
        prefs.edit().putStringSet("fav", HashSet(favs)).apply()
        toast(if (isFav(c)) "★ В избранное: ${c.name}" else "Убрано из избранного: ${c.name}")
    }

    /** Переключает избранное у канала с номером [pos] в текущем списке и сохраняет позицию в списке. */
    private fun favAt(pos: Int) {
        if (pos < 0 || pos >= shown.size) return
        toggleFav(shown[pos])
        renderChans()
        chList.setSelection(minOf(pos, maxOf(shown.size - 1, 0)))
    }

    private fun toggleGroup(g: String) {
        if (!hiddenGroups.remove(g)) hiddenGroups.add(g)
        prefs.edit().putStringSet("hg", HashSet(hiddenGroups)).apply()
        if (g in hiddenGroups && grp == g) grp = G_ALL
        zap = emptyList()
    }

    private fun buildGroupMgr() {
        gmList = lv()
        gmPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2101519.toInt())
            setPadding(dp(20), dp(20), dp(20), dp(20))
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply { text = "Группы каналов — OK: показать / скрыть"; textSize = 24f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(10)) })
            addView(gmList, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(gmPanel, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.6f).toInt(), -1, Gravity.CENTER_HORIZONTAL))
        gmList.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val g = ad(gmList).row(pos).tag as? String
            if (g != null) {
                toggleGroup(g)
                renderGroupMgr()
                gmList.setSelection(pos)
            }
        }
    }

    private fun renderGroupMgr() {
        ad(gmList).set(groups.map { g ->
            val hid = g in hiddenGroups
            Row((if (hid) "✗  " else "✓  ") + g, "каналов: " + chans.count { it.group == g } + (if (hid) " · скрыта" else ""), g, hid)
        })
    }

    private fun openGroupMgr() {
        if (groups.isEmpty()) { toast("Групп нет"); return }
        renderGroupMgr()
        showPanel(gmPanel)
        gmList.requestFocus()
    }

    /** [keepChannels] — телепрограмма открывается справа от списка каналов, список остаётся на экране. */
    private fun openArch(ch: Channel? = cur, keepChannels: Boolean = false) {
        val c = ch ?: return
        guide = c
        arTitle.text = "Архив · ${c.name}  (тип: ${archType(c)}, дней: ${if (c.days > 0) c.days else 7}" + (if (c.days == 0 && c.ctype.isEmpty() && c.csrc.isEmpty()) ", в плейлисте нет catchup)" else ")")
        val n = minOf(if (c.days > 0) c.days else 7, 14)
        val f = SimpleDateFormat("dd.MM", Locale.getDefault())
        ad(dayList).set((0..n).map { i ->
            Row(when (i) { 0 -> "Сегодня"; 1 -> "Вчера"; else -> f.format(Date(System.currentTimeMillis() - i * 86400000L)) })
        })
        dayOff = 0
        if (keepChannels) { hudBox.visibility = View.GONE; seekBox.visibility = View.GONE; arPanel.visibility = View.VISIBLE }
        else showPanel(arPanel)
        renderProgs()
        progList.requestFocus()
    }

    private fun renderProgs() {
        val c = guide ?: cur ?: return
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -dayOff)
        val s0 = cal.timeInMillis / 1000
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val s1 = cal.timeInMillis / 1000
        var l = (epg[c.eid] ?: emptyList()).filter { it.e > s0 && it.s < s1 }
        if (l.isEmpty()) l = (0 until 24).map { Prog(s0 + it * 3600L, s0 + (it + 1) * 3600L, "Эфир " + hm(s0 + it * 3600L)) }
        val n = now()
        val lim = n - (if (c.days > 0) c.days else 7) * 86400L
        ad(progList).set(l.map { p ->
            val fut = p.s > n; val live = p.s <= n && n < p.e; val ok = p.e > lim
            Row(p.title, hm(p.s) + "–" + hm(p.e) + when { live -> " · в эфире"; fut -> " · ещё не было"; !ok -> " · нет в архиве"; else -> "" }, p, fut || !ok)
        })
        var i = l.indexOfFirst { it.s <= n && n < it.e }
        if (i < 0) i = l.indexOfLast { it.s <= n }
        progList.setSelection(i.coerceAtLeast(0))
    }

    private fun openSetup() {
        etPl.setText((prefs.getString("pl", "") ?: "").ifEmpty { getString(R.string.default_playlist) })
        etEpg.setText((prefs.getString("epg", "") ?: "").ifEmpty { plEpgUrl.ifEmpty { getString(R.string.default_epg) } })
        etTz.setText(prefs.getInt("tz", 0).toString())
        etUa.setText(prefs.getString("ua", ""))
        btType.text = "Тип архива: $ctype"
        btSort.text = "Порядок каналов: ${sortNames[sortMode]}"
        btStart.text = startLabel()
        tvStat.text = "Программа передач: $epgStatus"
        showPanel(setup)
        etPl.requestFocus()
    }

    private fun saveSetup() {
        prefs.edit().putString("pl", etPl.text.toString().trim()).putString("epg", etEpg.text.toString().trim())
            .putInt("tz", etTz.text.toString().toIntOrNull() ?: 0).putString("ct", ctype).putString("ua", etUa.text.toString().trim()).apply()
        Iptv.customUa = etUa.text.toString().trim()
        httpFactory.setUserAgent(Iptv.customUa.ifEmpty { Iptv.goodUa ?: Iptv.DEFAULT_UA })
        closePanels()
        loadAll(true)
    }

    /* ---------- воспроизведение ---------- */
    private fun playUrl(url: String, hls: Boolean = false) {
        lastUrl = url; triedHls = hls
        val mi = MediaItem.Builder().setUri(url)
        if (hls) mi.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(mi.build())
        player.prepare()
        player.playWhenReady = true
    }

    /** Новое воспроизведение: сбрасывает отложенные повторы и состояние восстановления. */
    private fun startUrl(url: String) {
        ui.removeCallbacks(retryRun); ui.removeCallbacks(stallRun)
        retries = 0; readyMode = null
        playUrl(url)
    }

    /** Планирует переподключение с нарастающей паузой (1, 2, 4, 8, 8 с). false — попытки исчерпаны. */
    private fun scheduleRetry(): Boolean {
        if (lastUrl.isEmpty() || retries >= maxRetries) return false
        retries++
        retryHls = readyMode == true
        toast("Связь потеряна, повтор $retries из $maxRetries…")
        ui.removeCallbacks(retryRun)
        ui.postDelayed(retryRun, minOf(1000L shl (retries - 1), 8000L))
        return true
    }

    private fun playChan(c: Channel) {
        cur = c; arch = null; arcCands = emptyList()
        hideSeek.run()
        prefs.edit().putInt("last", c.idx).apply()
        startUrl(c.url)
        hud()
    }

    private fun playArch(p: Prog) {
        val c = cur ?: return
        arch = p
        val pref = prefs.getInt("arcv_" + Iptv.hostOf(c.url), -1)
        arcCands = Iptv.arcCandidates(c, p.s, p.e, ctype).sortedByDescending { it.first == pref }
        arcIdx = 0
        startUrl(arcCands[0].second)
        hud()
        toast("Архив: " + hm(p.s) + " " + p.title)
    }

    /** ↑/↓: листаем по списку, выбранному в панели (группа / избранное); пока не выбран — по всем нескрытым каналам. */
    private fun step(d: Int) {
        var l = zap
        val all = ordered()
        if (l.isEmpty()) l = all.filter { it.group !in hiddenGroups }
        if (l.isEmpty()) l = all
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it === cur }
        val n = if (i < 0) (if (d > 0) 0 else l.size - 1) else (i + d + l.size) % l.size
        playChan(l[n])
    }

    /* ---------- серверы и файлы ---------- */
    private fun archType(ch: Channel? = cur): String {
        val c = ch ?: return ctype
        return c.ctype.ifEmpty { if (c.csrc.isNotEmpty()) "default" else ctype }
    }

    private fun allServers(): List<Server> {
        val l = ArrayList<Server>()
        try { l += Iptv.parseServers(assets.open("servers.txt").use { String(it.readBytes(), Charsets.UTF_8) }) } catch (e: Exception) {}
        for (f in listOf(File(filesDir, "servers.txt"), File(getExternalFilesDir(null), "servers.txt"))) {
            try { if (f.exists()) l += Iptv.parseServers(f.readText()) } catch (e: Exception) {}
        }
        return l.distinctBy { it.pl }
    }

    private fun buildServers() {
        srvList = lv()
        srvPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2101519.toInt())
            setPadding(dp(20), dp(20), dp(20), dp(20))
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply { text = "Выберите сервер"; textSize = 24f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(10)) })
            addView(srvList, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(srvPanel, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.7f).toInt(), -1, Gravity.CENTER_HORIZONTAL))
        srvList.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val sv = ad(srvList).row(pos).tag as? Server
            if (sv != null) {
                prefs.edit().putString("pl", sv.pl).putString("epg", sv.epg).apply()
                closePanels()
                loadAll(true)
            }
        }
    }

    private fun openServers() {
        val l = allServers()
        if (l.isEmpty()) { toast("Список серверов пуст. Загрузите файл в настройках"); openSetup(); return }
        ad(srvList).set(l.map { Row(it.name, Iptv.hostOf(it.pl), it) })
        showPanel(srvPanel)
        srvList.requestFocus()
    }

    private fun pickFile() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, 7)
        } catch (e: Exception) {
            toast("Нет выбора файлов. Положите servers.txt в " + getExternalFilesDir(null)?.absolutePath)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val u = data?.data
        if (requestCode == 7 && resultCode == RESULT_OK && u != null) {
            Thread {
                try {
                    val t = contentResolver.openInputStream(u)!!.use { String(it.readBytes(), Charsets.UTF_8) }.removePrefix("\uFEFF")
                    runOnUiThread { importText(t) }
                } catch (e: Exception) {
                    runOnUiThread { toast("Не удалось прочитать файл") }
                }
            }.start()
        }
    }

    private fun importText(t: String) {
        if (t.contains("#EXTINF")) {
            File(filesDir, "local.m3u").writeText(t)
            prefs.edit().putString("pl", "local").apply()
            closePanels()
            loadAll(true)
        } else {
            File(filesDir, "servers.txt").writeText(t)
            if (Iptv.parseServers(t).isEmpty()) toast("В файле нет адресов. Формат: Название | http://… | EPG") else openServers()
        }
    }

    /* ---------- загрузка данных ---------- */
    private fun loadAll(force: Boolean = false) {
        val pl = prefs.getString("pl", "") ?: ""
        val tz = prefs.getInt("tz", 0)
        epg = emptyMap(); epgStatus = "загрузка…"
        toast("Загрузка плейлиста…")
        Thread {
            try {
                File(filesDir, "pl.m3u").delete()   // старый общий кэш: он не различал серверы
                val f = File(filesDir, "pl_" + Integer.toHexString(pl.hashCode()) + ".m3u")
                var cached = false
                val txt = if (pl == "local") File(filesDir, "local.m3u").readText()
                else try { Iptv.text(Iptv.http(pl)).also { f.writeText(it) } }
                catch (e: Exception) { if (f.exists()) { cached = true; f.readText() } else throw e }
                val fromCache = cached
                val list = Iptv.parseM3u(txt)
                if (list.ch.isEmpty()) throw Exception("в плейлисте нет каналов")
                runOnUiThread {
                    chans = list.ch
                    groups = list.ch.map { it.group }.distinct()
                    val startFav = prefs.getString("start", "fav") == "fav" && list.ch.any { isFav(it) }
                    grp = if (startFav) G_FAV else G_ALL
                    zap = if (startFav) ordered().filter { isFav(it) } else emptyList()
                    toast(if (fromCache) "Сервер недоступен — показан сохранённый список: ${list.ch.size}" else "Каналов: ${list.ch.size}")
                    playChan(list.ch.getOrElse(prefs.getInt("last", 0)) { list.ch[0] })
                }
                plEpgUrl = list.epgUrl
                loadEpgNow(list.ch, list.epgUrl, force, tz)
            } catch (e: Exception) {
                runOnUiThread { toast("Ошибка: " + e.message); if (chans.isEmpty()) openSetup() }
            }
        }.start()
    }

    /** Быстрый кэш уже разобранной программы: читается за доли секунды, в отличие от разбора большого XML. */
    private fun saveEpgCache(cf: File, key: String, src: Long, eids: List<String>, map: Map<String, List<Prog>>) {
        val titles = LinkedHashMap<String, Int>()
        for (l in map.values) for (p in l) titles.getOrPut(p.title) { titles.size }
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(cf), 65536)).use { o ->
            o.writeUTF(key); o.writeLong(src)
            o.writeInt(eids.size); eids.forEach { o.writeUTF(it) }
            o.writeInt(titles.size); titles.keys.forEach { o.writeUTF(it) }
            o.writeInt(map.size)
            for ((id, l) in map) {
                o.writeUTF(id); o.writeInt(l.size)
                for (p in l) { o.writeLong(p.s); o.writeLong(p.e); o.writeInt(titles[p.title]!!) }
            }
        }
    }

    private fun loadEpgCache(cf: File, key: String, src: Long): Pair<List<String>, Map<String, List<Prog>>>? {
        java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(cf), 65536)).use { i ->
            if (i.readUTF() != key || i.readLong() != src) return null
            val eids = List(i.readInt()) { i.readUTF() }
            val titles = List(i.readInt()) { i.readUTF() }
            val map = HashMap<String, List<Prog>>()
            repeat(i.readInt()) {
                val id = i.readUTF()
                map[id] = List(i.readInt()) { Prog(i.readLong(), i.readLong(), titles[i.readInt()]) }
            }
            return eids to map
        }
    }

    /**
     * Загрузка программы передач (вызывать из фонового потока).
     * Сначала мгновенно показывается кэш разобранной программы; свежий файл скачивается и разбирается только когда нужно.
     */
    private fun loadEpgNow(chs: List<Channel>, plEpg: String, force: Boolean, tz: Int) {
        val eu = (prefs.getString("epg", "") ?: "").ifEmpty { plEpg }.ifEmpty { getString(R.string.default_epg) }
        if (eu.isEmpty()) {
            runOnUiThread { epgStatus = "адрес не указан"; toast("Адрес программы передач не указан — задайте его в настройках") }
            return
        }
        val ef = File(filesDir, "epg.bin")
        val cf = File(filesDir, "epg.cache")
        val key = eu + "|" + tz + "|" + chs.size + "|" + chs.joinToString("\u0001") { it.id + "\u0002" + it.name }.hashCode()
        var downloaded = false
        fun download() { Iptv.download(eu, ef); prefs.edit().putString("epgsrc", eu).apply(); downloaded = true }
        fun publish(eids: List<String>, map: Map<String, List<Prog>>): Int {
            var m = 0
            for ((i, c) in chs.withIndex()) { c.eid = eids[i]; if (c.eid.isNotEmpty() && map.containsKey(c.eid)) m++ }
            val total = map.values.sumOf { it.size }
            runOnUiThread {
                epg = map
                epgStatus = "передач $total, каналов с программой $m из ${chs.size}"
                toast("Программа: $epgStatus")
                if (chPanel.visibility == View.VISIBLE && !moving) { val sp = chList.selectedItemPosition; renderChans(); chList.setSelection(sp.coerceAtLeast(0)) }
                if (setup.visibility == View.VISIBLE) tvStat.text = "Программа передач: $epgStatus"
                hud()
            }
            return m
        }
        fun parseAndPublish(): Int {
            val e = Iptv.parseEpg(ef, tz, chs)
            val eids = chs.map { Iptv.matchEid(e, it) }
            val m = publish(eids, e.map)
            try { saveEpgCache(cf, key, ef.lastModified(), eids, e.map) } catch (x: Exception) { cf.delete() }
            return m
        }
        try {
            var shownFromCache = false
            if (!force && cf.exists() && ef.exists()) {
                try {
                    val c = loadEpgCache(cf, key, ef.lastModified())
                    if (c != null && c.first.size == chs.size) { publish(c.first, c.second); shownFromCache = true }
                } catch (x: Exception) { cf.delete() }
            }
            val fresh = !force && ef.exists() && System.currentTimeMillis() - ef.lastModified() < 6 * 3600000L && prefs.getString("epgsrc", "") == eu
            if (shownFromCache && fresh) return
            if (!fresh) {
                try { download() } catch (dl: Exception) {
                    // не скачалось — остаёмся на прежних данных, если они от того же источника
                    if (shownFromCache) return
                    if (!(ef.exists() && prefs.getString("epgsrc", "") == eu)) throw dl
                }
            }
            if (shownFromCache && !downloaded) return
            var m = try { parseAndPublish() } catch (ex: Exception) { if (downloaded) throw ex; -1 }
            if (m <= 0 && !downloaded) { try { download(); m = parseAndPublish() } catch (ex: Exception) { if (m < 0) throw ex } }
        } catch (ex: Throwable) {
            runOnUiThread {
                epgStatus = "ошибка: " + (ex.message ?: ex.javaClass.simpleName) + (if (ex.message?.contains("403") == true) " — сервер отклонил запрос; проверьте ссылку в браузере или задайте User-Agent в настройках" else "")
                toast("Программа передач: $epgStatus")
                if (setup.visibility == View.VISIBLE) tvStat.text = "Программа передач: $epgStatus"
            }
        }
    }

    /* ---------- пульт ---------- */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (panelOpen()) {
            // телепрограмма рядом со списком каналов: ← из списка дней или «Назад» возвращает к выбору канала
            if (arPanel.visibility == View.VISIBLE && chPanel.visibility == View.VISIBLE &&
                (k == KeyEvent.KEYCODE_BACK || (k == KeyEvent.KEYCODE_DPAD_LEFT && dayList.hasFocus()))) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 || (k == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP)) {
                    if (arPanel.visibility == View.VISIBLE) { arPanel.visibility = View.GONE; chList.requestFocus() }
                }
                return true
            }
            if (moving) {
                if (chPanel.visibility != View.VISIBLE) moving = false
                else {
                    when (k) {
                        KeyEvent.KEYCODE_DPAD_UP -> { if (e.action == KeyEvent.ACTION_DOWN) moveBy(-1); return true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { if (e.action == KeyEvent.ACTION_DOWN) moveBy(1); return true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_POUND -> { if (e.action == KeyEvent.ACTION_UP) stopMove(); return true }
                        else -> return true
                    }
                }
            }
            if (k == KeyEvent.KEYCODE_BACK) {
                if (setup.visibility == View.VISIBLE && chans.isEmpty()) return super.dispatchKeyEvent(e)
                if (e.action == KeyEvent.ACTION_UP) {
                    if (srvPanel.visibility == View.VISIBLE) openSetup()
                    else if (gmPanel.visibility == View.VISIBLE) openChans()
                    else closePanels()
                }
                return true
            }
            // запасные клавиши избранного в списке каналов: Меню, «*», жёлтая
            if (chPanel.visibility == View.VISIBLE && chList.hasFocus() &&
                (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_STAR || k == KeyEvent.KEYCODE_PROG_YELLOW)) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) favAt(chList.selectedItemPosition)
                return true
            }
            // → на выбранном канале в списке — телепрограмма и архив этого канала
            if (chPanel.visibility == View.VISIBLE && chList.hasFocus() && k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) shown.getOrNull(chList.selectedItemPosition)?.let { openArch(it, true) }
                return true
            }
            if (chPanel.visibility == View.VISIBLE && chList.hasFocus() && (k == KeyEvent.KEYCODE_PROG_GREEN || k == KeyEvent.KEYCODE_POUND)) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) startMove()
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        val live = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_STAR
        )
        // «Назад» в прямом эфире — подтверждение выхода
        if (k == KeyEvent.KEYCODE_BACK && arch == null) {
            if (e.action == KeyEvent.ACTION_UP) confirmExit()
            return true
        }
        // «Назад» во время архива — возврат в прямой эфир
        if (k == KeyEvent.KEYCODE_BACK && arch != null && cur != null) {
            if (e.action == KeyEvent.ACTION_UP) { playChan(cur!!); toast("Прямой эфир") }
            return true
        }
        if (k !in live) return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) onLiveKey(k)
        // удержание ←/→ в архиве: непрерывная перемотка с ускорением (шаг растёт, пока кнопка зажата)
        else if (e.action == KeyEvent.ACTION_DOWN && arch != null && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val t = android.os.SystemClock.uptimeMillis()
            if (t - lastHoldSeek >= 250) {
                lastHoldSeek = t
                val hold = e.eventTime - e.downTime
                val step = when { hold < 1500 -> 30000L; hold < 3500 -> 60000L; else -> 120000L }
                seekArch(if (k == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
            }
        }
        return true
    }

    private var exitDlg: android.app.AlertDialog? = null

    private fun confirmExit() {
        if (exitDlg?.isShowing == true) return
        exitDlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Выйти из программы?")
            .setPositiveButton("Да") { _, _ -> finish() }
            .setNegativeButton("Нет", null)
            .create().also {
                it.show()
                it.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.requestFocus()   // по умолчанию — «Нет»
            }
    }

    private fun onLiveKey(k: Int) {
        when (k) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> openChans()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (arch != null) seekArch(-30000) else openArch()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (arch != null) seekArch(30000) else openArch()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> step(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> step(1)
            KeyEvent.KEYCODE_MENU -> openSetup()
            KeyEvent.KEYCODE_INFO -> hud()
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_STAR -> cur?.let { toggleFav(it); hud() }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player.playWhenReady = !player.playWhenReady; showSeek() }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (arch != null) seekArch(30000) else player.seekTo(player.currentPosition + 30000)
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (arch != null) seekArch(-30000) else player.seekTo(maxOf(0L, player.currentPosition - 30000))
        }
    }

    /* ---------- жизненный цикл ---------- */
    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(has: Boolean) {
        super.onWindowFocusChanged(has)
        if (has) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(retryRun); ui.removeCallbacks(stallRun)
        player.playWhenReady = false
    }
    override fun onStart() {
        super.onStart()
        val c = cur
        if (c != null) {
            if (arch == null) startUrl(c.url) else player.playWhenReady = true
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        Iptv.onUa = null
        player.release()
    }
}
