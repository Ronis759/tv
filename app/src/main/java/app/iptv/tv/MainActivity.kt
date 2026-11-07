package app.iptv.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class Row(val title: String, val sub: String = "", val tag: Any? = null, val dim: Boolean = false, val icon: String = "")

class RowAdapter(private val ctx: Context) : BaseAdapter() {
    private var rows: List<Row> = emptyList()
    var icons = false
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
        (tx.getChildAt(0) as TextView).apply { text = r.title; setTextColor(if (r.dim) 0xFF8A9AA5.toInt() else Color.WHITE) }
        (tx.getChildAt(1) as TextView).apply { text = r.sub; visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE }
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
    private lateinit var httpFactory: DefaultHttpDataSource.Factory
    private lateinit var etUa: EditText
    private lateinit var srvPanel: LinearLayout
    private lateinit var srvList: ListView
    private lateinit var tvStat: TextView
    private var epgStatus = "не загружена"

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private val types = listOf("shift", "flussonic", "append")
    private var chans: List<Channel> = emptyList()
    private var groups: List<String> = emptyList()
    private var epg: Map<String, List<Prog>> = emptyMap()
    private var shown: List<Channel> = emptyList()
    private var grp = "Все"
    private var cur: Channel? = null
    private var arch: Prog? = null
    private var dayOff = 0
    private var lastUrl = ""
    private var triedHls = false
    private var ctype = "shift"
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
        httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Iptv.customUa.ifEmpty { "Mozilla/5.0 (Linux; Android TV) IPTV" })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)).build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!triedHls && lastUrl.isNotEmpty() && !lastUrl.contains(".m3u8")) { playUrl(lastUrl, true); return }
                toast(if (arch != null) "Архив не воспроизводится (тип: ${archType()}) — смените тип в настройках. " + error.errorCodeName else "Ошибка воспроизведения: " + error.errorCodeName)
            }
        })
        pv = PlayerView(this)
        pv.useController = false
        pv.isFocusable = false
        pv.player = player
        pv.setOnClickListener { if (!panelOpen()) openChans() }
        root.addView(pv, FrameLayout.LayoutParams(-1, -1))

        buildHud(); buildPanels(); buildSetup(); buildServers(); buildToast()
        ctype = prefs.getString("ct", "shift") ?: "shift"
        if (prefs.getString("pl", "").isNullOrEmpty()) {
            val d = getString(R.string.default_playlist)
            if (d.isNotEmpty()) {
                prefs.edit().putString("pl", d).putString("epg", getString(R.string.default_epg)).apply()
                loadAll()
            } else if (allServers().isNotEmpty()) openServers() else openSetup()
        } else loadAll()
    }

    /* ---------- интерфейс ---------- */
    private fun buildHud() {
        hudBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD101519.toInt())
            setPadding(dp(24), dp(14), dp(24), dp(14))
            visibility = View.GONE
        }
        hudT = TextView(this).apply { textSize = 26f; setTextColor(Color.WHITE) }
        hudP = TextView(this).apply { textSize = 18f; setTextColor(0xFF6FE3C1.toInt()) }
        hudBox.addView(hudT); hudBox.addView(hudP)
        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        lp.setMargins(dp(40), 0, dp(40), dp(32))
        root.addView(hudBox, lp)
    }

    private fun buildToast() {
        toastTv = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE)
            setBackgroundColor(0xEE26323B.toInt())
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
        chPanel = panel(TextView(this).apply { text = "Каналы" }, grpList, chList, 0.62f, Gravity.START)
        arTitle = TextView(this)
        arPanel = panel(arTitle, dayList, progList, 0.62f, Gravity.END)

        grpList.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            if (pos == 0) openSetup() else if (pos == 1) openServers() else {
                grp = ad(grpList).row(pos).title
                renderChans(); chList.requestFocus()
            }
        }
        chList.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            if (pos < shown.size) { playChan(shown[pos]); closePanels() }
        }
        dayList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { dayOff = pos; renderProgs() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        dayList.onItemClickListener = AdapterView.OnItemClickListener { _, _, _, _ -> progList.requestFocus() }
        progList.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val p = ad(progList).row(pos).tag as? Prog
            val c = cur
            if (p != null && c != null) {
                val n = now()
                when {
                    p.s > n -> toast("Эта передача ещё не вышла")
                    p.s <= n && n < p.e -> { playChan(c); closePanels() }
                    else -> { playArch(p); closePanels() }
                }
            }
        }
    }

    private fun buildSetup() {
        fun field(h: String) = EditText(this).apply {
            setTextColor(Color.WHITE); setHintTextColor(0xFF667780.toInt()); hint = h
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setTextColor(0xFF8A9AA5.toInt()); setPadding(0, dp(14), 0, 0) }
        etPl = field("http://…/playlist.m3u")
        etEpg = field("http://…/epg.xml.gz (необязательно)")
        etTz = field("0").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED }
        btType = Button(this).apply {
            setOnClickListener { ctype = types[(types.indexOf(ctype) + 1) % types.size]; text = "Тип архива: $ctype" }
        }
        val save = Button(this).apply { text = "Сохранить и загрузить"; setOnClickListener { saveSetup() } }
        val cancel = Button(this).apply { text = "Отмена"; setOnClickListener { if (chans.isNotEmpty()) closePanels() } }
        etUa = field("пусто = автоподбор (VLC, okhttp, Mozilla…)")
        tvStat = TextView(this).apply { textSize = 16f; setTextColor(0xFF6FE3C1.toInt()); setPadding(0, dp(14), 0, dp(6)) }
        val btSrv = Button(this).apply { text = "Выбрать сервер из списка"; setOnClickListener { openServers() } }
        val btFile = Button(this).apply { text = "Загрузить список / плейлист из файла…"; setOnClickListener { pickFile() } }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(60), dp(30), dp(60), dp(30))
            addView(TextView(this@MainActivity).apply { text = "Настройка IPTV"; textSize = 30f; setTextColor(Color.WHITE) })
            addView(label("Ссылка на плейлист M3U")); addView(etPl)
            addView(label("Ссылка на программу передач XMLTV")); addView(etEpg)
            addView(label("Часовой пояс программы, часы от UTC (если не указан в EPG)")); addView(etTz)
            addView(label("User-Agent (если сервер отвечает 403)")); addView(etUa)
            addView(label("Тип архива, если в плейлисте не указан")); addView(btType)
            addView(tvStat); addView(btSrv); addView(btFile); addView(save); addView(cancel)
        }
        setup = ScrollView(this).apply { setBackgroundColor(0xFF0F1519.toInt()); visibility = View.GONE; addView(col) }
        root.addView(setup, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showPanel(p: View?) {
        chPanel.visibility = View.GONE; arPanel.visibility = View.GONE; setup.visibility = View.GONE; srvPanel.visibility = View.GONE
        hudBox.visibility = View.GONE
        p?.visibility = View.VISIBLE
    }
    private fun closePanels() = showPanel(null)
    private fun panelOpen() = chPanel.visibility == View.VISIBLE || arPanel.visibility == View.VISIBLE || setup.visibility == View.VISIBLE || srvPanel.visibility == View.VISIBLE

    private fun toast(t: String) {
        toastTv.text = t; toastTv.visibility = View.VISIBLE
        ui.removeCallbacks(hideToast); ui.postDelayed(hideToast, 3000)
    }

    private fun hud() {
        val c = cur ?: return
        val a = arch
        val p = a ?: nowProg(c)
        hudT.text = c.name + if (a != null) "  ·  архив" else ""
        hudP.text = if (p != null) "${hm(p.s)}–${hm(p.e)}  ${p.title}" else ""
        hudBox.visibility = View.VISIBLE
        ui.removeCallbacks(hideHud); ui.postDelayed(hideHud, 4500)
    }

    /* ---------- панели ---------- */
    private fun openChans() {
        ad(grpList).set((listOf("⚙ Настройки", "⇄ Сервер", "Все") + groups).map { Row(it) })
        renderChans()
        showPanel(chPanel)
        chList.setSelection(shown.indexOfFirst { it === cur }.coerceAtLeast(0))
        chList.requestFocus()
    }

    private fun renderChans() {
        shown = chans.filter { grp == "Все" || it.group == grp }
        ad(chList).set(shown.map { Row((if (it === cur) "▶ " else "") + it.name, nowProg(it)?.title ?: "", it, false, it.logo) })
    }

    private fun openArch() {
        val c = cur ?: return
        arTitle.text = "Архив · ${c.name}  (тип: ${archType()}, дней: ${if (c.days > 0) c.days else 7}" + (if (c.days == 0 && c.ctype.isEmpty() && c.csrc.isEmpty()) ", в плейлисте нет catchup)" else ")")
        val n = minOf(if (c.days > 0) c.days else 7, 14)
        val f = SimpleDateFormat("dd.MM", Locale.getDefault())
        ad(dayList).set((0..n).map { i ->
            Row(when (i) { 0 -> "Сегодня"; 1 -> "Вчера"; else -> f.format(Date(System.currentTimeMillis() - i * 86400000L)) })
        })
        dayOff = 0
        showPanel(arPanel)
        renderProgs()
        progList.requestFocus()
    }

    private fun renderProgs() {
        val c = cur ?: return
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
        etPl.setText(prefs.getString("pl", ""))
        etEpg.setText(prefs.getString("epg", ""))
        etTz.setText(prefs.getInt("tz", 0).toString())
        etUa.setText(prefs.getString("ua", ""))
        btType.text = "Тип архива: $ctype"
        tvStat.text = "Программа передач: $epgStatus"
        showPanel(setup)
        etPl.requestFocus()
    }

    private fun saveSetup() {
        prefs.edit().putString("pl", etPl.text.toString().trim()).putString("epg", etEpg.text.toString().trim())
            .putInt("tz", etTz.text.toString().toIntOrNull() ?: 0).putString("ct", ctype).putString("ua", etUa.text.toString().trim()).apply()
        Iptv.customUa = etUa.text.toString().trim()
        httpFactory.setUserAgent(Iptv.customUa.ifEmpty { "Mozilla/5.0 (Linux; Android TV) IPTV" })
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

    private fun playChan(c: Channel) {
        cur = c; arch = null
        prefs.edit().putInt("last", c.idx).apply()
        playUrl(c.url)
        hud()
    }

    private fun playArch(p: Prog) {
        val c = cur ?: return
        arch = p
        playUrl(Iptv.arcUrl(c, p.s, p.e, ctype))
        hud()
        toast("Архив: " + hm(p.s) + " " + p.title)
    }

    private fun step(d: Int) {
        if (chans.isEmpty()) return
        playChan(chans[((cur?.idx ?: 0) + d + chans.size) % chans.size])
    }

    /* ---------- серверы и файлы ---------- */
    private fun archType(): String {
        val c = cur ?: return ctype
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
        epg = emptyMap(); epgStatus = "не загружена"
        toast("Загрузка плейлиста…")
        Thread {
            try {
                val f = File(filesDir, "pl.m3u")
                val txt = if (pl == "local") File(filesDir, "local.m3u").readText()
                else try { Iptv.text(Iptv.http(pl)).also { f.writeText(it) } }
                catch (e: Exception) { if (f.exists()) f.readText() else throw e }
                val list = Iptv.parseM3u(txt)
                if (list.ch.isEmpty()) throw Exception("в плейлисте нет каналов")
                runOnUiThread {
                    chans = list.ch
                    groups = list.ch.map { it.group }.distinct()
                    grp = "Все"
                    toast("Каналов: ${list.ch.size}")
                    playChan(list.ch.getOrElse(prefs.getInt("last", 0)) { list.ch[0] })
                }
                val eu = (prefs.getString("epg", "") ?: "").ifEmpty { list.epgUrl }
                if (eu.isEmpty()) {
                    runOnUiThread { epgStatus = "адрес не указан"; toast("Адрес программы передач не указан — задайте его в настройках") }
                } else {
                    try {
                        val ef = File(filesDir, "epg.bin")
                        val fresh = !force && ef.exists() && System.currentTimeMillis() - ef.lastModified() < 6 * 3600000L && prefs.getString("epgsrc", "") == eu
                        val bytes = if (fresh) ef.readBytes() else Iptv.http(eu).also { ef.writeBytes(it); prefs.edit().putString("epgsrc", eu).apply() }
                        val e = Iptv.parseEpg(bytes, tz)
                        var m = 0
                        for (c in list.ch) { c.eid = Iptv.matchEid(e, c); if (c.eid.isNotEmpty() && e.map.containsKey(c.eid)) m++ }
                        val total = e.map.values.sumOf { it.size }
                        runOnUiThread {
                            epg = e.map
                            epgStatus = "передач $total, каналов с программой $m из ${list.ch.size}"
                            toast("Программа: $epgStatus")
                            hud()
                        }
                    } catch (ex: Throwable) {
                        runOnUiThread { epgStatus = "ошибка: " + (ex.message ?: ex.javaClass.simpleName) + (if (ex.message?.contains("403") == true) " — сервер отклонил запрос; проверьте ссылку в браузере или задайте User-Agent в настройках" else ""); toast("Программа передач: $epgStatus") }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Ошибка: " + e.message); if (chans.isEmpty()) openSetup() }
            }
        }.start()
    }

    /* ---------- пульт ---------- */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (panelOpen()) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (setup.visibility == View.VISIBLE && chans.isEmpty()) return super.dispatchKeyEvent(e)
                if (e.action == KeyEvent.ACTION_UP) { if (srvPanel.visibility == View.VISIBLE) openSetup() else closePanels() }
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        val live = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND
        )
        if (k !in live) return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) onLiveKey(k)
        return true
    }

    private fun onLiveKey(k: Int) {
        when (k) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> openChans()
            KeyEvent.KEYCODE_DPAD_LEFT -> openArch()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (arch != null) player.seekTo(player.currentPosition + 30000) else openArch()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> step(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> step(1)
            KeyEvent.KEYCODE_MENU -> openSetup()
            KeyEvent.KEYCODE_INFO -> hud()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> player.playWhenReady = !player.playWhenReady
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekTo(player.currentPosition + 30000)
            KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekTo(maxOf(0L, player.currentPosition - 30000))
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
    override fun onStop() { super.onStop(); player.playWhenReady = false }
    override fun onStart() {
        super.onStart()
        val c = cur
        if (c != null) {
            if (arch == null) playUrl(c.url) else player.playWhenReady = true
        }
    }
    override fun onDestroy() { super.onDestroy(); player.release() }
}
