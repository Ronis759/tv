package app.iptv.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object Logos {
    private val cache = LruCache<String, Bitmap>(200)
    private val ui = Handler(Looper.getMainLooper())

    /** url -> время (мс), до которого не пытаемся грузить снова. Long.MAX_VALUE — навсегда (404, не картинка). */
    private val bad = HashMap<String, Long>()
    private const val RETRY_MS = 5 * 60 * 1000L

    /**
     * Очередь ограничена и работает «последний пришёл — первый обслужен»: при быстрой прокрутке
     * загружаются логотипы того, что сейчас на экране, а устаревшие запросы вытесняются.
     * Вытесненный логотип подгрузится снова, когда строка вернётся на экран (getView вызывает load).
     */
    private val queue = object : LinkedBlockingDeque<Runnable>(48) {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }
    private val pool = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, queue, RejectedExecutionHandler { r, ex ->
        if (!ex.isShutdown) { queue.pollLast(); queue.offerFirst(r) }
    })

    private fun isBad(url: String): Boolean = synchronized(bad) {
        val t = bad[url] ?: return false
        if (System.currentTimeMillis() < t) true else { bad.remove(url); false }
    }

    private fun markBad(url: String, permanent: Boolean) = synchronized(bad) {
        bad[url] = if (permanent) Long.MAX_VALUE else System.currentTimeMillis() + RETRY_MS
    }

    /** [autoHide] = true: ImageView прячется, пока логотипа нет (нет ссылки, не загрузился), и показывается, когда он готов. */
    fun load(v: ImageView, url: String, autoHide: Boolean = false) {
        v.tag = url
        if (url.isEmpty()) { v.setImageDrawable(null); if (autoHide) v.visibility = View.GONE; return }
        val c = cache.get(url)
        if (c != null) { v.setImageBitmap(c); if (autoHide) v.visibility = View.VISIBLE; return }
        v.setImageDrawable(null)
        if (autoHide) v.visibility = View.GONE
        if (isBad(url)) return
        pool.execute {
            // строку уже переиспользовали под другой канал — загрузка не нужна
            if (v.tag != url) return@execute
            try {
                val b = Iptv.http(url, false)
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(b, 0, b.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 160 && o.outHeight / (s * 2) >= 100) s *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = s
                val bm = BitmapFactory.decodeByteArray(b, 0, b.size, o2)
                if (bm == null) {
                    markBad(url, true)
                } else {
                    cache.put(url, bm)
                    ui.post {
                        if (v.tag == url) {
                            v.setImageBitmap(bm)
                            if (autoHide) v.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: HttpCodeException) {
                markBad(url, e.code == 404 || e.code == 410)
            } catch (e: Throwable) {
                // сеть / таймаут / нехватка памяти — повторим позже
                markBad(url, false)
            }
        }
    }
}
