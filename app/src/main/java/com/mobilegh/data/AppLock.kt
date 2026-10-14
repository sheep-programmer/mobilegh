package com.mobilegh.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 应用锁：启动时以及离开应用超过设定时长后，需要通过指纹 / 面容 / 锁屏密码验证。
 * 设置与其他偏好一样存放在 SharedPreferences 中。
 */
object AppLock {
    /** 离开应用后多久重新锁定（毫秒） */
    val timeouts = listOf(0L to "立即", 60_000L to "1 分钟", 300_000L to "5 分钟", 900_000L to "15 分钟")

    private var prefs: SharedPreferences? = null

    var enabled by mutableStateOf(false)
        private set
    var timeoutIndex by mutableIntStateOf(0)
        private set
    var locked by mutableStateOf(false)
        private set

    /** 系统验证界面（如锁屏密码页）会让 Activity 进入后台，期间不计入离开时长 */
    @Volatile
    var authenticating = false

    private var leftAt = 0L

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean("enabled", false)
        timeoutIndex = p.getInt("timeout", 0).coerceIn(0, timeouts.lastIndex)
        // 冷启动：开启了应用锁就先锁住（未登录时没有需要保护的内容）
        locked = enabled && Session.token != null
    }

    fun setLockEnabled(on: Boolean) {
        enabled = on
        if (!on) locked = false
        prefs?.edit()?.putBoolean("enabled", on)?.apply()
    }

    fun setTimeout(index: Int) {
        timeoutIndex = index.coerceIn(0, timeouts.lastIndex)
        prefs?.edit()?.putInt("timeout", timeoutIndex)?.apply()
    }

    /** 所有页面都不可见时调用 */
    fun onBackground(now: Long = SystemClock.elapsedRealtime()) {
        if (!enabled || authenticating || Session.token == null) return
        leftAt = now
        if (timeouts[timeoutIndex].first == 0L) locked = true
    }

    /** 回到前台时调用：离开时长达到设定值则重新锁定 */
    fun onForeground(now: Long = SystemClock.elapsedRealtime()) {
        // 验证界面已关闭；即使没收到回调也不能让标记一直挂着，否则之后永远不会重新锁定
        authenticating = false
        val left = leftAt
        leftAt = 0L
        if (!enabled || left == 0L) return
        if (shouldRelock(left, now, timeouts[timeoutIndex].first)) locked = true
    }

    fun unlock() {
        locked = false
        leftAt = 0L
    }

    fun shouldRelock(leftAt: Long, now: Long, timeoutMs: Long): Boolean = now - leftAt >= timeoutMs
}
