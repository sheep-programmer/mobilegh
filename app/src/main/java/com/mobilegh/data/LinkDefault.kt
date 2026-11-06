package com.mobilegh.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 把 github.com 链接交给本应用打开（用来平替官方 GitHub App）。
 *
 * 官方 App 对 github.com 做了 App Links 验证，系统会直接交给它，应用本身无法越过这个验证。
 * 所以这里只能把用户引导到系统里，把 MobileGH 设为 github.com 的打开方式，并记住用户对「是否询问」的选择。
 */
object LinkDefault {
    /** 询问状态：未询问 / 跳过（下次再问）/ 同意 / 拒绝（之后不再问，设置里仍可手动设置） */
    enum class Status { Unasked, Skipped, Accepted, Declined }

    private const val PREFS = "link_default"
    private const val KEY_STATUS = "status"
    private const val PROBE = "https://github.com/"
    /** 清单里声明的三个域名；用户在系统页「添加链接」时勾选的也是它们 */
    private val HOSTS = listOf("github.com", "www.github.com", "gist.github.com")
    private lateinit var prefs: SharedPreferences

    var status by mutableStateOf(Status.Unasked)
        private set

    /** 本次进程启动后是否已经询问过；屏幕旋转等重建不会再次询问 */
    var askedThisLaunch = false

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        status = runCatching { Status.valueOf(prefs.getString(KEY_STATUS, null).orEmpty()) }.getOrDefault(Status.Unasked)
    }

    /** 询问规则的纯函数形式，便于单测：只有「未询问」或「跳过」且用户还没设置过时才问 */
    internal fun askDecision(current: Status, alreadySet: Boolean): Boolean =
        (current == Status.Unasked || current == Status.Skipped) && !alreadySet

    fun set(next: Status) {
        status = next
        prefs.edit().putString(KEY_STATUS, next.name).apply()
    }

    /**
     * 用户是否已在系统设置里把 github.com 等域名加给本应用。
     *
     * Android 12 起可以查询域名选择状态（[DomainVerificationManager] 只提供查询，写入是系统权限，应用不能代劳），
     * 这个结果比解析 Intent 更准：设备上同时装有浏览器时，选择框会让解析结果不是本应用。
     * 更低版本退回解析 Intent。
     */
    fun isConfigured(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = ctx.getSystemService(DomainVerificationManager::class.java)
            val state = runCatching { manager?.getDomainVerificationUserState(ctx.packageName) }.getOrNull()
            if (state != null) {
                return configured(
                    linkHandlingAllowed = state.isLinkHandlingAllowed,
                    hostStates = state.hostToStateMap,
                    hosts = HOSTS,
                    selectedState = DomainVerificationUserState.DOMAIN_STATE_SELECTED,
                )
            }
        }
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse(PROBE)).addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = ctx.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == ctx.packageName
    }

    /** 进入应用时是否需要询问：用户还没设置过，且没有选过「同意」或「拒绝」 */
    fun shouldAsk(ctx: Context): Boolean = askDecision(status, isConfigured(ctx))

    /**
     * 纯函数形式，便于单测：允许处理链接，且至少有一个域名被用户选中或已验证。
     * [selectedState] 传 [DomainVerificationUserState.DOMAIN_STATE_SELECTED]。
     */
    internal fun configured(
        linkHandlingAllowed: Boolean,
        hostStates: Map<String, Int>,
        hosts: List<String>,
        selectedState: Int,
    ): Boolean = linkHandlingAllowed && hosts.any { (hostStates[it] ?: 0) >= selectedState }

    /** 系统里「默认打开链接」的设置页；Android 12 以下没有该页面，退回应用信息页 */
    fun settingsIntent(ctx: Context): Intent {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
        } else {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
        return Intent(action).setData(Uri.parse("package:${ctx.packageName}"))
    }
}
