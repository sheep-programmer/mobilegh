package com.mobilegh.data

import android.util.LruCache
import android.os.SystemClock
import okhttp3.CacheControl
import okhttp3.Dispatcher
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 统一的图片下载：README 里的图片、头像、文件预览、图片查看器和「保存图片」都走这里。
 *
 * - 依次尝试 [Net.imageCandidates] 给出的所有节点，按「这类图片上成功过的节点」优先
 * - 校验返回内容确实是图片：有些镜像出错时会返回 200 + HTML 页面，直接交给 WebView 会显示裂图
 * - 内存里缓存原始字节，查看器和保存不用重新下载
 * - 同一张图同时被多处请求时只下载一次；刚失败过的图片短时间内不再重试
 */
object ImageFetch {
    class Result(val bytes: ByteArray, val mime: String?, val fetchedAt: Long = SystemClock.elapsedRealtime())
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    /** 失败后短时间内不重试，避免 WebView 重排或列表回滚时对同一张图反复跑完整条节点链 */
    private const val FAIL_TTL_MS = 45 * 1000L

    /**
     * 竞速窗口：同时最多请求几个候选节点。
     * 窗口太小会重新排长队，太大则一张图片就打满镜像的并发额度。
     */
    private const val RACE_WINDOW = 3
    private const val MAX_NODE_MEMO = 1024

    /**
     * 单个节点不能卡太久，否则一个坏节点会拖住整页图片。
     * 图片都不大，正常节点一两秒内就有响应，超时宁可换下一个节点。
     */
    private val http by lazy {
        Api.http.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            // 列表页会同时出现十几张头像；默认「每主机 5 个并发」会把它们排成一条长队。
            .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
            .build()
    }

    private val cache = object : LruCache<String, Result>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Result) = value.bytes.size.coerceAtLeast(1)
    }

    /** 地址 → 失败时间；只保留最近一小段时间，网络恢复后自动重试 */
    private val failed = LruCache<String, Long>(256)

    /** 这张图上次是从哪个节点拿到的。字节缓存被淘汰后重下时直接命中正确的节点 */
    private val nodeMemo = object : LruCache<String, String>(MAX_NODE_MEMO) {}

    private class InFlight {
        private val latch = CountDownLatch(1)
        @Volatile var result: Result? = null
        fun await(): Result? {
            runCatching { latch.await(25, TimeUnit.SECONDS) }
            return result
        }
        fun done() = latch.countDown()
    }

    private val inFlight = HashMap<String, InFlight>()

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
        if (!force) {
            cached(target)?.let { return it }
            if (recentlyFailed(key)) return null
        }
        val own = InFlight()
        // 同一张图被 Compose 与 WebView 同时请求时共用一次下载
        if (!force) {
            val running = synchronized(inFlight) { inFlight.getOrPut(key) { own } }
            if (running !== own) return running.await()
        }
        try {
            val r = fetchNetwork(target, force)
            own.result = r
            if (r != null) {
                failed.remove(key)
                cache.put(key, r)
            } else {
                failed.put(key, SystemClock.elapsedRealtime())
                AppLog.warn("image", "图片加载失败：" + target.substringBefore('?').take(200))
            }
            return r
        } finally {
            if (!force) synchronized(inFlight) { inFlight.remove(key) }
            own.done()
        }
    }

    private fun recentlyFailed(key: String): Boolean {
        val at = failed.get(key) ?: return false
        if (SystemClock.elapsedRealtime() - at > FAIL_TTL_MS) {
            failed.remove(key)
            return false
        }
        return true
    }

    private fun fetchNetwork(target: String, force: Boolean): Result? {
        val key = cacheKey(target)
        val token = Session.token
        val preferred = nodeMemo.get(key)
        val candidates = Net.imageCandidates(target).sortedBy { if (preferred != null && it.first == preferred) 0 else 1 }
        val winner = race(target, candidates, force) ?: return null
        val (node, outcome) = winner
        val r = outcome.result ?: return null
        if (key != cacheKey(target) || token != Session.token) return null
        Net.markImageOk(target, node)
        if (node != Net.DIRECT.id) nodeMemo.put(key, node)
        return r
    }

    private class Outcome(val result: Result?, val skipNode: Boolean)

    /**
     * 候选节点并发竞速，谁先返回有效图片就用谁；某个节点失败后立刻补上下一个，
     * 同时最多 [RACE_WINDOW] 个请求。
     *
     * README 图片、头像往往分布在多个镜像上，单个节点动辄 1–2 秒才给出 403/404；
     * 串行尝试会让第一张图等上好几秒。竞速把等待压到「最快那个可用节点」的时间。
     */
    private fun race(
        target: String,
        candidates: List<Pair<String, String>>,
        force: Boolean,
    ): Pair<String, Outcome>? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) {
            val (node, url) = candidates[0]
            val outcome = execute(target, node, url, force)
            return if (outcome.result != null) node to outcome else null
        }
        val winner = java.util.concurrent.atomic.AtomicReference<Pair<String, Outcome>?>(null)
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val pending = java.util.concurrent.atomic.AtomicInteger(0)
        val done = CountDownLatch(1)
        val calls = java.util.Collections.synchronizedList(ArrayList<okhttp3.Call>())

        fun finishIfExhausted() {
            if (winner.get() == null && pending.get() == 0 && next.get() >= candidates.size) done.countDown()
        }

        lateinit var settle: (String, Outcome, Boolean) -> Unit
        val launch = object : Runnable {
            override fun run() {
                if (winner.get() != null) return
                val index = next.getAndIncrement()
                if (index >= candidates.size) {
                    finishIfExhausted()
                    return
                }
                val (node, url) = candidates[index]
                val call = newCall(url, force)
                pending.incrementAndGet()
                calls += call
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        settle(node, Outcome(null, skipNode = true), call.isCanceled())
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        val outcome = runCatching { response.use { parse(it, url) } }
                            .getOrElse { Outcome(null, skipNode = true) }
                        settle(node, outcome, call.isCanceled())
                    }
                })
            }
        }

        settle = settle@{ node, outcome, canceled ->
            pending.decrementAndGet()
            if (outcome.result != null) {
                if (winner.compareAndSet(null, node to outcome)) {
                    synchronized(calls) { calls.toList() }.forEach { runCatching { it.cancel() } }
                    done.countDown()
                }
                return@settle
            }
            if (!canceled && outcome.skipNode && node != Net.DIRECT.id) {
                // 404/410 只说明这张图在这个地址不存在，不代表节点不可用
                Net.markImageFail(target, node)
            }
            // 这个节点没成，立刻补上下一个候选
            if (!canceled && winner.get() == null && next.get() < candidates.size) launch.run()
            finishIfExhausted()
        }

        repeat(minOf(RACE_WINDOW, candidates.size)) { launch.run() }
        runCatching { done.await(25, TimeUnit.SECONDS) }
        return winner.get()
    }

    /** 只有单个候选时走同步请求，省掉一次线程切换 */
    private fun execute(target: String, node: String, url: String, force: Boolean): Outcome =
        runCatching { newCall(url, force).execute().use { parse(it, url) } }.getOrElse {
            Outcome(null, skipNode = true)
        }.also {
            if (it.result == null && it.skipNode && node != Net.DIRECT.id) Net.markImageFail(target, node)
        }

    private fun newCall(url: String, force: Boolean) = http.routed().newCall(
        Request.Builder().url(url).apply { if (force) cacheControl(CacheControl.FORCE_NETWORK) }.build(),
    )

    private fun parse(resp: okhttp3.Response, url: String): Outcome {
        if (!resp.isSuccessful) return Outcome(null, resp.code != 404 && resp.code != 410)
        val type = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && (type.startsWith("text/html") || type.startsWith("application/json"))) {
            return Outcome(null, true)
        }
        if (resp.body.contentLength() > MAX_BYTES) return Outcome(null, true)
        val bytes = resp.body.byteStream().use { ImageContent.readLimited(it, MAX_BYTES) } ?: return Outcome(null, true)
        if (bytes.isEmpty() || looksLikeHtml(bytes)) return Outcome(null, true)
        val mime = mimeOf(url, bytes) ?: return Outcome(null, true)
        return Outcome(Result(bytes, mime), false)
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
