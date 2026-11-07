package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 视频播放的线路选择。
 *
 * 播放器按 Range 流式读取，转发类加速节点同样支持 Range（实测返回 206），所以视频也可以走加速。
 * 做法：只对 [Net.imageCandidates] 允许加速的地址（公开的 github.com 附件、raw 文件）并行探测前几个
 * 加速节点，第一个能按 Range 返回 206 的就用它；都不行或地址不允许加速时用原地址。
 * 签名地址（private-user-images 的 JWT）永远直连，与图片的策略一致（见 [Net.requiresDirectImage]）。
 */
object VideoRoute {
    private const val PROBE_LIMIT = 3
    private const val PROBE_TIMEOUT_SECONDS = 6L

    /** 探测用的客户端：不带 Token 拦截器，只走应用的线路设置 */
    private val probeClient by lazy {
        OkHttpClient.Builder().proxySelector(AppNetwork.proxySelector())
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
            .routed()
    }

    /** 返回播放器应当加载的地址：找到可用的加速节点就用它，否则返回 [src] 原样 */
    suspend fun pick(src: String): String = withContext(Dispatchers.IO) {
        val proxies = Net.imageCandidates(src).filter { it.first != Net.DIRECT.id }.take(PROBE_LIMIT)
        if (proxies.isEmpty()) return@withContext src
        val ok = coroutineScope {
            proxies.map { (_, url) -> async(Dispatchers.IO) { canRange(url) } }.map { it.await() }
        }
        // 按候选顺序取第一个可用的：顺序已经是「上次成功 → 测速快 → 其它」
        proxies.indices.firstOrNull { ok[it] }?.let { proxies[it].second } ?: src
    }

    /** 能否按 Range 取到部分内容（206）。只取 1 个字节，失败或超时都视为不可用 */
    private fun canRange(url: String): Boolean = try {
        probeClient.newCall(
            Request.Builder().url(url)
                .header("Range", "bytes=0-0")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "MobileGH")
                .build(),
        ).execute().use { it.code == 206 }
    } catch (e: Exception) {
        false
    }
}
