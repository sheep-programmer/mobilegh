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

@Serializable
data class PullRequestWidgetItem(val repo: String, val number: Int, val title: String, val updatedAt: String, val url: String)

@Serializable
data class PullRequestSnapshot(val login: String, val generation: Int, val items: List<PullRequestWidgetItem> = emptyList(), val at: Long = 0) {
    fun visibleTo(login: String, generation: Int): PullRequestSnapshot? = takeIf {
        it.login.equals(login, true) && (generation == 0 || it.generation == generation)
    }
}

@Serializable
data class ShortcutWidgetItem(val id: String, val label: String, val url: String, val labelKey: String? = null)

@Serializable
data class ShortcutSnapshot(val login: String, val generation: Int, val items: List<ShortcutWidgetItem> = emptyList(), val at: Long = 0) {
    fun visibleTo(login: String, generation: Int): ShortcutSnapshot? = takeIf {
        it.login.equals(login, true) && (generation == 0 || it.generation == generation)
    }
}

/**
 * 小组件数据的缓存与读取。应用内拉到新数据时写入并通知小组件刷新；
 * 小组件自己刷新时，缓存足够新就直接使用，避免重复请求。
 */
object WidgetStore {
    const val WEEKS = 20
    const val INBOX_ITEMS = 3
    const val PULL_REQUEST_ITEMS = 5
    const val SHORTCUT_ITEMS = 8
    private const val INBOX_PAGE = 50
    private const val FRESH_MS = 15 * 60 * 1000L

    enum class Kind { Contributions, Inbox, PullRequests, Shortcuts }

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

    fun pullRequestsOf(
        login: String,
        pulls: List<Issue>,
        generation: Int,
        webBase: String = Net.loginBase,
        now: Long = System.currentTimeMillis(),
    ): PullRequestSnapshot {
        val items = pulls.filter { it.state == "open" }
            .sortedByDescending { it.updatedAt.orEmpty() }
            .mapNotNull { pull ->
                val repo = pull.repositoryUrl?.substringAfter("/repos/", "")?.takeIf { it.count { c -> c == '/' } == 1 } ?: return@mapNotNull null
                PullRequestWidgetItem(
                    repo,
                    pull.number,
                    pull.title,
                    pull.updatedAt.orEmpty(),
                    pull.htmlUrl.ifBlank { Net.loginEndpoint("/$repo/pull/${pull.number}", webBase.trimEnd('/')) },
                )
            }.take(PULL_REQUEST_ITEMS)
        return PullRequestSnapshot(login, generation, items, now)
    }

    fun shortcutsOf(login: String, layout: HomeLayout, generation: Int, now: Long = System.currentTimeMillis()): ShortcutSnapshot {
        val labels = mapOf(
            "activity" to "动态", "issues" to "Issues", "pulls" to "Pull Requests", "favorites" to "收藏夹",
            "saved" to "稍后阅读", "repos" to "仓库", "orgs" to "组织", "stars" to "已 Star",
            "lists" to "Star 列表", "gists" to "Gists", "recent" to "最近浏览", "projects" to "项目",
            "drafts" to "草稿", "invitations" to "待处理邀请",
        )
        val items = layout.visibleSlots().mapNotNull { slot ->
            val search = layout.search(slot.id)
            val labelKey = if (search == null) labels[slot.id] else null
            if (search == null && labelKey == null) return@mapNotNull null
            val path = slot.id.removePrefix("search:").let { if (slot.id.startsWith("search:")) "search/$it" else it }
            ShortcutWidgetItem(slot.id, search?.name.orEmpty(), "mobilegh://shortcut/$path", labelKey)
        }.take(SHORTCUT_ITEMS)
        return ShortcutSnapshot(login, generation, items, now)
    }

    fun cachedPullRequests(login: String, generation: Int): PullRequestSnapshot? = read("pull_requests")
        ?.let { runCatching { Api.plain.decodeFromString(PullRequestSnapshot.serializer(), it) }.getOrNull() }
        ?.visibleTo(login, generation)

    fun cachedShortcuts(login: String, generation: Int): ShortcutSnapshot? = read("shortcuts")
        ?.let { runCatching { Api.plain.decodeFromString(ShortcutSnapshot.serializer(), it) }.getOrNull() }
        ?.visibleTo(login, generation)

    suspend fun loadPullRequests(): PullRequestSnapshot? {
        val login = Session.login ?: return null
        if (Session.token == null) return null
        val generation = Session.generation
        val cached = cachedPullRequests(login, generation)
        if (cached != null && System.currentTimeMillis() - cached.at < FRESH_MS) return cached
        return try {
            val result = GitHub.searchIssues("is:pr is:open assignee:$login archived:false", 1, sort = "updated", order = "desc", force = true)
            if (Session.generation != generation || !login.equals(Session.login, true)) return null
            pullRequestsOf(login, result.items, generation).also {
                write("pull_requests", Api.plain.encodeToString(PullRequestSnapshot.serializer(), it))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            cached ?: throw e
        }
    }

    fun loadShortcuts(): ShortcutSnapshot? {
        val login = Session.login ?: return null
        if (Session.token == null) return null
        return shortcutsOf(login, HomePrefs.layout(), Session.generation).also {
            if (write("shortcuts", Api.plain.encodeToString(ShortcutSnapshot.serializer(), it))) listener?.invoke(Kind.Shortcuts)
        }
    }

    fun saveShortcuts(login: String, layout: HomeLayout) {
        if (!login.equals(Session.login, true)) return
        val snap = shortcutsOf(login, layout, Session.generation)
        if (write("shortcuts", Api.plain.encodeToString(ShortcutSnapshot.serializer(), snap))) listener?.invoke(Kind.Shortcuts)
    }

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
        if (old != null && old.substringBefore(",\"at\":") == value.substringBefore(",\"at\":")) return false
        p.edit().putString(key, value).apply()
        return true
    }
}
