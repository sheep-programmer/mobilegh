package com.mobilegh.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URI
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
data class WorkingHours(
    val enabled: Boolean = false,
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 18 * 60,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
) {
    /** 跨夜窗口归属于开始那一天；所有换算都在用户当前时区完成。 */
    fun allows(instant: Instant, zone: ZoneId): Boolean {
        if (!enabled) return true
        require(startMinute in 0..1439 && endMinute in 0..1439) { "Invalid minute of day" }
        val local = instant.atZone(zone)
        val minute = local.hour * 60 + local.minute
        return if (startMinute < endMinute) {
            local.dayOfWeek in days && minute >= startMinute && minute < endMinute
        } else if (startMinute > endMinute) {
            minute >= startMinute && local.dayOfWeek in days ||
                minute < endMinute && local.dayOfWeek.minus(1) in days
        } else {
            local.dayOfWeek in days
        }
    }
}

@Serializable
data class BackgroundNotificationConfig(
    val enabled: Boolean = false,
    val workingHours: WorkingHours = WorkingHours(),
)

/** 后台通知是本机偏好与去重记录，不进入设置备份，也不代表 GitHub 云端设置。 */
object BackgroundNotificationSettings {
    private const val PREFS = "background_notifications"
    private const val CONFIG = "config"
    private var prefs: SharedPreferences? = null
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    var config by mutableStateOf(BackgroundNotificationConfig())
        private set

    @Volatile var foreground: Boolean = false
        private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        config = prefs?.getString(CONFIG, null)?.let {
            runCatching { Api.plain.decodeFromString(BackgroundNotificationConfig.serializer(), it) }.getOrNull()
        } ?: BackgroundNotificationConfig()
    }

    fun save(value: BackgroundNotificationConfig) {
        config = value
        prefs?.edit()?.putString(CONFIG, Api.plain.encodeToString(BackgroundNotificationConfig.serializer(), value))?.apply()
    }

    fun setForeground(value: Boolean) { foreground = value }

    fun notified(login: String, apiHost: String = Net.apiHost): Map<String, String> {
        val host = normalizeHistoryHost(apiHost)
        var raw = prefs?.getString(historyKey(login, host), null)
        // 旧版本只有公共 GitHub 的登录名键；企业账号不能继承这份来源不明的历史。
        if (raw == null && host == "api.github.com") raw = prefs?.getString(legacyHistoryKey(login), null)
        raw ?: return emptyMap()
        return runCatching { Api.plain.decodeFromString(mapSerializer, raw) }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun recordNotified(login: String, threads: List<Notification>, apiHost: String = Net.apiHost) {
        val host = normalizeHistoryHost(apiHost)
        val merged = LinkedHashMap(notified(login, host))
        threads.forEach { merged[it.id] = it.updatedAt.orEmpty() }
        val bounded = merged.entries.toList().takeLast(200).associate { it.key to it.value }
        prefs?.edit()?.putString(historyKey(login, host), Api.plain.encodeToString(mapSerializer, bounded))?.apply {
            if (host == "api.github.com") remove(legacyHistoryKey(login))
        }?.apply()
    }

    private fun legacyHistoryKey(login: String) = "sent_${login.lowercase()}"
    private fun historyKey(login: String, apiHost: String) = "sent_v2_${normalizeHistoryHost(apiHost)}_${login.lowercase()}"

    private fun normalizeHistoryHost(value: String): String {
        val raw = value.trim()
        val candidate = if (raw.contains("://")) raw else "https://$raw"
        return runCatching {
            val uri = URI(candidate)
            require(uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null)
            require(uri.port in setOf(-1, 443) && uri.rawPath.orEmpty().trim('/').isEmpty())
            require(uri.rawQuery == null && uri.rawFragment == null)
            requireNotNull(uri.host).lowercase()
        }.getOrElse { raw.lowercase() }
    }
}

object NotificationPollDecision {
    fun choose(
        settings: BackgroundNotificationConfig,
        login: String?,
        currentLogin: String?,
        generation: Int,
        currentGeneration: Int,
        foreground: Boolean,
        instant: Instant,
        zone: ZoneId,
        threads: List<Notification>,
        notified: Map<String, String>,
    ): List<Notification> {
        if (!settings.enabled || foreground || login == null ||
            !login.equals(currentLogin, ignoreCase = true) || generation != currentGeneration ||
            !settings.workingHours.allows(instant, zone)
        ) return emptyList()
        return threads.filter { it.unread && notified[it.id] != it.updatedAt.orEmpty() }
    }
}
