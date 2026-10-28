package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.data.GitHub
import com.mobilegh.data.Invitation
import com.mobilegh.data.OrgMembership
import com.mobilegh.data.Session
import com.mobilegh.nav.act
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.R
import com.mobilegh.ui.components.ActiveFilterChips
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.boxedItems
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.ChoicePicker
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.EventCat
import com.mobilegh.ui.components.eventCat
import com.mobilegh.ui.components.EventItem
import com.mobilegh.ui.components.FilterCard
import com.mobilegh.ui.components.FilterEntry
import com.mobilegh.ui.components.FilterSheet
import com.mobilegh.ui.components.CompactSearchField
import com.mobilegh.ui.components.FieldAction
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.RepoItem
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.components.UserItem
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

internal val SORTS get() = listOf("pushed" to tr("最近推送"), "updated" to tr("最近更新"), "full_name" to tr("名称"), "created" to tr("创建时间"))

/** Repositories available to the signed-in account, including collaborators and organizations. */
@Composable
fun ReposTab() {
    val nav = LocalNav.current
    val g = Gh.c
    val filters = listOf(tr("全部") to "owner,collaborator,organization_member", tr("我的") to "owner", tr("协作") to "collaborator", tr("组织") to "organization_member")
    val vis = listOf("all" to tr("全部可见性"), "public" to tr("公开"), "private" to tr("私有"))
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var visibility by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var hideForks by rememberSaveable { mutableStateOf(false) }
    var selectedOrg by rememberSaveable { mutableStateOf<String?>(null) }
    val directory = rememberLoader("repo-orgs") { GitHub.orgAccess(it) }
    val orgs = directory.data?.orgs.orEmpty().map { it.login }
    val safeOrg = selectedOrg?.takeIf { it in orgs }
    val key = "repos:$filter:$sort:$visibility"
    val pager = rememberPager(key) { p, force ->
        if (filter == 3) emptyList() else GitHub.myRepos(filters[filter].second, SORTS[sort].first, vis[visibility].first, p, force)
    }
    val state = rememberLazyListState()
    com.mobilegh.nav.OnTabReselect(Tab.Repos) { state.animateScrollToItem(0) }
    LaunchedEffect(query, key) { if (query.isNotBlank() && filter != 3) pager.loadAll() }
    var sheet by rememberSaveable { mutableStateOf(false) }
    // 非默认的排序、可见性、Fork、组织都以可撤销标签展示，一眼能看出当前列表被什么收窄
    val active = buildList {
        if (sort != 0) add("sort" to trf("按{0}", SORTS[sort].second))
        if (visibility != 0) add("visibility" to trf("仅{0}", vis[visibility].second))
        if (hideForks) add("forks" to tr("隐藏 Fork"))
        if (filter == 3 && safeOrg != null) add("org" to safeOrg)
    }
    Page(tr("仓库"), back = false, contentWindowInsets = WindowInsets(0), actions = {
        OcButton(R.drawable.oc_plus, { nav.push(Screen.CreateRepo) })
        OcButton(R.drawable.oc_shield_lock, { nav.push(Screen.OrgDiagnostics) })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactSearchField(
                    query, { query = it }, tr("筛选仓库"), Modifier.weight(1f), hint = tr("名称或描述"),
                    trailing = if (query.isEmpty()) null else { { FieldAction(R.drawable.oc_x, tr("清空筛选关键词")) { query = "" } } },
                )
                Spacer(Modifier.width(8.dp))
                FilterEntry(active.size) { sheet = true }
            }
            Chips(filters.map { it.first }, filter) { filter = it }
            if (active.isNotEmpty()) ActiveFilterChips(
                active,
                onRemove = { k ->
                    when (k) {
                        "sort" -> sort = 0
                        "visibility" -> visibility = 0
                        "forks" -> hideForks = false
                        "org" -> selectedOrg = null
                    }
                },
                onClear = { sort = 0; visibility = 0; hideForks = false; selectedOrg = null },
            )
            HDivider()
            if (filter == 3) {
                when {
                    directory.data == null && directory.loading -> Loading(Modifier.weight(1f).fillMaxWidth())
                    directory.data == null && directory.error != null -> ErrorState(directory.error!!, Modifier.weight(1f)) { directory.refresh() }
                    else -> OrganizationReposPanel(if (safeOrg == null) orgs else listOf(safeOrg), vis[visibility].first,
                        SORTS[sort].first, query.trim(), hideForks, directory.data?.warnings.orEmpty(), Modifier.weight(1f), directory::refresh)
                }
            } else {
                PagedList(pager, Modifier.weight(1f), state = state, empty = tr("没有匹配的仓库"), emptyIcon = R.drawable.oc_repo,
                    emptyAction = if (query.isNotBlank() || hideForks || visibility != 0 || filter != 0) tr("清除筛选") else tr("新建仓库"),
                    onEmptyAction = {
                        if (query.isNotBlank() || hideForks || visibility != 0 || filter != 0) {
                            query = ""; hideForks = false; visibility = 0; filter = 0
                        } else nav.push(Screen.CreateRepo)
                    }, itemKey = { it.id }, itemFilter = {
                    (visibility == 0 || (visibility == 2) == it.isPrivate) && (!hideForks || !it.fork) &&
                        (query.isBlank() || it.fullName.contains(query.trim(), true) || it.description?.contains(query.trim(), true) == true)
                }) { RepoItem(it) }
            }
        }
    }
    if (sheet) ReposFilterSheet(
        sort = sort, onSort = { sort = it },
        visibility = visibility, onVisibility = { visibility = it },
        hideForks = hideForks, onHideForks = { hideForks = it },
        orgs = if (filter == 3) orgs else null, selectedOrg = safeOrg, orgsLoading = directory.loading,
        onOrg = { selectedOrg = it }, onRefreshOrgs = { directory.refresh() },
        canReset = active.isNotEmpty(),
        onReset = { sort = 0; visibility = 0; hideForks = false; selectedOrg = null },
        onDismiss = { sheet = false },
    )
}

/** 仓库列表的排序与筛选：选项即时生效，背后的列表同步更新 */
@Composable
private fun ReposFilterSheet(
    sort: Int, onSort: (Int) -> Unit,
    visibility: Int, onVisibility: (Int) -> Unit,
    hideForks: Boolean, onHideForks: (Boolean) -> Unit,
    orgs: List<String>?, selectedOrg: String?, orgsLoading: Boolean,
    onOrg: (String?) -> Unit, onRefreshOrgs: () -> Unit,
    canReset: Boolean, onReset: () -> Unit, onDismiss: () -> Unit,
) {
    val g = Gh.c
    FilterSheet(
        tr("排序与筛选"), tr("选择后立即生效"), tr("关闭筛选"), onDismiss,
        footer = {
            GhButton(tr("重置"), Modifier.weight(1f), enabled = canReset) { onReset() }
            GhButton(tr("完成"), Modifier.weight(1f), primary = true) { onDismiss() }
        },
    ) {
        FilterCard(null) {
            ChoicePicker(tr("排序"), SORTS.map { it.second }, sort, onSort)
        }
        FilterCard(null) {
            ChoicePicker(tr("可见性"), listOf(tr("全部"), tr("公开"), tr("私有")), visibility, onVisibility)
            ChoicePicker("Fork", listOf(tr("包含 Fork"), tr("隐藏 Fork")), if (hideForks) 1 else 0) { onHideForks(it == 1) }
        }
        if (orgs != null) FilterCard(tr("组织")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (orgsLoading) tr("读取中…") else trf("{0} 个组织", orgs.size), Modifier.weight(1f), color = g.fgMuted, fontSize = 13.sp)
                OcButton(R.drawable.oc_sync, onRefreshOrgs, g.fgMuted, description = tr("刷新组织列表"))
            }
            Dropdown(selectedOrg ?: tr("全部组织"), listOf(tr("全部组织")) + orgs, { onOrg(orgs.getOrNull(it - 1)) }, active = selectedOrg != null)
        }
    }
}

@Composable
private fun OrganizationReposPanel(
    orgs: List<String>, visibility: String, sort: String, query: String, hideForks: Boolean,
    warnings: List<String>, modifier: Modifier, refreshDirectory: () -> Unit,
) {
    val g = Gh.c
    val nav = LocalNav.current
    val login = Session.login ?: return
    val repos = rememberLoader("org-repos:${orgs.joinToString(",")}:$sort") { GitHub.organizationRepos(orgs, sort, it) }
    val events = rememberLoader("org-events:${orgs.joinToString(",")}") { GitHub.organizationEvents(login, orgs, it) }
    var category by rememberSaveable { mutableStateOf(EventCat.All) }
    val filtered = repos.data.orEmpty().filter {
        (visibility == "all" || (visibility == "private") == it.isPrivate) && (!hideForks || !it.fork) &&
            (query.isBlank() || it.fullName.contains(query, true) || it.description?.contains(query, true) == true)
    }
    PullToRefreshBox(repos.refreshing || events.refreshing, { refreshDirectory(); repos.refresh(); events.refresh() }, modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(trf("{0} 个组织仓库", filtered.size), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = g.fg)
                    TextLink(tr("权限诊断")) { nav.push(Screen.OrgDiagnostics) }
                }
            }
            if (warnings.isNotEmpty()) item {
                Text(warnings.joinToString("\n"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = g.attention, fontSize = 12.sp)
            }
            if (repos.loading) item { Loading(Modifier.fillMaxWidth().height(80.dp)) }
            repos.error?.let { msg -> item { ErrorState(msg, Modifier.fillMaxWidth()) { repos.refresh() } } }
            if (filtered.isEmpty() && !repos.loading && repos.error == null) item {
                EmptyState(if (orgs.isEmpty()) tr("当前授权未返回组织，查看权限诊断或待处理邀请") else tr("没有匹配仓库；私有仓库需相应权限和 SSO 授权"), R.drawable.oc_organization)
            }
            filtered.groupBy { it.owner.login }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (org, group) ->
                item(key = "group:$org") { SectionTitle(org) { TextLink(tr("组织")) { nav.push(Screen.Org(org)) } } }
                boxedItems(group, key = { "repo:${it.id}" }) { RepoItem(it, showOwner = false) }
            }
            item { SectionTitle(tr("组织动态")) }
            item { Chips(EventCat.entries.map { it.label }, EventCat.entries.indexOf(category)) { category = EventCat.entries[it] } }
            val activity = events.data.orEmpty().distinctBy { it.id }.filter { category == EventCat.All || eventCat(it) == category }
            boxedItems(activity, key = { "event:${it.id}" }) { EventItem(it) }
            if (events.loading) item { Loading(Modifier.fillMaxWidth().height(64.dp)) }
            events.error?.let { msg -> item { ErrorState(msg, Modifier.fillMaxWidth()) { events.refresh() } } }
            if (activity.isEmpty() && !events.loading && events.error == null) item { EmptyState(tr("该分类暂时没有动态"), R.drawable.oc_pulse) }
            item { Text(tr("事件由 GitHub 提供，可能延迟；下拉刷新更新。"), Modifier.padding(16.dp), color = g.fgMuted, fontSize = 12.sp) }
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
    Page(tr("新建仓库")) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("所有者"), color = Gh.c.fgMuted, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                Dropdown(owners.getOrElse(owner) { "" }, owners, { owner = it })
            }
            Spacer(Modifier.height(12.dp))
            GhField(name, { name = it.replace(' ', '-') }, tr("仓库名称"), Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(10.dp))
            GhField(desc, { desc = it }, tr("描述（可选）"), Modifier.padding(horizontal = 16.dp), singleLine = false, minLines = 2)
            Spacer(Modifier.height(8.dp))
            SwitchRow(tr("私有仓库"), tr("只有你和你授权的人可以看到"), private) { private = it }
            SwitchRow(tr("添加 README"), tr("用 README 文件初始化仓库"), readme) { readme = it }
            Spacer(Modifier.height(16.dp))
            GhButton(tr("创建仓库"), Modifier.padding(horizontal = 16.dp).fillMaxWidth(), primary = true, enabled = name.isNotBlank() && !busy) {
                busy = true
                entry.act({ busy = false; ctx.toast(it) }) {
                    val r = GitHub.createRepo(if (owner == 0) null else owners[owner], name.trim(), desc.trim(), private, readme)
                    ctx.toast(trf("已创建 {0}", r.fullName))
                    nav.replace(Screen.Repo(r.owner.login, r.name))
                }
            }
        }
    }
}

@Composable
fun OrgsScreen() {
    val nav = LocalNav.current
    val g = Gh.c
    val access = rememberLoader("myorgs") { GitHub.orgAccess(it) }
    Page(tr("我的组织"), actions = { OcButton(R.drawable.oc_shield_lock, { nav.push(Screen.OrgDiagnostics) }) }) { pad ->
        com.mobilegh.ui.components.LoadBox(access, Modifier.padding(pad).fillMaxSize()) { data ->
            LazyColumn(Modifier.fillMaxSize()) {
                item { SectionTitle(trf("{0} 个可访问组织", data.orgs.size)) { TextLink(tr("权限诊断")) { nav.push(Screen.OrgDiagnostics) } } }
                if (data.warnings.isNotEmpty()) item {
                    Text(data.warnings.joinToString("\n"), Modifier.padding(16.dp), color = g.attention, fontSize = 13.sp)
                }
                if (data.orgs.isEmpty()) item { EmptyState(tr("当前授权没有返回组织。请检查权限、组织策略、SSO 和邀请状态。"), R.drawable.oc_organization) }
                boxedItems(data.orgs, key = { it.login }) { o ->
                    UserItem(com.mobilegh.data.User(login = o.login, avatarUrl = o.avatarUrl, type = "Organization", description = o.description))
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    GhButton(tr("重新读取组织"), Modifier.padding(horizontal = 16.dp).fillMaxWidth(), icon = R.drawable.oc_sync) { access.refresh() }
                    Spacer(Modifier.height(8.dp))
                    GhButton(tr("待处理组织邀请"), Modifier.padding(horizontal = 16.dp).fillMaxWidth(), icon = R.drawable.oc_mail) { nav.push(Screen.Invitations) }
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
    Page(tr("待处理邀请")) { pad ->
        val list = repoInv.data
        when {
            list == null && repoInv.error != null -> ErrorState(repoInv.error!!, Modifier.padding(pad)) { repoInv.load(true) }
            list == null -> Loading(Modifier.padding(pad).fillMaxSize())
            else -> PullToRefreshBox(repoInv.refreshing, { repoInv.refresh(); orgInv.refresh() }, Modifier.padding(pad)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    val orgs = orgInv.data.orEmpty().filter { "o${it.organization.login}" !in hidden }
                    if (orgs.isNotEmpty()) {
                        item { SectionTitle(tr("组织邀请")) }
                        boxedItems(orgs) { m -> OrgInviteRow(m) {
                            hidden = hidden + "o${m.organization.login}"
                            entry.act({ ctx.toast(it) }) { GitHub.acceptOrg(m.organization.login); ctx.toast(trf("已加入 {0}", m.organization.login)) }
                        } }
                    }
                    item { SectionTitle(tr("仓库协作邀请")) }
                    val repos = list.filter { "r${it.id}" !in hidden }
                    if (repos.isEmpty()) item { EmptyState(tr("没有待处理的仓库邀请"), R.drawable.oc_mail) }
                    boxedItems(repos, key = { it.id }) { inv ->
                        RepoInviteRow(
                            inv,
                            onAccept = {
                                hidden = hidden + "r${inv.id}"
                                entry.act({ ctx.toast(it); hidden = hidden - "r${inv.id}" }) {
                                    GitHub.acceptInvitation(inv.id)
                                    ctx.toast(tr("已接受，现在可以在「仓库 → 协作的」中找到它"))
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
                            tr("提示：接受邀请后，官方 App 的仓库列表仍只显示你创建的仓库，而本应用的「仓库 → 协作的」会显示它们。"),
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
                Text(trf("{0} 邀请你以「{1}」权限协作 · {2}", inv.inviter?.login ?: "", permZh(inv.permissions), relTime(inv.createdAt)), fontSize = 12.sp, color = g.fgMuted)
            }
        }
        if (inv.expired) Text(tr("邀请已过期"), color = g.danger, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhButton(tr("接受"), primary = true, enabled = !inv.expired, onClick = onAccept)
            GhButton(tr("拒绝"), danger = true, onClick = onDecline)
            GhButton(tr("查看"), onClick = onOpen)
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
            Text(trf("角色：{0}", if (m.role == "admin") tr("管理员") else tr("成员")), fontSize = 12.sp, color = Gh.c.fgMuted)
        }
        GhButton(tr("加入"), primary = true, onClick = onAccept)
    }
}

fun permZh(p: String) = when (p) {
    "read", "pull" -> tr("只读")
    "triage" -> tr("分类")
    "write", "push" -> tr("写入")
    "maintain" -> tr("维护")
    "admin" -> tr("管理员")
    else -> p
}
