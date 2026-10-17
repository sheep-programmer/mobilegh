package com.mobilegh.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 桌面小组件的数据快照：贡献图取最近若干周，收件箱取最新几条未读通知。 */
@Serializable
data class ContribSnapshot(val login: String = "", val total: Int = 0, val weeks: List<ContribWeek> = emptyList(), val at: Long = 0)

@Serializable
data class InboxItem(val repo: String = "", val title: String = "", val url: String? = null)

@Serializable
data class InboxSnapshot(val login: String = "", val unread: Int = 0, val more: Boolean = false, val items: List<InboxItem> = emptyList(), val at: Long = 0)

@Serializable
private data class InboxSource(val login: String, val items: List<Notification>, val more: Boolean, val at: Long)

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
    var hideInboxDetails by mutableStateOf(false)
        private set

    fun changeInboxPrivacy(hidden: Boolean) {
        hideInboxDetails = hidden
        prefs?.edit()?.putBoolean("hide_inbox_details", hidden)?.apply()
        listener?.invoke(Kind.Inbox)
    }

    fun invalidateInbox(login: String) {
        if (!login.equals(Session.login, true)) return
        source(login)?.let { updateInbox(it) }
        listener?.invoke(Kind.Inbox)
    }

    /** 由 App 设置：数据变化时请求刷新对应的小组件 */
    @Volatile
    var listener: ((Kind) -> Unit)? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)
        hideInboxDetails = prefs?.getBoolean("hide_inbox_details", false) == true
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

    @Synchronized
    fun saveInbox(login: String, unread: List<Notification>, more: Boolean) {
        val source = InboxSource(login, unread, more, System.currentTimeMillis())
        prefs?.edit()?.putString("inbox_source", Api.plain.encodeToString(InboxSource.serializer(), source))?.apply()
        if (updateInbox(source)) listener?.invoke(Kind.Inbox)
    }

    private fun source(login: String): InboxSource? = read("inbox_source")
        ?.let { runCatching { Api.plain.decodeFromString(InboxSource.serializer(), it) }.getOrNull() }
        ?.takeIf { it.login.equals(login, true) }

    @Synchronized
    private fun updateInbox(source: InboxSource): Boolean {
        val snap = inboxOf(source.login, NotificationStore.unread(source.login, source.items), source.more, source.at)
        return write("inbox", Api.plain.encodeToString(InboxSnapshot.serializer(), snap))
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
        val generation = Session.generation
        val cached = cachedContributions(login)
        if (cached != null && System.currentTimeMillis() - cached.at < FRESH_MS) return cached
        return try {
            val cal = GitHub.contributions(login, null).contributionCalendar
            if (Session.generation != generation || Session.login != login) return null
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
        val generation = Session.generation
        source(login)?.let { updateInbox(it) }
        val cached = cachedInbox(login)
        if (cached != null && System.currentTimeMillis() - cached.at < FRESH_MS) return cached
        return try {
            val list = Api.get<List<Notification>>("/notifications?all=false&participating=false&per_page=$INBOX_PAGE&page=1", force = true)
            if (Session.generation != generation || Session.login != login) return null
            saveInbox(login, list, list.size >= INBOX_PAGE)
            cachedInbox(login)
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
