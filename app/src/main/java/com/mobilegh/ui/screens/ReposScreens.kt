package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.Invitation
import com.mobilegh.data.OrgMembership
import com.mobilegh.data.Session
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.nav.act
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.EventItem
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.RepoItem
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.UserItem
import com.mobilegh.ui.components.boxedItems
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh

private val SORTS = listOf("pushed" to "最近推送", "updated" to "最近更新", "full_name" to "名称", "created" to "创建时间")

/**
 * 仓库 Tab：官方 App 只列出自己创建的仓库，这里可以按 所有者 / 协作者 / 组织成员 查看全部可访问的仓库
 */
@Composable
fun ReposTab() {
    val nav = LocalNav.current
    val g = Gh.c
    val filters = listOf(
        "全部" to "owner,collaborator,organization_member",
        "我创建的" to "owner",
        "协作的" to "collaborator",
        "组织的" to "organization_member",
    )
    val vis = listOf("all" to "全部可见性", "public" to "公开", "private" to "私有")
    var f by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var v by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var hideForks by rememberSaveable { mutableStateOf(false) }
    var orgIndex by rememberSaveable { mutableIntStateOf(0) }
    val orgs = rememberLoader("repo-orgs") { GitHub.myOrgs(it) }
    val orgNames = listOf("全部组织") + orgs.data.orEmpty().map { it.login }
    val safeOrgIndex = orgIndex.coerceIn(0, (orgNames.size - 1).coerceAtLeast(0))
    val selectedOrg = orgNames.getOrNull(safeOrgIndex)?.takeUnless { it == "全部组织" }
    val key = "repos:$f:$sort:$v"
    val pager = rememberPager(key) { p, force ->
        if (f == 3) emptyList() else GitHub.myRepos(filters[f].second, SORTS[sort].first, vis[v].first, p, force)
    }
    val state = rememberLazyListState()
    LaunchedEffect(nav.reselect) { if (nav.reselect > 0 && nav.tab == Tab.Repos) state.animateScrollToItem(0) }
    LaunchedEffect(query, key) { if (query.isNotBlank() && f != 3) pager.loadAll() }

    Page(
        "仓库", back = false, contentWindowInsets = WindowInsets(0),
        actions = { OcButton(R.drawable.oc_plus, { nav.push(Screen.CreateRepo) }) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Chips(filters.map { it.first }, f) { f = it }
            Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown(SORTS[sort].second, SORTS.map { it.second }, { sort = it })
                Dropdown(vis[v].second, vis.map { it.second }, { v = it })
                Dropdown(if (hideForks) "隐藏 Fork" else "含 Fork", listOf("含 Fork", "隐藏 Fork"), { hideForks = it == 1 })
            }
            if (f == 3) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("组织", color = g.fgMuted, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Dropdown(orgNames[safeOrgIndex], orgNames, { orgIndex = it })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (orgs.loading) "正在读取组织…" else "${orgs.data.orEmpty().size} 个组织",
                        color = g.fgMuted, fontSize = 12.sp,
                    )
                }
            }
            GhField(query, { query = it }, "筛选仓库", Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp), placeholder = "输入名称或描述")
            val q = query.trim()
            if (f == 3) {
                OrganizationReposPanel(
                    orgs = if (selectedOrg == null) orgs.data.orEmpty().map { it.login } else listOf(selectedOrg),
                    visibility = vis[v].first,
                    sort = SORTS[sort].first,
                    query = q,
                    hideForks = hideForks,
                )
            } else {
                HDivider()
                if (q.isEmpty() && !hideForks) {
                    PagedList(pager, state = state, empty = "没有仓库") { RepoItem(it) }
                } else {
                    val list = pager.items.filter {
                        (!hideForks || !it.fork) && (q.isEmpty() || it.fullName.contains(q, true) || it.description?.contains(q, true) == true)
                    }
                    PullToRefreshBox(pager.refreshing, pager::refresh) {
                        LazyColumn(Modifier.fillMaxSize(), state = state) {
                            item {
                                Text(
                                    "找到 ${list.size} 个${if (!pager.end) "（正在加载更多…）" else ""}",
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = g.fgMuted,
                                )
                            }
                            boxedItems(list, key = { it.id }) { RepoItem(it) }
                            if (!pager.end && !pager.loading && q.isEmpty()) item { LaunchedEffect(pager.items.size) { pager.loadMore() } }
                        }
                    }
                }
            }
        }
    }
}

/** 组织仓库专用面板：按组织汇总仓库，并在列表下方显示组织事件流。 */
@Composable
private fun OrganizationReposPanel(
    orgs: List<String>,
    visibility: String,
    sort: String,
    query: String,
    hideForks: Boolean,
) {
    val g = Gh.c
    val login = Session.login ?: return
    val repoKey = "org-repos:${orgs.joinToString(",")}:$sort"
    val eventKey = "org-events:${orgs.joinToString(",")}"
    val repos = rememberLoader(repoKey) { force -> GitHub.organizationRepos(orgs, sort, force) }
    val events = rememberLoader(eventKey) { force -> GitHub.organizationEvents(login, orgs, force) }
    val filtered = repos.data.orEmpty().filter {
        (visibility == "all" || (visibility == "private") == it.isPrivate) &&
            (!hideForks || !it.fork) &&
            (query.isBlank() || it.fullName.contains(query, true) || it.description?.contains(query, true) == true)
    }

    if (repos.data == null && repos.error != null) {
        ErrorState(repos.error!!, Modifier.fillMaxSize()) { repos.load(true); events.load(true) }
        return
    }
    if (repos.data == null) {
        Loading(Modifier.fillMaxSize())
        return
    }
    PullToRefreshBox(
        repos.refreshing || events.refreshing,
        onRefresh = { repos.refresh(); events.refresh() },
        Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${filtered.size} 个组织仓库", fontSize = 13.sp, color = g.fgMuted, modifier = Modifier.weight(1f))
                    TextLink("刷新") { repos.refresh(); events.refresh() }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    EmptyState(
                        if (orgs.isEmpty()) "没有读取到组织\n请确认 Token 包含 read:org，并在 GitHub 组织设置中完成 SSO 授权"
                        else "这些组织下没有符合当前筛选的仓库",
                        R.drawable.oc_organization,
                    )
                }
            } else {
                boxedItems(filtered, key = { it.id }) { RepoItem(it) }
            }
            item {
                SectionTitle("组织动态")
                Text(
                    "显示所选组织仓库的 push、Issue、Pull Request 和 Release；GitHub 事件接口可能有几分钟延迟。",
                    Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), fontSize = 12.sp, color = g.fgMuted,
                )
            }
            val activity = events.data.orEmpty()
            if (activity.isEmpty()) {
                item { EmptyState("暂时没有组织动态", R.drawable.oc_pulse) }
            } else {
                boxedItems(activity, key = { it.id }) { EventItem(it) }
            }
            if (events.loading) item { Loading(Modifier.fillMaxWidth().height(72.dp)) }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
fun RepoListScreen(kind: RepoKind, login: String, name: String) {
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val isMe = login.equals(Session.login, true)
    val pager = rememberPager("rl:$kind:$login:$name:$sort") { p, f ->
        when (kind) {
            RepoKind.User -> GitHub.userRepos(login, SORTS[sort].first, p, f)
            RepoKind.Starred -> GitHub.starred(if (isMe) null else login, p, f)
            RepoKind.Org -> GitHub.orgRepos(login, SORTS[sort].first, p, f)
            RepoKind.Forks -> GitHub.forks(login, name, p, f)
        }
    }
    val title = when (kind) {
        RepoKind.User -> "仓库"
        RepoKind.Starred -> "已 Star"
        RepoKind.Org -> "组织仓库"
        RepoKind.Forks -> "Fork"
    }
    Page(title, subtitle = if (name.isNotEmpty()) "$login/$name" else login) { pad ->
        Column(Modifier.padding(pad)) {
            if (kind == RepoKind.User || kind == RepoKind.Org) {
                Row(Modifier.padding(12.dp)) { Dropdown(SORTS[sort].second, SORTS.map { it.second }, { sort = it }) }
                HDivider()
            }
            PagedList(pager) { RepoItem(it, extra = if (kind == RepoKind.Forks) "创建于 ${relTime(it.createdAt)}" else null) }
        }
    }
}

@Composable
fun CreateRepoScreen() {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val orgs = rememberLoader("orgs") { GitHub.myOrgs(it) }
    var owner by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var private by rememberSaveable { mutableStateOf(false) }
    var readme by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val owners = listOf(Session.login ?: "") + orgs.data.orEmpty().map { it.login }
    Page("新建仓库") { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("所有者", color = Gh.c.fgMuted, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                Dropdown(owners.getOrElse(owner) { "" }, owners, { owner = it })
            }
            Spacer(Modifier.height(12.dp))
            GhField(name, { name = it.replace(' ', '-') }, "仓库名称", Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(10.dp))
            GhField(desc, { desc = it }, "描述（可选）", Modifier.padding(horizontal = 16.dp), singleLine = false, minLines = 2)
            Spacer(Modifier.height(8.dp))
            SwitchRow("私有仓库", "只有你和你授权的人可以看到", private) { private = it }
            SwitchRow("添加 README", "用 README 文件初始化仓库", readme) { readme = it }
            Spacer(Modifier.height(16.dp))
            GhButton("创建仓库", Modifier.padding(horizontal = 16.dp).fillMaxWidth(), primary = true, enabled = name.isNotBlank() && !busy) {
                busy = true
                entry.act({ busy = false; ctx.toast(it) }) {
                    val r = GitHub.createRepo(if (owner == 0) null else owners[owner], name.trim(), desc.trim(), private, readme)
                    ctx.toast("已创建 ${r.fullName}")
                    nav.replace(Screen.Repo(r.owner.login, r.name))
                }
            }
        }
    }
}

@Composable
fun OrgsScreen() {
    val nav = LocalNav.current
    val orgs = rememberLoader("myorgs") { GitHub.myOrgs(it) }
    Page("我的组织") { pad ->
        com.mobilegh.ui.components.LoadBox(orgs, Modifier.padding(pad).fillMaxSize()) { list ->
            LazyColumn(Modifier.fillMaxSize()) {
                if (list.isEmpty()) item {
                    EmptyState("你还没有加入任何组织\n（若已加入但看不到，请确认 Token 包含 read:org 权限，且组织已为 Token 授权 SSO）", R.drawable.oc_organization)
                }
                item { Spacer(Modifier.height(12.dp)) }
                boxedItems(list) { o ->
                    UserItem(com.mobilegh.data.User(login = o.login, avatarUrl = o.avatarUrl, type = "Organization", description = o.description))
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    GhButton("查看待处理的组织邀请", Modifier.padding(16.dp).fillMaxWidth(), icon = R.drawable.oc_mail) { nav.push(Screen.Invitations) }
                }
            }
        }
    }
}

/** 仓库协作邀请 / 组织邀请（官方 App 不能处理） */
@Composable
fun InvitationsScreen() {
    val g = Gh.c
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val repoInv = rememberLoader("rinv") { GitHub.repoInvitations(it) }
    val orgInv = rememberLoader("oinv") { runCatching { GitHub.orgInvitations(it) }.getOrDefault(emptyList()) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    Page("待处理邀请") { pad ->
        val list = repoInv.data
        when {
            list == null && repoInv.error != null -> ErrorState(repoInv.error!!, Modifier.padding(pad)) { repoInv.load(true) }
            list == null -> Loading(Modifier.padding(pad).fillMaxSize())
            else -> PullToRefreshBox(repoInv.refreshing, { repoInv.refresh(); orgInv.refresh() }, Modifier.padding(pad)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    val orgs = orgInv.data.orEmpty().filter { "o${it.organization.login}" !in hidden }
                    if (orgs.isNotEmpty()) {
                        item { SectionTitle("组织邀请") }
                        boxedItems(orgs) { m -> OrgInviteRow(m) {
                            hidden = hidden + "o${m.organization.login}"
                            entry.act({ ctx.toast(it) }) { GitHub.acceptOrg(m.organization.login); ctx.toast("已加入 ${m.organization.login}") }
                        } }
                    }
                    item { SectionTitle("仓库协作邀请") }
                    val repos = list.filter { "r${it.id}" !in hidden }
                    if (repos.isEmpty()) item { EmptyState("没有待处理的仓库邀请", R.drawable.oc_mail) }
                    boxedItems(repos, key = { it.id }) { inv ->
                        RepoInviteRow(
                            inv,
                            onAccept = {
                                hidden = hidden + "r${inv.id}"
                                entry.act({ ctx.toast(it); hidden = hidden - "r${inv.id}" }) {
                                    GitHub.acceptInvitation(inv.id)
                                    ctx.toast("已接受，现在可以在「仓库 → 协作的」中找到它")
                                }
                            },
                            onDecline = {
                                hidden = hidden + "r${inv.id}"
                                entry.act({ ctx.toast(it); hidden = hidden - "r${inv.id}" }) { GitHub.declineInvitation(inv.id) }
                            },
                            onOpen = { nav.push(Screen.Repo(inv.repository.owner.login, inv.repository.name)) },
                        )
                    }
                    item {
                        Text(
                            "提示：接受邀请后，官方 App 的仓库列表仍只显示你创建的仓库，而本应用的「仓库 → 协作的」会显示它们。",
                            Modifier.padding(16.dp), fontSize = 12.sp, color = g.fgMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoInviteRow(inv: Invitation, onAccept: () -> Unit, onDecline: () -> Unit, onOpen: () -> Unit) {
    val g = Gh.c
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(inv.inviter?.avatarUrl, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(inv.repository.fullName, fontWeight = FontWeight.SemiBold, color = g.accent, fontSize = 15.sp, modifier = Modifier.then(Modifier))
                Text("${inv.inviter?.login ?: ""} 邀请你以「${permZh(inv.permissions)}」权限协作 · ${relTime(inv.createdAt)}", fontSize = 12.sp, color = g.fgMuted)
            }
        }
        if (inv.expired) Text("邀请已过期", color = g.danger, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhButton("接受", primary = true, enabled = !inv.expired, onClick = onAccept)
            GhButton("拒绝", danger = true, onClick = onDecline)
            GhButton("查看", onClick = onOpen)
        }
    }
}

@Composable
private fun OrgInviteRow(m: OrgMembership, onAccept: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(m.organization.avatarUrl, 36.dp, square = true)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(m.organization.login, fontWeight = FontWeight.SemiBold, color = Gh.c.fg)
            Text("角色：${if (m.role == "admin") "管理员" else "成员"}", fontSize = 12.sp, color = Gh.c.fgMuted)
        }
        GhButton("加入", primary = true, onClick = onAccept)
    }
}

fun permZh(p: String) = when (p) {
    "read", "pull" -> "只读"
    "triage" -> "分类"
    "write", "push" -> "写入"
    "maintain" -> "维护"
    "admin" -> "管理员"
    else -> p
}
