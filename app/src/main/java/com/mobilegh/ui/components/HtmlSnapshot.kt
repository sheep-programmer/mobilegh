package com.mobilegh.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView

/**
 * 网页正文离开屏幕前留下的快照。
 *
 * 列表里的每条评论都是一个 WebView；滚出屏幕时 WebView 被释放，再滚回来要重新加载、重新排版，
 * 这段时间里正文区域是空白的，看起来就是「闪烁」。有了快照之后，再次滚入时先显示快照，
 * WebView 排版完成、淡入后覆盖在上面，两者内容相同，所以不会出现空白帧。
 *
 * 按字节数限制总量（默认约 24 MB），最久没用的先丢弃。
 */
internal object HtmlSnapshotCache {
    private const val MAX_BYTES = 24L * 1024 * 1024

    private val map = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {}
    private var totalBytes = 0L

    @Synchronized
    fun get(key: String): Bitmap? = map[key]

    @Synchronized
    fun has(key: String): Boolean = map.containsKey(key)

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        map.remove(key)?.let { totalBytes -= it.byteCount }
        map[key] = bitmap
        totalBytes += bitmap.byteCount
        // 从最久未用的开始丢，但至少保留刚放进去的这一张
        val iterator = map.entries.iterator()
        while (totalBytes > MAX_BYTES && map.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            totalBytes -= eldest.value.byteCount
            iterator.remove()
        }
    }
}

/**
 * 把已经显示完成的 WebView 自身内容画进位图，作为快照。
 *
 * 不能用 PixelCopy：它拷贝的是窗口里的屏幕像素，卡片被底部输入框、顶部栏盖住的部分会被一起截进去，
 * 之后显示快照时这些控件的文字就出现在评论里。这里只画 WebView 自己，不带窗口上的任何东西。
 * 只在 WebView 已显示、有实际尺寸时截取；画出来是空白的（例如 WebView 还没排版）就不保存。
 */
internal fun WebView.captureSnapshot(key: String) {
    if (HtmlSnapshotCache.has(key)) return
    val w = width
    val h = height
    if (w <= 0 || h <= 0 || alpha < 1f) return
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    draw(Canvas(bitmap))
    if (bitmap.isUniform()) {
        bitmap.recycle()
        return
    }
    HtmlSnapshotCache.put(key, bitmap)
}

/** 抽样检查整张图是否只有一种颜色（空白页）。 */
private fun Bitmap.isUniform(): Boolean {
    val first = getPixel(0, 0)
    for (i in 1..16) {
        val x = (width * i / 17).coerceIn(0, width - 1)
        val y = (height * i / 17).coerceIn(0, height - 1)
        if (getPixel(x, y) != first) return false
    }
    return getPixel(width / 2, height / 2) == first
}
