package com.mobilegh.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.mobilegh.service.DownloadService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.mobilegh.ui.components.toast
import java.io.File
import java.util.UUID
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

@Serializable
enum class DownloadState { Preparing, Queued, Running, Waiting, Complete, Failed, Cancelled }

/**
 * 下载失败的原因是否值得换一个节点重试。
 *
 * 空间不足、文件已存在、存储设备不可用、写入失败这类换哪个节点都一样；
 * 401/403/404/407/451 说明资源本身不可取，反复换节点只会把失败拖得更久。
 * 其余（连接中断、超时、HTTP 数据错误、跳转过多、5xx、其他 4xx）都换下一个节点试。
 */
fun retryableDownloadFailure(reason: Int): Boolean = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE,
    DownloadManager.ERROR_FILE_ALREADY_EXISTS,
    DownloadManager.ERROR_DEVICE_NOT_FOUND,
    DownloadManager.ERROR_FILE_ERROR,
    -> false
    in 400..599 -> reason !in setOf(401, 403, 404, 407, 451)
    else -> true
}

/** 下载记录最多保留的条数（不含进行中的任务） */
internal const val HISTORY_KEEP = 20

/** 超出保留数量、需要从列表中移出的较早记录（纯函数，便于单测）；列表按新到旧排列 */
internal fun historyOverflow(tasks: List<DownloadTask>, keep: Int = HISTORY_KEEP): List<DownloadTask> =
    tasks.filter { !it.active }.drop(keep)

/**
 * 同时提交给 DownloadManager 的下载上限。DownloadManager 没有并发设置，由应用控制提交数量；
 * 超出的任务留在应用内排队（状态 Queued、没有 managerId），前面的结束后再提交。
 */
internal const val MAX_CONCURRENT_DOWNLOADS = 3

/** 还能不能再占用一个名额：已占用的数量小于上限（纯函数，便于单测） */
internal fun canStartNext(running: Int, limit: Int = MAX_CONCURRENT_DOWNLOADS): Boolean = running < limit

/** 排队中的一项：只需要 key 和是否暂停 */
internal data class QueueEntry(val key: String, val paused: Boolean)

/**
 * 从排队中挑出本次应该提交的任务（纯函数，便于单测）。
 *
 * - queue：按入队先后排列，最早的在前（先进先出）
 * - running：当前已占用名额的任务数（已提交或正在准备提交）
 * - 已暂停的任务跳过：既不提交，也不占名额，也不挡住后面的任务
 *
 * 返回按队列顺序的 key，数量不超过剩余名额。
 */
internal fun keysToPromote(queue: List<QueueEntry>, running: Int, limit: Int = MAX_CONCURRENT_DOWNLOADS): List<String> {
    val promoted = mutableListOf<String>()
    var slots = running
    for (entry in queue) {
        if (entry.paused) continue
        if (!canStartNext(slots, limit)) break
        promoted += entry.key
        slots++
    }
    return promoted
}

/**
 * 系统报告的失败原因 → 给用户看的说明。原始错误码不显示，只写进日志。
 * HTTP 类（401/403/404/407/429/451/5xx）与私有下载的映射保持一致。
 */
internal fun downloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> tr("存储空间不足，请清理后重试")
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> tr("同名文件已存在，请重试")
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> tr("存储设备不可用")
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> tr("下载地址跳转过多")
    DownloadManager.ERROR_FILE_ERROR -> tr("无法写入文件，请检查下载目录")
    DownloadManager.ERROR_CANNOT_RESUME -> tr("下载中断且无法续传，请重新下载")
    DownloadManager.ERROR_HTTP_DATA_ERROR -> tr("数据传输中断，请检查网络后重试")
    401 -> tr("需要登录 GitHub 后才能下载")
    403, 404 -> tr("没有下载权限，或资源已经删除")
    407 -> tr("网络代理需要认证，请检查代理设置")
    429 -> tr("请求过于频繁，请稍后重试")
    451 -> tr("该资源因法律原因无法下载")
    in 500..599 -> tr("服务器暂时不可用，请稍后重试")
    else -> tr("下载失败，请检查网络后重试")
}

/**
 * 卡住或过慢时界面上的提示。私有下载只有一条直连地址，没有可更换的节点，文案不提「更换节点」。
 */
internal fun stuckDownloadHint(isPrivate: Boolean, slow: Boolean): String = when {
    slow -> tr("下载速度过慢，系统仍在继续；可取消或更换节点")
    isPrivate -> tr("连接 GitHub 没有响应，系统仍在重试；可取消后重新下载")
    else -> tr("节点无响应，系统仍在重试；可取消或更换节点")
}

/** 慢速判定的窗口：连续监控满这么久，平均速度才算数，短时间的波动不算 */
internal const val SLOW_WINDOW_MS = 45_000L

/**
 * 平均速度低于 64 KB/s 视为节点太慢。取值依据：远低于正常加速节点的速度，普通波动不会触发；
 * 而 64 KB/s 下 20 MB 的 APK 要五分钟多，这时换节点通常比继续等划算。
 */
internal const val SLOW_BYTES_PER_SEC = 64L * 1024

/**
 * 进行中的下载是否慢到值得换一个节点（纯函数，便于单测）。
 *
 * - windowMs / windowBytes：连续监控期间累计的时长和新增字节
 * - remainingBytes：还剩多少字节，总大小未知时为 null
 * - hasNextCandidate：是否还有下一个候选节点；私有下载、手动节点、已是最后一个候选时为 false，永不换
 *
 * 剩下的数据按当前速度在一个窗口内就能下完的不换：换节点要从头再下，反而更慢。
 */
internal fun isSlowDownload(windowMs: Long, windowBytes: Long, remainingBytes: Long?, hasNextCandidate: Boolean): Boolean {
    if (!hasNextCandidate || windowMs < SLOW_WINDOW_MS) return false
    val bytesPerSec = windowBytes * 1000.0 / windowMs
    if (bytesPerSec >= SLOW_BYTES_PER_SEC) return false
    if (remainingBytes != null && remainingBytes <= bytesPerSec * SLOW_WINDOW_MS / 1000.0) return false
    return true
}

@Serializable
data class DownloadTask(
    val key: String = UUID.randomUUID().toString(),
    val managerId: Long? = null,
    val url: String,
    val filename: String,
    val isPrivate: Boolean = false,
    val mimeType: String? = null,
    val state: DownloadState = DownloadState.Preparing,
    val downloaded: Long = 0,
    val total: Long = -1,
    val message: String? = null,
    val appDirectory: Boolean = false,
    /** 下载完成后的落地路径。用于打开文件 / 删除本地文件；DownloadManager 的 URI 不可靠。 */
    val filePath: String? = null,
    /** 下载完成后在 MediaStore 中的 URI（Android 10 及以上落在「下载」文件夹时使用） */
    val contentUri: String? = null,
    /** 公开下载的候选地址（按节点排列）：失败后自动换下一个，私有下载为空（只有一条直连地址） */
    val targets: List<DownloadTarget> = emptyList(),
    /** 当前正在使用的候选序号 */
    val attempt: Int = 0,
    /** 本次尝试使用的节点，用于统计哪个节点真的能下载 */
    val nodeId: String? = null,
    val nodeName: String? = null,
    /** 排队中用户点了「暂停」：不提交给 DownloadManager，直到用户继续。只对应用内排队的任务有效 */
    val paused: Boolean = false,
) {
    val active get() = state in setOf(DownloadState.Preparing, DownloadState.Queued, DownloadState.Running, DownloadState.Waiting)
    /** 还在应用内排队、尚未交给 DownloadManager 的任务（没有 managerId） */
    val waitingForSlot get() = state == DownloadState.Queued
    /** 能暂停或继续：排队中、传输中，或已暂停的任务 */
    val canTogglePause get() = state == DownloadState.Queued || state == DownloadState.Running ||
        state == DownloadState.Preparing || (state == DownloadState.Waiting && paused)
    val progress: Float? get() = when {
        state == DownloadState.Complete -> 1f
        total > 0 -> (downloaded.toDouble() / total).toFloat().coerceIn(0f, 0.99f)
        else -> null
    }
}

/** 应用内下载：断点续传引擎（[ResumableTransfer]）负责传输，这里负责任务状态、排队、换节点、历史和文件操作；Token 不会保存。 */
object Downloads {
    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var foreground = false
    private var lastServiceCount = -1
    /** 每个任务正在运行的传输协程，按任务 key 索引 */
    private val transfers = HashMap<String, Job>()
    /** 用户点了暂停、正在取消的任务：取消后保留已下载的部分，状态改为已暂停 */
    private val pausing = HashSet<String>()
    private val privateHttp by lazy { OkHttpClient.Builder().proxySelector(AppNetwork.proxySelector()).followRedirects(false).followSslRedirects(false).build() }
    private val transferHttp by lazy {
        OkHttpClient.Builder().proxySelector(AppNetwork.proxySelector())
            // 读超时 20 秒：网络切换后旧连接会静默失效，太长的超时会让每次重试都卡住很久
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            // 多段并行要同时开多条连接。OkHttp 默认每个主机最多 5 个请求，会把线程数悄悄压到 5，所以这里放开到设置的上限
            // 只用 HTTP/1.1：HTTP/2 会把所有 Range 请求复用到同一条 TCP 连接上，镜像对单条连接限速，
            // 分段就没有并行效果。HTTP/1.1 下每个分块各自建立连接，才能真正并发下载。
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = SegmentPlan.MAX_SEGMENTS * 2
            })
            .connectionPool(ConnectionPool(SegmentPlan.MAX_SEGMENTS * 2, 5, TimeUnit.MINUTES))
            .build()
    }
    private val engine by lazy { ResumableTransfer(transferHttp) }
    /** 没有进度的任务从什么时候开始卡住的（只记内存，用于判断该换节点了） */
    private val stalledSince = HashMap<String, Long>()
    private const val STALL_MS = 20_000L
    /** 每次尝试的速度采样（只记内存）：window* 用于判断慢速，total* 用于算完成时的实测速度 */
    private val paces = HashMap<String, Pace>()
    /** 两次采样间隔超过这个时间，说明监控中断（App 在后台、暂停），这段时间不计入速度 */
    private const val PACE_GAP_MS = 3_000L
    /** 采样时长不足 1 秒时噪声太大，不记录实测速度 */
    private const val MIN_SPEED_SAMPLE_MS = 1_000L
    private const val MONITOR_TICK_MS = 500L

    private class Pace {
        var lastAt = 0L
        var lastBytes = 0L
        var windowMs = 0L
        var windowBytes = 0L
        var totalMs = 0L
        var totalBytes = 0L
    }

    /** 一次传输要用的地址、请求头和节点 */
    private class Source(val url: String, val headers: Map<String, String>, val nodeId: String?, val nodeName: String)

    val tasks = mutableStateListOf<DownloadTask>()
    var selectedKey by mutableStateOf<String?>(null)
    var showHistory by mutableStateOf(false)
    /** 完成或失败、而用户没有在看它的弹窗时记下它；直到用户点开或点 ✕ 才消失，不会自动收起 */
    var finishedKey by mutableStateOf<String?>(null)
    val finishedTask get() = tasks.firstOrNull { it.key == finishedKey && !it.active }
    val activeCount get() = tasks.count { it.active }
    /** 正在传输的任务数；其余未完成的（排队、已暂停）计为排队 */
    val runningCount get() = tasks.count { it.state == DownloadState.Running || it.state == DownloadState.Preparing }
    val queuedCount get() = activeCount - runningCount
    /** 因超出保留数量而从列表移出的历史记录条数（累计，清空历史时归零） */
    var droppedRecords by mutableStateOf(0)
        private set
    /** 仅 Wi-Fi 下载（默认关闭）：开启后计费网络下不开始新的下载，进行中的会继续 */
    var wifiOnly by mutableStateOf(false)
        private set
    /** 单个下载同时使用的连接数（分段数）；1 即单连接续传。设置页可改，默认 [SegmentPlan.DEFAULT_SEGMENTS] */
    var threads by mutableIntStateOf(SegmentPlan.DEFAULT_SEGMENTS)
        private set

    fun init(ctx: Context) {
        context = ctx.applicationContext
        prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        wifiOnly = prefs.getBoolean("wifiOnly", false)
        threads = prefs.getInt("threads", SegmentPlan.DEFAULT_SEGMENTS).takeIf { it in SegmentPlan.THREAD_CHOICES }
            ?: SegmentPlan.DEFAULT_SEGMENTS
        droppedRecords = prefs.getInt("dropped", 0)
        tasks.clear()
        runCatching {
            val stored = prefs.getString("tasks", null) ?: return@runCatching
            tasks.addAll(Api.plain.decodeFromString<List<DownloadTask>>(stored).map {
                when {
                    // 旧版交给系统下载器的记录无法继续，直接提示重新下载
                    it.active && it.managerId != null -> it.copy(state = DownloadState.Failed, message = tr("旧版下载记录已失效，请重新下载"))
                    // 上次正在传输的：进程结束了，保留已下的部分，等用户继续
                    it.state == DownloadState.Running || it.state == DownloadState.Preparing ->
                        it.copy(state = DownloadState.Waiting, paused = true, message = tr("应用已重启，已暂停，可以继续下载"))
                    else -> it
                }
            })
        }
        pump()
    }

    fun changeWifiOnly(on: Boolean) {
        wifiOnly = on
        prefs.edit().putBoolean("wifiOnly", on).apply()
    }

    /** 设置单个下载的连接数；只接受 [SegmentPlan.THREAD_CHOICES] 中的值，其它值忽略 */
    fun changeThreads(n: Int) {
        if (n !in SegmentPlan.THREAD_CHOICES) return
        threads = n
        prefs.edit().putInt("threads", n).apply()
    }

    fun setForeground(visible: Boolean) {
        foreground = visible
        if (visible) pump()
    }

    fun start(rawUrl: String, filename: String?, isPrivate: Boolean, mimeType: String? = null) {
        tasks.firstOrNull { it.url == rawUrl && it.active }?.let { selectedKey = it.key; showHistory = false; return }
        val task = DownloadTask(
            url = rawUrl,
            filename = safeFilename(filename ?: Uri.parse(rawUrl).lastPathSegment ?: "download"),
            isPrivate = isPrivate,
            mimeType = mimeType,
            // 私有下载只有一条解析后的直连地址；公开下载准备好整条节点候选链，失败自动换下一个
            targets = if (isPrivate) emptyList() else Net.downloadTargets(rawUrl),
            // 统一先记为排队，由 pump() 按名额决定是否立即开始
            state = DownloadState.Queued,
        )
        tasks.add(0, task)
        // 超出保留数量的较早记录移出列表，并计数，让界面能说明移掉了多少条
        historyOverflow(tasks).takeIf { it.isNotEmpty() }?.let { overflow ->
            val dropKeys = overflow.map { it.key }.toSet()
            tasks.removeAll { it.key in dropKeys }
            droppedRecords += overflow.size
            prefs.edit().putInt("dropped", droppedRecords).apply()
        }
        selectedKey = task.key
        showHistory = false
        AppLog.info("download", tr("开始下载：") + task.filename + "（" + (if (isPrivate) "private" else "public") + "）")
        save()
        pump()
        if (tasks.firstOrNull { it.key == task.key }?.waitingForSlot == true) {
            AppLog.info("download", tr("同时下载已满，已加入排队：") + task.filename)
        }
    }

    /**
     * 按入队先后补足名额：排队中且未暂停的任务开始传输，直到占满 [MAX_CONCURRENT_DOWNLOADS]。
     * 开始前先把状态改为 Preparing，名额在同一次调用里就已占用，不会重复开始。
     */
    private fun pump() {
        if (!::context.isInitialized) return
        val queue = tasks.asReversed().filter { it.waitingForSlot }
        if (queue.isNotEmpty() && !(wifiOnly && isMeteredNow())) {
            val running = tasks.count { it.state == DownloadState.Preparing || it.state == DownloadState.Running }
            val keys = keysToPromote(queue.map { QueueEntry(it.key, it.paused) }, running)
            for (key in keys) {
                val current = tasks.firstOrNull { it.key == key } ?: continue
                val promoted = current.copy(state = DownloadState.Preparing, paused = false, message = null)
                update(promoted)
                launchTransfer(promoted)
            }
            if (keys.isNotEmpty()) save()
        }
        syncService()
    }

    /** 当前网络是否按流量计费（Wi-Fi 下载开启时用于暂缓新任务） */
    private fun isMeteredNow(): Boolean =
        context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == true

    /** 前台服务只在有进行中的下载时保持，数量变化时更新通知 */
    private fun syncService() {
        val count = tasks.count { it.state == DownloadState.Running || it.state == DownloadState.Preparing }
        if (count == lastServiceCount) return
        lastServiceCount = count
        com.mobilegh.service.DownloadService.sync(context, count)
    }

    private fun launchTransfer(task: DownloadTask) {
        transfers.remove(task.key)?.cancel()
        transfers[task.key] = scope.launch { runTransfer(task.key) }
        startMonitor()
    }

    /** 一次传输：解析地址 → 下载（可续传）→ 完成则落盘，失败则换节点或报错 */
    private suspend fun runTransfer(key: String) {
        val started = tasks.firstOrNull { it.key == key } ?: return
        val source = try {
            withContext(Dispatchers.IO) { resolveSource(started) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            transfers.remove(key)
            update(started.copy(state = DownloadState.Failed, message = e.networkHint() ?: e.message ?: tr("无法开始下载")))
            save(); pump()
            return
        }
        tasks.firstOrNull { it.key == key }?.let { current ->
            if (current.state == DownloadState.Cancelled) return
            update(current.copy(state = DownloadState.Running, nodeId = source.nodeId, nodeName = source.nodeName, message = null))
        }
        AppLog.info("download", started.filename + tr(" 使用节点：") + source.nodeName + tr("；地址主机：") + (Uri.parse(source.url).host ?: "unknown"))
        val partial = partialFile(started)
        val result = try {
            // 大文件先探测 Range 支持：支持就并行分段，更快；否则退回单连接续传
            engine.runParallel(
                source.url, source.headers, partial,
                onProgress = { done, total -> scope.launch { progress(key, done, total) } },
                segments = SegmentedTransfer(transferHttp, maxSegments = threads),
                // 经加速节点下载时，HTML 页面不是文件内容（代理的错误页），必须判为失败而不是存成文件
                rejectHtml = source.nodeId != null && source.nodeId != Net.DIRECT.id,
            )
        } catch (e: CancellationException) {
            onInterrupted(key)
            throw e
        }
        when (result) {
            TransferResult.Complete -> finishTransfer(key, partial)
            is TransferResult.Failed -> failTransfer(key, result)
        }
    }

    private fun progress(key: String, done: Long, total: Long) {
        val current = tasks.firstOrNull { it.key == key } ?: return
        if (current.state != DownloadState.Running) return
        update(current.copy(downloaded = done, total = total))
    }

    /** 传输协程被取消：用户暂停则保留部分文件并改为已暂停；用户取消则删除部分文件。换节点等其它取消不改状态。 */
    private fun onInterrupted(key: String) {
        val current = tasks.firstOrNull { it.key == key } ?: return
        when {
            current.state == DownloadState.Cancelled -> {
                partialFile(current).delete()
                SegmentedTransfer.cleanup(partialFile(current))
            }
            key in pausing -> {
                pausing.remove(key)
                transfers.remove(key)
                update(current.copy(state = DownloadState.Waiting, paused = true, message = tr("已暂停")))
                save(); pump()
            }
        }
    }

    private fun finishTransfer(key: String, partial: File) {
        transfers.remove(key)
        val task = tasks.firstOrNull { it.key == key } ?: return
        scope.launch {
            val published = try {
                withContext(Dispatchers.IO) { publish(task, partial) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn("download", task.filename + "：" + (e.message ?: e.javaClass.simpleName))
                update(task.copy(state = DownloadState.Failed, message = tr("无法保存到「下载」文件夹，请检查存储空间后重试")))
                save(); pump()
                return@launch
            }
            partial.delete()
            val current = tasks.firstOrNull { it.key == key } ?: return@launch
            update(current.copy(
                state = DownloadState.Complete, filename = published.name, filePath = published.filePath,
                contentUri = published.contentUri, downloaded = if (current.total > 0) current.total else current.downloaded,
                message = null, paused = false,
            ))
            DownloadService.notifyFinished(context, published.name, tr("下载完成"))
            save(); pump()
        }
    }

    private fun failTransfer(key: String, result: TransferResult.Failed) {
        transfers.remove(key)
        val task = tasks.firstOrNull { it.key == key } ?: return
        if (task.state == DownloadState.Cancelled) return
        // 任何失败都先换下一个候选节点：代理可能对某个文件返回 404、403 或错误页，而直连还没试过。
        // 节点都试过了才显示错误（部分文件保留，重新下载时从断点继续）
        if (rotate(task, result.message)) return
        AppLog.warn("download", task.filename + "：" + result.message + (result.httpCode?.let { " (HTTP $it)" } ?: ""))
        reportAllFailed(task)
        val message = failureText(result.httpCode, result.retryable)
        update(task.copy(state = DownloadState.Failed, message = message))
        DownloadService.notifyFinished(context, task.filename, message)
        save(); pump()
    }

    /** 失败的说明：按 HTTP 状态给出中文提示，原始信息只写日志 */
    private fun failureText(httpCode: Int?, retryable: Boolean): String = when (httpCode) {
        401 -> tr("GitHub 授权失效，请重新登录")
        403 -> tr("没有下载权限，或资源已经删除")
        404 -> tr("文件不存在或已被删除")
        429 -> tr("请求太频繁，请稍后重试")
        in 500..599 -> tr("服务器暂时不可用，请稍后重试")
        else -> if (retryable) tr("网络中断，可以点击重试从断点继续") else tr("下载失败，请检查网络后重试")
    }

    /** 换用下一个候选节点继续（保留已下的部分）。返回 false 表示没有可换的节点。 */
    private fun rotate(task: DownloadTask, reason: String?): Boolean {
        val next = task.attempt + 1
        // 私有下载、手动节点和已是最后一个候选时不换
        if (task.isPrivate || next >= task.targets.size) return false
        val failed = task.targets[task.attempt]
        val target = task.targets[next]
        stalledSince.remove(task.key)
        paces.remove(task.key)
        AppLog.warn(
            "download",
            task.filename + tr(" 节点 ") + failed.nodeName + tr(" 失败") + (reason?.let { "（$it）" } ?: "") + tr("，改用 ") + target.nodeName + tr("，从断点续传"),
        )
        Net.markDownloadFail(failed.nodeId)
        transfers.remove(task.key)?.cancel()
        val rotating = tasks.firstOrNull { it.key == task.key } ?: return false
        if (rotating.state == DownloadState.Cancelled) return false
        val updated = rotating.copy(
            attempt = next,
            state = DownloadState.Preparing,
            paused = false,
            downloaded = 0,
            message = tr("节点 ") + failed.nodeName + tr(" 不通，正在改用 ") + target.nodeName,
        )
        update(updated)
        save()
        launchTransfer(updated)
        return true
    }

    /** 所有候选都试过之后的收尾：记下失败节点并重新测速，供下次选择参考 */
    private fun reportAllFailed(task: DownloadTask) {
        if (task.targets.size <= 1) return
        task.targets.getOrNull(task.attempt)?.let { Net.markDownloadFail(it.nodeId) }
        AppLog.warn("download", task.filename + tr(" 所有下载节点都失败了"))
        Net.speedTestAsync()
    }

    /** 解析这次传输用的地址、请求头和节点（私有下载需要先换到可直接下载的地址） */
    private fun resolveSource(task: DownloadTask): Source {
        val headers = mutableMapOf("User-Agent" to "MobileGH (Android)")
        if (task.isPrivate) {
            val token = Session.token
            require(!token.isNullOrBlank()) { tr("请先登录 GitHub 后下载") }
            val (url, authorization) = resolvePrivateUrl(task.url, token)
            authorization?.let { headers["Authorization"] = it }
            if (Uri.parse(url).host == "api.github.com") {
                headers["Accept"] = "application/octet-stream"
                headers["X-GitHub-Api-Version"] = "2022-11-28"
            }
            return Source(url, headers, null, tr("直连 GitHub"))
        }
        val target = task.targets.getOrNull(task.attempt)
        val url = target?.url ?: Net.download(task.url)
        return Source(url, headers, target?.nodeId, target?.nodeName ?: Net.downloadNode().name)
    }

    /** 部分下载文件放在应用自己的目录，不需要存储权限；完成后再复制到「下载」文件夹 */
    private fun partialFile(task: DownloadTask): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(File(base, "transfers").apply { mkdirs() }, "${task.key}.part")
    }

    /**
     * 把完成的部分文件放进「下载」文件夹：Android 10 及以上通过 MediaStore 写入（分区存储，不需要额外权限）；
     * 更早的系统直接写入公共下载目录，没有写入权限时退回应用自己的目录。
     */
    private fun publish(task: DownloadTask, partial: File): Published {
        val mime = task.mimeType ?: mimeFor(task.filename) ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, task.filename)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values) ?: error(tr("无法创建下载文件"))
            try {
                val out = context.contentResolver.openOutputStream(uri) ?: error(tr("无法写入下载文件"))
                out.use { stream -> partial.inputStream().use { it.copyTo(stream, 64 * 1024) } }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
            val name = context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: task.filename
            return Published(name, null, uri.toString())
        }
        @Suppress("DEPRECATION")
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val canWritePublic = ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val directory = if (canWritePublic && publicDir != null) publicDir else context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        directory.mkdirs()
        val name = uniqueFilename(task.filename, directory)
        val target = File(directory, name)
        partial.copyTo(target, overwrite = false)
        return Published(name, target.absolutePath, null)
    }

    private class Published(val name: String, val filePath: String?, val contentUri: String?)

    private fun startMonitor() {
        if (monitor?.isActive == true) return
        monitor = scope.launch {
            do {
                tick()
                delay(MONITOR_TICK_MS)
            } while (isActive && tasks.any { it.state == DownloadState.Running || it.state == DownloadState.Preparing })
        }
    }

    /** 监控一轮：卡住或太慢的传输换节点（或提示），并补足排队 */
    private fun tick() {
        tasks.filter { it.state == DownloadState.Running }.forEach { task ->
            val pace = samplePace(task.key, task)
            val stalled = stalledReason(task.key, task)
            val slow = if (stalled == null) slowReason(task, pace) else null
            val reason = stalled ?: slow ?: return@forEach
            if (!rotate(task, reason)) {
                tasks.firstOrNull { it.key == task.key }?.let { current ->
                    update(current.copy(message = stuckDownloadHint(current.isPrivate, slow = stalled == null)))
                }
            }
        }
        pump()
    }

    private fun stalledReason(key: String, task: DownloadTask): String? {
        // 排在另一个正在下载的任务后面等着，不算卡住
        val queuedBehindAnother = tasks.any { it.key != key && it.state == DownloadState.Running }
        val stuck = task.state == DownloadState.Running && !queuedBehindAnother && task.downloaded <= 0
        if (!stuck) {
            stalledSince.remove(key)
            return null
        }
        val since = stalledSince[key]
        if (since == null) {
            stalledSince[key] = System.currentTimeMillis()
            return null
        }
        return if (System.currentTimeMillis() - since >= STALL_MS) tr("节点没有响应") else null
    }

    /**
     * 记录一次进度采样。只累计连续监控期间的字节和时间：暂停、非运行状态、或者两次采样间隔过长
     * （App 在后台时没有刷新）都会断开，断开后慢速窗口也重新计时。
     */
    private fun samplePace(key: String, task: DownloadTask): Pace {
        val pace = paces.getOrPut(key) { Pace() }
        val now = System.currentTimeMillis()
        if (task.state != DownloadState.Running) {
            pace.lastAt = 0L
            pace.windowMs = 0L
            pace.windowBytes = 0L
            return pace
        }
        if (pace.lastAt != 0L && now - pace.lastAt <= PACE_GAP_MS) {
            val dt = now - pace.lastAt
            val db = (task.downloaded - pace.lastBytes).coerceAtLeast(0L)
            pace.windowMs += dt
            pace.windowBytes += db
            pace.totalMs += dt
            pace.totalBytes += db
        } else {
            pace.windowMs = 0L
            pace.windowBytes = 0L
        }
        pace.lastAt = now
        pace.lastBytes = task.downloaded
        return pace
    }

    /** 有进度但平均速度长时间太低：返回原因（用于换节点），否则返回 null。 */
    private fun slowReason(task: DownloadTask, pace: Pace): String? {
        if (task.state != DownloadState.Running || task.downloaded <= 0) {
            pace.windowMs = 0L
            pace.windowBytes = 0L
            return null
        }
        val hasNext = !task.isPrivate && task.attempt + 1 < task.targets.size
        val remaining = task.total.takeIf { it > 0 }?.let { (it - task.downloaded).coerceAtLeast(0L) }
        if (isSlowDownload(pace.windowMs, pace.windowBytes, remaining, hasNext)) return tr("下载速度过慢")
        if (pace.windowMs >= SLOW_WINDOW_MS) {
            pace.windowMs = 0L
            pace.windowBytes = 0L
        }
        return null
    }

    /** 暂停或继续。排队中的：切换是否参与排队；传输中的：中断传输并保留部分文件；已暂停的：重新排队。 */
    fun togglePause(task: DownloadTask) {
        val current = tasks.firstOrNull { it.key == task.key } ?: return
        when {
            current.state == DownloadState.Queued -> {
                update(current.copy(paused = !current.paused))
                save(); pump()
            }
            current.state == DownloadState.Running || current.state == DownloadState.Preparing -> {
                pausing.add(current.key)
                transfers[current.key]?.cancel()
            }
            current.state == DownloadState.Waiting && current.paused -> {
                update(current.copy(state = DownloadState.Queued, paused = false, message = null))
                save(); pump()
            }
        }
    }

    /** 取消：传输中的会删除部分文件，排队中的直接结束。 */
    fun cancel(task: DownloadTask) {
        // 以列表里的最新记录为准：界面拿到的副本可能已经被刷新或换过节点
        val current = tasks.firstOrNull { it.key == task.key } ?: return
        if (!current.active) return
        paces.remove(current.key)
        stalledSince.remove(current.key)
        pausing.remove(current.key)
        update(current.copy(state = DownloadState.Cancelled, message = null, paused = false))
        save()
        val job = transfers.remove(current.key)
        if (job != null) job.cancel() else scope.launch(Dispatchers.IO) {
            partialFile(current).delete()
            SegmentedTransfer.cleanup(partialFile(current))
        }
        pump()
    }

    /** 重试：失败或取消的任务从断点继续（部分文件还在就接着下），已完成的记录则重新开始一条新任务。 */
    fun retry(task: DownloadTask) {
        if (task.active) return
        if (task.state == DownloadState.Complete) {
            tasks.removeAll { it.key == task.key }
            start(task.url, task.filename, task.isPrivate, task.mimeType)
            return
        }
        tasks.firstOrNull { it.key == task.key }?.let { current ->
            // 重试时从第一个候选节点重新开始：网络变了，之前排在后面的节点可能已经能用了（私有下载只有一条地址）
            val first = if (current.isPrivate) current.attempt else 0
            update(current.copy(state = DownloadState.Queued, paused = false, message = null, attempt = first))
            save(); pump()
        }
    }

    /**
     * 打开已下载的文件。
     *
     * 优先用记录下来的 MediaStore URI（分区存储下可以直接读取），其次用落盘路径经 FileProvider 生成的 URI。
     */
    fun open(ctx: Context, task: DownloadTask) {
        scope.launch {
            val mime = task.mimeType ?: mimeFor(task.filename) ?: "application/octet-stream"
            val uri = withContext(Dispatchers.IO) { readableUri(ctx, task) }
            AppLog.info("download", tr("打开文件：") + task.filename + " → " + (uri?.toString() ?: tr("无可用 URI")))
            if (uri == null) {
                // 记录保留为「完成」，列表里会按 isFileMissing 显示「文件已不在」并提供重新下载
                ctx.toast(tr("文件已被移动或删除，请重新下载"))
                return@launch
            }
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
                .onSuccess { AppLog.info("download", tr("已请求系统打开：") + task.filename) }
                .onFailure {
                    AppLog.warn("download", tr("没有应用可以打开 ") + task.filename + "：" + it.message)
                    ctx.toast(tr("没有可以打开此文件的应用，文件已保存在「下载」文件夹"))
                }
        }
    }

    /**
     * 安装已下载的 APK（在线更新用）。
     *
     * Android 8+ 要求「安装未知应用」授权；未授权时直接跳到该设置页，
     * 用户开启后回到应用再次点击即可。
     */
    fun installApk(ctx: Context, task: DownloadTask) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = ctx.packageManager
            if (!pm.canRequestPackageInstalls()) {
                runCatching {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(Uri.parse("package:" + ctx.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { ctx.toast(tr("请先在系统设置中允许本应用安装应用")) }
                ctx.toast(tr("请开启「允许安装未知应用」，然后回来再次点击更新"))
                return
            }
        }
        open(ctx, task)
    }

    private fun readableUri(ctx: Context, task: DownloadTask): Uri? {
        task.contentUri?.let { raw ->
            val uri = Uri.parse(raw)
            if (fileExists(task)) return uri
        }
        task.filePath?.let { path ->
            val f = File(path)
            if (f.isFile) runCatching { FileProvider.getUriForFile(ctx, authority(ctx), f) }.getOrNull()?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreUri(ctx, task.filename)?.let { return it }
        }
        return null
    }

    /** 在 MediaStore.Downloads 中按显示名查找文件 */
    private fun mediaStoreUri(ctx: Context, name: String): Uri? = runCatching {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else return null
        ctx.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(name),
            "${MediaStore.Downloads._ID} DESC",
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
    }.getOrNull()

    /** 在文件管理器里显示文件所在的「下载」文件夹 */
    fun revealFolder(ctx: Context, task: DownloadTask) {
        // 优先定位到具体文件，失败再退回打开下载目录
        val fileUri = task.filePath?.let { path ->
            runCatching {
                FileProvider.getUriForFile(ctx, authority(ctx), File(path))
            }.getOrNull()
        }
        val intents = buildList {
            if (fileUri != null) {
                add(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, "vnd.android.document/directory")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
            add(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                        "vnd.android.document/directory",
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
        for (i in intents) {
            if (runCatching { ctx.startActivity(i) }.isSuccess) return
        }
        ctx.toast(tr("没有可用的文件管理器，文件保存在「下载」文件夹"))
    }

    /** 删除本地文件（可选择只删文件、保留记录） */
    suspend fun deleteFile(ctx: Context, task: DownloadTask): Boolean {
        val ok = withContext(Dispatchers.IO) { deleteFileQuiet(ctx, task) }
        if (ok) {
            tasks.firstOrNull { it.key == task.key }?.let { current ->
                update(current.copy(filePath = null, contentUri = null, state = DownloadState.Cancelled, message = tr("本地文件已删除")))
                save()
            }
        }
        return ok
    }

    /** 从下载记录中移除（不删除已完成的本地文件）；传输中的会被取消并删除部分文件 */
    fun forget(ctx: Context, task: DownloadTask) {
        tasks.removeAll { it.key == task.key }
        if (selectedKey == task.key) selectedKey = null
        save()
        if (task.active) cancel(task)
        pump()
    }

    /** 清空下载历史；先移除任务，再在后台删除文件，避免查询/删除阻塞界面。 */
    suspend fun clearHistory(ctx: Context, withFiles: Boolean): Pair<Int, Int> {
        // 只清理已经结束的记录：正在下载或排队中的任务保留
        val snapshot = tasks.filterNot { it.active }
        val finishedKeys = snapshot.map { it.key }.toSet()
        tasks.removeAll { it.key in finishedKeys }
        finishedKeys.forEach { key -> stalledSince.remove(key); paces.remove(key) }
        droppedRecords = 0
        prefs.edit().putInt("dropped", 0).apply()
        selectedKey = selectedKey?.takeIf { key -> tasks.any { it.key == key } }
        showHistory = false
        save()
        val files = withContext(Dispatchers.IO) {
            var deleted = 0
            snapshot.forEach { task ->
                if (withFiles && deleteFileQuiet(ctx, task)) deleted++
            }
            deleted
        }
        AppLog.info("download", trf("清空下载记录 {0} 条", snapshot.size) + if (withFiles) trf("，同时删除 {0} 个本地文件", files) else tr("，保留已下载文件"))
        return snapshot.size to files
    }

    /** 删除本地文件但不改动记录状态（用于后台删除/清空历史） */
    private fun deleteFileQuiet(ctx: Context, task: DownloadTask): Boolean {
        task.contentUri?.let { raw ->
            if (runCatching { ctx.contentResolver.delete(Uri.parse(raw), null, null) > 0 }.getOrDefault(false)) return true
        }
        val file = task.filePath?.let { File(it) }?.takeIf { it.isFile }
        var ok = file != null && runCatching { file.delete() }.getOrDefault(false)
        if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 记录里没有 URI 的旧任务：按显示名在 MediaStore 中查找后删除
            ok = mediaStoreUri(ctx, task.filename)?.let { uri ->
                runCatching { ctx.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            } ?: false
        }
        return ok
    }

    fun fileExists(task: DownloadTask): Boolean {
        task.filePath?.let { if (File(it).isFile) return true }
        val uri = task.contentUri?.let { Uri.parse(it) } ?: return false
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    /** 下载已完成，但本地文件已经不在（被移动或删除）：记录还在，打开会失败 */
    fun isFileMissing(task: DownloadTask): Boolean = task.state == DownloadState.Complete && !fileExists(task)

    suspend fun deleteAllFiles(ctx: Context): Int = clearHistory(ctx, true).second

    private fun authority(ctx: Context) = ctx.packageName + ".fileprovider"

    private fun update(task: DownloadTask) {
        tasks.indexOfFirst { it.key == task.key }.takeIf { it >= 0 }?.let { index ->
            val old = tasks[index]
            if (old != task) {
                if (old.state != task.state && task.state in setOf(DownloadState.Complete, DownloadState.Failed, DownloadState.Cancelled)) {
                    AppLog.info("download", task.filename + "：" + task.state.name + (task.message?.let { " · " + it } ?: ""))
                }
                if (old.state != task.state && task.state == DownloadState.Waiting) {
                    AppLog.info("download", task.filename + tr("：暂停（") + (task.message ?: tr("原因未知")) + "）")
                }
                // 记下哪个节点真的下成了：下次优先用它，失败过的节点排到最后
                if (old.state != task.state && task.state == DownloadState.Complete) {
                    task.nodeId?.let(Net::markDownloadOk)
                }
                // 完成或失败时用户不在看它的弹窗（例如点过「后台下载」）：留下提示，避免完成后找不到
                if (old.state != task.state && task.state in setOf(DownloadState.Complete, DownloadState.Failed) && selectedKey != task.key) {
                    finishedKey = task.key
                }
                tasks[index] = task
            }
        }
    }

    private fun save() { prefs.edit().putString("tasks", Api.plain.encodeToString(tasks.toList())).apply() }
    private fun safeFilename(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim().take(180)
        .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "download"
    private fun uniqueFilename(name: String, directory: File): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var candidate = name
        var suffix = 1
        while (File(directory, candidate).exists()) candidate = name.take(dot) + " (" + suffix++ + ")" + name.drop(dot)
        return candidate
    }
    private fun mimeFor(name: String): String? = when {
        name.endsWith(".apk", true) -> "application/vnd.android.package-archive"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
    }

    private fun resolvePrivateUrl(initial: String, token: String): Pair<String, String?> {
        var url = initial.toHttpUrl()
        repeat(6) {
            require(url.isHttps) { tr("GitHub 下载地址必须使用 HTTPS") }
            if (url.host !in authHosts) return url.toString() to null
            val req = Request.Builder().url(url).header("Authorization", "Bearer " + token)
                .header("Accept", privateDownloadAccept(url.toString())).build()
            privateHttp.routed().newCall(req).execute().use { response ->
                if (response.code in 300..399) {
                    val next = response.header("Location")?.let(url::resolve) ?: error(tr("下载跳转缺少地址"))
                    require(next.isHttps) { tr("下载跳转必须使用 HTTPS") }
                    url = next
                } else {
                    require(response.isSuccessful) {
                        when (response.code) {
                            401 -> tr("GitHub 授权失效，请重新登录")
                            403, 404 -> tr("没有下载权限，或资源已经删除")
                            else -> tr("无法下载资源（HTTP ") + response.code + "）"
                        }
                    }
                    return url.toString() to ("Bearer " + token)
                }
            }
        }
        error(tr("下载地址跳转过多"))
    }

    private val authHosts = setOf("github.com", "api.github.com", "raw.githubusercontent.com", "codeload.github.com")
}
