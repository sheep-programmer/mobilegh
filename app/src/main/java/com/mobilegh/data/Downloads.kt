package com.mobilegh.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.mobilegh.ui.components.toast
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
    /** 下载完成后的落地路径。用于打开文件 / 删除本地文件；DownloadManager 的 URI 不可靠。 */
    val filePath: String? = null,
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
        AppLog.info("download", "开始下载：" + task.filename + "（" + (if (isPrivate) "private" else "public") + "）")
        save()
        scope.launch {
            try {
                val queued = withContext(Dispatchers.IO) { enqueue(task, Session.token) }
                val current = tasks.firstOrNull { it.key == task.key }
                if (current == null || current.state == DownloadState.Cancelled) {
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
        return task.copy(
            managerId = manager.enqueue(request),
            filename = actualName,
            state = DownloadState.Queued,
            appDirectory = appDirectory,
            filePath = File(directory, actualName).absolutePath,
        )
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

    /**
     * 打开已下载的文件。
     *
     * 不用 DownloadManager.getUriForDownloadedFile：它返回的是 content://downloads/... 内部 URI，
     * 外部应用通常无权读取（点击无反应）；而且 VISIBILITY_HIDDEN 的记录会被系统在 7 天后静默清理。
     * 这里改为直接按记录下来的落盘路径，通过 FileProvider 生成可读 URI。
     */
    fun open(ctx: Context, task: DownloadTask) {
        scope.launch {
            val mime = task.mimeType ?: mimeFor(task.filename) ?: "application/octet-stream"
            val uri = withContext(Dispatchers.IO) { readableUri(ctx, task) }
            AppLog.info("download", "打开文件：" + task.filename + " → " + (uri?.toString() ?: "无可用 URI"))
            if (uri == null) {
                update(task.copy(state = DownloadState.Failed, message = "文件已被移动或删除，请重新下载"))
                save()
                return@launch
            }
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
                .onSuccess { AppLog.info("download", "已请求系统打开：" + task.filename) }
                .onFailure {
                    AppLog.warn("download", "没有应用可以打开 " + task.filename + "：" + it.message)
                    ctx.toast("没有可以打开此文件的应用，文件已保存在「下载」文件夹")
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
                }.onFailure { ctx.toast("请先在系统设置中允许本应用安装应用") }
                ctx.toast("请开启「允许安装未知应用」，然后回来再次点击更新")
                return
            }
        }
        open(ctx, task)
    }

    /**
     * 取得一个外部应用可读的 URI。
     *
     * 优先用落盘路径经 FileProvider 分享（API 26–28 与部分 29+ 设备可用）；
     * API 29+ 起公共「下载」目录受分区存储限制，java.io.File 可能读不到，
     * 此时改为在 MediaStore.Downloads 中按文件名查找真正的 content://media URI。
     */
    private fun readableUri(ctx: Context, task: DownloadTask): Uri? {
        task.filePath?.let { path ->
            val f = File(path)
            if (f.isFile) runCatching { FileProvider.getUriForFile(ctx, authority(ctx), f) }.getOrNull()?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreUri(ctx, task.filename)?.let { return it }
        }
        // 最后退回 DownloadManager 自己的 URI（同一应用入队时可用）
        return task.managerId?.let { id -> runCatching { manager.getUriForDownloadedFile(id) }.getOrNull() }
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
                        setDataAndType(fileUri.parentOrSelf(), "vnd.android.document/directory")
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
        ctx.toast("没有可用的文件管理器，文件保存在「下载」文件夹")
    }

    /** 删除本地文件（可选择只删文件、保留记录） */
    suspend fun deleteFile(ctx: Context, task: DownloadTask): Boolean {
        val ok = withContext(Dispatchers.IO) { deleteFileQuiet(ctx, task) }
        if (ok) {
            task.managerId?.let { id -> withContext(Dispatchers.IO) { runCatching { manager.remove(id) } } }
            tasks.firstOrNull { it.key == task.key }?.let { current ->
                update(current.copy(managerId = null, filePath = null, state = DownloadState.Cancelled, message = "本地文件已删除"))
                save()
            }
        }
        return ok
    }

    /** 从下载记录中移除（不删除已完成的本地文件） */
    fun forget(ctx: Context, task: DownloadTask) {
        tasks.removeAll { it.key == task.key }
        if (selectedKey == task.key) selectedKey = null
        save()
        task.managerId?.takeIf { task.active }?.let { id ->
            scope.launch { withContext(Dispatchers.IO) { runCatching { manager.remove(id) } } }
        }
    }

    /** 清空下载历史；先移除任务，再在后台删除文件，避免查询/删除阻塞界面。 */
    suspend fun clearHistory(ctx: Context, withFiles: Boolean): Pair<Int, Int> {
        val snapshot = tasks.toList()
        tasks.clear()
        selectedKey = null
        showHistory = false
        save()
        val files = withContext(Dispatchers.IO) {
            var deleted = 0
            snapshot.forEach { task ->
                if (withFiles && deleteFileQuiet(ctx, task)) deleted++
                task.managerId?.takeIf { task.active || withFiles }?.let { id -> runCatching { manager.remove(id) } }
            }
            deleted
        }
        AppLog.info("download", "清空下载记录 ${snapshot.size} 条" + if (withFiles) "，同时删除 $files 个本地文件" else "，保留已下载文件")
        return snapshot.size to files
    }

    /** 删除本地文件但不改动记录状态（用于后台删除/清空历史） */
    private fun deleteFileQuiet(ctx: Context, task: DownloadTask): Boolean {
        val file = task.filePath?.let { File(it) }?.takeIf { it.isFile }
        var ok = file != null && runCatching { file.delete() }.getOrDefault(false)
        if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 分区存储下 File 删除会失败，改用 MediaStore 删除自己创建的文件（无需额外权限）
            ok = mediaStoreUri(ctx, task.filename)?.let { uri ->
                runCatching { ctx.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            } ?: false
        }
        return ok
    }

    fun fileExists(task: DownloadTask): Boolean = task.filePath?.let { File(it).isFile } == true

    suspend fun deleteAllFiles(ctx: Context): Int = clearHistory(ctx, true).second

    private fun authority(ctx: Context) = ctx.packageName + ".fileprovider"

    private fun Uri.parentOrSelf(): Uri = this

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
