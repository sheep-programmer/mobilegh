package com.mobilegh.ui.components

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mobilegh.data.ImageFetch
import com.mobilegh.data.ImageContent
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 极简图片加载器：内存 LRU + OkHttp 磁盘缓存 + 按显示尺寸采样解码。替代 Coil/Glide 以保持轻量。 */
object Images {
    private val mem = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }

    /** 解码尺寸以 [MEM_KEY] 区分；同一地址的小图和大图各留一份，各自命中 */
    private const val MEM_KEY = "%s#%d"

    private val ladder = intArrayOf(64, 128, 256, 512, 1024, 2048)

    private fun key(url: String, maxDim: Int) = MEM_KEY.format(ImageFetch.cacheKey(url), maxDim)

    /** 解码中的地址：同一张图被多个列表项引用时只解码一次 */
    private val decoding = HashMap<String, CompletableDeferred<ImageBitmap?>>()

    /** 已经解码好的最大尺寸；只要不小于需要的尺寸就能直接复用，避免重复解码 */
    fun cached(url: String, maxDim: Int = 2048): ImageBitmap? {
        val raw = ImageFetch.cacheKey(url)
        // 精确尺寸的条目直接可用，即使原图本身就比 maxDim 小
        mem.get(MEM_KEY.format(raw, maxDim))?.let { return it }
        for (dim in ladder) {
            if (dim <= maxDim) continue
            val bmp = mem.get(MEM_KEY.format(raw, dim)) ?: continue
            if (bmp.width.coerceAtLeast(bmp.height) >= maxDim) return bmp
        }
        return null
    }

    suspend fun load(url: String, maxDim: Int = 2048, force: Boolean = false): ImageBitmap? {
        if (!force) {
            cached(url, maxDim)?.let { return it }
        } else {
            val raw = ImageFetch.cacheKey(url)
            ladder.forEach { dim -> mem.remove(MEM_KEY.format(raw, dim)) }
            mem.remove(key(url, maxDim))
        }
        val memKey = key(url, maxDim)
        val mine = CompletableDeferred<ImageBitmap?>()
        val running = if (!force) {
            synchronized(decoding) { decoding.getOrPut(memKey) { mine } }
        } else {
            mine
        }
        if (running !== mine) return running.await()
        return try {
            val bmp = withContext(Dispatchers.IO) {
                val r = ImageFetch.fetch(url, force) ?: return@withContext null
                decode(r.bytes, maxDim)?.also { if (memKey == key(url, maxDim)) mem.put(memKey, it) }
            }
            mine.complete(bmp)
            bmp
        } catch (t: Throwable) {
            mine.complete(null)
            null
        } finally {
            if (!force) synchronized(decoding) { decoding.remove(memKey) }
        }
    }

    /** SVG 渲染的放大倍数：徽章等矢量图按 3 倍像素栅格化，在高密度屏上保持清晰；逻辑尺寸 = 位图尺寸 / 倍数 */
    internal const val SVG_SCALE = 3f

    /** 由 SVG 栅格化得到的位图 → 它的放大倍数；普通位图不在表里（视为 1 倍）。弱引用，位图被回收后自动清除 */
    private val svgScales = java.util.WeakHashMap<ImageBitmap, Float>()

    /** 位图相对「逻辑像素（dp）」的倍数：SVG 位图是 [SVG_SCALE]，普通图片按原始像素显示时为 1 */
    fun scaleOf(bitmap: ImageBitmap): Float = synchronized(svgScales) { svgScales[bitmap] } ?: 1f

    /** 把 SVG 画成位图；解析失败返回 null。文档没有声明尺寸时用 viewBox，再不行取 300×150（浏览器的默认值） */
    internal fun decodeSvg(bytes: ByteArray, maxDim: Int): ImageBitmap? = try {
        com.caverock.androidsvg.SVG.registerExternalFileResolver(SvgFileResolver)
        val svg = com.caverock.androidsvg.SVG.getFromInputStream(java.io.ByteArrayInputStream(bytes))
        var w = svg.documentWidth
        var h = svg.documentHeight
        if (w <= 0f || h <= 0f) {
            val box = svg.documentViewBox
            if (box != null && box.width() > 0f && box.height() > 0f) { w = box.width(); h = box.height() } else { w = 300f; h = 150f }
        }
        val scale = minOf(SVG_SCALE, maxDim / maxOf(w, h)).coerceAtLeast(0.1f)
        val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        // 很多徽章 SVG 没有写 viewBox：不补上的话，放大文档尺寸时内容不会跟着放大，只会缩在左上角
        if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, w, h)
        svg.setDocumentWidth(w * scale)
        svg.setDocumentHeight(h * scale)
        svg.renderToCanvas(android.graphics.Canvas(bmp))
        bmp.asImageBitmap().also { synchronized(svgScales) { svgScales[it] = scale } }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** 处理 SVG 内部 <image> 元素（如 Shields.io 徽章内嵌的 base64 矢量 logo） */
    private object SvgFileResolver : com.caverock.androidsvg.SVGExternalFileResolver() {
        private val depth = ThreadLocal.withInitial { 0 }

        override fun resolveImage(filename: String?): Bitmap? {
            if (filename.isNullOrBlank()) return null
            val currentDepth = depth.get() ?: 0
            if (currentDepth >= 3) return null
            depth.set(currentDepth + 1)
            return try {
                resolveInternal(filename)
            } catch (_: Throwable) {
                null
            } finally {
                depth.set(currentDepth)
            }
        }

        private fun resolveInternal(filename: String): Bitmap? {
            // 1. Data URI (徽章内嵌 SVG/PNG 矢量/位图图标)
            if (filename.startsWith("data:", ignoreCase = true)) {
                val parsed = ImageContent.parseDataUri(filename) ?: return null
                return if (parsed.isSvg) {
                    renderEmbeddedSvg(parsed.bytes)
                } else {
                    BitmapFactory.decodeByteArray(parsed.bytes, 0, parsed.bytes.size)
                }
            }
            // 2. 外部 HTTP/HTTPS 图片引用
            if (filename.startsWith("http://", ignoreCase = true) || filename.startsWith("https://", ignoreCase = true)) {
                cached(filename, 512)?.let { return it.asAndroidBitmap() }
                val fetched = ImageFetch.cached(filename) ?: ImageFetch.fetch(filename)
                val bytes = fetched?.bytes ?: return null
                return if (ImageContent.mimeOf(filename, bytes) == "image/svg+xml") {
                    renderEmbeddedSvg(bytes)
                } else {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            return null
        }

        private fun renderEmbeddedSvg(bytes: ByteArray): Bitmap? = try {
            val svg = com.caverock.androidsvg.SVG.getFromInputStream(java.io.ByteArrayInputStream(bytes))
            var w = svg.documentWidth
            var h = svg.documentHeight
            if (w <= 0f || w.isNaN() || h <= 0f || h.isNaN()) {
                val box = svg.documentViewBox
                if (box != null && box.width() > 0f && box.height() > 0f) {
                    w = box.width()
                    h = box.height()
                } else {
                    w = 48f
                    h = 48f
                }
            }
            val maxSide = maxOf(w, h).coerceAtLeast(1f)
            val scale = (72f / maxSide).coerceIn(1f, 4f)
            val bw = (w * scale).toInt().coerceAtLeast(1)
            val bh = (h * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, w, h)
            svg.setDocumentWidth(bw.toFloat())
            svg.setDocumentHeight(bh.toFloat())
            svg.renderToCanvas(android.graphics.Canvas(bmp))
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /** 采样解码，避免大图直接撑爆内存 */
    fun decode(bytes: ByteArray, maxDim: Int): ImageBitmap? {
        if (ImageContent.mimeOf("", bytes) == "image/svg+xml") return decodeSvg(bytes, maxDim)
        return decodeRaster(bytes, maxDim)
    }

    private fun decodeRaster(bytes: ByteArray, maxDim: Int): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap()
    } catch (e: OutOfMemoryError) {
        // 大图解码失败时清理图片缓存，避免一次 OOM 直接杀掉整个客户端。
        mem.evictAll()
        null
    }
}

fun sizedAvatar(url: String, px: Int): String =
    if (url.contains("avatars.githubusercontent.com")) url + (if (url.contains('?')) "&" else "?") + "s=$px" else url

/**
 * 小图按显示尺寸解码。
 *
 * 以前一律按 2048px 解码：一张 40dp 的头像也要解出几千像素的位图，列表滚动时既慢又费内存。
 * 等布局尺寸确定后再加载，避免先以 2048px 解码头像，再为实际尺寸重复解码。
 */
@Composable
fun NetImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Boolean = true,
    /** 读屏描述。为空时不挂语义，和原来一样；挂在外层容器上，加载完成前也能读到 */
    contentDescription: String? = null,
    /** 已知目标采样边长；传入时首帧直接触发加载，不用等待 onSizeChanged 布局回调，消除首帧空白延迟 */
    targetDim: Int? = null,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val initialDim = targetDim?.coerceIn(64, 2048)
    val maxDim = initialDim ?: maxOf(size.width, size.height).takeIf { it > 0 }?.coerceIn(64, 2048)
    val requestUrl = url?.takeIf { it.isNotBlank() }
    val requestKey = requestUrl?.let { ImageFetch.cacheKey(it) }
    // produceState 的 key 只重启协程，不会重置已记住的 value；换 URL 时必须另建状态。
    val bmp by key(requestKey, maxDim) {
        produceState<ImageBitmap?>(if (requestUrl != null && maxDim != null) Images.cached(requestUrl, maxDim) else null) {
            if (value == null && requestUrl != null && maxDim != null) {
                val loaded = Images.load(requestUrl, maxDim)
                if (requestKey == ImageFetch.cacheKey(requestUrl)) value = loaded
            }
        }
    }
    val desc = contentDescription
    val semanticsModifier = if (desc != null) Modifier.semantics { this.contentDescription = desc } else Modifier
    Box(modifier.onSizeChanged { if (targetDim == null) size = it }.then(semanticsModifier)) {
        Box(if (placeholder && bmp == null) Modifier.matchParentSize().background(Gh.c.neutralMuted) else Modifier.matchParentSize()) {
            bmp?.let { Image(it, null, Modifier.matchParentSize(), contentScale = contentScale) }
        }
    }
}

@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier, square: Boolean = false, contentDescription: String? = null) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(40)
    val shape = if (square) RoundedCornerShape(size / 5) else CircleShape
    NetImage(
        url?.takeIf { it.isNotBlank() }?.let { sizedAvatar(it, px) },
        modifier.size(size).clip(shape).border(1.dp, Gh.c.borderMuted, shape),
        contentDescription = contentDescription,
        targetDim = px,
    )
}

/** 支持双指缩放、双击放大的图片查看 */
@Composable
fun ZoomImage(url: String, modifier: Modifier = Modifier) {
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        if (s <= 1f || box.width <= 0 || box.height <= 0) return Offset.Zero
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    val state = rememberTransformableState { zoom, pan, _ ->
        val ns = (scale * zoom).coerceIn(1f, 8f)
        scale = ns
        offset = clamp(offset + pan, ns)
    }
    Box(
        modifier
            .onSizeChanged { box = it; offset = clamp(offset, scale) }
            .pointerInput(url) {
                detectTapGestures(
                    // 单击进入全屏查看器（可保存、分享）
                    onTap = { ImageViewer.open(url) },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    },
                )
            }
            .transformable(state),
    ) {
        NetImage(
            url,
            Modifier.matchParentSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
            contentScale = ContentScale.Fit,
            placeholder = false,
        )
    }
}
