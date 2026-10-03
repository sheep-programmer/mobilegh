package com.mobilegh.data

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.mobilegh.BuildConfig

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
            AppLog.info("update", "检查更新：远端 $version / 当前 ${BuildConfig.VERSION_NAME} / 架构 $abi")
            available
        } catch (e: Throwable) {
            error = e.message ?: "检查更新失败"
            AppLog.warn("update", "检查更新失败", e)
            null
        } finally {
            checking = false
        }
    }

    /** 启动后静默检查一次，有新版本再提示 */
    fun checkQuietly() {
        if (checking || available != null) return
        scope.launch { runCatching { check() } }
    }

    /** 下载并安装；完成后调用方负责触发安装 */
    fun download(info: Info) {
        Downloads.start(
            rawUrl = info.url,
            filename = info.assetName,
            isPrivate = false,
            mimeType = "application/vnd.android.package-archive",
        )
    }

    /** 版本号比较：按数字段依次比较，允许 "1.2.3" 形式 */
    fun isNewer(remote: String, current: String): Boolean {
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
}
