package com.mobilegh.data

import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * README / Issue 里的视频和超大 GIF：不进图片缓存、不走镜像，直接从源站流式转发，并保留 Range。
 *
 * 图片链路（[ImageFetch]）会把整个文件读进内存，单个上限 16 MB，还套着 20 秒的总超时，
 * 视频动辄几十 MB，于是要么一直转圈，要么直接 404。播放器则依赖 Range 请求来边下边播和拖动进度，
 * 所以这里必须把 Range 原样转给源站，再把 206 和 Content-Range 原样还给播放器。
 */
object MediaProxy {
    private val VIDEO_EXT = Regex("\\.(mp4|webm|mov|m4v|ogv)([?#]|$)", RegexOption.IGNORE_CASE)

    /** 视频扩展名，或播放器发出的 Range 请求（图片不会带 Range），都走流式转发。 */
    fun wants(url: String, range: String?): Boolean = range != null || VIDEO_EXT.containsMatchIn(url)

    private val JWT_IN_URL = Regex("jwt=([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)")

    /**
     * 网页里是否含有已过期的一次性媒体令牌。GitHub 给 README 里的图片 / 视频配的地址带短期 JWT，
     * 缓存的旧 HTML 会让视频请求 404；此时应当重新取一次 README。
     */
    internal fun hasExpiredToken(html: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        JWT_IN_URL.findAll(html).any { m -> tokenExpiry(m.groupValues[1])?.let { it <= nowSeconds } ?: false }

    /** 解析 JWT 里的 exp（秒）；格式不对时返回 null */
    internal fun tokenExpiry(jwt: String): Long? {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        val json = runCatching { String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8) }.getOrNull() ?: return null
        return Regex("\"exp\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    /** 视频扩展名的链接（如 `<a href="x.mp4">`）：点开时交给原生播放器，而不是当作网页。 */
    fun isVideoLink(url: String): Boolean = VIDEO_EXT.containsMatchIn(url)

    /** 流式返回的上游响应：[body] 由调用方读完或关闭，关闭即断开与源站的连接。 */
    class Stream(
        val code: Int,
        val reason: String,
        val mime: String?,
        val headers: Map<String, String>,
        val body: InputStream,
    )

    /**
     * 不设整体超时（视频可能播很久），只限制连接和单次读取的等待。
     * 不经过 [AppNetwork] 的节点路由：视频直连源站，镜像往往不支持 Range，也会限速。
     */
    private val http by lazy {
        Api.http.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 失败（网络错误、404 等）返回 null，由调用方决定给 WebView 什么。 */
    fun open(url: String, range: String?): Stream? {
        val request = okhttp3.Request.Builder().url(url).header("Accept", "*/*").apply {
            if (range != null) header("Range", range)
        }.build()
        val resp = runCatching { http.newCall(request).execute() }.getOrNull()
        if (resp == null) {
            AppLog.warn("media", tr("视频请求失败：") + url.substringBefore('?').take(120))
            return null
        }
        if (!resp.isSuccessful && resp.code != 206) {
            AppLog.warn("media", trf("视频请求返回 {0}：{1}", resp.code, url.substringBefore('?').take(120)))
            resp.close()
            return null
        }
        AppLog.info("media", trf("视频流式转发 {0} range={1}", resp.code, range ?: "-"))
        val keep = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified")
        val headers = buildMap {
            keep.forEach { name -> resp.header(name)?.let { put(name, it) } }
            put("Cache-Control", "max-age=600")
            put("Access-Control-Allow-Origin", "*")
        }
        return Stream(
            code = resp.code,
            reason = resp.message.ifBlank { if (resp.code == 206) "Partial Content" else "OK" },
            mime = resp.header("Content-Type")?.substringBefore(';')?.trim(),
            headers = headers,
            body = resp.body.byteStream(),
        )
    }
}
