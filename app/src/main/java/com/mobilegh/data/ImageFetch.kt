package com.mobilegh.data

import android.util.LruCache
import android.os.SystemClock
import okhttp3.Request
import okhttp3.CacheControl
import java.util.concurrent.TimeUnit

/**
 * 统一的图片下载：README 里的图片、头像、文件预览、图片查看器和「保存图片」都走这里。
 *
 * - 依次尝试 [Net.imageCandidates] 给出的所有节点，记住成功的节点
 * - 校验返回内容确实是图片：有些镜像出错时会返回 200 + HTML 页面，直接交给 WebView 会显示裂图
 * - 内存里缓存原始字节，查看器和保存不用重新下载
 */
object ImageFetch {
    class Result(val bytes: ByteArray, val mime: String?, val fetchedAt: Long = SystemClock.elapsedRealtime())
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    /** 单个节点不能卡太久，否则一个坏节点会拖住整页图片 */
    private val http by lazy {
        Api.http.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val cache = object : LruCache<String, Result>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Result) = value.bytes.size.coerceAtLeast(1)
    }

    /** 仓库内的图片链接（github.com/o/r/blob|raw/ref/path）统一转成 raw 地址，便于走加速节点 */
    fun normalize(url: String): String = ImageContent.normalize(url)

    fun cacheKey(url: String): String = "${Session.generation}:${normalize(url)}"

    fun cached(url: String): Result? {
        val key = cacheKey(url)
        val result = cache.get(key) ?: return null
        if (SystemClock.elapsedRealtime() - result.fetchedAt > CACHE_TTL_MS) {
            cache.remove(key)
            return null
        }
        return result
    }

    /** 阻塞调用，必须在后台线程执行 */
    fun fetch(url: String, force: Boolean = false): Result? {
        val target = normalize(url)
        val key = cacheKey(target)
        val token = Session.token
        if (force) cache.remove(key) else cached(target)?.let { return it }
        for ((node, candidate) in Net.imageCandidates(target)) {
            val r = runCatching { get(candidate, force) }.getOrNull()
            if (r != null) {
                if (key != cacheKey(target) || token != Session.token) return null
                Net.markImageOk(node)
                cache.put(key, r)
                return r
            }
            if (node != Net.DIRECT.id) Net.markImageFail(node)
        }
        AppLog.warn("image", "图片加载失败：" + target.substringBefore('?').take(200))
        return null
    }

    private fun get(url: String, force: Boolean): Result? =
        http.newCall(Request.Builder().url(url).apply { if (force) cacheControl(CacheControl.FORCE_NETWORK) }.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val type = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            if (type != null && (type.startsWith("text/html") || type.startsWith("application/json"))) return null
            if (resp.body.contentLength() > MAX_BYTES) return null
            val bytes = resp.body.byteStream().use { ImageContent.readLimited(it, MAX_BYTES) } ?: return null
            if (bytes.isEmpty() || looksLikeHtml(bytes)) return null
            val mime = mimeOf(url, bytes) ?: return null
            Result(bytes, mime)
        }

    private fun looksLikeHtml(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 64), Charsets.ISO_8859_1).trimStart().lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    /** 由扩展名或文件头判断类型；raw 地址有时返回 text/plain 或 octet-stream */
    fun mimeOf(url: String, bytes: ByteArray? = null): String? = ImageContent.mimeOf(url, bytes)

    fun extOf(mime: String?): String = when (mime) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        "image/avif" -> "avif"
        "image/bmp" -> "bmp"
        else -> "png"
    }
}
