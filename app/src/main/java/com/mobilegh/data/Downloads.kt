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
    /** 公开下载的候选地址（按节点排列）：失败后自动换下一个，私有下载为空（只有一条直连地址） */
    val targets: List<DownloadTarget> = emptyList(),
    /** 当前正在使用的候选序号 */
    val attempt: Int = 0,
    /** 本次尝试使用的节点，用于统计哪个节点真的能下载 */
    val nodeId: String? = null,
    val nodeName: String? = null,
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
    /** 没有进度的任务从什么时候开始卡住的（只记内存，用于判断该换节点了） */
    private val stalledSince = HashMap<String, Long>()
    private const val STALL_MS = 20_000L
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
            // 私有下载只有一条解析后的直连地址；公开下载准备好整条节点候选链，失败自动换下一个
            targets = if (isPrivate) emptyList() else Net.downloadTargets(rawUrl),
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
                val current = tasks.firstOrNull { it.key == task.key }?.takeIf { it.state != DownloadState.Cancelled }
                // 起不来也可能是这个节点不通，先换节点，全都试过才把错误显示给用户
                val rotated = if (current != null) rotate(current, e.networkHint() ?: e.message) else null
                if (rotated == null) {
                    AppLog.warn("download", "无法开始下载：" + task.filename + "：" + (e.message ?: e.javaClass.simpleName))
                    if (current != null) {
                        reportAllFailed(current)
                        update(current.copy(state = DownloadState.Failed, message = e.networkHint() ?: e.message ?: "无法开始下载"))
                        save()
                    }
                }
            }
        }
    }

    private fun enqueue(task: DownloadTask, token: String?): DownloadTask {
        var url = task.url
        var authorization: String? = null
        // 候选链里的当前一条；旧记录没有候选链时退回单节点选择
        val target = task.targets.getOrNull(task.attempt)
        if (task.isPrivate) {
            require(!token.isNullOrBlank()) { "请先登录 GitHub 后下载" }
            val resolved = resolvePrivateUrl(url, token)
            url = resolved.first
            authorization = resolved.second
        } else if (target != null) {
            url = target.url
        } else {
            url = Net.download(url)
        }
        val nodeName = if (task.isPrivate) "直连 GitHub" else target?.nodeName ?: Net.downloadNode().name
        AppLog.info(
            "download",
            task.filename + " 使用节点：" + nodeName +
                if (target != null && task.targets.size > 1) "（第 ${task.attempt + 1}/${task.targets.size} 个候选）" else "" +
                "；地址主机：" + (Uri.parse(url).host ?: "unknown"),
        )
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
            nodeId = if (task.isPrivate) null else target?.nodeId,
            nodeName = nodeName,
        )
    }

    /**
     * 换用下一个候选节点重试。返回换过节点的新任务；返回 null 表示已经试完所有候选，
     * 调用方按失败处理。私有下载和手动选定的节点都不换。
     */
    private suspend fun rotate(task: DownloadTask, reason: String?): DownloadTask? {
        val next = task.attempt + 1
        // 没有下一个候选了：停在最后一个节点上继续等系统自己重试，失败由调用方统一处理
        if (task.isPrivate || next >= task.targets.size) return null
        val failed = task.targets[task.attempt]
        val target = task.targets[next]
        // 新的一次尝试重新计时，否则会拿着上一个节点的超时时间立刻再换一次
        stalledSince.remove(task.key)
        AppLog.warn(
            "download",
            task.filename + " 节点 " + failed.nodeName + " 失败" + (reason?.let { "（$it）" } ?: "") + "，改用 " + target.nodeName + " 重试",
        )
        Net.markDownloadFail(failed.nodeId)
        // 清掉上一次的排队记录和残留的半截文件，避免下一个节点因同名文件直接失败
        task.managerId?.let { id -> withContext(Dispatchers.IO) { runCatching { manager.remove(id) } } }
        withContext(Dispatchers.IO) { task.filePath?.let { path -> runCatching { File(path).delete() } } }
        val rotating = tasks.firstOrNull { it.key == task.key } ?: return null
        if (rotating.state == DownloadState.Cancelled) return null
        val updated = rotating.copy(
            attempt = next,
            state = DownloadState.Preparing,
            managerId = null,
            downloaded = 0,
            total = -1,
            message = "节点 " + failed.nodeName + " 不通，正在改用 " + target.nodeName,
        )
        update(updated)
        save()
        return try {
            enqueue(updated, Session.token).also { update(it); save() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rotate(updated, e.networkHint() ?: e.message)
        }
    }

    /** 所有候选都试过之后的收尾：记下失败节点并重新测速，供下次选择参考 */
    private fun reportAllFailed(task: DownloadTask) {
        if (task.targets.size <= 1) return
        task.targets.getOrNull(task.attempt)?.let { Net.markDownloadFail(it.nodeId) }
        AppLog.warn("download", task.filename + " 所有下载节点都失败了")
        Net.speedTestAsync()
    }

    private fun resolvePrivateUrl(initial: String, token: String): Pair<String, String?> {
        var url = initial.toHttpUrl()
        repeat(6) {
            require(url.isHttps) { "GitHub 下载地址必须使用 HTTPS" }
            if (url.host !in authHosts) return url.toString() to null
            val req = Request.Builder().url(url).header("Authorization", "Bearer " + token)
                .header("Accept", privateDownloadAccept(url.toString())).build()
            privateHttp.routed().newCall(req).execute().use { response ->
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
        val queried = withContext(Dispatchers.IO) {
            runCatching {
                manager.query(DownloadManager.Query().setFilterById(*snapshot.map { it.managerId!! }.toLongArray())).use { cursor ->
                    val result = HashMap<Long, DownloadTask>()
                    val reasons = HashMap<Long, Int>()
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
                        if (state == DownloadState.Failed) reasons[id] = reason
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
                    result to reasons
                }
            }.getOrNull()
        } ?: return
        val rows = queried.first
        val reasons = queried.second
        var persist = false
        snapshot.forEach { previous ->
            val current = tasks.firstOrNull { it.key == previous.key } ?: return@forEach
            if (current.state == DownloadState.Cancelled) return@forEach
            var next = rows[previous.managerId] ?: previous.copy(state = DownloadState.Failed, message = "下载记录或文件已被移除，请重试")
            val reason = reasons[previous.managerId]
            val stalled = stalledReason(current.key, next, reason)
            // 第一次看到失败、且原因像是节点不通时自动换下一个节点，而不是直接把错误抛给用户
            if (next.state == DownloadState.Failed && current.state != DownloadState.Failed &&
                reason != null && retryableFailure(reason)
            ) {
                val rotated = rotate(current, next.message)
                next = rotated ?: if (current.targets.size > 1) {
                    reportAllFailed(current)
                    next.copy(message = "所有下载节点都不通，请检查网络后重试")
                } else next
            } else if (stalled != null) {
                // 节点不通时系统会自己重试好几轮，等它自己认输要几分钟，这里先换节点
                next = rotate(current, stalled) ?: next
            }
            if (next.state in setOf(DownloadState.Complete, DownloadState.Failed, DownloadState.Cancelled)) {
                stalledSince.remove(current.key)
            }
            if (current != next) persist = true
            update(next)
        }
        if (persist) save()
    }

    /**
     * 卡在某个节点上的判断。
     *
     * 交给系统之后一直没有进展（没有任何字节，或者暂停了很久），说明这个节点不通：
     * 系统自己会重试好几轮，等它认输要好几分钟，不如先换下一个节点。
     * 「等待网络连接」不换——那种情况网络恢复后系统会自己续传。
     * 返回卡住的原因（用于换节点），没卡住返回 null。
     */
    private fun stalledReason(key: String, task: DownloadTask, reason: Int?): String? {
        val waitingForNetwork = task.state == DownloadState.Waiting &&
            (reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK || reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI)
        // 排在另一个正在下载的任务后面等着，不算卡住
        val queuedBehindAnother = tasks.any { it.key != key && it.state == DownloadState.Running }
        val stuck = task.active && !waitingForNetwork && !queuedBehindAnother &&
            (task.state != DownloadState.Running || task.downloaded <= 0)
        if (!stuck) {
            stalledSince.remove(key)
            return null
        }
        val since = stalledSince[key]
        if (since == null) {
            stalledSince[key] = System.currentTimeMillis()
            return null
        }
        // 已经下了一部分又停住的多给一点时间：可能是大文件在慢速网络上，系统还能续传
        val limit = if (task.downloaded > 0) STALL_MS * 3 else STALL_MS
        return if (System.currentTimeMillis() - since >= limit) "节点没有响应" else null
    }

    /**
     * 换节点重试是否有可能成功。
     *
     * 空间不足、文件已存在、存储设备不可用、写入失败这类原因换哪个节点都一样；
     * 权限/未找到（401/403/404/407/451）也说明是资源本身的问题，不该反复重试。
     */
    private fun retryableFailure(reason: Int) = retryableDownloadFailure(reason)

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
        stalledSince.clear()
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
                if (old.state != task.state && task.state == DownloadState.Waiting) {
                    AppLog.info("download", task.filename + "：暂停（" + (task.message ?: "原因未知") + "）")
                }
                // 记下哪个节点真的下成了：下次优先用它，失败过的节点排到最后
                if (old.state != task.state && task.state == DownloadState.Complete) {
                    task.nodeId?.let(Net::markDownloadOk)
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
