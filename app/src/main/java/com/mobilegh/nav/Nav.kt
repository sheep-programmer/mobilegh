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

/** 所有页面。使用轻量的自定义导航栈，无需 navigation 库，也无需参数序列化。 */
sealed interface Screen {
    data object Root : Screen
    data class Repo(val owner: String, val name: String) : Screen
    data class Files(val owner: String, val name: String, val path: String, val ref: String?) : Screen
    data class FileView(val owner: String, val name: String, val path: String, val ref: String?) : Screen
    data class Commits(val owner: String, val name: String, val ref: String?, val path: String? = null) : Screen
    data class CommitDetail(val owner: String, val name: String, val sha: String) : Screen
    data class Issues(val owner: String, val name: String, val pulls: Boolean) : Screen
    data class IssueDetail(val owner: String, val name: String, val number: Int, val isPull: Boolean) : Screen
    data class PullFiles(val owner: String, val name: String, val number: Int) : Screen
    data class NewIssue(val owner: String, val name: String) : Screen
    data class Actions(val owner: String, val name: String) : Screen
    data class RunDetail(val owner: String, val name: String, val runId: Long) : Screen
    data class JobLog(val owner: String, val name: String, val jobId: Long, val title: String) : Screen
    data class Releases(val owner: String, val name: String) : Screen
    data class Branches(val owner: String, val name: String) : Screen
    data class Insights(val owner: String, val name: String) : Screen
    data class RepoSettings(val owner: String, val name: String) : Screen
    data class Collaborators(val owner: String, val name: String) : Screen
    data class Profile(val login: String) : Screen
    data class Org(val login: String) : Screen
    data class Users(val kind: UserKind, val a: String, val b: String = "") : Screen
    data class Repos(val kind: RepoKind, val login: String = "", val name: String = "") : Screen
    data object Orgs : Screen
    data object Invitations : Screen
    data class Gists(val login: String?) : Screen
    data class GistDetail(val id: String) : Screen
    data class Activity(val login: String) : Screen
    data object Settings : Screen
    data object Logs : Screen
    data object CreateRepo : Screen
    data object Login : Screen
    data class MyIssues(val pulls: Boolean) : Screen
}

enum class UserKind { Followers, Following, Stargazers, Watchers, Contributors, Members }
enum class RepoKind { User, Starred, Org, Forks }

enum class Tab(val title: String) { Home("首页"), Repos("仓库"), Notifications("通知"), Explore("探索"), Me("我的") }

private val ids = AtomicLong()

class Entry(val screen: Screen) {
    val id: String = "e${ids.incrementAndGet()}"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val bag = HashMap<String, Any>()
    fun dispose() = scope.cancel()
}

class Navigator {
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
        lastWasPush = true
        stack.add(Entry(s))
    }

    fun replace(s: Screen) {
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
