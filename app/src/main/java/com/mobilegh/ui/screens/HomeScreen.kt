package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.HomePrefs
import com.mobilegh.data.Involvement
import com.mobilegh.data.IssueQuery
import com.mobilegh.data.Session
import com.mobilegh.data.StarListAccount
import com.mobilegh.data.UserLists
import com.mobilegh.data.Web
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.EventCat
import com.mobilegh.ui.components.EventItem
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.eventCat
import com.mobilegh.ui.components.IssueItem
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.theme.Gh

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val g = Gh.c
    val login = Session.login ?: return
    val me = rememberLoader("me") { GitHub.viewer(it) }
    val account = remember(login, Session.generation) { StarListAccount(login, Session.generation) }
    val lists = rememberLoader("home-collections:$login") { UserLists.lists(account, login) }
    val badges = rememberLoader("home-achievements:$login") { Web.achievements(login, self = true) }
    val listsRevision by UserLists.changes.collectAsState()
    val observedRevision = retain("home-collections-revision:$login") { mutableLongStateOf(listsRevision) }
    LaunchedEffect(listsRevision) {
        if (observedRevision.longValue != listsRevision) {
            observedRevision.longValue = listsRevision
            lists.refresh()
        }
    }
    com.mobilegh.ui.components.RefreshProfileOnChange(me)
    val invites = rememberLoader("inv") { f ->
        GitHub.repoInvitations(f).size + runCatching { GitHub.orgInvitations(f).size }.getOrDefault(0)
    }
    val contrib = rememberContrib(login)
    val feed = rememberPager("feed") { p, f -> GitHub.received(login, p, f) }
    var cat by rememberSaveable { mutableStateOf(EventCat.All) }
    val state = rememberLazyListState()
    val layout = HomePrefs.layout()
    val shortcuts = arrangedShortcuts(
        layout,
        homeShortcuts(
            onIssues = { nav.push(Screen.MyIssues(false)) },
            onPulls = { nav.push(Screen.MyIssues(true)) },
            onRepos = { nav.select(Tab.Repos) },
            onOrgs = { nav.push(Screen.Orgs) },
            onStars = { nav.push(Screen.Repos(RepoKind.Starred, login)) },
            onLists = { nav.push(Screen.StarLists(login)) },
            onGists = { nav.push(Screen.Gists(null)) },
            onRecent = { nav.push(Screen.RecentRepositories) },
        ),
    ) { nav.push(savedSearchScreen(it.query)) }
    com.mobilegh.nav.OnTabReselect(Tab.Home) { state.animateScrollToItem(0) }

    Page(
        "首页", back = false, contentWindowInsets = WindowInsets(0),
        actions = {
            OcButton(R.drawable.oc_plus, { nav.push(Screen.CreateRepo) })
            OcButton(R.drawable.oc_gear, { nav.push(Screen.Settings) })
        },
    ) { pad ->
        PagedList(
            feed, Modifier.padding(pad).fillMaxSize(), state,
            empty = "关注一些开发者或 star 一些仓库后，这里会显示动态",
            emptyIcon = R.drawable.oc_pulse, emptyAction = "去探索", onEmptyAction = { nav.select(Tab.Explore) },
            onRefresh = { me.refresh(); invites.refresh(); contrib.refresh(); lists.refresh(); badges.refresh() },
            itemFilter = { cat == EventCat.All || eventCat(it) == cat },
            itemKey = { it.id },
            header = {
                item { HomeProfile(login, me.data, Session.avatar) { nav.select(Tab.Me) } }
                invites.data?.takeIf { it > 0 }?.let { n -> item { HomeInviteBanner(n) { nav.push(Screen.Invitations) } } }
                item { SectionTitle("我的工作") { TextLink("编辑") { nav.push(Screen.HomeCustomize) } } }
                item {
                    if (shortcuts.isEmpty()) {
                        Text("所有快捷入口都已隐藏，点「编辑」可重新显示", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = g.fgMuted)
                    } else {
                        HomeShortcuts(shortcuts)
                    }
                }
                if (layout.favorites.isNotEmpty()) {
                    item { SectionTitle("收藏仓库") { TextLink("编辑") { nav.push(Screen.HomeCustomize) } } }
                    item {
                        MenuGroup {
                            layout.favorites.forEachIndexed { i, f ->
                                HomeFavoriteRow(
                                    f, canUp = i > 0, canDown = i < layout.favorites.lastIndex,
                                    onOpen = { nav.push(Screen.Repo(f.owner, f.name)) },
                                    onMove = { d -> HomePrefs.moveFavorite(i, d) },
                                    onRemove = { HomePrefs.removeFavorite(f) },
                                )
                            }
                        }
                    }
                }
                item { SectionTitle("收藏列表") { TextLink("全部") { nav.push(Screen.StarLists(login)) } } }
                item {
                    // 首页和列表页共用搜索/排序状态，两处看到的顺序一致
                    val listBrowser = rememberSharedListBrowser("home-lists:$login")
                    Column {
                        SearchBox(
                            listBrowser.input, listBrowser.query, { listBrowser.input = it },
                            { listBrowser.apply() }, { listBrowser.clear() },
                            "搜索收藏列表", "名称或描述",
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        )
                        SortBar(
                            LIST_SORTS, listBrowser.sort, listBrowser.descending,
                            { listBrowser.pick(it) }, { listBrowser.descending = !listBrowser.descending },
                        )
                        HomeLists(
                            lists.data?.let { listBrowser.shown(it) }, lists.data?.size ?: 0, lists.error,
                            onList = { nav.push(Screen.StarLists(login, it.id)) },
                            onAll = { nav.push(Screen.StarLists(login)) },
                            onRetry = { lists.load(true) },
                        )
                    }
                }
                item { SectionTitle("贡献") { TextLink("详情") { nav.select(Tab.Me) } } }
                item {
                    Card(Modifier.padding(horizontal = 16.dp)) {
                        Column {
                            ContributionBody(contrib, full = false)
                            HDivider()
                            HomeAchievementsRow(badges.data, badges.error, onOpen = { nav.push(Screen.Achievements(login)) }, onRetry = { badges.load(true) })
                        }
                    }
                }
                item { SectionTitle("动态") }
                item {
                    Chips(EventCat.entries.map { it.label }, EventCat.entries.indexOf(cat)) { cat = EventCat.entries[it] }
                }
            },
        ) { EventItem(it) }
    }
}

/** 我的 Issues / PR（官方网页的 github.com/issues 页面） */
@Composable
fun MyIssuesScreen(pulls: Boolean, preset: IssueQuery? = null) {
    val start = (preset ?: IssueQuery()).copy(pulls = pulls, repo = null)
    val query = rememberIssueQuery(if (start.involves == null) start.copy(involves = Involvement.Created) else start)
    var saving by remember { mutableStateOf(false) }
    val q = query.value
    Page(
        if (pulls) "我的 Pull Requests" else "我的 Issues",
        actions = {
            MoreMenu(buildList {
                add(MenuAction("保存到首页…") { saving = true })
                if (q.hasFilters) add(MenuAction("清除筛选") { query.value = q.cleared() })
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            IssueFilterBar(q, { query.value = it.normalized() }, segments = true)
            IssueResults(q, { query.value = it.normalized() }, null, null)
        }
    }
    if (saving) SaveSearchDialog(q, "${q.involves?.label ?: ""}${if (pulls) " PR" else " Issues"}".trim()) { saving = false }
}
