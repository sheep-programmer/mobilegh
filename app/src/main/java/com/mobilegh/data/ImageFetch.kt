package com.mobilegh.data

import android.util.LruCache
import android.os.SystemClock
import okhttp3.CacheControl
import okhttp3.Dispatcher
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.mobilegh.i18n.tr

/**
 * 统一的图片下载：README 里的图片、头像、文件预览、图片查看器和「保存图片」都走这里。
 *
 * - 依次尝试 [Net.imageCandidates] 给出的所有节点，按「这类图片上成功过的节点」优先
 * - 校验返回内容确实是图片：有些镜像出错时会返回 200 + HTML 页面，直接交给 WebView 会显示裂图
 * - 内存里缓存原始字节，查看器和保存不用重新下载
 * - 增加持久化磁盘缓存：下载过的图片与图标跨界面、跨重启瞬间直出（0ms）
 * - 优化首轮竞速：直连 (DIRECT) 总是参与前排并发竞速，网络良好时无需白等镜像队列
 * - 同一张图同时被多处请求时只下载一次；刚失败过的图片短时间内不再重试
 */
object ImageFetch {
    class Result(val bytes: ByteArray, val mime: String?, val fetchedAt: Long = SystemClock.elapsedRealtime())
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    /** 失败后短时间内不重试，避免 WebView 重排或列表回滚时对同一张图反复跑完整条节点链 */
    private const val FAIL_TTL_MS = 10 * 1000L

    /**
     * 竞速窗口：同时最多请求几个候选节点。
     * 窗口太小会重新排长队，太大则一张图片就打满镜像的并发额度。
     */
    private const val RACE_WINDOW = 4
    private const val MAX_NODE_MEMO = 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var explicitDiskDir: File? = null

    private const val MAX_DISK_CACHE_BYTES = 80L * 1024 * 1024 // 80 MB
    private var lastTrimTime = 0L

    fun init(cacheDir: File) {
        explicitDiskDir = File(cacheDir, "image_cache").also { if (!it.exists()) it.mkdirs() }
    }

    private fun resolveDiskDir(): File? {
        val d = explicitDiskDir ?: runCatching { Api.cacheDir }.getOrNull()?.let { File(it, "image_cache") }
        if (d != null && !d.exists()) {
            runCatching { d.mkdirs() }
        }
        return d?.takeIf { it.exists() && it.isDirectory }
    }

    private fun diskKey(url: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun readDiskCache(target: String): Result? {
        val dir = resolveDiskDir() ?: return null
        val file = File(dir, diskKey(target))
        if (!file.exists() || !file.isFile) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 2) return null
            val mimeLen = bytes[0].toInt() and 0xFF
            if (bytes.size < 1 + mimeLen) return null
            val mime = if (mimeLen > 0) String(bytes, 1, mimeLen, Charsets.UTF_8) else null
            val imgBytes = bytes.copyOfRange(1 + mimeLen, bytes.size)
            file.setLastModified(System.currentTimeMillis())
            Result(imgBytes, mime)
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeDiskCache(target: String, result: Result) {
        val dir = resolveDiskDir() ?: return
        try {
            val mimeBytes = (result.mime ?: "").toByteArray(Charsets.UTF_8)
            if (mimeBytes.size > 255) return
            val total = 1 + mimeBytes.size + result.bytes.size
            if (total > MAX_BYTES) return
            val buf = ByteArray(total)
            buf[0] = mimeBytes.size.toByte()
            System.arraycopy(mimeBytes, 0, buf, 1, mimeBytes.size)
            System.arraycopy(result.bytes, 0, buf, 1 + mimeBytes.size, result.bytes.size)
            val file = File(dir, diskKey(target))
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeBytes(buf)
            tmp.renameTo(file)
            trimDiskCacheIfNeeded(dir)
        } catch (_: Throwable) {
        }
    }

    private fun trimDiskCacheIfNeeded(dir: File) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrimTime < 60_000L) return
        lastTrimTime = now
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
            var total = files.sumOf { it.length() }
            if (total > MAX_DISK_CACHE_BYTES) {
                val sorted = files.sortedBy { it.lastModified() }
                for (f in sorted) {
                    val len = f.length()
                    if (f.delete()) total -= len
                    if (total <= MAX_DISK_CACHE_BYTES * 0.75) break
                }
            }
        }
    }

    /**
     * 单个节点不能卡太久，否则一个坏节点会拖住整页图片。
     * 图片都不大，正常节点一两秒内就有响应，超时宁可换下一个节点。
     */
    private val http by lazy {
        Api.http.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
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
        val target = normalize(url)
        val key = cacheKey(target)
        val result = cache.get(key)
        if (result != null) {
            if (SystemClock.elapsedRealtime() - result.fetchedAt > CACHE_TTL_MS) {
                cache.remove(key)
            } else {
                return result
            }
        }
        val diskRes = readDiskCache(target)
        if (diskRes != null) {
            cache.put(key, diskRes)
            return diskRes
        }
        return null
    }

    /**
     * 阻塞调用，必须在后台线程执行。
     *
     * [background] 为 true 时是页面打开时的静默预取：它失败不写入负缓存，也不和界面请求共用同一次下载。
     * 以前预取一旦失败，界面在接下来 10 秒里都会直接拿到「失败」，徽章等图片只能手动重新加载才出来。
     */
    fun fetch(url: String, force: Boolean = false, background: Boolean = false): Result? {
        val target = normalize(url)
        val key = cacheKey(target)
        if (!force) {
            cached(target)?.let { return it }
            if (recentlyFailed(key)) return null
        } else {
            failed.remove(key)
            cache.remove(key)
        }
        val shared = !force && !background
        val own = InFlight()
        // 同一张图被 Compose 与 WebView 同时请求时共用一次下载
        if (shared) {
            val running = synchronized(inFlight) { inFlight.getOrPut(key) { own } }
            if (running !== own) return running.await()
        }
        try {
            val r = fetchNetwork(target, force)
            own.result = r
            if (r != null) {
                failed.remove(key)
                cache.put(key, r)
                writeDiskCache(target, r)
            } else if (!background) {
                failed.put(key, SystemClock.elapsedRealtime())
                AppLog.warn("image", tr("图片加载失败：") + target.substringBefore('?').take(200))
            }
            return r
        } finally {
            if (shared) synchronized(inFlight) { inFlight.remove(key) }
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

    /** 标记地址最近加载失败（测试或外部明确知道不可达时使用） */
    fun markFailed(url: String) {
        val key = cacheKey(normalize(url))
        failed.put(key, SystemClock.elapsedRealtime())
    }

    private fun fetchNetwork(target: String, force: Boolean): Result? {
        val key = cacheKey(target)
        val token = Session.token
        val preferred = nodeMemo.get(key)
        val candidates = prioritizeRaceCandidates(Net.imageCandidates(target), preferred)
        val winner = race(target, candidates, force)
        val r = winner?.second?.result ?: autoRetryFallback(target, force) ?: return null
        val winningNode = winner?.first ?: Net.GITHUB_API_ID
        if (key != cacheKey(target) || token != Session.token) return null
        Net.markImageOk(target, winningNode)
        if (winningNode != Net.DIRECT.id) nodeMemo.put(key, winningNode)
        return r
    }

    /**
     * 自动内部回退重试：当常规竞速因网络抖动、公共镜像临时故障全部未命中时，
     * 在直接向用户报错/显示裂图前，主动尝试官方 API 原始端点与直连，
     * 彻底避免偶发网络波动导致用户必须手动点击重试。
     */
    private fun autoRetryFallback(target: String, force: Boolean): Result? {
        val raw = Net.parseRaw(target)
        if (raw != null) {
            val apiUrl = Net.githubApiContentsUrl(raw)
            val apiOutcome = execute(target, Net.GITHUB_API_ID, apiUrl, force = true)
            if (apiOutcome.result != null) return apiOutcome.result
        }
        val directOutcome = execute(target, Net.DIRECT.id, target, force = true)
        return directOutcome.result
    }

    /**
     * 将直连 (DIRECT) 及官方 API (GITHUB_API) 纳入前排首轮竞速窗口中，
     * 确保在直连可用或官方 API 可用时，能够在首轮 100~300ms 内瞬间胜出返回，
     * 免去在长队后面白白等待几十个代理超时。
     */
    internal fun prioritizeRaceCandidates(
        candidates: List<Pair<String, String>>,
        preferred: String?,
    ): List<Pair<String, String>> {
        if (candidates.size <= 2) return candidates
        val result = candidates.sortedBy { if (preferred != null && it.first == preferred) 0 else 1 }.toMutableList()
        val directIdx = result.indexOfFirst { it.first == Net.DIRECT.id }
        if (directIdx >= RACE_WINDOW) {
            val direct = result.removeAt(directIdx)
            val insertPos = if (preferred != null && result.isNotEmpty() && result[0].first == preferred) 1 else 0
            result.add(insertPos.coerceAtMost(result.size), direct)
        }
        val apiIdx = result.indexOfFirst { it.first == Net.GITHUB_API_ID }
        if (apiIdx >= RACE_WINDOW) {
            val api = result.removeAt(apiIdx)
            val insertPos = if (result.size > 1) 1 else result.size
            result.add(insertPos, api)
        }
        return result
    }

    /** 后台静默预加载图片到磁盘与内存缓存 */
    fun prefetch(url: String) {
        if (url.isBlank()) return
        val target = normalize(url)
        if (cached(target) != null) return
        scope.launch {
            runCatching { fetch(target, background = true) }
        }
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

    private fun isGitHubApiUrl(url: String): Boolean {
        val host = ImageContent.parseUrl(url)?.first ?: return false
        return host == "api.github.com" || host == Net.apiHost
    }

    private fun newCall(url: String, force: Boolean): okhttp3.Call {
        val b = Request.Builder().url(url)
        if (force) b.cacheControl(CacheControl.FORCE_NETWORK)
        if (isGitHubApiUrl(url)) {
            b.header("Accept", "application/vnd.github.raw")
        }
        return http.routed().newCall(b.build())
    }

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
