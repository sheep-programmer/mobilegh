package com.mobilegh.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.UUID

@Serializable
enum class DownloadState { Preparing, Queued, Running, Waiting, Complete, Failed, Cancelled }

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
) {
    val active get() = state in setOf(DownloadState.Preparing, DownloadState.Queued, DownloadState.Running, DownloadState.Waiting)
    val progress: Float? get() = when {
        state == DownloadState.Complete -> 1f
        total > 0 -> (downloaded.toDouble() / total).toFloat().coerceIn(0f, 0.99f)
        else -> null
    }
}

/** 系统负责后台传输，应用负责进度、历史和文件操作；这里不会保存 Token。 */
object Downloads {
    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var foreground = false
    private val manager get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val privateHttp by lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build() }
    private val authHosts = setOf("github.com", "api.github.com", "raw.githubusercontent.com", "codeload.github.com")

    val tasks = mutableStateListOf<DownloadTask>()
    var selectedKey by mutableStateOf<String?>(null)
    var showHistory by mutableStateOf(false)
    val activeCount get() = tasks.count { it.active }

    fun init(ctx: Context) {
        context = ctx.applicationContext
        prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        tasks.clear()
        runCatching {
            val stored = prefs.getString("tasks", null) ?: return@runCatching
            tasks.addAll(Api.plain.decodeFromString<List<DownloadTask>>(stored).map {
                if (it.active && it.managerId == null) it.copy(state = DownloadState.Failed, message = "下载准备被中断，请重试") else it
            })
        }
    }

    fun setForeground(visible: Boolean) {
        foreground = visible
        if (visible) startMonitor() else { monitor?.cancel(); monitor = null }
    }

    fun start(rawUrl: String, filename: String?, isPrivate: Boolean, mimeType: String? = null) {
        tasks.firstOrNull { it.url == rawUrl && it.active }?.let { selectedKey = it.key; showHistory = false; return }
        val task = DownloadTask(
            url = rawUrl,
            filename = safeFilename(filename ?: Uri.parse(rawUrl).lastPathSegment ?: "download"),
            isPrivate = isPrivate,
            mimeType = mimeType,
        )
        tasks.add(0, task)
        tasks.filter { !it.active }.drop(20).forEach { tasks.remove(it) }
        selectedKey = task.key
        showHistory = false
        AppLog.info("download", "开始下载：" + task.filename + "（" + if (isPrivate) "private" else "public" + "）")
        save()
        scope.launch {
            try {
                val queued = withContext(Dispatchers.IO) { enqueue(task, Session.token) }
                if (tasks.firstOrNull { it.key == task.key }?.state == DownloadState.Cancelled) {
                    withContext(Dispatchers.IO) { queued.managerId?.let { manager.remove(it) } }
                } else {
                    update(queued)
                    save()
                    startMonitor()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error("download", "无法开始下载：" + task.filename, e)
                if (tasks.firstOrNull { it.key == task.key }?.state != DownloadState.Cancelled) {
                    update(task.copy(state = DownloadState.Failed, message = e.message ?: "无法开始下载"))
                    save()
                }
            }
        }
    }

    private fun enqueue(task: DownloadTask, token: String?): DownloadTask {
        var url = task.url
        var authorization: String? = null
        if (task.isPrivate) {
            require(!token.isNullOrBlank()) { "请先登录 GitHub 后下载" }
            val resolved = resolvePrivateUrl(url, token)
            url = resolved.first
            authorization = resolved.second
        } else {
            url = Net.download(url)
        }
        AppLog.info("download", task.filename + " 使用节点：" + if (task.isPrivate) "直连 GitHub" else Net.downloadNode().name + "；地址主机：" + (Uri.parse(url).host ?: "unknown"))
        val appDirectory = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val directory = if (appDirectory) context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        requireNotNull(directory) { "下载目录不可用" }
        val actualName = uniqueFilename(task.filename, directory)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(actualName)
            setDescription("MobileGH 下载")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            if (appDirectory) setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, actualName)
            else setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, actualName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            (task.mimeType ?: mimeFor(actualName))?.let(::setMimeType)
            authorization?.let { addRequestHeader("Authorization", it) }
            if (task.isPrivate && Uri.parse(url).host == "api.github.com") {
                addRequestHeader("Accept", "application/octet-stream")
                addRequestHeader("X-GitHub-Api-Version", "2022-11-28")
            }
            addRequestHeader("User-Agent", "MobileGH (Android)")
        }
        return task.copy(managerId = manager.enqueue(request), filename = actualName, state = DownloadState.Queued, appDirectory = appDirectory)
    }

    private fun resolvePrivateUrl(initial: String, token: String): Pair<String, String?> {
        var url = initial.toHttpUrl()
        repeat(6) {
            require(url.isHttps) { "GitHub 下载地址必须使用 HTTPS" }
            if (url.host !in authHosts) return url.toString() to null
            val req = Request.Builder().url(url).header("Authorization", "Bearer " + token)
                .header("Accept", "application/octet-stream").build()
            privateHttp.newCall(req).execute().use { response ->
                if (response.code in 300..399) {
                    val next = response.header("Location")?.let(url::resolve) ?: error("下载跳转缺少地址")
                    require(next.isHttps) { "下载跳转必须使用 HTTPS" }
                    url = next
                } else {
                    require(response.isSuccessful) {
                        when (response.code) {
                            401 -> "GitHub 授权失效，请重新登录"
                            403, 404 -> "没有下载权限，或资源已经删除"
                            else -> "无法下载资源（HTTP " + response.code + "）"
                        }
                    }
                    return url.toString() to ("Bearer " + token)
                }
            }
        }
        error("下载地址跳转过多")
    }

    private fun startMonitor() {
        if (!foreground || monitor?.isActive == true) return
        monitor = scope.launch {
            do {
                refresh()
                delay(500)
            } while (isActive && tasks.any { it.active && it.managerId != null })
        }
    }

    suspend fun refresh() {
        val snapshot = tasks.filter { it.managerId != null && it.state != DownloadState.Cancelled }
        if (snapshot.isEmpty()) return
        val rows = withContext(Dispatchers.IO) {
            runCatching {
                manager.query(DownloadManager.Query().setFilterById(*snapshot.map { it.managerId!! }.toLongArray())).use { cursor ->
                    val result = HashMap<Long, DownloadTask>()
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                        val original = snapshot.firstOrNull { it.managerId == id } ?: continue
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        val state = when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> DownloadState.Complete
                            DownloadManager.STATUS_FAILED -> DownloadState.Failed
                            DownloadManager.STATUS_RUNNING -> DownloadState.Running
                            DownloadManager.STATUS_PAUSED -> DownloadState.Waiting
                            else -> DownloadState.Queued
                        }
                        result[id] = original.copy(
                            state = state,
                            downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0),
                            total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                            message = when (state) {
                                DownloadState.Failed -> failureMessage(reason)
                                DownloadState.Waiting -> if (reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK || reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) "等待网络连接" else "网络中断，正在重试"
                                else -> null
                            },
                        )
                    }
                    result
                }
            }.getOrNull()
        } ?: return
        var persist = false
        snapshot.forEach { previous ->
            val current = tasks.firstOrNull { it.key == previous.key } ?: return@forEach
            if (current.state == DownloadState.Cancelled) return@forEach
            val next = rows[previous.managerId] ?: previous.copy(state = DownloadState.Failed, message = "下载记录或文件已被移除，请重试")
            if (current != next) persist = true
            update(next)
        }
        if (persist) save()
    }

    fun cancel(task: DownloadTask) {
        if (!task.active) return
        update(task.copy(state = DownloadState.Cancelled, message = null))
        save()
        task.managerId?.let { id -> scope.launch { withContext(Dispatchers.IO) { runCatching { manager.remove(id) } } } }
    }

    fun retry(task: DownloadTask) {
        if (task.active) return
        tasks.removeAll { it.key == task.key }
        start(task.url, task.filename, task.isPrivate, task.mimeType)
    }

    fun open(ctx: Context, task: DownloadTask) {
        scope.launch {
            val id = task.managerId ?: return@launch
            val (uri, mime) = withContext(Dispatchers.IO) {
                runCatching { manager.getUriForDownloadedFile(id) to manager.getMimeTypeForDownloadedFile(id) }.getOrDefault(null to null)
            }
            if (uri == null) {
                update(task.copy(state = DownloadState.Failed, message = "文件已被移动或删除，请重新下载"))
                save()
                return@launch
            }
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, task.mimeType ?: mimeFor(task.filename) ?: mime ?: "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }.onFailure {
                android.widget.Toast.makeText(ctx, "没有可以打开此文件的应用，文件已保存在下载目录", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun update(task: DownloadTask) {
        tasks.indexOfFirst { it.key == task.key }.takeIf { it >= 0 }?.let { index ->
            val old = tasks[index]
            if (old != task) {
                if (old.state != task.state && task.state in setOf(DownloadState.Complete, DownloadState.Failed, DownloadState.Cancelled)) {
                    AppLog.info("download", task.filename + "：" + task.state.name + (task.message?.let { " · " + it } ?: ""))
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
    private fun failureMessage(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请清理后重试"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "同名文件已存在，请重试"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "存储设备不可用"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下载地址跳转过多"
        DownloadManager.ERROR_FILE_ERROR -> "无法写入文件，请检查下载目录"
        else -> "下载失败，请检查网络后重试（" + reason + "）"
    }
}
