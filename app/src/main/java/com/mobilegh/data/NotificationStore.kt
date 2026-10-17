package com.mobilegh.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class LocalNotifications(
    val read: List<String> = emptyList(),
    val unread: List<String> = emptyList(),
    val done: List<Notification> = emptyList(),
    val known: List<Notification> = emptyList(),
)

/** GitHub 不提供的通知状态按账号加密保存；未提交的撤销窗口不进入此存储。 */
object NotificationStore {
    private var prefs: SharedPreferences? = null
    private val cache = ConcurrentHashMap<String, LocalNotifications>()

    fun init(ctx: Context) { prefs = ctx.getSharedPreferences("local_notifications", Context.MODE_PRIVATE) }

    fun read(login: String): LocalNotifications = cache.getOrPut(login.lowercase()) {
        prefs?.getString(login.lowercase(), null)?.let { encrypted ->
            runCatching { Api.plain.decodeFromString(LocalNotifications.serializer(), Crypto.decrypt(encrypted)) }
                .onFailure { AppLog.warn("notifications", "本机通知状态读取失败") }.getOrNull()
        } ?: LocalNotifications()
    }

    /** 调用者在 IO 上执行，账号作为参数固定，不使用保存时的当前账号。 */
    fun save(login: String, value: LocalNotifications) {
        val key = login.lowercase()
        if (cache[key] == value) return
        val encrypted = Crypto.encrypt(Api.plain.encodeToString(LocalNotifications.serializer(), value))
        check(prefs?.edit()?.putString(key, encrypted)?.commit() == true) { "无法保存本机通知状态" }
        cache[key] = value
        WidgetStore.invalidateInbox(login)
    }

    fun unread(login: String, server: List<Notification>): List<Notification> {
        val local = read(login)
        val done = local.done.mapTo(HashSet()) { it.id }
        val read = local.read.toHashSet()
        val unread = local.unread.toHashSet()
        return (server + local.known.filter { it.id in unread }).distinctBy { it.id }
            .filter { it.id !in done && (it.id in unread || it.unread && it.id !in read) }
            .map { it.copy(unread = true) }
    }
}
