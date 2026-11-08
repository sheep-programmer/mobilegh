package com.mobilegh.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.mobilegh.BuildConfig
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/**
 * 在线更新：从 GitHub Release 读取最新版本，按当前设备架构挑选对应 APK。
 *
 * 下载复用 [Downloads]，因此公开仓库的 APK 会走已配置的加速节点；
 * 更新源就是本项目自己的 Release，无需额外的更新服务器。
 */
object Update {
    private const val OWNER = "sheep-programmer"
    private const val REPO = "mobilegh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 界面相关的清理（删除下载记录）需要在主线程执行 */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private const val PREFS = "update"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    data class Info(
        val version: String,
        val tag: String,
        val notes: String?,
        val assetName: String,
        val url: String,
        val size: Long,
    )

    /** 当前设备最匹配的 ABI 关键字，与 Release 资源命名保持一致 */
    val abi: String = when {
        Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", true) } -> "arm64-v8a"
        Build.SUPPORTED_ABIS.any { it.equals("armeabi-v7a", true) } -> "armeabi-v7a"
        Build.SUPPORTED_ABIS.any { it.equals("x86_64", true) } -> "x86_64"
        else -> "universal"
    }

    var checking by mutableStateOf(false)
        private set

    /** 发现的新版本；null 表示已是最新或尚未检查 */
    var available by mutableStateOf<Info?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 查询最新 Release，挑出匹配当前架构的 APK */
    suspend fun check(): Info? {
        checking = true
        error = null
        return try {
            val rel = GitHub.latestRelease(OWNER, REPO, false) ?: return null.also { available = null }
            val version = rel.tagName.trimStart('v', 'V')
            val apk = rel.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            val asset = apk.firstOrNull { it.name.contains(abi, ignoreCase = true) }
                ?: apk.firstOrNull { it.name.contains("universal", ignoreCase = true) }
                ?: apk.firstOrNull()
                ?: return null.also { available = null }
            val info = Info(version, rel.tagName, rel.body, asset.name, asset.browserDownloadUrl, asset.size)
            available = info.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
            AppLog.info("update", trf("检查更新：远端 {0} / 当前 {1} / 架构 {2}", version, BuildConfig.VERSION_NAME, abi))
            available
        } catch (e: Throwable) {
            error = e.networkHint() ?: e.message ?: tr("检查更新失败")
            AppLog.warn("update", tr("检查更新失败"), e)
            null
        } finally {
            checking = false
        }
    }

    /**
     * 启动检查发现新版本时是否自动弹窗：同一版本只弹一次；选了「跳过此版本」后，这个版本不再自动提示
     * （设置里仍能手动查看与下载）。
     */
    fun shouldPrompt(info: Info): Boolean =
        ::prefs.isInitialized && prefs.getString("skippedVersion", null) != info.version && prompted != info.version

    /** 本次运行中已经自动提示过的版本 */
    private var prompted: String? = null

    fun markPrompted(info: Info) { prompted = info.version }

    fun skip(info: Info) {
        prompted = info.version
        if (::prefs.isInitialized) prefs.edit().putString("skippedVersion", info.version).apply()
    }

    /** 启动后静默检查一次，有新版本再提示 */
    fun checkQuietly() {
        if (checking || available != null) return
        scope.launch { runCatching { check() } }
    }

    /** 下载并安装；完成后调用方负责触发安装 */
    fun download(info: Info) {
        // 记下待安装的版本：下次启动时若已是这个版本，说明安装包已经装好，可以删除
        prefs.edit().putString("pendingFile", info.assetName).putString("pendingVersion", info.version).apply()
        Downloads.start(
            rawUrl = info.url,
            filename = info.assetName,
            isPrivate = false,
            mimeType = "application/vnd.android.package-archive",
        )
    }

    /**
     * 启动时调用：上次下载的安装包对应的版本已经在运行，说明更新已装好。
     * 删除安装包和它的下载记录，释放空间；版本还没到（没装、或取消了）则保留，方便之后再装。
     */
    fun removeInstalledPackage(ctx: Context) {
        val file = prefs.getString("pendingFile", null) ?: return
        val version = prefs.getString("pendingVersion", null) ?: return
        if (!shouldRemoveInstalledPackage(version, BuildConfig.VERSION_NAME)) return
        prefs.edit().clear().apply()
        mainScope.launch {
            val installed = Downloads.tasks.filter { it.filename == file && it.state == DownloadState.Complete }
            installed.forEach { task ->
                Downloads.deleteFile(ctx, task)
                Downloads.forget(ctx, task)
            }
            if (installed.isNotEmpty()) AppLog.info("update", trf("已删除已安装更新的安装包：{0}", file))
        }
    }

    /** 版本号比较：按数字段依次比较，允许 "1.2.3" 形式 */
    fun isNewer(remote: String, current: String): Boolean = isNewerVersion(remote, current)
}

/**
 * 版本号比较：按数字段依次比较，允许 "1.2.3" 形式。
 * 放在 object 外面：单测不会初始化 [Update]（它依赖 android.os.Build）。
 */
internal fun isNewerVersion(remote: String, current: String): Boolean {
    val r = remote.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
    val c = current.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
    if (r.isEmpty()) return false
    for (i in 0 until maxOf(r.size, c.size)) {
        val a = r.getOrElse(i) { 0 }
        val b = c.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

/**
 * 上次下载的安装包是否可以删除：记录下来的版本已经不低于当前运行的版本，
 * 说明新版本已经装好；版本无法识别时保守地保留安装包。
 */
internal fun shouldRemoveInstalledPackage(pendingVersion: String?, currentVersion: String): Boolean {
    if (pendingVersion.isNullOrBlank()) return false
    if (pendingVersion.none { it.isDigit() }) return false
    return !isNewerVersion(pendingVersion, currentVersion)
}
