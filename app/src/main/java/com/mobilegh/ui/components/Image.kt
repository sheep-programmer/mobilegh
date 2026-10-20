package com.mobilegh.ui.components

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mobilegh.data.ImageFetch
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

    suspend fun load(url: String, maxDim: Int = 2048): ImageBitmap? {
        cached(url, maxDim)?.let { return it }
        val memKey = key(url, maxDim)
        val mine = CompletableDeferred<ImageBitmap?>()
        val running = synchronized(decoding) { decoding.getOrPut(memKey) { mine } }
        if (running !== mine) return running.await()
        try {
            val bmp = withContext(Dispatchers.IO) {
                val r = ImageFetch.fetch(url) ?: return@withContext null
                decode(r.bytes, maxDim)?.also { if (memKey == key(url, maxDim)) mem.put(memKey, it) }
            }
            mine.complete(bmp)
            return bmp
        } catch (t: Throwable) {
            mine.complete(null)
            throw t
        } finally {
            synchronized(decoding) { decoding.remove(memKey) }
        }
    }

    /** 采样解码，避免大图直接撑爆内存 */
    fun decode(bytes: ByteArray, maxDim: Int): ImageBitmap? = try {
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
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val maxDim = maxOf(size.width, size.height).takeIf { it > 0 }?.coerceIn(64, 2048)
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
    Box(modifier.onSizeChanged { size = it }) {
        Box(if (placeholder && bmp == null) Modifier.matchParentSize().background(Gh.c.neutralMuted) else Modifier.matchParentSize()) {
            bmp?.let { Image(it, null, Modifier.matchParentSize(), contentScale = contentScale) }
        }
    }
}

@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier, square: Boolean = false) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(40)
    val shape = if (square) RoundedCornerShape(size / 5) else CircleShape
    NetImage(
        url?.takeIf { it.isNotBlank() }?.let { sizedAvatar(it, px) },
        modifier.size(size).clip(shape).border(1.dp, Gh.c.borderMuted, shape),
    )
}

/** 支持双指缩放、双击放大的图片查看 */
@Composable
fun ZoomImage(url: String, modifier: Modifier = Modifier) {
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan * scale
    }
    Box(
        modifier
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
