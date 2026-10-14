package com.mobilegh.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable

/** 桌面小组件的数据快照：贡献图取最近若干周，收件箱取最新几条未读通知。 */
@Serializable
data class ContribSnapshot(val login: String = "", val total: Int = 0, val weeks: List<ContribWeek> = emptyList(), val at: Long = 0)

@Serializable
data class InboxItem(val repo: String = "", val title: String = "", val url: String? = null)

@Serializable
data class InboxSnapshot(val login: String = "", val unread: Int = 0, val more: Boolean = false, val items: List<InboxItem> = emptyList(), val at: Long = 0)

/**
 * 小组件数据的缓存与读取。应用内拉到新数据时写入并通知小组件刷新；
 * 小组件自己刷新时，缓存足够新就直接使用，避免重复请求。
 */
object WidgetStore {
    const val WEEKS = 20
    const val INBOX_ITEMS = 3
    private const val INBOX_PAGE = 50
    private const val FRESH_MS = 15 * 60 * 1000L

    enum class Kind { Contributions, Inbox }

    private var prefs: SharedPreferences? = null

    /** 由 App 设置：数据变化时请求刷新对应的小组件 */
    @Volatile
    var listener: ((Kind) -> Unit)? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    }

    /** 只保留最近 n 周，总数按这几周重新累计。 */
    fun recentWeeks(cal: ContribCalendar, n: Int = WEEKS): ContribCalendar {
        val weeks = cal.weeks.takeLast(n)
        return ContribCalendar(weeks.sumOf { w -> w.contributionDays.sumOf { it.contributionCount } }, weeks)
    }

    fun inboxOf(login: String, unread: List<Notification>, more: Boolean, now: Long = System.currentTimeMillis()): InboxSnapshot {
        val sorted = unread.filter { it.unread }.sortedByDescending { it.updatedAt.orEmpty() }
        return InboxSnapshot(
            login, sorted.size, more,
            sorted.take(INBOX_ITEMS).map { InboxItem(it.repository.fullName, it.subject.title, it.subject.url) }, now,
        )
    }

    fun saveContributions(login: String, cal: ContribCalendar) {
        val recent = recentWeeks(cal)
        val snap = ContribSnapshot(login, recent.totalContributions, recent.weeks, System.currentTimeMillis())
        if (write("contrib", Api.plain.encodeToString(ContribSnapshot.serializer(), snap))) listener?.invoke(Kind.Contributions)
    }

    fun saveInbox(login: String, unread: List<Notification>, more: Boolean) {
        val snap = inboxOf(login, unread, more)
        if (write("inbox", Api.plain.encodeToString(InboxSnapshot.serializer(), snap))) listener?.invoke(Kind.Inbox)
    }

    fun cachedContributions(login: String): ContribSnapshot? = read("contrib")
        ?.let { runCatching { Api.plain.decodeFromString(ContribSnapshot.serializer(), it) }.getOrNull() }
        ?.takeIf { it.login.equals(login, true) }

    fun cachedInbox(login: String): InboxSnapshot? = read("inbox")
        ?.let { runCatching { Api.plain.decodeFromString(InboxSnapshot.serializer(), it) }.getOrNull() }
        ?.takeIf { it.login.equals(login, true) }

    /** 未登录返回 null；请求失败时退回到同一账号的旧缓存，没有缓存则抛出异常。 */
    suspend fun loadContributions(): ContribSnapshot? {
        val login = Session.login ?: return null
        if (Session.token == null) return null
        val cached = cachedContributions(login)
        if (cached != null && System.currentTimeMillis() - cached.at < FRESH_MS) return cached
        return try {
            val cal = GitHub.contributions(login, null).contributionCalendar
            val recent = recentWeeks(cal)
            ContribSnapshot(login, recent.totalContributions, recent.weeks, System.currentTimeMillis()).also {
                write("contrib", Api.plain.encodeToString(ContribSnapshot.serializer(), it))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            cached ?: throw e
        }
    }

    suspend fun loadInbox(): InboxSnapshot? {
        val login = Session.login ?: return null
        if (Session.token == null) return null
        val cached = cachedInbox(login)
        if (cached != null && System.currentTimeMillis() - cached.at < FRESH_MS) return cached
        return try {
            val list = Api.get<List<Notification>>("/notifications?all=false&participating=false&per_page=$INBOX_PAGE&page=1", force = true)
            inboxOf(login, list, list.size >= INBOX_PAGE).also {
                write("inbox", Api.plain.encodeToString(InboxSnapshot.serializer(), it))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            cached ?: throw e
        }
    }

    private fun read(key: String): String? = prefs?.getString(key, null)

    /** 内容没变时不写入，也不触发刷新（只更新时间戳没有意义）。 */
    private fun write(key: String, value: String): Boolean {
        val p = prefs ?: return false
        val old = p.getString(key, null)
        p.edit().putString(key, value).apply()
        return old == null || old.substringBefore(",\"at\":") != value.substringBefore(",\"at\":")
    }
}
