package app.iptv.tv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

class Channel(
    val idx: Int, val id: String, val name: String, val tname: String, val logo: String, val group: String,
    val url: String, val days: Int, val ctype: String, val csrc: String
) { var eid: String = "" }

class Prog(val s: Long, val e: Long, val title: String)
class Playlist(val ch: List<Channel>, val epgUrl: String)
class HttpCodeException(val code: Int) : IOException("HTTP $code")
class Epg(val map: Map<String, List<Prog>>, val names: Map<String, String>, val ids: Map<String, String>)
class Server(val name: String, val pl: String, val epg: String)

object Iptv {
    const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android TV) IPTV"
    var customUa: String = ""
    /** User-Agent, с которым сервер реально ответил (если пришлось подбирать). Передаётся и в плеер. */
    var goodUa: String? = null
    /** Вызывается из фонового потока, когда подобран новый рабочий User-Agent. */
    var onUa: ((String) -> Unit)? = null
    private val UAS = listOf(
        "Mozilla/5.0", "VLC/3.0.20 LibVLC/3.0.20", "okhttp/4.12.0",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )

    /** Открывает соединение с ручной обработкой редиректов; возвращает его только при коде 200. */
    private fun connect(url: String, ua: String): HttpURLConnection {
        var u = url
        for (i in 0 until 6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 40000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "*/*")
            val code = try { c.responseCode } catch (e: IOException) { c.disconnect(); throw e }
            if (code in 300..399) {
                val l = c.getHeaderField("Location")
                c.disconnect()
                if (l == null) break
                u = URL(URL(u), l).toString()
                continue
            }
            if (code != 200) { c.disconnect(); throw HttpCodeException(code) }
            return c
        }
        throw IOException("Не удалось загрузить: $url")
    }

    /** Перебирает User-Agent при 401/403/406/429/451 и отдаёт открытое соединение в [block]. */
    private fun <T> withUa(url: String, remember: Boolean, block: (HttpURLConnection) -> T): T {
        val list = if (customUa.isNotEmpty()) listOf(customUa) else listOfNotNull(goodUa) + UAS.filter { it != goodUa }
        var last: IOException? = null
        for ((idx, ua) in list.withIndex()) {
            var c: HttpURLConnection? = null
            try {
                c = connect(url, ua)
                val r = block(c)
                // Запоминаем UA только если пришлось подбирать (не первый в списке)
                if (remember && customUa.isEmpty() && idx > 0) { goodUa = ua; onUa?.invoke(ua) }
                return r
            } catch (e: HttpCodeException) {
                last = e
                if (e.code !in intArrayOf(401, 403, 406, 429, 451)) throw e
            } finally {
                c?.disconnect()
            }
        }
        throw last ?: IOException("HTTP 403")
    }

    /** [remember] = false для побочных запросов (логотипы), чтобы они не меняли общий User-Agent. */
    fun http(url: String, remember: Boolean = true): ByteArray =
        withUa(url, remember) { c -> c.inputStream.use { it.readBytes() } }

    /** Потоковая загрузка в файл (без хранения всего ответа в памяти). Старый файл заменяется только при успехе. */
    fun download(url: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            withUa(url, true) { c -> c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 65536) } } }
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("Не удалось сохранить файл")
            }
        } finally {
            tmp.delete()
        }
    }

    private fun isGz(b: ByteArray) = b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()

    fun text(b: ByteArray): String {
        val raw = if (isGz(b)) GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() } else b
        return String(raw, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private val AT = Regex("([\\w-]+)=\"([^\"]*)\"")
    private fun attrs(s: String): Map<String, String> =
        AT.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    fun parseM3u(t: String): Playlist {
        val out = ArrayList<Channel>()
        var head: Map<String, String> = emptyMap()
        var a: Map<String, String>? = null
        var name = ""
        var grp: String? = null
        for (raw in t.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            when {
                l.startsWith("#EXTM3U") -> head = attrs(l)
                l.startsWith("#EXTINF") -> {
                    a = attrs(l)
                    name = l.substring(l.lastIndexOf(',') + 1).trim()
                    grp = null
                }
                l.startsWith("#EXTGRP:") -> grp = l.substring(8).trim()
                l.startsWith("#") -> {}
                else -> {
                    val x = a
                    if (x != null) {
                        val days = (x["catchup-days"] ?: x["tvg-rec"] ?: head["catchup-days"])?.toIntOrNull() ?: 0
                        out.add(
                            Channel(
                                out.size, x["tvg-id"] ?: "", name.ifEmpty { x["tvg-name"] ?: "Канал" }, x["tvg-name"] ?: "",
                                x["tvg-logo"] ?: "", x["group-title"] ?: grp ?: "Без группы", l, days,
                                x["catchup"] ?: x["catchup-type"] ?: head["catchup"] ?: head["catchup-type"] ?: "",
                                x["catchup-source"] ?: head["catchup-source"] ?: ""
                            )
                        )
                        a = null
                    }
                }
            }
        }
        return Playlist(out, (head["url-tvg"] ?: head["x-tvg-url"] ?: "").split(",")[0].trim())
    }

    private val FILL = Regex("""\$?\{(\w+)\}""")

    fun arcUrl(c: Channel, s: Long, e: Long, def: String): String {
        val n = System.currentTimeMillis() / 1000
        val d = e - s
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = s * 1000
        fun f(x: Int) = x.toString().padStart(2, '0')
        val v = mapOf(
            "start" to "$s", "utc" to "$s", "end" to "$e", "lutc" to "$n", "timestamp" to "$n",
            "offset" to "${n - s}", "duration" to "$d",
            "Y" to "${cal.get(Calendar.YEAR)}", "m" to f(cal.get(Calendar.MONTH) + 1),
            "d" to f(cal.get(Calendar.DAY_OF_MONTH)), "H" to f(cal.get(Calendar.HOUR_OF_DAY)),
            "M" to f(cal.get(Calendar.MINUTE)), "S" to f(cal.get(Calendar.SECOND))
        )
        fun fill(t: String) = FILL.replace(t) { v[it.groupValues[1]] ?: it.value }
        val type = (c.ctype.ifEmpty { if (c.csrc.isNotEmpty()) "default" else def }).lowercase()
        val url = c.url
        if (type == "append") return url + fill(c.csrc.ifEmpty { "?utc={start}&lutc={timestamp}" })
        if (type == "default" || (c.csrc.isNotEmpty() && type != "shift" && !type.startsWith("flus") && type != "fs")) {
            val t = fill(c.csrc)
            return if (t.startsWith("http")) t else url + t
        }
        if (type.startsWith("flus") || type == "fs") {
            val p = url.substringBefore('?')
            val q = if (url.contains('?')) "?" + url.substringAfter('?') else ""
            val b = p.substringBeforeLast('/')
            return if (p.contains("mpegts") || p.endsWith(".ts")) "$b/timeshift_abs-$s.ts$q"
            else "$b/index-$s-$d.m3u8$q"
        }
        return url + (if (url.contains('?')) "&" else "?") + "utc=$s&lutc=$n"
    }

    /**
     * Варианты ссылки на архив в порядке попыток: сначала по правилам плейлиста/типа архива,
     * затем типичные форматы Flussonic. Число — устойчивый номер формата (запоминается для сервера).
     */
    fun arcCandidates(c: Channel, s: Long, e: Long, def: String): List<Pair<Int, String>> {
        val n = System.currentTimeMillis() / 1000
        val d = e - s
        val url = c.url
        val p = url.substringBefore('?')
        val q = if (url.contains('?')) "?" + url.substringAfter('?') else ""
        val b = p.substringBeforeLast('/')
        val l = ArrayList<Pair<Int, String>>()
        l.add(0 to arcUrl(c, s, e, def))
        l.add(1 to url + (if (url.contains('?')) "&" else "?") + "utc=$s&lutc=$n")
        l.add(2 to "$b/video-$s-$d.m3u8$q")
        l.add(3 to "$b/index-$s-$d.m3u8$q")
        if (p.contains("mpegts") || p.endsWith(".ts")) l.add(4 to "$b/timeshift_abs-$s.ts$q")
        return l.distinctBy { it.second }
    }

    private val XR = Regex("""^(\d{4})(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)?\s*([+-]\d{4})?""")

    private fun xt(s: String?, tz: Int): Long {
        val m = XR.find(s ?: "") ?: return 0L
        val g = m.groupValues
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), if (g[6].isEmpty()) 0 else g[6].toInt())
        var z = tz * 3600
        if (g[7].isNotEmpty()) {
            val sign = if (g[7][0] == '-') -1 else 1
            z = sign * (g[7].substring(1, 3).toInt() * 3600 + g[7].substring(3).toInt() * 60)
        }
        return c.timeInMillis / 1000 - z
    }

    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    fun matchEid(e: Epg, c: Channel): String {
        if (c.id.isNotEmpty()) {
            if (e.map.containsKey(c.id)) return c.id
            val x = e.ids[c.id.lowercase()]
            if (x != null) return x
        }
        for (n in listOf(c.tname, c.name)) {
            if (n.isEmpty()) continue
            val k = norm(n)
            val a = e.names[k]
            if (a != null) return a
            val b = e.names[k.removeSuffix("hd")]
            if (b != null) return b
        }
        return ""
    }

    fun hostOf(u: String): String = try { URL(u).host } catch (e: Exception) { u }

    fun parseServers(t: String): List<Server> {
        val out = ArrayList<Server>()
        for (raw in t.split(Regex("\r\n|\r|\n"))) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("//")) continue
            val tk = l.split("|").map { it.trim() }
            val sv = when {
                tk.size == 1 -> Server(hostOf(tk[0]), tk[0], "")
                tk.size == 2 -> Server(tk[0], tk[1], "")
                else -> Server(tk[0], tk[1], tk[2])
            }
            if (sv.pl.startsWith("http")) out.add(sv)
        }
        return out
    }

    /**
     * Потоковый разбор XMLTV из файла (gzip определяется по сигнатуре).
     * Если передан [chans], в памяти остаются только передачи каналов плейлиста
     * и только в окне, которое реально нужно архиву — это резко снижает расход памяти.
     */
    fun parseEpg(f: File, tz: Int, chans: List<Channel>?): Epg {
        val raw = BufferedInputStream(FileInputStream(f), 65536)
        raw.mark(2)
        val b0 = raw.read()
        val b1 = raw.read()
        raw.reset()
        val inp: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 65536) else raw
        return inp.use { parseEpgStream(it, tz, chans) }
    }

    private fun parseEpgStream(inp: InputStream, tz: Int, chans: List<Channel>?): Epg {
        val p = Xml.newPullParser()
        p.setInput(inp, null)
        val map = HashMap<String, MutableList<Prog>>()
        val names = HashMap<String, String>()
        val ids = HashMap<String, String>()
        val titles = HashMap<String, String>()
        val nowS = System.currentTimeMillis() / 1000
        val keepDays = (chans?.maxOfOrNull { if (it.days > 0) it.days else 7 } ?: 14).coerceIn(1, 14)
        val lo = nowS - keepDays * 86400L
        val hi = nowS + 2 * 86400L
        var wanted: Set<String>? = null
        var chId = ""; var inCh = false; var pch = ""
        var s = 0L; var e = 0L; var title = ""; var inProg = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> { inCh = true; chId = p.getAttributeValue(null, "id") ?: ""; ids[chId.lowercase()] = chId }
                    "display-name" -> if (inCh) {
                        val k = norm(p.nextText())
                        if (k.isNotEmpty()) {
                            if (!names.containsKey(k)) names[k] = chId
                            val k2 = k.removeSuffix("hd")
                            if (k2.isNotEmpty() && !names.containsKey(k2)) names[k2] = chId
                        }
                    }
                    "programme" -> {
                        if (chans != null && wanted == null) {
                            // Список каналов XMLTV уже прочитан — определяем, чьи передачи нужны
                            val tmp = Epg(map, names, ids)
                            wanted = chans.mapNotNullTo(HashSet<String>()) { c -> matchEid(tmp, c).takeIf { it.isNotEmpty() } }
                        }
                        inProg = true; title = ""
                        pch = p.getAttributeValue(null, "channel") ?: ""
                        s = xt(p.getAttributeValue(null, "start"), tz)
                        e = xt(p.getAttributeValue(null, "stop"), tz)
                    }
                    "title" -> if (inProg && title.isEmpty()) title = p.nextText().trim()
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "channel" -> inCh = false
                    "programme" -> {
                        val w = wanted
                        if (inProg && e > s && e > lo && s < hi && (w == null || pch in w)) {
                            val t = titles.getOrPut(title) { title }
                            map.getOrPut(pch) { ArrayList() }.add(Prog(s, e, t))
                        }
                        inProg = false
                    }
                }
            }
            ev = p.next()
        }
        map.values.forEach { l -> l.sortBy { it.s } }
        return Epg(map, names, ids)
    }
}
