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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mobilegh.data.ImageFetch
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 极简图片加载器：内存 LRU + OkHttp 磁盘缓存 + 采样解码。替代 Coil/Glide 以保持轻量。 */
object Images {
    private val mem = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }

    fun cached(url: String): ImageBitmap? = if (ImageFetch.cached(url) != null) mem.get(ImageFetch.cacheKey(url)) else null

    suspend fun load(url: String, maxDim: Int = 2048): ImageBitmap? {
        cached(url)?.let { return it }
        val key = ImageFetch.cacheKey(url)
        return withContext(Dispatchers.IO) {
            val r = ImageFetch.fetch(url) ?: return@withContext null
            decode(r.bytes, maxDim)?.also { if (key == ImageFetch.cacheKey(url)) mem.put(key, it) }
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

@Composable
fun NetImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Boolean = true,
) {
    val bmp by produceState(url?.let { Images.cached(it) }, url) {
        if (value == null && url != null) value = Images.load(url)
    }
    Box(if (placeholder && bmp == null) modifier.background(Gh.c.neutralMuted) else modifier) {
        bmp?.let { Image(it, null, Modifier.matchParentSize(), contentScale = contentScale) }
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
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan * scale
    }
    Box(
        modifier
            .pointerInput(Unit) {
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
