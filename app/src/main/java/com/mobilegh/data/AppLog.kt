package com.mobilegh.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import java.util.UUID
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

/**
 * 应用内诊断日志：不记录 Token、Cookie、密码或请求响应正文，只记录类别、状态和错误摘要。
 * 文件超过 2 MiB 时保留末尾内容，避免日志影响应用存储。
 *
 * 崩溃相关记录分三层，任何一层都不依赖「进程还活着」：
 * 1. Java 未捕获异常：同步写入独立的崩溃文件，下次启动时写入日志；
 * 2. 系统退出原因（Android 11+）：能看到原生崩溃、ANR、被系统杀掉，这些不经过 Java 异常处理器；
 * 3. 启动时记录设备与版本信息，导出的日志自带这些背景。
 */
object AppLog {
    private const val TAG = "MobileGH"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_ENTRIES = 3000
    private const val MAX_DETAIL = 12000
    private const val MAX_CRASH_REPORT = 64 * 1024
    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var crashFile: File
    private val lock = Any()
    private val mainHandler by lazy { runCatching { Handler(Looper.getMainLooper()) }.getOrNull() }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
    val entries = mutableStateListOf<AppLogEntry>()
    val version = mutableIntStateOf(0)
    private var installed = false

    fun init(ctx: Context) {
        if (installed) return
        installed = true
        dir = File(ctx.filesDir, "logs").also { it.mkdirs() }
        file = File(dir, "mobilegh.log")
        crashFile = File(dir, "last-crash.txt")
        // 先装崩溃处理器：后面的初始化出错也能留下记录
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashReport(thread, throwable)
            previous?.uncaughtException(thread, throwable)
        }
        load()
        reportPreviousCrash()
        info("app", tr("MobileGH 启动") + " · " + deviceSummary())
        recordExitReasons(ctx)
    }

    fun info(category: String, message: String) = write(AppLogEntry(level = "INFO", category = category, message = safe(message)))
    fun warn(category: String, message: String, error: Throwable? = null) = write(entry("WARN", category, message, error))
    fun error(category: String, message: String, error: Throwable? = null) = write(entry("ERROR", category, message, error))

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
        appendLine(tr("日志数：") + list.size)
        appendLine()
        list.asReversed().forEach { e ->
            append('[').append(e.time).append("] [").append(e.level).append("] [")
                .append(e.category).append("] ").append(e.message).appendLine()
            e.detail?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        }
    }

    /** 版本、系统与机型，用于判断问题是否只出现在某个系统版本或某种机型上 */
    private fun deviceSummary(): String =
        "v${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}"

    private fun entry(level: String, category: String, message: String, error: Throwable?): AppLogEntry {
        val detail = error?.let { if (level == "ERROR") stack(it) else brief(it) }
        return AppLogEntry(level = level, category = category, message = safe(message), detail = detail, thread = Thread.currentThread().name)
    }

    private fun stack(e: Throwable) = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString().take(MAX_DETAIL)

    /**
     * WARN 只留一行摘要。
     *
     * 网络类警告每次重试、每个候选节点都会触发，完整堆栈会把日志刷满（一次失败几十行），
     * 真正需要堆栈的崩溃和错误走 [stack]。
     */
    private fun brief(e: Throwable) = (e.javaClass.simpleName + ": " + (e.message ?: "")).trim().take(300)

    /**
     * 未捕获异常：同步写入独立的崩溃文件。不取 [lock]、不经过主线程队列，
     * 即使随后进程立刻结束，报告也已经落盘；任何失败都只吞掉，不能影响原本的崩溃流程。
     */
    private fun saveCrashReport(thread: Thread, throwable: Throwable) {
        runCatching {
            val text = buildString {
                appendLine("time: ${Instant.now()}")
                appendLine("device: ${deviceSummary()}")
                appendLine("thread: ${thread.name}")
                appendLine()
                append(StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString())
            }
            crashFile.writeText(text.take(MAX_CRASH_REPORT))
        }
    }

    /** 上一次运行的未捕获异常：启动时写入日志并删除崩溃文件，每次只报告一次。 */
    private fun reportPreviousCrash() {
        runCatching {
            if (!crashFile.exists()) return
            val text = crashFile.readText()
            crashFile.delete()
            write(AppLogEntry(level = "ERROR", category = "crash", message = tr("上次运行发生未捕获异常"), detail = text.take(MAX_DETAIL)))
        }
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
            val trace = runCatching { info.traceInputStream?.use { readText(it, MAX_DETAIL * 2) } }.getOrNull()
            val level = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> "ERROR"
                ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "WARN"
                else -> "INFO"
            }
            write(AppLogEntry(
                level = level,
                category = "exit",
                message = trf("上次进程退出：{0}", exitReasonName(info.reason)),
                detail = buildString {
                    appendLine("time: ${Instant.ofEpochMilli(info.timestamp)}")
                    appendLine("status: ${info.status}")
                    info.description?.takeIf { it.isNotBlank() }?.let { appendLine("description: $it") }
                    trace?.takeIf { it.isNotBlank() }?.let { append(it) }
                }.take(MAX_DETAIL * 2),
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
                entries.add(0, entry)
                while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
                version.intValue++
            }
        }
    }

    /** 所有状态更新按文件操作顺序进入主线程队列，避免后台写入越过随后的 clear。 */
    private fun updateState(update: () -> Unit) {
        val h = mainHandler
        if (h != null) h.post { Snapshot.withMutableSnapshot { update() } }
        else runCatching { Snapshot.withMutableSnapshot { update() } }
    }

    private fun load() {
        synchronized(lock) {
            val loaded = runCatching {
                file.takeIf { it.exists() }?.readLines()?.takeLast(MAX_ENTRIES)?.mapNotNull { line ->
                    runCatching { json.decodeFromString(AppLogEntry.serializer(), line) }.getOrNull()
                }.orEmpty()
            }.getOrDefault(emptyList())
            updateState {
                entries.clear()
                entries.addAll(loaded.asReversed())
                version.intValue++
            }
        }
    }

    private val SECRET = Regex("(?i)(bearer\\s+|gh[pousr]_[A-Za-z0-9_]+|token[=:]\\s*)[^\\s,;]+")

    /** 去掉 Token 等凭据，不截断 */
    private fun redact(text: String): String = text.replace(SECRET, "\$1<redacted>")

    private fun safe(message: String): String = redact(message).take(1000)
}
