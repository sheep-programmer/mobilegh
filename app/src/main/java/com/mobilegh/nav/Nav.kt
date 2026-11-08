package com.mobilegh.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicLong
import com.mobilegh.data.History
import com.mobilegh.data.IssueQuery
import com.mobilegh.data.Session
import com.mobilegh.data.ProjectOwnerKind
import com.mobilegh.i18n.tr
import kotlinx.serialization.Serializable

/**
 * 所有页面。使用轻量的自定义导航栈，无需 navigation 库。
 * 可序列化：进程被回收后，导航栈由 [NavRestore] 写入 SavedStateHandle 并恢复。
 */
@Serializable
sealed interface Screen {
    @Serializable data object Root : Screen
    @Serializable data class Repo(val owner: String, val name: String) : Screen
    @Serializable data class Files(val owner: String, val name: String, val path: String, val ref: String?) : Screen
    /** 仓库 Wiki 的某个 Markdown 页面；page 为空表示首页 */
    @Serializable data class Wiki(val owner: String, val name: String, val page: String? = null) : Screen
    @Serializable data class FileView(val owner: String, val name: String, val path: String, val ref: String?, @Serializable(with = IntRangeSerializer::class) val lines: IntRange? = null) : Screen
    /** 分支或路径还不确定的链接（blob、tree、raw）：由 BlobScreen 确定分支、路径，以及它是文件还是目录 */
    @Serializable data class Blob(val owner: String, val name: String, val rest: String, @Serializable(with = IntRangeSerializer::class) val lines: IntRange? = null) : Screen
    @Serializable data class FileFinder(val owner: String, val name: String, val ref: String?) : Screen
    @Serializable data class CodeSearch(val owner: String, val name: String) : Screen
    /** 独立搜索页：类型、关键词与组合筛选都在其中 */
    @Serializable data class Search(val type: Int = 0, val query: String = "") : Screen
    @Serializable data class Blame(val owner: String, val name: String, val path: String, val ref: String?) : Screen
    /** path == null 表示在 dir 目录下新建文件 */
    @Serializable data class EditFile(val owner: String, val name: String, val ref: String?, val path: String?, val dir: String = "") : Screen
    /** 上传文件（夹）：用完整 git 流程写成一次提交并推送到分支；path 是当前浏览的目录 */
    @Serializable data class UploadFiles(val owner: String, val name: String, val ref: String?, val path: String = "") : Screen
    @Serializable data class NewPull(val owner: String, val name: String, val head: String, val base: String, val title: String = "", val body: String = "") : Screen
    /** releaseId == null 表示新建 */
    @Serializable data class EditRelease(val owner: String, val name: String, val releaseId: Long? = null) : Screen
    @Serializable data class RunWorkflow(val owner: String, val name: String, val workflowId: Long, val path: String, val title: String, val ref: String? = null) : Screen
    @Serializable data object RecentRepositories : Screen
    @Serializable data object SearchHistory : Screen
    @Serializable data object EditProfile : Screen
    @Serializable data class Commits(val owner: String, val name: String, val ref: String?, val path: String? = null,
        val author: String? = null, val since: String? = null, val until: String? = null) : Screen
    @Serializable data class CommitDetail(val owner: String, val name: String, val sha: String) : Screen
    @Serializable data class Compare(val owner: String, val name: String, val base: String = "HEAD~1", val head: String = "HEAD") : Screen
    /** preset：从首页保存的搜索打开时带入的筛选条件 */
    @Serializable data class Issues(val owner: String, val name: String, val pulls: Boolean, val preset: IssueQuery? = null) : Screen
    /** [commentId] 来自 `#issuecomment-N` / `#pullrequestreview-N` 链接：打开后滚动到这条评论或审查；为空时停在正文 */
    @Serializable data class IssueDetail(val owner: String, val name: String, val number: Int, val isPull: Boolean, val commentId: Long? = null) : Screen
    @Serializable data class PullFiles(val owner: String, val name: String, val number: Int) : Screen
    @Serializable data class PullChecks(val owner: String, val name: String, val number: Int, val sha: String) : Screen
    @Serializable data class NewIssue(val owner: String, val name: String) : Screen
    @Serializable data class Actions(val owner: String, val name: String) : Screen
    @Serializable data class RunDetail(val owner: String, val name: String, val runId: Long) : Screen
    @Serializable data class JobLog(val owner: String, val name: String, val jobId: Long, val title: String, val runId: Long = 0) : Screen
    /** [tag] 来自 `/releases/tag/…`、`/releases/download/…` 链接：显示并展开这个版本；`latest` 表示最新版本；为空时只列出 */
    @Serializable data class Releases(val owner: String, val name: String, val tag: String? = null, val releaseId: Long? = null) : Screen
    /** 仓库最近的推送记录：谁推送到了哪个分支 */
    @Serializable data class Pushes(val owner: String, val name: String) : Screen
    @Serializable data class Branches(val owner: String, val name: String) : Screen
    @Serializable data class Insights(val owner: String, val name: String) : Screen
    @Serializable data class SecurityAlerts(val owner: String, val name: String) : Screen
    /** 合并队列（网页 /owner/repo/queue） */
    @Serializable data class MergeQueue(val owner: String, val name: String) : Screen
    @Serializable data class BranchRules(val owner: String, val name: String) : Screen
    @Serializable data class Contributors(val owner: String, val name: String) : Screen
    @Serializable data class Discussions(val owner: String, val name: String) : Screen
    @Serializable data class DiscussionDetail(val owner: String, val name: String, val number: Int) : Screen
    @Serializable data class RepoSettings(val owner: String, val name: String) : Screen
    /** 用户/组织/仓库的 Projects 列表；kind 为 Repo 时 login 是仓库 owner，name 是仓库名 */
    @Serializable data class Projects(val kind: ProjectOwnerKind, val login: String, val name: String = "") : Screen
    @Serializable data class ProjectDetail(val id: String, val title: String) : Screen
    @Serializable data class Collaborators(val owner: String, val name: String) : Screen
    @Serializable data class Profile(val login: String) : Screen
    @Serializable data class Org(val login: String) : Screen
    @Serializable data class Users(val kind: UserKind, val a: String, val b: String = "") : Screen
    @Serializable data class Repos(val kind: RepoKind, val login: String = "", val name: String = "") : Screen
    @Serializable data object Orgs : Screen
    @Serializable data object Invitations : Screen
    @Serializable data class Gists(val login: String?) : Screen
    @Serializable data class GistDetail(val id: String) : Screen
    /** id == null 表示新建 */
    @Serializable data class GistEditor(val id: String? = null) : Screen
    @Serializable data class Activity(val login: String) : Screen
    @Serializable data class StarLists(val login: String, val initialListId: String? = null, val local: Boolean = false) : Screen
    @Serializable data class Achievements(val login: String) : Screen
    @Serializable data object Settings : Screen
    @Serializable data object Logs : Screen
    /** 未发送的草稿列表 */
    @Serializable data object Drafts : Screen
    /** 首页收藏：从自己的仓库、Star 或搜索结果里添加 */
    @Serializable data object AddFavorites : Screen
    @Serializable data object Authenticator : Screen
    @Serializable data object OrgDiagnostics : Screen
    @Serializable data object CreateRepo : Screen
    @Serializable data object Login : Screen
    @Serializable data object Reauthorize : Screen
    @Serializable data class MyIssues(val pulls: Boolean, val preset: IssueQuery? = null) : Screen
    @Serializable data object HomeCustomize : Screen
}

@Serializable enum class UserKind { Followers, Following, Stargazers, Watchers, Contributors, Members }
@Serializable enum class RepoKind { User, Starred, Org, Forks }

enum class Tab(private val titleZh: String) { Home("首页"), Repos("仓库"), Notifications("通知"), Explore("探索"), Me("我的");
    val title: String get() = tr(titleZh) }

private val ids = AtomicLong()

class Entry(val screen: Screen) {
    val id: String = "e${ids.incrementAndGet()}"
    /** 打开页面时的账号；写操作据此确认没有在中途切换账号 */
    val login: String? = Session.login
    val generation: Int = Session.generation
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val bag = HashMap<String, Any>()
    fun dispose() = scope.cancel()
}

class Navigator {
    val clipboardLinks = ClipboardLinkTracker()
    var tab by mutableStateOf(Tab.Home)
    val root = Entry(Screen.Root)
    val tabEntries = Tab.entries.associateWith { Entry(Screen.Root) }
    val stack = mutableStateListOf<Entry>()
    var lastWasPush = true
        private set

    /** 同一 Tab 被重复点击时自增，页面据此滚动到顶部 */
    var reselect by mutableIntStateOf(0)

    val top: Entry get() = stack.lastOrNull() ?: root

    fun push(s: Screen) {
        History.navigation(s)
        lastWasPush = true
        stack.add(Entry(s))
    }

    fun replace(s: Screen) {
        History.navigation(s)
        lastWasPush = true
        stack.removeLastOrNull()?.dispose()
        stack.add(Entry(s))
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        lastWasPush = false
        stack.removeAt(stack.lastIndex).dispose()
        return true
    }

    fun popToRoot() {
        lastWasPush = false
        stack.forEach { it.dispose() }
        stack.clear()
    }

    /** 进程重建后恢复 Tab 与栈。只应在刚创建的 Navigator 上调用；不记入浏览历史。 */
    fun restore(restored: RestoredNav) {
        tab = restored.tab
        restored.screens.forEach { stack.add(Entry(it)) }
    }

    /**
     * 打开链接指向的页面。
     *
     * 只有在没有任何可返回的页面时（停在首页 / 标签页，链接来自通知、搜索或外部 Intent），
     * 才先压入仓库主页，让返回键回到仓库而不是直接退出。
     *
     * 应用内页面里点链接（README、Issue 里提到的另一个 Issue、代码搜索等）就和浏览器一样：
     * 只压入目标页，返回键回到刚才的页面。不能凭空插入仓库主页，否则返回路径会多出一层，
     * 例如 #314 → #31 再返回会先到仓库主页，而不是 #314。
     */
    fun pushLink(target: Screen) {
        val home = repoHomeOf(target)
        if (home != null && stack.isEmpty()) push(home)
        if (top.screen != target) push(target)
    }

    /** 点仓库名回到仓库主页：如果上一页就是仓库主页则直接返回，否则替换当前页。 */
    fun toRepoHome(owner: String, name: String) {
        val home = Screen.Repo(owner, name)
        val below = stack.getOrNull(stack.lastIndex - 1)?.screen
        if (below == home) pop() else replace(home)
    }

    fun parentFiles(owner: String, name: String, path: String, ref: String?) {
        val parent = Screen.Files(owner, name, path.substringBeforeLast('/', ""), ref)
        if (stack.dropLast(1).lastOrNull()?.screen == parent) pop() else replace(parent)
    }

    fun dispose() {
        popToRoot()
        root.dispose()
        tabEntries.values.forEach { it.dispose() }
    }

    fun select(t: Tab) {
        if (tab == t) reselect++ else tab = t
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("no nav") }
val LocalEntry = staticCompositionLocalOf<Entry> { error("no entry") }

/** 在当前页面条目上保留对象：离开再返回页面时数据不丢失、不重复加载 */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T : Any> retain(key: String, init: (Entry) -> T): T {
    val e = LocalEntry.current
    return remember(e, key) { e.bag.getOrPut(key) { init(e) } as T }
}

/** 只响应当前 composition 收到的新事件，返回页面时忽略历史重选序号。 */
@Composable
fun OnTabReselect(tab: Tab, block: suspend () -> Unit) {
    val nav = LocalNav.current
    val baseline = remember(nav, tab) { nav.reselect }
    androidx.compose.runtime.LaunchedEffect(nav.reselect) {
        if (nav.tab == tab && nav.reselect > baseline) block()
    }
}

/** 仓库内的页面所属的仓库主页；不属于某个仓库的页面返回 null。 */
internal fun repoHomeOf(screen: Screen): Screen.Repo? = when (screen) {
    is Screen.Files -> Screen.Repo(screen.owner, screen.name)
    is Screen.Wiki -> Screen.Repo(screen.owner, screen.name)
    is Screen.FileView -> Screen.Repo(screen.owner, screen.name)
    is Screen.Blob -> Screen.Repo(screen.owner, screen.name)
    is Screen.FileFinder -> Screen.Repo(screen.owner, screen.name)
    is Screen.CodeSearch -> Screen.Repo(screen.owner, screen.name)
    is Screen.Blame -> Screen.Repo(screen.owner, screen.name)
    is Screen.EditFile -> Screen.Repo(screen.owner, screen.name)
    is Screen.UploadFiles -> Screen.Repo(screen.owner, screen.name)
    is Screen.NewPull -> Screen.Repo(screen.owner, screen.name)
    is Screen.EditRelease -> Screen.Repo(screen.owner, screen.name)
    is Screen.RunWorkflow -> Screen.Repo(screen.owner, screen.name)
    is Screen.Commits -> Screen.Repo(screen.owner, screen.name)
    is Screen.CommitDetail -> Screen.Repo(screen.owner, screen.name)
    is Screen.Compare -> Screen.Repo(screen.owner, screen.name)
    is Screen.Issues -> Screen.Repo(screen.owner, screen.name)
    is Screen.IssueDetail -> Screen.Repo(screen.owner, screen.name)
    is Screen.PullFiles -> Screen.Repo(screen.owner, screen.name)
    is Screen.PullChecks -> Screen.Repo(screen.owner, screen.name)
    is Screen.NewIssue -> Screen.Repo(screen.owner, screen.name)
    is Screen.Actions -> Screen.Repo(screen.owner, screen.name)
    is Screen.RunDetail -> Screen.Repo(screen.owner, screen.name)
    is Screen.JobLog -> Screen.Repo(screen.owner, screen.name)
    is Screen.Releases -> Screen.Repo(screen.owner, screen.name)
    is Screen.Pushes -> Screen.Repo(screen.owner, screen.name)
    is Screen.Branches -> Screen.Repo(screen.owner, screen.name)
    is Screen.Insights -> Screen.Repo(screen.owner, screen.name)
    is Screen.SecurityAlerts -> Screen.Repo(screen.owner, screen.name)
    is Screen.MergeQueue -> Screen.Repo(screen.owner, screen.name)
    is Screen.BranchRules -> Screen.Repo(screen.owner, screen.name)
    is Screen.Contributors -> Screen.Repo(screen.owner, screen.name)
    is Screen.Discussions -> Screen.Repo(screen.owner, screen.name)
    is Screen.DiscussionDetail -> Screen.Repo(screen.owner, screen.name)
    is Screen.RepoSettings -> Screen.Repo(screen.owner, screen.name)
    is Screen.Collaborators -> Screen.Repo(screen.owner, screen.name)
    is Screen.Projects -> if (screen.kind == ProjectOwnerKind.Repo) Screen.Repo(screen.login, screen.name) else null
    is Screen.Users -> when (screen.kind) {
        UserKind.Stargazers, UserKind.Watchers, UserKind.Contributors ->
            if (screen.b.isNotEmpty()) Screen.Repo(screen.a, screen.b) else null
        else -> null
    }
    is Screen.Repos -> if (screen.kind == RepoKind.Forks && screen.name.isNotEmpty()) Screen.Repo(screen.login, screen.name) else null
    else -> null
}
