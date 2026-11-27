package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.BuiltinShortcut
import com.mobilegh.data.GitHub
import com.mobilegh.data.HomePrefs
import com.mobilegh.data.Involvement
import com.mobilegh.data.IssueQuery
import com.mobilegh.data.Session
import com.mobilegh.data.StarListAccount
import com.mobilegh.data.UserLists
import com.mobilegh.data.UserListsClient
import com.mobilegh.data.UserStatusApi
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.retain
import com.mobilegh.ui.Badges
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val g = Gh.c
    val login = Session.login ?: return
    val me = rememberLoader("me") { GitHub.viewer(it) }
    val lists = rememberStarListCatalog(login)
    val listsRevision by UserLists.changes.collectAsState()
    val observedRevision = retain("home-collections-revision:$login") { mutableLongStateOf(listsRevision) }
    LaunchedEffect(listsRevision) {
        if (observedRevision.longValue != listsRevision) {
            observedRevision.longValue = listsRevision
            lists.refreshSilently()
        }
    }
    RefreshProfileOnChange(me)
    val userStatus = rememberLoader("home-status:$login") { runCatching { UserStatusApi.getStatus(login, isViewer = true) }.getOrNull() }
    var showStatusSheet by remember { mutableStateOf(false) }
    var showProfilePopup by remember { mutableStateOf(false) }
    val invites = rememberLoader("inv") { f ->
        GitHub.repoInvitations(f).size + runCatching { GitHub.orgInvitations(f).size }.getOrDefault(0)
    }
    val pinned = rememberLoader("home-pinned:$login") { runCatching { GitHub.pinned(login) }.getOrDefault(emptyList()) }
    val state = rememberLazyListState()
    val layout = HomePrefs.layout()
    // 首次进入或未同步过时，自动将 GitHub 官方置顶仓库同步至收藏夹
    LaunchedEffect(pinned.data, layout.pinnedSynced) {
        val pins = pinned.data
        if (pins != null && !layout.pinnedSynced) {
            if (pins.isNotEmpty() && layout.favorites.isEmpty()) {
                HomePrefs.syncWithPinned(pins, merge = false)
            } else {
                HomePrefs.markPinnedSynced()
            }
        }
    }
    val shortcuts = arrangedShortcuts(
        layout,
        homeShortcuts(
            onActivity = { nav.push(Screen.Feed) },
            onIssues = { nav.push(Screen.MyIssues(false)) },
            onPulls = { nav.push(Screen.MyIssues(true)) },
            onRepos = { nav.select(Tab.Repos) },
            onOrgs = { nav.push(Screen.Orgs) },
            onStars = { nav.select(Tab.Star) },
            onLists = { nav.push(Screen.StarLists(login)) },
            onGists = { nav.push(Screen.Gists(null)) },
            onRecent = { nav.push(Screen.RecentRepositories) },
            onFavorites = { nav.push(Screen.Favorites) },
            onSaved = { nav.push(Screen.Saved) },
            onProjects = { nav.push(Screen.Projects(com.mobilegh.data.ProjectOwnerKind.User, login)) },
            onDrafts = { nav.push(Screen.Drafts) },
            onSettings = { nav.push(Screen.Settings) },
            onInvitations = { nav.push(Screen.Invitations) },
            invitationsCount = invites.data?.takeIf { it > 0 },
        ),
    ) { nav.push(savedSearchScreen(it.query)) }
    com.mobilegh.nav.OnTabReselect(Tab.Home) { state.animateScrollToItem(0) }
    var creatingList by remember { mutableStateOf(false) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var pickingIssueRepo by remember { mutableStateOf(false) }
    var creatingBusy by remember { mutableStateOf(false) }
    var creatingError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val unread by Badges.unreadFlow.collectAsState()
    val refreshing = me.refreshing || userStatus.refreshing || lists.refreshing || invites.refreshing || pinned.refreshing

    Page(
        title = tr("首页"),
        back = false,
        contentWindowInsets = WindowInsets(0),
        snackbar = snackbar,
        actions = {
            // 搜索
            OcButton(R.drawable.oc_search, { nav.push(Screen.Search()) }, description = tr("搜索"))
            // 创建
            OcButton(R.drawable.oc_plus, { showCreateSheet = true }, description = tr("新建"))
            HomeDownloadsButton()
            // 消息（带未读角标）
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { nav.push(Screen.Notifications) },
                contentAlignment = Alignment.Center,
            ) {
                if (unread > 0) {
                    BadgedBox(badge = {
                        Badge(containerColor = g.accent) {
                            Text(if (unread > 99) "99+" else "$unread", fontSize = 9.sp)
                        }
                    }) {
                        Oc(R.drawable.oc_bell, g.fg, 20.dp)
                    }
                } else {
                    Oc(R.drawable.oc_bell, g.fg, 20.dp)
                }
            }
            Spacer(Modifier.width(2.dp))
            // 我的（圆形头像按钮，点击弹出个人信息悬浮窗）
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { showProfilePopup = true }
                    .testTag("home_avatar_btn"),
                contentAlignment = Alignment.Center,
            ) {
                Avatar(me.data?.avatarUrl ?: Session.avatar, size = 28.dp)
            }
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                me.refresh()
                userStatus.refresh()
                invites.refresh()
                lists.refresh()
                pinned.refresh()
            },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            ScrollbarLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = state,
            ) {
                invites.data?.takeIf { it > 0 }?.let { n ->
                    item { HomeInviteBanner(n) { nav.push(Screen.Invitations) } }
                }

                // 1. 我的工作区块（排在最上方）
                val displayShortcuts = shortcuts.filterNot {
                    it.id == BuiltinShortcut.Favorites.id || it.id == BuiltinShortcut.Lists.id
                }
                item {
                    SectionTitle(tr("我的工作")) {
                        SectionActionChip(tr("编辑"), tooltip = tr("自定义我的工作入口")) {
                            nav.push(Screen.HomeCustomize)
                        }
                    }
                }
                item {
                    if (displayShortcuts.isEmpty()) {
                        Text(
                            tr("所有入口都已隐藏，点「编辑」可重新显示"),
                            Modifier.padding(horizontal = 16.dp),
                            fontSize = 13.sp,
                            color = g.fgMuted,
                        )
                    } else {
                        HomeShortcuts(displayShortcuts)
                    }
                }

                // 2. 收藏夹区块（常用工作仓库直达）
                item {
                    SectionTitle(
                        tr("收藏夹"),
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                SectionActionChip(tr("同步"), tooltip = tr("同步 GitHub 置顶仓库")) {
                                    scope.launch {
                                        val pins = runCatching { GitHub.pinned(login) }.getOrDefault(emptyList())
                                        if (pins.isNotEmpty()) {
                                            HomePrefs.syncWithPinned(pins, merge = true)
                                            snackbar.showSnackbar(trf("已同步 {0} 个 GitHub 置顶仓库", pins.size))
                                        } else {
                                            snackbar.showSnackbar(tr("GitHub 暂无置顶仓库"))
                                        }
                                    }
                                }
                                SectionActionChip(tr("添加"), tooltip = tr("添加收藏")) { nav.push(Screen.AddFavorites) }
                                // 整理进入只含收藏夹的整理视图，不涉及「我的工作」；超过 8 个时最右上角展示「全部」查看
                                if (layout.favorites.isNotEmpty()) {
                                    SectionActionChip(tr("整理"), tooltip = tr("整理收藏")) { nav.push(Screen.FavoritesOrganize) }
                                }
                                if (layout.favorites.size > HOME_FAVORITES_MAX) {
                                    SectionActionChip(tr("全部"), tooltip = trf("查看全部 {0} 个收藏", layout.favorites.size)) { nav.push(Screen.Favorites) }
                                }
                            }
                        },
                    )
                }
                item {
                    if (layout.favorites.isEmpty()) {
                        Card(Modifier.padding(horizontal = 16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Oc(R.drawable.oc_bookmark, g.attention, 18.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        tr("添加常用仓库到收藏夹以便快速直达，支持与 GitHub 官方置顶仓库同步。"),
                                        fontSize = 12.sp,
                                        color = g.fgMuted,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                ButtonRowAdaptive {
                                    GhButton(
                                        tr("同步 GitHub 置顶"),
                                        icon = R.drawable.oc_sync,
                                        primary = true,
                                    ) {
                                        scope.launch {
                                            val pins = runCatching { GitHub.pinned(login) }.getOrDefault(emptyList())
                                            if (pins.isNotEmpty()) {
                                                HomePrefs.syncWithPinned(pins, merge = true)
                                                snackbar.showSnackbar(trf("已同步 {0} 个 GitHub 置顶仓库", pins.size))
                                            } else {
                                                snackbar.showSnackbar(tr("GitHub 暂无置顶仓库，去添加常用仓库"))
                                                nav.push(Screen.AddFavorites)
                                            }
                                        }
                                    }
                                    GhButton(
                                        tr("添加收藏"),
                                        icon = R.drawable.oc_plus,
                                    ) {
                                        nav.push(Screen.AddFavorites)
                                    }
                                }
                            }
                        }
                    } else {
                        Card(Modifier.padding(horizontal = 16.dp)) {
                            Column {
                                // 首页最多显示 8 个，其余通过右上角「全部」查看
                                layout.favorites.take(HOME_FAVORITES_MAX).forEachIndexed { i, f ->
                                    if (i > 0) HDivider()
                                    HomeFavoriteRow(
                                        repo = f,
                                        canUp = i > 0,
                                        canDown = i < layout.favorites.lastIndex,
                                        onOpen = { nav.push(Screen.Repo(f.owner, f.name)) },
                                        onMove = { d -> HomePrefs.moveFavorite(i, d) },
                                        onRemove = { removeFavoriteWithUndo(scope, snackbar, i, f) },
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Star 列表区块（极简横卡展示，点「全部」进入完整管理）
                val allLists = lists.data.orEmpty()
                if (allLists.isNotEmpty() || lists.loading) {
                    item {
                        SectionTitle(tr("Star 列表")) {
                            SectionActionChip(tr("全部"), tooltip = tr("全部 Star 列表")) {
                                nav.push(Screen.StarLists(login))
                            }
                        }
                    }
                    item {
                        HomeLists(
                            allLists.take(8),
                            allLists.size,
                            lists.error,
                            onList = { nav.push(Screen.StarLists(login, it.id)) },
                            onAll = { nav.push(Screen.StarLists(login)) },
                            onCreate = { creatingList = true },
                            onRetry = { lists.load(true) },
                        )
                    }
                } else {
                    item {
                        SectionTitle(tr("Star 列表")) {
                            SectionActionChip(tr("创建"), tooltip = tr("新建 Star 列表")) {
                                creatingList = true
                            }
                        }
                    }
                    item {
                        Card(Modifier.padding(horizontal = 16.dp)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { creatingList = true }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Oc(R.drawable.oc_checklist, g.accent, 18.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(tr("创建 Star 列表"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                                    Text(tr("将 Star 的开源项目分类归纳整理"), fontSize = 11.sp, color = g.fgMuted)
                                }
                                Oc(R.drawable.oc_plus, g.accent, 14.dp)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }

    if (showProfilePopup) {
        HomeProfileDialog(
            login = login,
            user = me.data,
            avatar = Session.avatar,
            status = userStatus.data,
            onSetStatus = { showStatusSheet = true },
            onViewProfile = { nav.push(Screen.Me) },
            onDismiss = { showProfilePopup = false },
        )
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

    if (showCreateSheet) {
        HomeCreateSheet(
            canCreateList = lists.data.orEmpty().size < UserListsClient.MAX_LISTS,
            onDismiss = { showCreateSheet = false },
            onNewIssue = { showCreateSheet = false; pickingIssueRepo = true },
            onCreateRepo = { showCreateSheet = false; nav.push(Screen.CreateRepo) },
            onCreateOrg = { showCreateSheet = false; nav.push(Screen.CreateOrg) },
            onImportRepo = { showCreateSheet = false; nav.push(Screen.ImportRepo) },
            onCreateGist = { showCreateSheet = false; nav.push(Screen.GistEditor(null)) },
            onCreateList = { showCreateSheet = false; creatingList = true },
            onNewShortcut = { showCreateSheet = false; nav.push(Screen.NewShortcut) },
        )
    }

    if (pickingIssueRepo) {
        HomeNewIssueRepoPicker(
            favorites = layout.favorites,
            onDismiss = { pickingIssueRepo = false },
            onPick = { owner, name ->
                pickingIssueRepo = false
                nav.push(Screen.NewIssue(owner, name))
            },
        )
    }
}

@Composable
private fun HomeCreateSheet(
    canCreateList: Boolean,
    onDismiss: () -> Unit,
    onNewIssue: () -> Unit,
    onCreateRepo: () -> Unit,
    onCreateOrg: () -> Unit,
    onImportRepo: () -> Unit,
    onCreateGist: () -> Unit,
    onCreateList: () -> Unit,
    onNewShortcut: () -> Unit,
) {
    val g = Gh.c
    FilterSheet(
        title = tr("新建"),
        subtitle = tr("选择要创建的内容"),
        closeDescription = tr("关闭"),
        onDismiss = onDismiss,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        contentSpacing = 0.dp,
        footer = {},
    ) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(g.canvas, shape)
                .border(1.dp, g.border, shape),
        ) {
            CreateActionRow(R.drawable.oc_issue_opened, tr("新建 Issue"), tr("选择仓库并提交新的 Issue"), onClick = onNewIssue)
            HDivider(Modifier.padding(start = 52.dp))
            CreateActionRow(R.drawable.oc_repo, tr("新建仓库"), tr("创建一个新的个人或组织仓库"), onClick = onCreateRepo)
            HDivider(Modifier.padding(start = 52.dp))
            CreateActionRow(R.drawable.oc_organization, tr("新建组织"), tr("创建一个新的 GitHub 团队或组织"), onClick = onCreateOrg)
            HDivider(Modifier.padding(start = 52.dp))
            CreateActionRow(R.drawable.oc_repo_push, tr("导入仓库"), tr("从其他 Git 地址导入已有代码仓库"), onClick = onImportRepo)
            HDivider(Modifier.padding(start = 52.dp))
            CreateActionRow(R.drawable.oc_code, tr("新建 Gist"), tr("创建公开或私密代码片段"), onClick = onCreateGist)
            if (canCreateList) {
                HDivider(Modifier.padding(start = 52.dp))
                CreateActionRow(R.drawable.oc_checklist, tr("新建 Star 列表"), tr("创建用于归类收藏仓库的列表"), onClick = onCreateList)
            }
            HDivider(Modifier.padding(start = 52.dp))
            CreateActionRow(R.drawable.oc_plus, tr("添加首页快捷方式"), tr("将常用仓库或页面固定到首页"), onClick = onNewShortcut)
        }
    }
}

@Composable
private fun CreateActionRow(
    icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, g.accent, g.accentSubtle, 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
            Text(subtitle, fontSize = 12.sp, color = g.fgMuted)
        }
        Oc(R.drawable.oc_chevron_right, g.fgMuted, 16.dp)
    }
}

@Composable
private fun HomeNewIssueRepoPicker(
    favorites: List<com.mobilegh.data.FavoriteRepo>,
    onDismiss: () -> Unit,
    onPick: (owner: String, name: String) -> Unit,
) {
    val g = Gh.c
    var query by remember { mutableStateOf("") }
    val mine = rememberLoader("home-new-issue-repos") {
        GitHub.myRepos("owner,collaborator,organization_member", "pushed", "all", 1, it)
    }
    var remoteResults by remember { mutableStateOf<List<com.mobilegh.data.Repo>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val trimmed = query.trim()
    LaunchedEffect(trimmed) {
        if (trimmed.length < 2) {
            remoteResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(300)
        searching = true
        remoteResults = runCatching { GitHub.searchRepos(trimmed, null, 1, false).items }.getOrDefault(emptyList())
        searching = false
    }
    val localCandidates = remember(favorites, mine.data) {
        val seen = HashSet<String>()
        buildList {
            favorites.forEach { fav ->
                if (seen.add(fav.fullName.lowercase())) add(fav.owner to fav.name)
            }
            mine.data.orEmpty().forEach { repo ->
                val fn = "${repo.owner.login}/${repo.name}"
                if (seen.add(fn.lowercase())) add(repo.owner.login to repo.name)
            }
        }
    }
    val shown = remember(localCandidates, remoteResults, trimmed) {
        if (trimmed.isEmpty()) localCandidates
        else {
            val seen = HashSet<String>()
            buildList {
                localCandidates.filter { (o, n) -> "$o/$n".contains(trimmed, ignoreCase = true) }
                    .forEach { (o, n) -> if (seen.add("$o/$n".lowercase())) add(o to n) }
                remoteResults.forEach { repo ->
                    val pair = repo.owner.login to repo.name
                    if (seen.add("${pair.first}/${pair.second}".lowercase())) add(pair)
                }
            }
        }
    }
    FilterSheet(
        title = tr("新建 Issue"),
        subtitle = tr("选择要提交 Issue 的仓库"),
        closeDescription = tr("关闭"),
        onDismiss = onDismiss,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        contentSpacing = 10.dp,
        footer = {},
    ) {
        GhField(query, { query = it }, tr("搜索仓库 (owner/name)"), singleLine = true)
        when {
            (mine.loading && localCandidates.isEmpty()) || searching && shown.isEmpty() ->
                Loading(Modifier.fillMaxWidth().height(72.dp))
            shown.isEmpty() ->
                Text(tr("没有匹配的仓库，试试输入完整仓库名"), fontSize = 13.sp, color = g.fgMuted, modifier = Modifier.padding(vertical = 8.dp))
            else -> {
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .clip(shape)
                        .background(g.canvas, shape)
                        .border(1.dp, g.border, shape)
                        .verticalScrollWithBar(),
                ) {
                    shown.forEachIndexed { index, (owner, name) ->
                        if (index > 0) HDivider(Modifier.padding(start = 42.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onPick(owner, name) }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Oc(R.drawable.oc_repo, g.fgMuted, 16.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("$owner / $name", Modifier.weight(1f), fontSize = 14.sp, color = g.fg)
                            Oc(R.drawable.oc_chevron_right, g.fgMuted, 14.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeDownloadsButton() {
    val count = com.mobilegh.data.Downloads.activeCount
    BadgedBox(badge = {
        if (count > 0) Badge { Text(if (count > 99) "99+" else count.toString(), fontSize = 9.sp) }
    }) {
        OcButton(R.drawable.oc_download, com.mobilegh.data.Downloads::openHistory, description = tr("下载记录"))
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

/** 首页收藏夹最多直接显示的个数 */
internal const val HOME_FAVORITES_MAX = 8
