package app.iptv.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.util.concurrent.Executors

object Logos {
    private val cache = LruCache<String, Bitmap>(200)
    private val pool = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())
    private val bad = HashSet<String>()

    /** [autoHide] = true: ImageView прячется, пока логотипа нет (нет ссылки, не загрузился), и показывается, когда он готов. */
    fun load(v: ImageView, url: String, autoHide: Boolean = false) {
        v.tag = url
        if (url.isEmpty()) { v.setImageDrawable(null); if (autoHide) v.visibility = View.GONE; return }
        val c = cache.get(url)
        if (c != null) { v.setImageBitmap(c); if (autoHide) v.visibility = View.VISIBLE; return }
        v.setImageDrawable(null)
        if (autoHide) v.visibility = View.GONE
        if (synchronized(bad) { bad.contains(url) }) return
        pool.execute {
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
                    synchronized(bad) { bad.add(url) }
                } else {
                    cache.put(url, bm)
                    ui.post {
                        if (v.tag == url) {
                            v.setImageBitmap(bm)
                            if (autoHide) v.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Throwable) {
                synchronized(bad) { bad.add(url) }
            }
        }
    }
}
