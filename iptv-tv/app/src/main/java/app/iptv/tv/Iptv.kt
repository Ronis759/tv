package app.iptv.tv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

class Channel(
    val idx: Int, val id: String, val name: String, val logo: String, val group: String,
    val url: String, val days: Int, val ctype: String, val csrc: String
) { var eid: String = "" }

class Prog(val s: Long, val e: Long, val title: String)
class Playlist(val ch: List<Channel>, val epgUrl: String)
class Epg(val map: Map<String, List<Prog>>, val names: Map<String, String>)

object Iptv {
    fun http(url: String): ByteArray {
        var u = url
        for (i in 0 until 6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 40000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = c.responseCode
            if (code in 300..399) {
                val l = c.getHeaderField("Location") ?: break
                u = URL(URL(u), l).toString()
                c.disconnect()
                continue
            }
            if (code != 200) throw IOException("HTTP $code")
            return c.inputStream.use { it.readBytes() }
        }
        throw IOException("Не удалось загрузить: $url")
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
        for (raw in t.split(Regex("\r\n|\r|\n"))) {
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
                                out.size, x["tvg-id"] ?: "", name.ifEmpty { x["tvg-name"] ?: "Канал" },
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

    fun parseEpg(b: ByteArray, tz: Int): Epg {
        val inp: InputStream = if (isGz(b)) GZIPInputStream(ByteArrayInputStream(b)) else ByteArrayInputStream(b)
        val p = Xml.newPullParser()
        p.setInput(inp, null)
        val map = HashMap<String, MutableList<Prog>>()
        val names = HashMap<String, String>()
        var chId = ""; var inCh = false; var pch = ""
        var s = 0L; var e = 0L; var title = ""; var inProg = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> { inCh = true; chId = p.getAttributeValue(null, "id") ?: "" }
                    "display-name" -> if (inCh) {
                        val t = p.nextText().trim().lowercase()
                        if (t.isNotEmpty() && !names.containsKey(t)) names[t] = chId
                    }
                    "programme" -> {
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
                        if (inProg && e > s) map.getOrPut(pch) { ArrayList() }.add(Prog(s, e, title))
                        inProg = false
                    }
                }
            }
            ev = p.next()
        }
        map.values.forEach { l -> l.sortBy { it.s } }
        return Epg(map, names)
    }
}
