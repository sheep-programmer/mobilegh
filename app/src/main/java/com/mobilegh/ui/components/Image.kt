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
import com.mobilegh.data.Api
import com.mobilegh.data.Net
import com.mobilegh.data.await
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 极简图片加载器：内存 LRU + OkHttp 磁盘缓存 + 采样解码。替代 Coil/Glide 以保持轻量。 */
object Images {
    private val mem = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }

    fun cached(url: String): ImageBitmap? = mem.get(url)

    suspend fun load(url: String, maxDim: Int = 2048): ImageBitmap? {
        mem.get(url)?.let { return it }
        for (u in Net.rawCandidates(url)) {
            fetch(u, maxDim)?.let { mem.put(url, it); return it }
        }
        return null
    }

    private suspend fun fetch(url: String, maxDim: Int): ImageBitmap? {
        return try {
            val resp = Api.http.newCall(Request.Builder().url(url).build()).await()
            withContext(Dispatchers.IO) {
                resp.use {
                    if (!it.isSuccessful) return@withContext null
                    val bytes = it.body.bytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.asImageBitmap()
                }
            }
        } catch (e: Exception) {
            null
        }
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
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
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
