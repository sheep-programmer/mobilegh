package com.mobilegh.ui.screens

import com.mobilegh.ui.screens.SyncAchievements
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.Oc
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.FavoriteRepo
import com.mobilegh.data.GitHub
import com.mobilegh.data.HomePrefs
import com.mobilegh.data.Involvement
import com.mobilegh.data.IssueQuery
import com.mobilegh.data.Session
import com.mobilegh.data.StarListAccount
import com.mobilegh.data.UserLists
import com.mobilegh.data.UserStatusApi
import com.mobilegh.data.Web
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.retain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.EventCat
import com.mobilegh.ui.components.EventItem
import com.mobilegh.ui.components.parseInstant
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.eventCat
import com.mobilegh.ui.components.IssueItem
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.SectionActionChip
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.UserStatusBottomSheet
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val g = Gh.c
    val login = Session.login ?: return
    val me = rememberLoader("me") { GitHub.viewer(it) }
    val lists = rememberStarListCatalog(login)
    val badges = rememberLoader("home-achievements:$login") { force -> Web.achievements(login, self = true, force = force) }
    SyncAchievements(login, badges)
    val listsRevision by UserLists.changes.collectAsState()
    val observedRevision = retain("home-collections-revision:$login") { mutableLongStateOf(listsRevision) }
    LaunchedEffect(listsRevision) {
        if (observedRevision.longValue != listsRevision) {
            observedRevision.longValue = listsRevision
            lists.refresh()
        }
    }
    com.mobilegh.ui.components.RefreshProfileOnChange(me)
    val userStatus = rememberLoader("home-status:$login") { runCatching { UserStatusApi.getStatus(login, isViewer = true) }.getOrNull() }
    var showStatusSheet by remember { mutableStateOf(false) }
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
            onFavorites = { nav.push(Screen.Favorites) },
            onSaved = { nav.push(Screen.Saved) },
        ),
    ) { nav.push(savedSearchScreen(it.query)) }
    com.mobilegh.nav.OnTabReselect(Tab.Home) { state.animateScrollToItem(0) }
    // 首页「新建列表」直接在这里弹出创建框，不跳转到列表页
    var creatingList by remember { mutableStateOf(false) }
    var creatingBusy by remember { mutableStateOf(false) }
    var creatingError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // 移出收藏后的撤销提示
    val snackbar = remember { SnackbarHostState() }

    Page(
        tr("首页"), back = false, contentWindowInsets = WindowInsets(0), snackbar = snackbar,
        actions = {
            OcButton(R.drawable.oc_plus, { nav.push(Screen.CreateRepo) })
            OcButton(R.drawable.oc_gear, { nav.push(Screen.Settings) })
        },
    ) { pad ->
        PagedList(
            feed, Modifier.padding(pad).fillMaxSize(), state,
            empty = tr("关注一些开发者或 Star 一些仓库后，这里会显示动态"),
            emptyIcon = R.drawable.oc_pulse, emptyAction = tr("去探索"), onEmptyAction = { nav.select(Tab.Explore) },
            onRefresh = { me.refresh(); userStatus.refresh(); invites.refresh(); contrib.refresh(); lists.refresh(); badges.refresh() },
            // 非「全部」分类是本地过滤，关闭自动翻页，避免稀有分类连续拉取空页；底部保留「加载更多」
            autoLoad = cat == EventCat.All,
            itemFilter = { cat == EventCat.All || eventCat(it) == cat },
            // 按发生时间倒序严格排列，确保最新动态排在最上方
            itemSort = compareByDescending<com.mobilegh.data.Event> { parseInstant(it.createdAt)?.toEpochMilli() ?: Long.MIN_VALUE },
            itemKey = { it.id },
            header = {
                item { HomeProfile(login, me.data, Session.avatar, userStatus.data, onSetStatus = { showStatusSheet = true }) { nav.select(Tab.Me) } }
                invites.data?.takeIf { it > 0 }?.let { n -> item { HomeInviteBanner(n) { nav.push(Screen.Invitations) } } }
                item { SectionTitle(tr("我的工作")) { SectionActionChip(tr("编辑"), tooltip = tr("自定义快捷入口")) { nav.push(Screen.HomeCustomize) } } }
                item {
                    if (shortcuts.isEmpty()) {
                        Text(tr("所有快捷入口都已隐藏，点「编辑」可重新显示"), Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = g.fgMuted)
                    } else {
                        HomeShortcuts(shortcuts)
                    }
                }
                // 贡献详情在「我的」页的贡献活动区块里，所以按钮文字如实写成查看全部贡献
                item { SectionTitle(tr("贡献")) { SectionActionChip(tr("详情"), tooltip = tr("查看全部贡献")) { nav.select(Tab.Me) } } }
                item {
                    Card(Modifier.padding(horizontal = 16.dp)) {
                        Column {
                            ContributionBody(contrib, full = false)
                            HDivider()
                            HomeAchievementsRow(badges.data, badges.error, onOpen = { nav.push(Screen.Achievements(login)) }, onRetry = { badges.load(true) })
                        }
                    }
                }
                // 这里列的是 GitHub Star 列表（与「我的」里的 Star 列表是同一批），不是首页的收藏仓库
                item { SectionTitle(tr("Star 列表")) { SectionActionChip(tr("全部"), tooltip = tr("全部 Star 列表")) { nav.push(Screen.StarLists(login)) } } }
                item {
                    // 首页和列表页共用搜索/排序状态，两处看到的顺序一致
                    val listBrowser = rememberSharedListBrowser("home-lists:$login")
                    Column {
                        SearchBox(
                            listBrowser.input, listBrowser.query, { listBrowser.input = it },
                            { listBrowser.apply() }, { listBrowser.clear() },
                            tr("搜索 Star 列表"), tr("名称或描述"),
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
                            onCreate = { creatingList = true },
                            onRetry = { lists.load(true) },
                        )
                    }
                }
                item {
                    SectionTitle(tr("动态"), trailing = {
                        Dropdown(
                            label = cat.label,
                            options = EventCat.filterOptions.map { it.label },
                            onSelect = { cat = EventCat.filterOptions[it] },
                            active = cat != EventCat.All,
                        )
                    })
                }
            },
        ) { EventItem(it) }
    }
    if (creatingList) GitHubListEditor(null, creatingBusy, creatingError, onDismiss = {
        if (!creatingBusy) { creatingList = false; creatingError = null }
    }) { name, description, privateList ->
        creatingBusy = true
        creatingError = null
        scope.launch {
            try {
                UserLists.create(StarListAccount(login, Session.generation), name, description, privateList)
                creatingList = false
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { creatingError = failure.friendly() }
            finally { creatingBusy = false }
        }
    }
    if (showStatusSheet) {
        UserStatusBottomSheet(
            current = userStatus.data,
            onDismiss = { showStatusSheet = false },
            onSaved = { userStatus.refresh(); me.refresh() },
        )
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
        if (pulls) tr("我的 Pull Requests") else tr("我的 Issues"),
        actions = {
            MoreMenu(buildList {
                add(MenuAction(tr("保存到首页…")) { saving = true })
                if (q.hasFilters) add(MenuAction(tr("清除筛选")) { query.value = q.cleared() })
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            IssueFilterBar(q, { query.value = it.normalized() }, segments = true)
            IssueResults(q, { query.value = it.normalized() }, null, null)
        }
    }
    if (saving) SaveSearchDialog(q, "${q.involves?.label ?: ""}${if (pulls) " Pull Requests" else " Issues"}".trim()) { saving = false }
}
