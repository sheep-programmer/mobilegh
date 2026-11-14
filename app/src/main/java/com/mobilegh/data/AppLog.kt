package com.mobilegh.data

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import com.mobilegh.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

@Serializable
data class AppLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val time: String = Instant.now().toString(),
    val level: String = "INFO",
    val category: String = "app",
    val message: String,
    val detail: String? = null,
    val thread: String? = null,
)

data class Breadcrumb(
    val timeMillis: Long,
    val category: String,
    val message: String,
)

/**
 * 应用内诊断日志：不记录 Token、Cookie、密码或请求响应正文，只记录类别、状态和错误摘要。
 * 文件超过 2 MiB 时保留末尾内容，避免日志影响应用存储。
 *
 * 崩溃相关记录分四层，任何一层都不依赖「进程还活着」：
 * 1. Java 未捕获异常：同步写入独立的崩溃文件与崩溃历史，同时立刻追加写入日志文件，下次启动时展示在顶部；
 * 2. 系统退出原因（Android 11+）：能看到原生崩溃、ANR、被系统杀掉，解析信号、内存与系统 trace；
 * 3. 主线程卡顿看门狗：主线程阻塞超过 5 秒时立刻抓取主线程堆栈，ANR 之前就能看到卡在哪里；
 * 4. 心跳与会话快照：操作轨迹实时落盘，下次启动能还原「上一次运行最后在做什么」。
 */
object AppLog {
    private const val TAG = "MobileGH"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_ENTRIES = 3000
    private const val MAX_DETAIL = 12000
    private const val MAX_CRASH_REPORT = 128 * 1024
    private const val MAX_CRASH_HISTORY = 512L * 1024
    private const val MAX_BREADCRUMBS = 60
    private const val MAX_THREAD_DUMP = 48 * 1024
    private const val WATCHDOG_INTERVAL_MS = 3000L
    private const val HEARTBEAT_INTERVAL_MS = 15000L
    private const val STALL_THRESHOLD_MS = 5000L
    private const val STALL_REPORT_GAP_MS = 60_000L

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var crashFile: File
    private lateinit var crashHistory: File
    private lateinit var sessionFile: File
    private lateinit var breadcrumbFile: File
    private val lock = Any()
    private val breadcrumbsLock = Any()
    private val breadcrumbs = ArrayDeque<Breadcrumb>(MAX_BREADCRUMBS + 5)
    private val mainHandler by lazy { runCatching { Handler(Looper.getMainLooper()) }.getOrNull() }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
    /** 会话快照与轨迹落盘都走这个单线程：界面导航不能被磁盘写入拖慢 */
    private val writeQueue by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "applog-writer").apply { isDaemon = true }
        }
    }

    val entries = mutableStateListOf<AppLogEntry>()
    val version = mutableIntStateOf(0)
    private var installed = false

    private var appContext: Context? = null
    val appStartTime: Long = currentElapsedRealtime()
    private val sessionStartedAt: Long = System.currentTimeMillis()

    @Volatile var currentScreen: String? = null
        private set
    @Volatile var foregroundActivity: String? = null
        private set
    @Volatile var isForeground: Boolean = false
        private set

    /** 上一次运行留下的会话快照与操作轨迹：原生崩溃时 Java 处理器跑不到，只能靠它们还原现场 */
    private var previousSession: SessionSnapshot? = null
    private var previousBreadcrumbs: List<Breadcrumb> = emptyList()
    private var previousCrashFileFound = false
    @Volatile private var lastBreadcrumbFlush = 0L
    private var breadcrumbsDirty = false
    private val flushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastStallReportAt = 0L
    private var pendingProbe: CountDownLatch? = null
    private var watchdogStarted = false

    @Serializable
    private data class SessionSnapshot(
        val startedAt: Long = 0L,
        val heartbeat: Long = 0L,
        val screen: String? = null,
        val version: String = "",
        val foreground: Boolean = false,
    )

    fun currentElapsedRealtime(): Long = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)

    fun init(ctx: Context) {
        if (installed) return
        installed = true
        appContext = ctx.applicationContext
        dir = File(ctx.filesDir, "logs").also { it.mkdirs() }
        file = File(dir, "mobilegh.log")
        crashFile = File(dir, "last-crash.txt")
        crashHistory = File(dir, "crash-history.log")
        sessionFile = File(dir, "session.json")
        breadcrumbFile = File(dir, "breadcrumbs.log")

        // 先读上一次运行的现场，再开始覆盖写入
        previousSession = readSession()
        previousBreadcrumbs = readPersistedBreadcrumbs()
        previousCrashFileFound = crashFile.exists()

        (ctx.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var startedCount = 0

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                foregroundActivity = activity.javaClass.simpleName
                breadcrumb("lifecycle", "${activity.javaClass.simpleName} created")
            }

            override fun onActivityStarted(activity: Activity) {
                startedCount++
                isForeground = startedCount > 0
                foregroundActivity = activity.javaClass.simpleName
            }

            override fun onActivityResumed(activity: Activity) {
                foregroundActivity = activity.javaClass.simpleName
                breadcrumb("lifecycle", "${activity.javaClass.simpleName} resumed")
            }

            override fun onActivityPaused(activity: Activity) {
                breadcrumb("lifecycle", "${activity.javaClass.simpleName} paused")
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount = (startedCount - 1).coerceAtLeast(0)
                isForeground = startedCount > 0
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                breadcrumb("lifecycle", "${activity.javaClass.simpleName} destroyed")
                if (foregroundActivity == activity.javaClass.simpleName) {
                    foregroundActivity = null
                }
            }
        })

        // 先装崩溃处理器：后面的初始化出错也能留下记录
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashReport(thread, throwable)
            previous?.uncaughtException(thread, throwable)
        }
        load()
        reportPreviousCrash()
        writeSession()
        startWatchdog()
        info("app", tr("MobileGH 启动") + " · " + deviceSummary(), previousRunSummary())
        recordExitReasons(ctx)
    }

    fun breadcrumb(category: String, message: String) {
        val now = System.currentTimeMillis()
        val clean = safe(message)
        synchronized(breadcrumbsLock) {
            if (breadcrumbs.size >= MAX_BREADCRUMBS) {
                breadcrumbs.removeFirst()
            }
            breadcrumbs.addLast(Breadcrumb(now, category, clean))
            breadcrumbsDirty = true
        }
        // 网络请求会产生大量轨迹：限流写盘，既不拖慢请求，也能在进程被杀后留下最近几十步
        if (now - lastBreadcrumbFlush >= 2000) scheduleFlush()
    }

    fun trackScreen(screen: String) {
        currentScreen = screen
        breadcrumb("nav", tr("进入页面：") + screen)
        scheduleSessionWrite()
    }

    fun getRecentBreadcrumbs(): List<Breadcrumb> = synchronized(breadcrumbsLock) {
        breadcrumbs.toList()
    }

    fun clearBreadcrumbs() = synchronized(breadcrumbsLock) {
        breadcrumbs.clear()
    }

    fun formatBreadcrumbs(crashTimeMillis: Long = System.currentTimeMillis()): String =
        formatBreadcrumbList(synchronized(breadcrumbsLock) { breadcrumbs.toList() }, crashTimeMillis)

    /** 把轨迹格式化成「崩溃前 -3.2s」这样的相对时间，方便还原崩溃前发生了什么 */
    fun formatBreadcrumbList(list: List<Breadcrumb>, referenceTimeMillis: Long): String {
        if (list.isEmpty()) return tr("无轨迹记录")
        return buildString {
            list.forEach { b ->
                val diffMs = referenceTimeMillis - b.timeMillis
                val diffSec = kotlin.math.abs(diffMs) / 1000.0
                // 参考点之后（如最后一次心跳之后又发生了操作）用 +，不要一律钳成 +0.0s，否则顺序看不出来
                val sign = if (diffMs < 0) "+" else "-"
                appendLine("[$sign${"%.1f".format(diffSec)}s] [${b.category}] ${b.message}")
            }
        }.trimEnd()
    }

    fun extractRootCause(throwable: Throwable): Throwable {
        var cause: Throwable = throwable
        val visited = mutableSetOf<Throwable>()
        while (cause.cause != null && cause.cause !== cause && visited.add(cause)) {
            cause = cause.cause!!
        }
        return cause
    }

    fun extractRootCauseLocation(throwable: Throwable): String {
        val root = extractRootCause(throwable)
        val stack = root.stackTrace
        val firstAppLine = stack.firstOrNull { it.className.startsWith("com.mobilegh.") }
            ?: stack.firstOrNull()
        return if (firstAppLine != null) {
            val fileName = firstAppLine.fileName ?: firstAppLine.className.substringAfterLast('.')
            "$fileName:${firstAppLine.lineNumber}"
        } else {
            "unknown"
        }
    }

    /**
     * 异常链：外层包装（Retrofit/serialization/协程）经常把真正的错误藏在 cause 里，
     * 逐层列出来才能看出「谁包了谁、根因是哪一个」。
     */
    fun formatCauseChain(throwable: Throwable): String = buildString {
        var cur: Throwable? = throwable
        var depth = 0
        val seen = mutableSetOf<Throwable>()
        while (cur != null && depth < 12 && seen.add(cur)) {
            appendLine("[$depth] ${cur.javaClass.name}: ${cur.message ?: "无说明"}")
            cur.stackTrace.firstOrNull { it.className.startsWith("com.mobilegh.") }?.let {
                appendLine("      at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})")
            }
            cur.suppressed.forEach { s ->
                appendLine("      suppressed: ${s.javaClass.name}: ${s.message ?: "无说明"}")
            }
            cur = cur.cause
            depth++
        }
        if (depth >= 12) appendLine("…（异常链超过 12 层，已截断）")
    }.trimEnd()

    /** 全部线程快照：死锁、OkHttp 线程卡住、协程调度器饥饿都能从这里看出来 */
    fun formatAllThreads(crashingThread: Thread?, limit: Int = MAX_THREAD_DUMP): String {
        val traces = runCatching { Thread.getAllStackTraces() }.getOrDefault(emptyMap())
        if (traces.isEmpty()) return tr("无法获取线程快照")
        val main = runCatching { Looper.getMainLooper()?.thread }.getOrNull()
        val ordered = traces.entries.sortedWith(
            compareBy({ it.key !== crashingThread }, { it.key !== main }, { it.key.name }),
        )
        return buildString {
            ordered.forEach { (t, stack) ->
                if (length > limit) {
                    appendLine("…（其余线程已省略，共 ${traces.size} 个）")
                    return@buildString
                }
                appendLine("\"${t.name}\" id=${t.diagId()} state=${t.state} daemon=${t.isDaemon} priority=${t.priority}")
                stack.take(40).forEach { appendLine("    at $it") }
                if (stack.size > 40) appendLine("    … 另有 ${stack.size - 40} 帧")
            }
        }.trimEnd()
    }

    fun formatUptime(uptimeMs: Long): String {
        val totalSec = (uptimeMs / 1000).coerceAtLeast(0)
        val seconds = totalSec % 60
        val minutes = (totalSec / 60) % 60
        val hours = totalSec / 3600
        return when {
            hours > 0 -> "${hours}小时 ${minutes}分 ${seconds}秒"
            minutes > 0 -> "${minutes}分 ${seconds}秒"
            else -> "${seconds}秒 (${uptimeMs}ms)"
        }
    }

    private fun formatAgo(timeMillis: Long): String {
        val diff = System.currentTimeMillis() - timeMillis
        if (diff < 0) return "刚刚"
        val sec = diff / 1000
        return when {
            sec < 60 -> "${sec}秒前"
            sec < 3600 -> "${sec / 60}分钟前"
            sec < 86400 -> "${sec / 3600}小时前"
            else -> "${sec / 86400}天前"
        }
    }

    fun memorySummary(ctx: Context?): String = buildString {
        val rt = Runtime.getRuntime()
        val usedHeap = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val totalHeap = rt.totalMemory() / (1024 * 1024)
        val maxHeap = rt.maxMemory() / (1024 * 1024)
        val heapPercent = if (maxHeap > 0) (usedHeap * 100 / maxHeap) else 0
        appendLine("JVM 堆内存: 已用 ${usedHeap}MB / 当前分配 ${totalHeap}MB / 最大上限 ${maxHeap}MB (${heapPercent}%)")

        runCatching {
            val nativeAlloc = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
            val nativeSize = Debug.getNativeHeapSize() / (1024 * 1024)
            appendLine("原生堆内存: 已分配 ${nativeAlloc}MB / 已保留 ${nativeSize}MB")
        }

        procStatus("VmRSS")?.let { appendLine("进程 RSS: $it") }
        procStatus("VmSize")?.let { appendLine("进程虚拟内存: $it") }

        ctx?.let { c ->
            runCatching {
                val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(memInfo)
                if (am != null) {
                    val availMb = memInfo.availMem / (1024 * 1024)
                    val totalMb = memInfo.totalMem / (1024 * 1024)
                    appendLine("系统物理内存: 可用 ${availMb}MB / 总计 ${totalMb}MB · lowMemory=${memInfo.lowMemory} · threshold=${memInfo.threshold / (1024 * 1024)}MB")
                }
                runCatching {
                    val own = am?.getProcessMemoryInfo(intArrayOf(Process.myPid()))?.firstOrNull()
                    if (own != null) {
                        appendLine("本进程内存: PSS=${own.totalPss / 1024}MB · 私有脏页=${own.totalPrivateDirty / 1024}MB")
                    }
                }
                val filesDir = c.filesDir
                val freeStorageMb = filesDir.usableSpace / (1024 * 1024)
                appendLine("内部存储可用空间: ${freeStorageMb}MB")
            }
        }
    }.trimEnd()

    fun networkSummary(ctx: Context?): String {
        if (ctx == null) return "未知"
        return runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "无网络管理服务"
            val active = cm.activeNetwork ?: return "离线/未连接网络"
            val caps = cm.getNetworkCapabilities(active) ?: return "已连接 (未知)"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动蜂窝网络"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN 虚拟网络"
                else -> "已连接 (其它通道)"
            }
        }.getOrDefault("读取失败")
    }

    /** 系统、机型与构建：判断问题是否只出现在某个系统版本、某种机型或某个 ABI 上 */
    fun buildSummary(): String = buildString {
        appendLine("应用版本: v${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        appendLine("包名: ${BuildConfig.APPLICATION_ID}")
        appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · 安全补丁 ${safeBuild { Build.VERSION.SECURITY_PATCH } ?: "未知"}")
        appendLine("机型: ${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL} (device=${Build.DEVICE}, product=${Build.PRODUCT})")
        appendLine("系统指纹: ${Build.FINGERPRINT}")
        appendLine("构建标签: ${safeBuild { Build.TAGS }?.ifBlank { "无" } ?: "无"}")
        appendLine("ABI: ${safeBuild { Build.SUPPORTED_ABIS.joinToString() } ?: "未知"}")
        appendLine("CPU 核心: ${Runtime.getRuntime().availableProcessors()}")
        appendLine("调试器连接: ${runCatching { Debug.isDebuggerConnected() }.getOrDefault(false)}")
    }.trimEnd()

    /** 系统属性在单元测试与异常机型上可能为空，读不到时退回「未知」而不是让诊断本身抛异常 */
    private inline fun <T> safeBuild(block: () -> T?): T? = runCatching { block() }.getOrNull()

    /** getId() 在 API 36 标记废弃（新增的 threadId() 需要 API 36），低版本仍要能编译运行 */
    @Suppress("DEPRECATION")
    private fun Thread.diagId(): Long = runCatching { this.id }.getOrDefault(-1L)

    /** 进程级状态：线程数、文件描述符、OOM 权重，这些是「被杀」类问题的关键线索 */
    fun processSummary(): String = buildString {
        val pid = runCatching { Process.myPid() }.getOrDefault(-1)
        appendLine("进程 PID: $pid · Java 线程数: ${Thread.activeCount()}")
        procStatus("Threads")?.let { appendLine("/proc/self/status Threads: $it") }
        procStatus("VmPeak")?.let { appendLine("虚拟内存峰值: $it") }
        val fdCount = runCatching { File("/proc/self/fd").list()?.size }.getOrNull()
        val fdLimit = runCatching {
            File("/proc/self/limits").takeIf { it.exists() }?.readLines()
                ?.firstOrNull { it.startsWith("Max open files") }?.split(Regex("\\s{2,}"))?.getOrNull(1)
        }.getOrNull()
        if (fdCount != null) appendLine("文件描述符: $fdCount" + (fdLimit?.let { " / 上限 $it" } ?: ""))
        procStatus("oom_score_adj")?.let { appendLine("OOM 权重 (oom_score_adj): $it") }
    }.trimEnd()

    fun decodeSignal(status: Int): String? {
        val sig = if (status in 1..31) status else (status and 0x7F).takeIf { it in 1..31 } ?: return null
        return when (sig) {
            1 -> "SIGHUP 挂起"
            2 -> "SIGINT 中断"
            3 -> "SIGQUIT 退出"
            4 -> "SIGILL 非法指令"
            5 -> "SIGTRAP 陷阱"
            6 -> "SIGABRT 中止"
            7 -> "SIGBUS 总线错误"
            8 -> "SIGFPE 浮点异常"
            9 -> "SIGKILL 强制终止"
            11 -> "SIGSEGV 段错误/非法内存访问"
            13 -> "SIGPIPE 管道破裂"
            15 -> "SIGTERM 终止请求"
            31 -> "SIGSYS 非法系统调用"
            else -> "Signal $sig"
        }
    }

    fun decodeImportance(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> tr("前台活跃")
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> tr("前台服务")
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> tr("前台可见")
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> tr("可感知后台")
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> tr("不能保存状态")
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> tr("后台服务")
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> tr("后台缓存")
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> tr("已销毁")
        else -> "level $importance"
    }

    fun buildCrashReport(
        thread: Thread,
        throwable: Throwable,
        crashTime: Instant = Instant.now(),
    ): Pair<String, String> {
        val crashTimeMillis = System.currentTimeMillis()
        val root = extractRootCause(throwable)
        val location = extractRootCauseLocation(throwable)
        val rootMsg = root.message?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""
        val headline = "${root.javaClass.simpleName} ($location)$rootMsg"

        val report = buildString {
            appendLine("=========================================")
            appendLine("【异常崩溃诊断报告】")
            appendLine("=========================================")
            appendLine("发生时间: $crashTime")
            appendLine("进程运行时间: ${runCatching { formatUptime(currentElapsedRealtime() - appStartTime) }.getOrDefault("未知")}")
            appendLine("崩溃发生线程: ${thread.name} (tid=${thread.diagId()}, priority=${thread.priority}, group=${thread.threadGroup?.name ?: "无"})")
            appendLine("应用前台状态: ${if (isForeground) "前台活跃 (Foreground)" else "处于后台 (Background)"} (TopActivity: ${foregroundActivity ?: "无"})")
            appendLine("当前浏览页面: ${currentScreen ?: "未记录/启动阶段"}")
            appendLine("网络状态: ${networkSummary(appContext)}")
            appendLine()

            appendLine("-----------------------------------------")
            appendLine("【设备与构建信息】")
            appendLine("-----------------------------------------")
            appendLine(buildSummary())
            appendLine()

            appendLine("-----------------------------------------")
            appendLine("【进程与内存状态快照】")
            appendLine("-----------------------------------------")
            appendLine(processSummary())
            appendLine()
            appendLine(memorySummary(appContext))
            appendLine()

            appendLine("-----------------------------------------")
            appendLine("【根本原因 (Root Cause)】")
            appendLine("-----------------------------------------")
            appendLine("根本异常类型: ${root.javaClass.name}")
            appendLine("根本异常说明: ${root.message ?: "无详细说明"}")
            appendLine("触发代码位置: $location")
            val appFrames = root.stackTrace.filter { it.className.startsWith("com.mobilegh.") }.take(8)
            if (appFrames.isNotEmpty()) {
                appendLine("应用关键栈帧:")
                appFrames.forEach { appendLine("  -> at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})") }
            }
            appendLine()
            appendLine("【异常链（由外到内）】")
            appendLine(formatCauseChain(throwable))
            appendLine()

            appendLine("-----------------------------------------")
            appendLine("【最近操作与事件轨迹 (Breadcrumbs)】")
            appendLine("-----------------------------------------")
            appendLine(formatBreadcrumbs(crashTimeMillis))
            appendLine()

            appendLine("-----------------------------------------")
            appendLine("【完整异常堆栈 (Stack Trace)】")
            appendLine("-----------------------------------------")
            append(StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString().trim())
            appendLine()

            val mainThread = runCatching { Looper.getMainLooper()?.thread }.getOrNull()
            if (mainThread != null && thread !== mainThread) {
                appendLine()
                appendLine("-----------------------------------------")
                appendLine("【主线程当前堆栈 (Main Thread)】")
                appendLine("-----------------------------------------")
                val mainStack = mainThread.stackTrace
                if (mainStack.isNotEmpty()) {
                    mainStack.take(30).forEach { appendLine("  at $it") }
                } else {
                    appendLine("主线程堆栈为空（空闲或阻塞在原生层）")
                }
            }

            appendLine()
            appendLine("-----------------------------------------")
            appendLine("【全部线程快照 (All Threads)】")
            appendLine("-----------------------------------------")
            append(formatAllThreads(thread))
            appendLine()
            appendLine("=========================================")
        }

        return headline to report
    }

    fun info(category: String, message: String, detail: String? = null) =
        write(AppLogEntry(level = "INFO", category = category, message = safe(message), detail = detail?.take(MAX_DETAIL)))

    /**
     * 同一条错误短时间内重复出现只记第一次（例如列表里每个条目都 404）。
     * 记录的是「首次出现」，不会掩盖问题，也不会把日志刷满。
     */
    private val recentWarnings = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val WARN_DEDUPE_WINDOW_MS = 30_000L

    fun warn(category: String, message: String, error: Throwable? = null, context: String? = null): Boolean {
        val key = "$category|$message|${error?.javaClass?.name.orEmpty()}"
        val now = System.currentTimeMillis()
        val last = recentWarnings[key]
        if (last != null && now - last < WARN_DEDUPE_WINDOW_MS) return false
        recentWarnings[key] = now
        if (recentWarnings.size > 500) {
            recentWarnings.entries.removeIf { now - it.value > WARN_DEDUPE_WINDOW_MS }
        }
        write(entry("WARN", category, message, error, context))
        return true
    }

    fun error(category: String, message: String, error: Throwable? = null, context: String? = null) =
        write(entry("ERROR", category, message, error, context))

    /** 历史崩溃记录（多次闪退时逐条保留），供日志页导出 */
    fun crashHistoryText(): String = runCatching {
        if (!::crashHistory.isInitialized || !crashHistory.exists()) return ""
        // 旧版本曾把同一次崩溃写两遍，导出时按 id 去重，避免历史里出现重复报告
        val list = dedupeById(crashHistory.readLines().mapNotNull { line ->
            runCatching { json.decodeFromString(AppLogEntry.serializer(), line) }.getOrNull()
        })
        list.joinToString("\n\n") { e ->
            "[${e.time}] [${e.thread ?: "?"}] ${e.message}\n${e.detail.orEmpty()}"
        }
    }.getOrDefault("")

    /**
     * 按 id 去重（保留最后一条）。界面用 id 作为 LazyColumn 的 key，
     * 任何来源的重复 id 都会让整页抛「Key ... was already used」直接崩溃。
     */
    fun dedupeById(list: List<AppLogEntry>): List<AppLogEntry> {
        val unique = LinkedHashMap<String, AppLogEntry>(list.size)
        list.forEach { unique[it.id] = it }
        return unique.values.toList()
    }

    fun clear() {
        synchronized(lock) {
            runCatching { file.writeText("") }
            updateState { entries.clear(); version.intValue++ }
        }
    }

    fun export(list: List<AppLogEntry>): String = buildString {
        appendLine(tr("MobileGH 日志导出"))
        appendLine(tr("生成时间：") + Instant.now())
        appendLine(tr("设备：") + deviceSummary())
        appendLine(tr("运行时间：") + runCatching { formatUptime(currentElapsedRealtime() - appStartTime) }.getOrDefault("未知"))
        appendLine(tr("日志数：") + list.size)
        appendLine()
        list.asReversed().forEach { e ->
            append('[').append(e.time).append("] [").append(e.level).append("] [")
                .append(e.category).append("] ").append(e.message).appendLine()
            e.detail?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        }
    }

    /** 版本、系统与机型，用于判断问题是否只出现在某个系统版本或某种机型上 */
    fun deviceSummary(): String =
        "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · ${safeBuild { Build.SUPPORTED_ABIS.firstOrNull() }.orEmpty()}"

    private fun entry(
        level: String,
        category: String,
        message: String,
        error: Throwable?,
        context: String? = null,
    ): AppLogEntry {
        val detail = buildString {
            context?.takeIf { it.isNotBlank() }?.let { appendLine(it.trim().take(MAX_DETAIL / 2)) }
            error?.let {
                if (level == "ERROR") {
                    val root = extractRootCause(it)
                    val loc = extractRootCauseLocation(it)
                    appendLine("【根因诊断】: ${root.javaClass.name}: ${root.message ?: "无说明"}")
                    appendLine("【代码位置】: $loc")
                    appendLine("【异常链】:")
                    appendLine(formatCauseChain(it))
                    appendLine()
                    append(stack(it))
                } else {
                    appendLine(brief(it))
                }
            }
        }.takeIf { it.isNotBlank() }
        return AppLogEntry(
            level = level,
            category = category,
            message = safe(message),
            detail = detail,
            thread = Thread.currentThread().name,
        )
    }

    private fun stack(e: Throwable) = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString().take(MAX_DETAIL)

    /**
     * WARN 只留一行摘要。
     *
     * 网络类警告每次重试、每个候选节点都会触发，完整堆栈会把日志刷满（一次失败几十行），
     * 真正需要堆栈的崩溃和错误走 [stack]。
     */
    private fun brief(e: Throwable): String {
        val root = extractRootCause(e)
        val msg = e.message ?: root.message ?: ""
        val rootInfo = if (root !== e) " [根因: ${root.javaClass.simpleName}: ${root.message.orEmpty()}]" else ""
        return "${e.javaClass.simpleName}: $msg$rootInfo".trim().take(500)
    }

    /**
     * 未捕获异常：同步写入独立的崩溃文件与崩溃历史，同时追加到主日志文件。不取 [lock]、不经过主线程队列，
     * 即使随后进程立刻结束，报告也已经落盘；任何失败都只吞掉，不能影响原本的崩溃流程。
     *
     * 先写一份最小报告（异常 + 堆栈），再补全线程快照等耗时信息：补全过程本身出问题时，
     * 磁盘上至少还有能定位的堆栈，而不是空白。
     */
    private fun saveCrashReport(thread: Thread, throwable: Throwable) {
        runCatching { flushBreadcrumbs() }
        runCatching { writeSession() }

        val root = runCatching { extractRootCause(throwable) }.getOrDefault(throwable)
        val minimal = runCatching {
            AppLogEntry(
                level = "ERROR",
                category = "crash",
                message = safe(trf("应用崩溃：{0}", "${root.javaClass.simpleName}: ${root.message.orEmpty()}")),
                detail = redact(StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString().take(MAX_CRASH_REPORT)),
                thread = thread.name,
            )
        }.getOrNull()
        // 最小报告只保证崩溃文件有内容，不进历史：历史里只留一份完整报告
        if (minimal != null) runCatching { persistCrash(minimal, appendHistory = false) }

        runCatching {
            val (headline, fullReport) = buildCrashReport(thread, throwable)
            val entry = AppLogEntry(
                level = "ERROR",
                category = "crash",
                message = safe(trf("应用崩溃：{0}", headline)),
                detail = redact(fullReport.take(MAX_CRASH_REPORT)),
                thread = thread.name,
            )
            persistCrash(entry, replaceLast = minimal != null)
        }
    }

    /** 崩溃记录落盘：独立文件给下次启动读，历史文件保留多次崩溃；主日志只在下次启动时写入一份 */
    private fun persistCrash(entry: AppLogEntry, replaceLast: Boolean = false, appendHistory: Boolean = true) {
        val encoded = json.encodeToString(AppLogEntry.serializer(), entry)
        crashFile.writeText(encoded)
        file.parentFile?.mkdirs()
        // 主日志不在这里写：下次启动 reportPreviousCrash 会把同一条（同一个 id）追加进来。
        // 两处都写会让日志里出现两条 id 相同的记录，LazyColumn 的 key 重复会直接抛异常。
        if (appendHistory) {
            // 历史文件保留多次崩溃：连续闪退时不会只剩最后一次
            if (replaceLast) {
                val lines = runCatching { crashHistory.readLines() }.getOrDefault(emptyList()).toMutableList()
                if (lines.isNotEmpty()) lines.removeAt(lines.lastIndex)
                lines.add(encoded)
                crashHistory.writeText(lines.joinToString("\n", postfix = "\n"))
            } else {
                crashHistory.appendText(encoded + "\n")
            }
            trimFile(crashHistory, MAX_CRASH_HISTORY)
        }
    }

    /** 上一次运行的未捕获异常：启动时写入日志并删除崩溃文件，每次只报告一次。 */
    private fun reportPreviousCrash() {
        runCatching {
            if (!crashFile.exists()) return
            val text = crashFile.readText().trim()
            crashFile.delete()
            val entry = runCatching { json.decodeFromString(AppLogEntry.serializer(), text) }.getOrNull()
            if (entry != null) {
                write(entry)
            } else {
                write(AppLogEntry(level = "ERROR", category = "crash", message = tr("上次运行发生未捕获异常"), detail = text.take(MAX_DETAIL)))
            }
        }
    }

    private fun previousRunSummary(): String? {
        val s = previousSession ?: return null
        if (s.startedAt <= 0) return null
        return buildString {
            appendLine("上次运行启动: ${Instant.ofEpochMilli(s.startedAt)}")
            appendLine("上次最后心跳: ${Instant.ofEpochMilli(s.heartbeat)} (${formatAgo(s.heartbeat)})")
            appendLine("上次会话时长: ${formatUptime(s.heartbeat - s.startedAt)}")
            appendLine("上次最后页面: ${s.screen ?: "未记录"}")
            appendLine("上次版本: ${s.version}")
            if (previousBreadcrumbs.isNotEmpty()) {
                appendLine()
                appendLine("【上次运行最后操作轨迹】")
                // 以最后一条轨迹为基准：心跳每 15 秒才写一次，用它做基准会让末尾几条都变成 +0.0s
                appendLine(formatBreadcrumbList(previousBreadcrumbs, previousBreadcrumbs.lastOrNull()?.timeMillis ?: s.heartbeat))
            }
        }.trimEnd()
    }

    private fun readSession(): SessionSnapshot? = runCatching {
        if (!::sessionFile.isInitialized || !sessionFile.exists()) return null
        json.decodeFromString(SessionSnapshot.serializer(), sessionFile.readText())
    }.getOrNull()

    private fun writeSession() {
        if (!::sessionFile.isInitialized) return
        val snapshot = SessionSnapshot(
            startedAt = sessionStartedAt,
            heartbeat = System.currentTimeMillis(),
            screen = currentScreen,
            version = BuildConfig.VERSION_NAME,
            foreground = isForeground,
        )
        runCatching { sessionFile.writeText(json.encodeToString(SessionSnapshot.serializer(), snapshot)) }
    }

    private fun readPersistedBreadcrumbs(): List<Breadcrumb> = runCatching {
        if (!::breadcrumbFile.isInitialized) return emptyList()
        breadcrumbFile.takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size == 3) Breadcrumb(parts[0].toLongOrNull() ?: 0L, parts[1], parts[2]) else null
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun flushBreadcrumbs() {
        if (!::breadcrumbFile.isInitialized) return
        val text = synchronized(breadcrumbsLock) {
            if (!breadcrumbsDirty) return
            breadcrumbsDirty = false
            lastBreadcrumbFlush = System.currentTimeMillis()
            breadcrumbs.joinToString("\n") { "${it.timeMillis}\t${it.category}\t${it.message.replace('\t', ' ').replace('\n', ' ')}" }
        }
        runCatching { breadcrumbFile.writeText(text) }
    }

    private fun scheduleFlush() {
        if (!::breadcrumbFile.isInitialized) return
        // 高频调用（每个网络请求一次）时只排一个任务，避免写队列被刷屏
        if (!flushScheduled.compareAndSet(false, true)) return
        writeQueue.execute {
            flushScheduled.set(false)
            runCatching { flushBreadcrumbs() }
        }
    }

    private fun scheduleSessionWrite() {
        if (!::sessionFile.isInitialized) return
        writeQueue.execute { runCatching { writeSession() } }
    }

    /**
     * 心跳线程：定期落盘会话快照与操作轨迹，并探测主线程是否被卡住。
     * 原生崩溃、被系统杀死时 Java 层没有机会写任何东西，只能靠这些落盘数据在下次启动还原现场。
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        Thread({
            var lastHeartbeat = 0L
            while (true) {
                runCatching {
                    val now = currentElapsedRealtime()
                    if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                        lastHeartbeat = now
                        writeSession()
                        flushBreadcrumbs()
                    }
                    probeMainThread()
                }
                runCatching { Thread.sleep(WATCHDOG_INTERVAL_MS) }
            }
        }, "applog-watchdog").apply { isDaemon = true }.start()
    }

    private fun probeMainThread() {
        if (!isForeground) return
        val handler = mainHandler ?: return
        val pending = pendingProbe
        if (pending != null && pending.count > 0L) {
            // 上一次探测还挂在队列里，说明主线程仍然没空转
            reportStall(STALL_THRESHOLD_MS)
            return
        }
        val latch = CountDownLatch(1)
        pendingProbe = latch
        if (!handler.post { latch.countDown() }) {
            pendingProbe = null
            return
        }
        if (!latch.await(STALL_THRESHOLD_MS, TimeUnit.MILLISECONDS)) {
            reportStall(STALL_THRESHOLD_MS)
        }
    }

    /** 主线程卡顿：ANR 之前先留一份现场，能看到到底卡在哪个方法上 */
    private fun reportStall(blockedMs: Long) {
        val now = currentElapsedRealtime()
        if (now - lastStallReportAt < STALL_REPORT_GAP_MS) return
        lastStallReportAt = now
        val mainThread = runCatching { Looper.getMainLooper()?.thread }.getOrNull() ?: return
        val stack = runCatching { mainThread.stackTrace }.getOrDefault(emptyArray())
        val detail = buildString {
            appendLine("【主线程卡顿诊断】")
            appendLine("检测时间: ${Instant.now()}")
            appendLine("阻塞时长: 至少 ${blockedMs}ms（Android 的 ANR 阈值是 5000ms）")
            appendLine("当前页面: ${currentScreen ?: "未记录"}")
            appendLine("前台 Activity: ${foregroundActivity ?: "无"}")
            appendLine("进程运行时间: ${formatUptime(currentElapsedRealtime() - appStartTime)}")
            appendLine("网络状态: ${networkSummary(appContext)}")
            appendLine()
            appendLine("【主线程堆栈】")
            if (stack.isEmpty()) {
                appendLine("堆栈为空（阻塞在原生层或等待锁）")
            } else {
                stack.take(40).forEach { appendLine("  at $it") }
            }
            appendLine()
            appendLine("【内存快照】")
            appendLine(memorySummary(appContext))
        }
        write(
            AppLogEntry(
                level = "WARN",
                category = "stall",
                message = trf("主线程卡顿超过 {0} 秒", (blockedMs / 1000).coerceAtLeast(1)),
                detail = detail.take(MAX_DETAIL),
                thread = mainThread.name,
            ),
        )
    }

    /**
     * 系统记录的进程退出原因（Android 11+）：原生崩溃、ANR、低内存、被系统杀掉都能看到。
     * 放到后台线程读取，ANR 的 trace 可能很大；每条记录只报告一次，靠时间戳去重。
     */
    private fun recordExitReasons(ctx: Context) {
        // SDK 判断与调用放在同一个 lambda 里，lint 才能识别这里的 API 版本保护
        Thread({
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) reportExitReasons(ctx)
            }
        }, "exit-reasons").apply { isDaemon = true }.start()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun reportExitReasons(ctx: Context) {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val seenFile = File(dir, "exit-seen.txt")
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        var newest = seen
        am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10).sortedBy { it.timestamp }.forEach { info ->
            if (info.timestamp <= seen) return@forEach
            newest = maxOf(newest, info.timestamp)
            val trace = runCatching { info.traceInputStream?.use { readText(it, MAX_THREAD_DUMP) } }.getOrNull()
            val level = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> "ERROR"
                ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "WARN"
                else -> "INFO"
            }
            val reasonStr = exitReasonName(info.reason)
            val importanceStr = decodeImportance(info.importance)
            val pssMb = info.pss / 1024
            val rssMb = info.rss / 1024
            val memSummary = if (pssMb > 0) " · PSS ${pssMb}MB" else ""
            val sigInfo = decodeSignal(info.status)
            val statusStr = if (sigInfo != null) " ($sigInfo)" else if (info.status != 0) " (status ${info.status})" else ""

            val title = trf("上次进程退出：{0}", "$reasonStr$statusStr · $importanceStr$memSummary")

            val detailReport = buildString {
                appendLine("=========================================")
                appendLine("【系统进程退出诊断 (ApplicationExitInfo)】")
                appendLine("=========================================")
                appendLine("退出时间: ${Instant.ofEpochMilli(info.timestamp)}")
                appendLine("退出原因: $reasonStr (code ${info.reason})")
                appendLine("退出状态: ${info.status}" + (sigInfo?.let { " [$it]" } ?: ""))
                appendLine("应用活跃级别: $importanceStr (importance=${info.importance})")
                appendLine("进程名: ${info.processName.ifBlank { ctx.packageName }}")
                if (pssMb > 0 || rssMb > 0) {
                    appendLine("内存占用快照: PSS=${pssMb}MB, RSS=${rssMb}MB")
                }
                info.description?.takeIf { it.isNotBlank() }?.let {
                    appendLine("系统描述说明: $it")
                }
                appendLine()
                appendLine("设备: ${deviceSummary()}")

                // Java 崩溃已经由崩溃处理器完整记录，这里不重复贴轨迹
                val abnormal = info.reason == ApplicationExitInfo.REASON_CRASH ||
                    info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    info.reason == ApplicationExitInfo.REASON_ANR ||
                    info.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                    info.reason == ApplicationExitInfo.REASON_SIGNALED ||
                    info.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ||
                    info.reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                if (abnormal) {
                    val session = previousSession
                    if (session != null && session.startedAt > 0) {
                        appendLine()
                        appendLine("-----------------------------------------")
                        appendLine("【上一次运行现场】")
                        appendLine("-----------------------------------------")
                        appendLine("启动时间: ${Instant.ofEpochMilli(session.startedAt)}")
                        appendLine("最后心跳: ${Instant.ofEpochMilli(session.heartbeat)} (${formatAgo(session.heartbeat)})")
                        appendLine("会话时长: ${formatUptime(session.heartbeat - session.startedAt)}")
                        appendLine("最后页面: ${session.screen ?: "未记录"}")
                        appendLine("版本: ${session.version}")
                    }
                    if (previousBreadcrumbs.isNotEmpty() && !(previousCrashFileFound && info.reason == ApplicationExitInfo.REASON_CRASH)) {
                        appendLine()
                        appendLine("-----------------------------------------")
                        appendLine("【上次运行最后操作轨迹】")
                        appendLine("-----------------------------------------")
                        // 以系统记录的退出时刻为基准：可以直接看出每步操作距离进程死亡多久
                        appendLine(formatBreadcrumbList(previousBreadcrumbs, info.timestamp))
                    }
                }
                if (!trace.isNullOrBlank()) {
                    appendLine()
                    appendLine("-----------------------------------------")
                    appendLine("【系统 Trace / 故障诊断转储】")
                    appendLine("-----------------------------------------")
                    append(trace)
                }
                appendLine("=========================================")
            }

            write(AppLogEntry(
                level = level,
                category = "exit",
                message = title,
                detail = detailReport.take(MAX_CRASH_REPORT),
            ))
        }
        if (newest != seen) runCatching { seenFile.writeText(newest.toString()) }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> tr("Java 崩溃")
        ApplicationExitInfo.REASON_CRASH_NATIVE -> tr("原生崩溃")
        ApplicationExitInfo.REASON_ANR -> tr("无响应（ANR）")
        ApplicationExitInfo.REASON_LOW_MEMORY -> tr("内存不足")
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> tr("资源占用过高")
        ApplicationExitInfo.REASON_USER_REQUESTED -> tr("用户结束")
        ApplicationExitInfo.REASON_USER_STOPPED -> tr("用户停止")
        ApplicationExitInfo.REASON_SIGNALED -> tr("信号终止")
        ApplicationExitInfo.REASON_EXIT_SELF -> tr("正常退出")
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> tr("初始化失败")
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> tr("权限变更")
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> tr("依赖进程退出")
        ApplicationExitInfo.REASON_OTHER -> tr("其它")
        // 以下为 Android 14 新增的取值，按数值对应，避免引用更高 API 版本的常量
        14 -> tr("系统冻结")
        15 -> tr("应用状态变化")
        16 -> tr("应用更新安装")
        else -> "code $reason"
    }

    /** 读取 /proc/self 下的进程状态，例如 VmRSS、Threads、oom_score_adj */
    private fun procStatus(key: String): String? = runCatching {
        File("/proc/self/status").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 文件超过 [maxBytes] 时只保留后一半，避免崩溃历史无限增长 */
    private fun trimFile(target: File, maxBytes: Long) {
        runCatching {
            if (target.length() <= maxBytes) return
            val bytes = target.readBytes()
            target.writeBytes(bytes.copyOfRange((bytes.size - maxBytes / 2).coerceAtLeast(0).toInt(), bytes.size))
        }
    }

    /** 最多读取 [limit] 个字符，超出部分丢弃，避免超大的 trace 撑爆内存 */
    private fun readText(input: InputStream, limit: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, minOf(n, limit - out.size()))
        }
        return out.toString("UTF-8")
    }

    private fun write(raw: AppLogEntry) {
        // detail 里可能是异常信息或系统 trace，同样要脱敏（message 在各入口已处理）
        val entry = if (raw.detail == null) raw else raw.copy(detail = redact(raw.detail))
        if (raw.category != "exit" && raw.category != "crash") {
            breadcrumb(raw.category, raw.message)
        }
        runCatching { Log.println(if (entry.level == "ERROR") Log.ERROR else if (entry.level == "WARN") Log.WARN else Log.INFO, TAG, "[${entry.category}] ${entry.message}") }
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(json.encodeToString(AppLogEntry.serializer(), entry) + "\n")
                if (file.length() > MAX_BYTES) {
                    val bytes = file.readBytes()
                    file.writeBytes(bytes.copyOfRange((bytes.size - MAX_BYTES / 2).coerceAtLeast(0).toInt(), bytes.size))
                }
            }
            // 文件写入和状态排队必须处于同一临界区，clear 才能得到完全相同的顺序。
            updateState {
                // 同一个 id 只保留一条：界面用 id 作为 LazyColumn 的 key，重复会抛
                // 「Key ... was already used」把整个日志页打崩
                val dup = entries.indexOfFirst { it.id == entry.id }
                if (dup >= 0) entries.removeAt(dup)
                entries.add(0, entry)
                while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
                version.intValue++
            }
        }
    }

    /** 所有状态更新按文件操作顺序进入主线程队列，避免后台写入越过随后的 clear。 */
    private fun updateState(update: () -> Unit) {
        // post 返回 false 表示主线程队列已不可用（退出中 / 测试环境），此时直接更新，
        // 否则界面永远拿不到新日志，日志页会一直显示旧内容。
        val posted = mainHandler?.post { Snapshot.withMutableSnapshot { update() } } == true
        if (!posted) runCatching { Snapshot.withMutableSnapshot { update() } }
    }

    private fun load() {
        synchronized(lock) {
            val loaded = runCatching {
                file.takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
                    runCatching { json.decodeFromString(AppLogEntry.serializer(), line) }.getOrNull()
                }.orEmpty()
            }.getOrDefault(emptyList())
            // 旧版本曾把同一条崩溃同时写进崩溃文件和主日志，日志里因此有重复 id；
            // 界面用 id 做 LazyColumn 的 key，重复会直接崩溃，所以读取时按 id 去重（保留最后一条）。
            val trimmed = dedupeById(loaded).takeLast(MAX_ENTRIES)
            updateState {
                entries.clear()
                entries.addAll(trimmed.asReversed())
                version.intValue++
            }
        }
    }

    private val SECRET = Regex("(?i)(bearer\\s+|gh[pousr]_[A-Za-z0-9_]+|token[=:]\\s*)[^\\s,;]+")

    /** 去掉 Token 等凭据，不截断 */
    private fun redact(text: String): String = text.replace(SECRET, "\$1<redacted>")

    private fun safe(message: String): String = redact(message).take(1000)
}
