package com.mobilegh.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.UUID

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
 */
object AppLog {
    private const val TAG = "MobileGH"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_ENTRIES = 3000
    private lateinit var file: File
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
    val entries = mutableStateListOf<AppLogEntry>()
    val version = mutableIntStateOf(0)
    private var installed = false

    fun init(ctx: Context) {
        if (installed) return
        installed = true
        file = File(ctx.filesDir, "logs/mobilegh.log")
        file.parentFile?.mkdirs()
        load()
        info("app", "MobileGH 启动")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error("crash", "未捕获异常：" + (throwable.message ?: throwable.javaClass.simpleName), throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun info(category: String, message: String) = write(AppLogEntry(level = "INFO", category = category, message = safe(message)))
    fun warn(category: String, message: String, error: Throwable? = null) = write(entry("WARN", category, message, error))
    fun error(category: String, message: String, error: Throwable? = null) = write(entry("ERROR", category, message, error))

    fun clear() {
        synchronized(lock) { runCatching { file.writeText("") } }
        Snapshot.withMutableSnapshot { entries.clear(); version.intValue++ }
    }

    fun export(list: List<AppLogEntry>): String = buildString {
        appendLine("MobileGH 日志导出")
        appendLine("生成时间：" + Instant.now())
        appendLine("日志数：" + list.size)
        appendLine()
        list.asReversed().forEach { e ->
            append('[').append(e.time).append("] [").append(e.level).append("] [")
                .append(e.category).append("] ").append(e.message).appendLine()
            e.detail?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        }
    }

    private fun entry(level: String, category: String, message: String, error: Throwable?): AppLogEntry {
        val detail = error?.let {
            StringWriter().also { sw -> it.printStackTrace(PrintWriter(sw)) }.toString().take(12000)
        }
        return AppLogEntry(level = level, category = category, message = safe(message), detail = detail, thread = Thread.currentThread().name)
    }

    private fun write(entry: AppLogEntry) {
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
        }
        Snapshot.withMutableSnapshot {
            entries.add(0, entry)
            while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
            version.intValue++
        }
    }

    private fun load() {
        val loaded = synchronized(lock) {
            runCatching {
                file.takeIf { it.exists() }?.readLines()?.takeLast(MAX_ENTRIES)?.mapNotNull { line ->
                    runCatching { json.decodeFromString(AppLogEntry.serializer(), line) }.getOrNull()
                }.orEmpty()
            }.getOrDefault(emptyList())
        }
        Snapshot.withMutableSnapshot {
            entries.clear()
            entries.addAll(loaded.asReversed())
            version.intValue++
        }
    }

    private fun safe(message: String): String = message.replace(Regex("(?i)(bearer\\s+|gh[pousr]_[A-Za-z0-9_]+|token[=:]\\s*)[^\\s,;]+"), "\\$1<redacted>").take(1000)
}
