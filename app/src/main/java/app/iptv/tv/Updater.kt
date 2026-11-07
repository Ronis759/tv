package app.iptv.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Проверка и установка обновлений из GitHub Releases (последний релиз, в нём файл .apk). */
object Updater {
    class Info(val code: Long, val tag: String, val url: String, val size: Long)

    /** Номер установленной сборки (versionCode = номер запуска GitHub Actions). */
    fun installedCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    /** Последний релиз репозитория [repo] («владелец/имя»). null — в релизе нет APK или номер не прочитался. */
    fun fetch(repo: String): Info? {
        val j = try {
            JSONObject(Iptv.text(Iptv.http("https://api.github.com/repos/$repo/releases/latest")))
        } catch (e: HttpCodeException) {
            if (e.code == 404) throw IOException("релизов пока нет") else throw e
        }
        val tag = j.optString("tag_name")
        val code = Regex("\\d+").findAll(tag).lastOrNull()?.value?.toLongOrNull() ?: return null
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", true))
                return Info(code, tag, a.getString("browser_download_url"), a.optLong("size"))
        }
        return null
    }

    /** Скачивает APK во внутреннюю папку; неполный файл удаляется. Уже скачанный полный файл используется повторно. */
    fun download(ctx: Context, info: Info): File {
        val dir = File(ctx.filesDir, "updates").apply { mkdirs() }
        val f = File(dir, "update-${info.code}.apk")
        dir.listFiles()?.forEach { if (it != f) it.delete() }
        if (f.exists() && info.size > 0 && f.length() == info.size) return f
        Iptv.download(info.url, f)
        if (info.size > 0 && f.length() != info.size) { f.delete(); throw IOException("файл загружен не полностью") }
        return f
    }

    /** Запускает системный установщик. null — всё в порядке, иначе текст ошибки для пользователя. */
    fun install(a: Activity, f: File): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !a.packageManager.canRequestPackageInstalls()) {
            return try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
                "Разрешите установку из этого приложения и снова нажмите «Проверить обновления»"
            } catch (e: Exception) {
                "Разрешите установку из неизвестных источников для этого приложения в настройках ТВ"
            }
        }
        return try {
            val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
            a.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            null
        } catch (e: Exception) {
            "На устройстве нет установщика приложений. Установите APK вручную"
        }
    }
}
