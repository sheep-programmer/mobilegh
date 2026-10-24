package com.mobilegh.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Achievement
import com.mobilegh.data.Api
import com.mobilegh.data.GitHub
import com.mobilegh.data.ListedRepository
import com.mobilegh.data.LocalStarList
import com.mobilegh.data.LocalStarListsStore
import com.mobilegh.data.Repo
import com.mobilegh.data.Session
import com.mobilegh.data.StarListAccount
import com.mobilegh.data.StarLists
import com.mobilegh.data.Web
import com.mobilegh.data.GitHubStarList
import com.mobilegh.data.RepositoryListMemberships
import com.mobilegh.data.UserListRepository
import com.mobilegh.data.UserLists
import com.mobilegh.data.UserListsClient
import com.mobilegh.data.starMatches
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Segmented
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.emptyItem
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.NetImage
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.RepoItem
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun AchievementsStrip(login: String, self: Boolean) {
    val ctx = rememberCtx()
    val loader = rememberLoader("ach-public:${login.lowercase(Locale.ROOT)}") { Web.achievements(login, self) }
    Column {
        SectionTitle("成就", trailing = { Text("公开主页", fontSize = 12.sp, color = Gh.c.fgMuted) })
        when {
            loader.data == null && loader.error != null -> Text(
                "成就暂时无法读取 · 点击重试", color = Gh.c.fgMuted, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable { loader.load(true) }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            loader.data == null -> Loading(Modifier.fillMaxWidth().height(56.dp))
            loader.data!!.isEmpty() -> Text(
                "公开主页未显示成就，隐藏的成就无法读取。", color = Gh.c.fgMuted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            else -> Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                loader.data!!.forEach { badge ->
                    AchievementBadge(badge, Modifier.clickable {
                        ctx.openBrowser("https://github.com/${Api.encodePath(login)}?achievement=${Api.q(badge.slug)}&tab=achievements")
                    })
                }
            }
        }
    }
}

@Composable
internal fun AchievementBadge(badge: Achievement, modifier: Modifier = Modifier) {
    val tier = when (badge.tier) { "bronze" -> "铜级"; "silver" -> "银级"; "gold" -> "金级"; else -> "" }
    Column(
        modifier.width(76.dp).semantics(mergeDescendants = true) {
            contentDescription = listOf(badge.name, tier, "x${badge.count}").filter(String::isNotEmpty).joinToString("，")
        }, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(60.dp), contentAlignment = Alignment.BottomEnd) {
            NetImage(badge.imageUrl, Modifier.size(60.dp), contentScale = ContentScale.Fit, placeholder = false)
            if (badge.count > 1) Pill("x${badge.count}", Gh.c.accent)
        }
        Spacer(Modifier.height(4.dp))
        Text(badge.name, fontSize = 11.sp, lineHeight = 13.sp, color = Gh.c.fgMuted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (tier.isNotEmpty()) Text(tier, fontSize = 11.sp, color = Gh.c.fgMuted)
    }
}

@Composable
fun AchievementsScreen(login: String) {
    val ctx = rememberCtx()
    val self = login.equals(Session.login, true)
    val badges = rememberLoader("ach-public:${login.lowercase(Locale.ROOT)}") { Web.achievements(login, self) }
    fun open(badge: Achievement) {
        ctx.openBrowser("https://github.com/${Api.encodePath(login)}?achievement=${Api.q(badge.slug)}&tab=achievements")
    }
    Page(if (self) "我的成就" else "成就", subtitle = login, actions = {
        OcButton(R.drawable.oc_link_external, { ctx.openBrowser("https://github.com/${Api.encodePath(login)}?tab=achievements") })
    }) { pad ->
        LoadBox(badges, Modifier.padding(pad).fillMaxSize()) { data ->
            val listState = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp)) {
                item { Text("公开主页的徽章、等级和倍数", color = Gh.c.fgMuted, fontSize = 13.sp) }
                if (data.isEmpty()) emptyItem(listState, "公开主页尚未显示成就；隐藏的徽章无法读取", R.drawable.oc_verified, "在 GitHub 查看") {
                    ctx.openBrowser("https://github.com/${Api.encodePath(login)}?tab=achievements")
                }
                else item {
                    FlowRow(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(24.dp), maxItemsInEachRow = 3) {
                        data.forEach { badge -> AchievementBadge(badge, Modifier.clickable { open(badge) }) }
                    }
                }
            }
        }
    }
}

/** All local detail/edit/picker states live inside the existing Screen.StarLists(login) route. */
@Composable
fun StarListsScreen(login: String, initialListId: String? = null) {
    key(login.lowercase(Locale.ROOT), Session.generation) {
        var local by rememberSaveable(login, Session.generation) { mutableStateOf(false) }
        val self = Session.token != null && login.equals(Session.login, true)
        BackHandler(enabled = local) { local = false }
        if (local && self) StarListsContent(login, Session.generation) { local = false }
        else GitHubListsContent(login, Session.generation, initialListId, onLocal = if (self) ({ local = true }) else null)
    }
}

@Composable
private fun StarListsContent(login: String, generation: Int, onGitHub: () -> Unit) {
    val ctx = rememberCtx()
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val account = remember { StarListAccount(login, generation) }
    val store = remember { StarLists.store(ctx) }
    val self = Session.token != null && login.equals(Session.login, true)
    var lists by remember { mutableStateOf<List<LocalStarList>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable(account) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<LocalStarList?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LocalStarList?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable(account) { mutableStateOf("") }
    val selected = lists?.firstOrNull { it.id == selectedId }

    fun load() {
        scope.launch {
            try {
                lists = withContext(Dispatchers.IO) { store.read(account) }
                error = null
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
        }
    }

    fun change(onDone: () -> Unit = {}, action: () -> List<LocalStarList>) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try {
                lists = withContext(Dispatchers.IO) { action() }
                onDone()
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { saving = false }
        }
    }

    LaunchedEffect(account, self) { if (self) load() }
    BackHandler(enabled = selectedId != null && !editorOpen && deleting == null && !pickerOpen) {
        selectedId = null
        query = ""
    }
    Page(selected?.name ?: "Star 列表", subtitle = "$login · 本机列表", onBack = if (selected != null) ({ selectedId = null; query = "" }) else null, actions = {
        if (selected != null) {
            OcButton(R.drawable.oc_plus, { pickerOpen = true }, enabled = !saving && selected.repositories.size < LocalStarListsStore.MAX_REPOSITORIES)
            MoreMenu(listOf(MenuAction("编辑列表") { editing = selected; editorOpen = true }, MenuAction("删除列表", danger = true) { deleting = selected }))
        } else OcButton(R.drawable.oc_link_external, { ctx.openBrowser("https://github.com/${Api.encodePath(login)}?tab=stars") })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (selectedId == null && self) Segmented(listOf("GitHub", "本机"), 1, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), enabled = !saving) { onGitHub() }
            Column(Modifier.weight(1f)) {
                Text("按账号保存在当前设备，不会同步到 GitHub。", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = Gh.c.fgMuted)
                if (error != null && lists != null) Text(error!!, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Gh.c.danger, fontSize = 13.sp)
                when {
                    lists == null && error != null -> ErrorState(error!!, Modifier.weight(1f)) { load() }
                    lists == null -> Loading(Modifier.weight(1f).fillMaxWidth())
                    selected == null -> {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            GhButton("新建本机列表", icon = R.drawable.oc_plus, enabled = !saving && lists!!.size < LocalStarListsStore.MAX_LISTS) { editing = null; editorOpen = true }
                        }
                        Text("最多 ${LocalStarListsStore.MAX_LISTS} 个列表，每个 ${LocalStarListsStore.MAX_REPOSITORIES} 个仓库，总计 ${LocalStarListsStore.MAX_MEMBERSHIPS} 条记录。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 11.sp, color = Gh.c.fgMuted)
                        if (lists!!.isEmpty()) EmptyState("暂无本机列表，创建一个来整理 Stars。", R.drawable.oc_checklist, Modifier.weight(1f).fillMaxWidth(), "新建本机列表") { editing = null; editorOpen = true }
                        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                            items(lists!!, key = { it.id }) { list ->
                                Card(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).clickable { selectedId = list.id; query = "" }) {
                                    Column(Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(list.name, color = Gh.c.fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Pill("${list.repositories.size} 个仓库")
                                        }
                                        if (list.description.isNotEmpty()) Text(list.description, color = Gh.c.fgMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        if (selected.description.isNotEmpty()) Text(selected.description, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Gh.c.fgMuted, fontSize = 13.sp)
                        GhField(query, { query = it }, "搜索此列表", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        val filtered = selected.repositories.filter { it.fullName.contains(query.trim(), true) }
                        if (filtered.isEmpty()) {
                            if (selected.repositories.isEmpty()) EmptyState("列表为空，添加已 Star 的仓库。", R.drawable.oc_star, Modifier.weight(1f).fillMaxWidth(), "添加仓库") { pickerOpen = true }
                            else EmptyState("没有匹配的仓库", R.drawable.oc_search, Modifier.weight(1f).fillMaxWidth(), "清除搜索") { query = "" }
                        }
                        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(filtered, key = { it.id }) { repo ->
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable { nav.push(Screen.Repo(repo.owner, repo.name)) }.padding(vertical = 12.dp)) {
                                        Text(repo.fullName, fontWeight = FontWeight.SemiBold, color = Gh.c.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (repo.isPrivate) Text("私有仓库", fontSize = 11.sp, color = Gh.c.fgMuted)
                                    }
                                    TextButton(onClick = { change { store.removeRepository(account, selected.id, repo.id) } }, enabled = !saving) { Text("移除") }
                                }
                            }
                        }
                        Text("移除仓库或删除本机列表不会取消 GitHub Star。", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = Gh.c.fgMuted)
                    }
                }
            }
        }
    }

    if (editorOpen) ListEditor(editing, saving, error, onDismiss = { if (!saving) { editorOpen = false; error = null } }) { name, description ->
        val id = editing?.id
        change(onDone = { editorOpen = false }) {
            if (id == null) store.create(account, name, description) else store.update(account, id, name, description)
        }
    }
    deleting?.let { list ->
        GhDialog("删除本机列表？", onDismiss = { if (!saving) { deleting = null; error = null } }, confirm = "删除", danger = true, confirmEnabled = !saving, onConfirm = {
            change(onDone = { deleting = null; selectedId = null }) { store.delete(account, list.id) }
        }) {
            Column {
                Text("删除「${list.name}」及其本机分类记录。仓库的 GitHub Star 会保留。")
                if (error != null) Text(error!!, color = Gh.c.danger)
            }
        }
    }
    if (pickerOpen && selected != null) StarredRepositoryPicker(account, selected, saving, error, onDismiss = { if (!saving) { pickerOpen = false; error = null } }) { repos ->
        change(onDone = { pickerOpen = false }) { store.addRepositories(account, selected.id, repos) }
    }
}

@Composable
private fun ListEditor(list: LocalStarList?, saving: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember(list?.id) { mutableStateOf(list?.name.orEmpty()) }
    var description by remember(list?.id) { mutableStateOf(list?.description.orEmpty()) }
    GhDialog(if (list == null) "新建本机列表" else "编辑本机列表", onDismiss = onDismiss, confirm = if (saving) "保存中…" else "保存", confirmEnabled = !saving && name.isNotBlank(), onConfirm = { onSave(name, description) }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GhField(name, { if (it.length <= LocalStarListsStore.MAX_NAME) name = it }, "名称")
            GhField(description, { if (it.length <= LocalStarListsStore.MAX_DESCRIPTION) description = it }, "描述（可选）", singleLine = false, minLines = 2)
            Text("仅保存在当前设备，不会同步到 GitHub。", color = Gh.c.fgMuted, fontSize = 12.sp)
            if (error != null) Text(error, color = Gh.c.danger, fontSize = 13.sp)
        }
    }
}

/** Uses the supported /user/starred endpoint; no browser session is needed, including PAT login. */
@Composable
private fun StarredRepositoryPicker(
    account: StarListAccount,
    list: LocalStarList,
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onAdd: (List<ListedRepository>) -> Unit,
) {
    StarredRepoSelectionDialog(account, list.repositories.map { it.id }.toSet(), LocalStarListsStore.MAX_REPOSITORIES - list.repositories.size, saving, saveError, onDismiss) { repos ->
        onAdd(repos.map(ListedRepository::from))
    }
}

@Composable
private fun StarredRepoSelectionDialog(
    account: StarListAccount,
    existing: Set<Long>,
    available: Int,
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onAdd: (List<Repo>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var repos by remember { mutableStateOf<List<Repo>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var end by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val maxPages = 100 // 3,000 candidates at the current API page size; never load all unboundedly.

    fun loadMore() {
        if (loading || end || saving) return
        loading = true
        error = null
        scope.launch {
            try {
                check(Session.generation == account.generation && account.login.equals(Session.login, true) && Session.token != null) { "账号已切换，请重新打开列表" }
                val next = page + 1
                val loaded = GitHub.starred(null, next, false)
                check(Session.generation == account.generation && account.login.equals(Session.login, true) && Session.token != null) { "账号已切换，请重新打开列表" }
                repos = (repos + loaded).distinctBy { it.id }
                page = next
                end = loaded.size < GitHub.PER || page >= maxPages
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { loading = false }
        }
    }

    LaunchedEffect(account) { loadMore() }
    val filtered = repos.filter { repo ->
        repo.fullName.contains(query.trim(), true) || repo.name.contains(query.trim(), true) || repo.owner.login.contains(query.trim(), true)
    }
    GhDialog("添加已 Star 的仓库", onDismiss = onDismiss, confirm = if (saving) "保存中…" else "添加 (${selected.size})", confirmEnabled = !saving && selected.isNotEmpty(), onConfirm = {
        onAdd(repos.filter { it.id in selected })
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GhField(query, { query = it }, "搜索已加载的 Stars")
            Text("已加载 ${repos.size} 个 · 可再添加 $available 个。搜索较早的 Stars 时请继续加载。", color = Gh.c.fgMuted, fontSize = 12.sp)
            if (saveError != null) Text(saveError, color = Gh.c.danger, fontSize = 12.sp)
            if (error != null) Text(error!!, color = Gh.c.danger, fontSize = 12.sp)
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 300.dp)) {
                items(filtered, key = { it.id }) { repo ->
                    val already = repo.id in existing
                    val checked = already || repo.id in selected
                    val enabled = !saving && !already && (checked || selected.size < available)
                    fun toggle() { selected = if (repo.id in selected) selected - repo.id else selected + repo.id }
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { toggle() }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = { toggle() }, enabled = enabled)
                        Column(Modifier.weight(1f)) {
                            Text(repo.fullName.ifBlank { "${repo.owner.login}/${repo.name}" }, color = Gh.c.fg, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (already) Text("已在列表中", color = Gh.c.fgMuted, fontSize = 11.sp)
                            else if (repo.isPrivate) Text("私有仓库", color = Gh.c.fgMuted, fontSize = 11.sp)
                        }
                    }
                }
                if (filtered.isEmpty() && !loading) item { Text(if (repos.isEmpty() && error == null) "暂无已 Star 的仓库" else "未找到匹配；可加载更多 Stars。", color = Gh.c.fgMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp)) }
            }
            if (loading) Loading(Modifier.fillMaxWidth().height(36.dp))
            else if (!end) GhButton(if (error == null) "加载更多 Stars" else "重试加载", enabled = !saving) { loadMore() }
            else if (page >= maxPages) Text("已达到 ${maxPages * GitHub.PER} 个候选仓库的读取上限。", color = Gh.c.fgMuted, fontSize = 12.sp)
        }
    }
}

/** Parent hook: show from a repository menu; manages memberships without adding a route. */
@Composable
private fun LocalOnlyStarListDialog(repo: Repo, onDismiss: () -> Unit) {
    val login = Session.login
    if (login == null || Session.token == null) return
    key(login.lowercase(Locale.ROOT), Session.generation, repo.id) {
        RepositoryListMembershipDialog(repo, StarListAccount(login, Session.generation), onDismiss)
    }
}

@Composable
private fun RepositoryListMembershipDialog(repo: Repo, account: StarListAccount, onDismiss: () -> Unit) {
    val ctx = rememberCtx()
    val nav = LocalNav.current
    val store = remember { StarLists.store(ctx) }
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<LocalStarList>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var starred by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val original = lists?.filter { list -> list.repositories.any { it.id == repo.id } }?.map { it.id }?.toSet().orEmpty()

    fun load() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val local = withContext(Dispatchers.IO) { store.read(account) }
                lists = local
                selected = local.filter { list -> list.repositories.any { it.id == repo.id } }.map { it.id }.toSet()
                val status = GitHub.isStarred(repo.owner.login, repo.name)
                check(Session.generation == account.generation && account.login.equals(Session.login, true) && Session.token != null) { "账号已切换，请重新打开列表" }
                starred = status
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { loading = false }
        }
    }

    LaunchedEffect(account) { load() }
    GhDialog("加入本机列表", onDismiss = { if (!saving) onDismiss() }, confirm = if (saving) "保存中…" else "保存", confirmEnabled = !saving && !loading && lists != null && selected != original && (starred == true || selected.all { it in original }), onConfirm = {
        saving = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.setRepositoryLists(account, ListedRepository.from(repo), selected, original) }
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { saving = false }
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(repo.fullName.ifBlank { "${repo.owner.login}/${repo.name}" }, fontWeight = FontWeight.SemiBold)
            Text("仅保存在当前设备，不会同步到 GitHub。", color = Gh.c.fgMuted, fontSize = 12.sp)
            if (starred == false) Text("请先 Star 此仓库再添加；仍可移除已有的本机分类。", color = Gh.c.fgMuted, fontSize = 12.sp)
            if (error != null) {
                Text(error!!, color = Gh.c.danger, fontSize = 12.sp)
                if (!saving) TextButton(onClick = { load() }) { Text("重新加载") }
            }
            if (loading) Loading(Modifier.fillMaxWidth().height(40.dp))
            if (lists?.isEmpty() == true) Text("暂无本机列表，先创建一个。", color = Gh.c.fgMuted, fontSize = 13.sp)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(lists.orEmpty(), key = { it.id }) { list ->
                    val checked = list.id in selected
                    val enabled = !saving && !loading && (starred == true || checked || list.id in original)
                    fun toggle() { selected = if (checked) selected - list.id else selected + list.id }
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = { toggle() }, enabled = enabled)
                        Text(list.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            TextButton(onClick = { onDismiss(); nav.push(Screen.StarLists(account.login)) }, enabled = !saving) { Text("管理 / 新建本机列表") }
        }
    }
}

/** Owns each detail request so leaving a list also invalidates its results and retry state. */
internal class UserListDetailLoader<T>(
    private val scope: CoroutineScope,
    private val limit: Int,
    private val id: (T) -> String,
) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var total by mutableIntStateOf(0)
        private set
    var loaded by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var limited by mutableStateOf(false)
        private set
    private var cursor: String? = null
    private var source: (suspend (String?) -> com.mobilegh.data.UserListPage<T>)? = null
    private var job: Job? = null
    private var operation = 0
    private var replaceOnRetry = true

    fun open(listId: String?, fetch: (suspend (String?) -> com.mobilegh.data.UserListPage<T>)? = null) {
        operation++
        job?.cancel()
        loading = false
        items = emptyList(); cursor = null; loaded = false; error = null; total = 0
        hasMore = listId != null; limited = false
        source = fetch
        if (listId != null) refresh()
    }

    fun loadMore() { if (!loading && hasMore && !limited) request(false) }
    fun refresh() { request(true) }
    fun retry() { if (!loading) request(replaceOnRetry) }

    private fun request(replace: Boolean) {
        val fetch = source ?: return
        val version = ++operation
        job?.cancel()
        val after = if (replace) null else cursor
        loading = true
        error = null
        replaceOnRetry = replace
        job = scope.launch {
            try {
                val page = fetch(after)
                currentCoroutineContext().ensureActive()
                if (version != operation) return@launch
                val next = if (replace) page.items else items + page.items
                check(next.map(id).distinct().size == next.size && (page.nextCursor == null || page.nextCursor != after)) { "GitHub 仓库分页重复，请刷新" }
                check(next.size <= page.totalCount && (page.nextCursor != null || next.size == page.totalCount)) { "GitHub 列表仓库读取不完整，请刷新" }
                items = next.take(limit)
                total = page.totalCount
                cursor = page.nextCursor
                hasMore = cursor != null
                limited = next.size > limit || (hasMore && items.size >= limit)
                loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (version == operation) error = failure.friendly()
            } finally {
                if (version == operation) loading = false
            }
        }
    }
}

@Composable
private fun GitHubListsContent(login: String, generation: Int, initialListId: String?, onLocal: (() -> Unit)?) {
    val ctx = rememberCtx()
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val account = remember { StarListAccount(Session.login.orEmpty(), generation) }
    val self = login.equals(account.login, true)
    val revision by UserLists.changes.collectAsState()
    var lists by remember { mutableStateOf<List<GitHubStarList>?>(null) }
    var selectedId by rememberSaveable(account, login, initialListId) { mutableStateOf(initialListId) }
    val detail = remember(account, login) { UserListDetailLoader<UserListRepository>(scope, UserListsClient.MAX_DETAIL_ITEMS) { it.nodeId } }
    val repositories = detail.items
    val detailTotal = detail.total
    val detailLoaded = detail.loaded
    var listLoading by remember { mutableStateOf(false) }
    val loading = listLoading || detail.loading
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GitHubStarList?>(null) }
    var deleting by remember { mutableStateOf<GitHubStarList?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable(account, login) { mutableStateOf("") }
    // 列表条目的方向，默认倒序：true = 倒序（从尾部 before 往回翻），false = 正序（从头部 after 往后翻）
    var itemDesc by rememberSaveable(account, login) { mutableStateOf(true) }
    val selected = lists?.firstOrNull { it.id == selectedId }

    suspend fun reload() {
        lists = UserLists.lists(account, login)
        if (selectedId != null && lists!!.none { it.id == selectedId }) selectedId = null
    }
    fun load() {
        if (listLoading || saving) return
        listLoading = true
        scope.launch {
            try { reload(); error = null } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { listLoading = false }
        }
    }
    fun mutate(onDone: () -> Unit = {}, action: suspend () -> Unit) {
        if (saving || loading) return
        saving = true
        error = null
        scope.launch {
            try {
                action()
                onDone()
                try { reload() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
                    error = "已保存到 GitHub，但刷新失败：${failure.friendly()}"
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { saving = false }
        }
    }
    LaunchedEffect(selected?.id, itemDesc) {
        val list = selected
        if (list == null) detail.open(null)
        // 正序从头部 after 往后翻，倒序从尾部 before 往回翻：方向变了就重新请求，不在本地反转
        else detail.open(list.id) { cursor -> UserLists.items(account, list, cursor, descending = itemDesc) }
    }
    LaunchedEffect(account, login, revision) {
        // Changes may originate from another screen, including a partially completed batch.
        while (saving || listLoading) kotlinx.coroutines.delay(30)
        load()
        if (selected != null) detail.refresh()
    }
    BackHandler(enabled = selectedId != null && !editorOpen && !pickerOpen && deleting == null) {
        selectedId = null; query = ""
    }
    Page(selected?.name ?: "Star 列表", subtitle = "$login · GitHub${if (!self) " · 只读" else " · 已同步"}", onBack = if (selected != null) ({ selectedId = null; query = "" }) else null, actions = {
        if (selected != null) {
            if (self) OcButton(R.drawable.oc_plus, { pickerOpen = true }, enabled = !saving && !loading)
            MoreMenu(buildList {
                add(MenuAction("刷新") { if (!saving && !loading) { load(); detail.refresh() } })
                if (self) {
                    add(MenuAction("编辑列表") { editing = selected; editorOpen = true })
                    add(MenuAction("删除列表", danger = true) { deleting = selected })
                }
                add(MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/${Api.encodePath(login)}?tab=stars") })
            })
        } else OcButton(R.drawable.oc_link_external, { ctx.openBrowser("https://github.com/${Api.encodePath(login)}?tab=stars") })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (selected == null && onLocal != null) Segmented(listOf("GitHub", "本机"), 0, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), enabled = !saving && !loading) { onLocal() }
            val visibleError = error ?: detail.error
            if (visibleError != null && lists != null) Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(visibleError, fontSize = 13.sp, color = Gh.c.danger)
                if (!saving && !loading) TextButton(onClick = {
                    if (error != null || selected == null) load() else detail.retry()
                }) { Text("重新加载") }
            }
            if (lists == null) {
                if (error != null && !loading) ErrorState(error!!, Modifier.weight(1f).fillMaxWidth()) { load() }
                else Loading(Modifier.weight(1f).fillMaxWidth())
            } else if (selected == null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (self) GhButton("新建 GitHub 列表", icon = R.drawable.oc_plus, enabled = !saving && !loading && lists!!.size < UserListsClient.MAX_LISTS) { editing = null; editorOpen = true }
                    GhButton("刷新", enabled = !saving && !loading) { load() }
                }
                Text(if (self) "列表与 GitHub 同步；公开列表可被其他人查看。" else "查看公开的 GitHub 列表。", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = Gh.c.fgMuted)
                // 列表目录：和首页卡片区共用同一份搜索/排序状态
                val browser = rememberSharedListBrowser("home-lists:$login")
                SearchBox(
                    browser.input, browser.query, { browser.input = it }, { browser.apply() }, { browser.clear() },
                    "搜索列表", "名称或描述",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                SortBar(
                    LIST_SORTS, browser.sort, browser.descending,
                    { browser.pick(it) }, { browser.descending = !browser.descending },
                    enabled = !saving && !loading,
                )
                val shownLists = browser.shown(lists!!)
                when {
                    lists!!.isEmpty() -> if (self) EmptyState("暂无 GitHub 列表，创建一个来整理 Stars。", R.drawable.oc_checklist, Modifier.weight(1f).fillMaxWidth(), "新建 GitHub 列表") { editing = null; editorOpen = true }
                    else EmptyState("$login 还没有公开的 GitHub 列表", R.drawable.oc_checklist, Modifier.weight(1f).fillMaxWidth(), "刷新") { load() }
                    shownLists.isEmpty() -> EmptyState("没有匹配的列表", R.drawable.oc_search, Modifier.weight(1f).fillMaxWidth(), "清除搜索") { browser.clear() }
                    else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(shownLists, key = { it.id }) { list ->
                            Card(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).clickable(enabled = !saving && !loading) { selectedId = list.id; query = "" }) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(list.name, modifier = Modifier.weight(1f), color = Gh.c.fg, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Pill(if (list.isPrivate) "私有" else "公开")
                                        Spacer(Modifier.width(6.dp)); Pill("${list.count} 个仓库")
                                    }
                                    if (list.description.isNotEmpty()) Text(list.description, modifier = Modifier.padding(top = 6.dp), color = Gh.c.fgMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            } else {
                // 列表详情：和官方 FetchList 一样按 items 的原始顺序翻页，只提供正序 / 倒序
                var itemInput by rememberSaveable(selected.id) { mutableStateOf("") }
                var itemQuery by rememberSaveable(selected.id) { mutableStateOf("") }
                Text(
                    "${if (selected.isPrivate) "私有" else "公开"} · 已加载 ${repositories.size} / $detailTotal",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.fgMuted, fontSize = 12.sp,
                )
                if (selected.description.isNotEmpty()) Text(selected.description, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.fgMuted, fontSize = 13.sp)
                SearchBox(
                    itemInput, itemQuery, { itemInput = it }, { itemQuery = itemInput.trim() },
                    { itemInput = ""; itemQuery = "" },
                    "搜索列表内仓库", "名称或描述",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("GitHub 顺序", Modifier.weight(1f), color = Gh.c.fgMuted, fontSize = 13.sp)
                    DirectionButton(itemDesc) { itemDesc = !itemDesc }
                }
                // 方向由服务端决定（正序 after 往后、倒序 before 往回），这里只做搜索过滤
                run {
                    val filtered = repositories.filter { starMatches(it.repo, itemQuery) }
                    if (filtered.isEmpty() && detailLoaded && !detail.hasMore && !loading) {
                        if (repositories.isNotEmpty()) EmptyState("没有匹配的已加载仓库", R.drawable.oc_search, Modifier.weight(1f).fillMaxWidth(), "清除搜索") { itemInput = ""; itemQuery = "" }
                        else if (self) EmptyState("列表暂无仓库，添加已 Star 的仓库。", R.drawable.oc_star, Modifier.weight(1f).fillMaxWidth(), "添加仓库") { pickerOpen = true }
                        else EmptyState("列表暂无仓库", R.drawable.oc_star, Modifier.weight(1f).fillMaxWidth())
                    } else if (filtered.isEmpty() && !detailLoaded) {
                        if (loading) Loading(Modifier.weight(1f).fillMaxWidth())
                        else Spacer(Modifier.weight(1f))
                    } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(filtered, key = { it.nodeId }) { item ->
                            RepoItem(item.repo)
                        }
                        // 滚到底自动加载下一页，不放「加载更多」按钮
                        item(key = "list-footer") {
                            when {
                                loading -> Loading(Modifier.fillMaxWidth().height(40.dp))
                                detail.limited -> Text("已达 ${UserListsClient.MAX_DETAIL_ITEMS} 个仓库的显示上限，请在网页中继续查看。", Modifier.padding(16.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
                                detail.hasMore -> LaunchedEffect(detail.loaded, detail.hasMore) {
                                    // 尾部一旦进入组合（即滑到底）就继续拉下一页
                                    if (detail.hasMore && !detail.loading && !saving) detail.loadMore()
                                }
                            }
                        }
                    }
                }
                if (self) Text("移除分类不会取消 Star。", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
            }
        }
    }
    if (editorOpen) GitHubListEditor(editing, saving, error, onDismiss = { if (!saving) { editorOpen = false; error = null } }) { name, description, privateList ->
        val target = editing
        mutate(onDone = { editorOpen = false }) {
            if (target == null) UserLists.create(account, name, description, privateList)
            else UserLists.update(account, target, name, description, privateList)
        }
    }
    deleting?.let { list ->
        GhDialog("删除 GitHub 列表？", onDismiss = { if (!saving) deleting = null }, confirm = "删除", danger = true, confirmEnabled = !saving, onConfirm = {
            mutate(onDone = { deleting = null; selectedId = null }) { UserLists.delete(account, list) }
        }) {
            Column {
                Text("从 GitHub 删除「${list.name}」。仓库的 Star 会保留。")
                if (error != null) Text(error!!, color = Gh.c.danger, fontSize = 12.sp)
            }
        }
    }
    if (pickerOpen && selected != null) StarredRepoSelectionDialog(
        account, repositories.map { it.repo.id }.filter { it > 0 }.toSet(), UserListsClient.MAX_BATCH_ADD, saving, error,
        onDismiss = { if (!saving) { pickerOpen = false; error = null } },
    ) { repos -> mutate(onDone = { pickerOpen = false }) { UserLists.addRepositories(account, selected.id, repos) } }
}

@Composable
fun GitHubListEditor(list: GitHubStarList?, saving: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String, String, Boolean) -> Unit) {
    var name by remember(list?.id) { mutableStateOf(list?.name.orEmpty()) }
    var description by remember(list?.id) { mutableStateOf(list?.description.orEmpty()) }
    var privateList by remember(list?.id) { mutableStateOf(list?.isPrivate ?: false) }
    GhDialog(if (list == null) "新建 GitHub 列表" else "编辑 GitHub 列表", onDismiss = onDismiss, confirm = if (saving) "保存中…" else "保存到 GitHub", confirmEnabled = !saving && name.isNotBlank(), onConfirm = { onSave(name, description, privateList) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GhField(name, { if (it.length <= UserListsClient.MAX_NAME) name = it }, "名称")
            GhField(description, { if (it.length <= UserListsClient.MAX_DESCRIPTION) description = it }, "描述（可选）", singleLine = false, minLines = 2)
            Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { privateList = !privateList }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(privateList, onCheckedChange = { privateList = it }, enabled = !saving)
                Text("私有列表（仅自己可见）", fontSize = 13.sp)
            }
            Text(if (privateList) "列表将保存在 GitHub，设为私有。" else "公开列表会显示在你的 GitHub Stars 页面。", color = Gh.c.fgMuted, fontSize = 12.sp)
            if (error != null) Text(error, color = Gh.c.danger, fontSize = 12.sp)
        }
    }
}

/** Primary repository menu hook: synced GitHub memberships, with local storage as an explicit option. */
@Composable
fun AddToStarListsDialog(repo: Repo, onDismiss: () -> Unit) {
    val login = Session.login ?: return
    if (Session.token == null) return
    key(login.lowercase(Locale.ROOT), Session.generation, repo.id) {
        var local by remember { mutableStateOf(false) }
        if (local) LocalOnlyStarListDialog(repo, onDismiss)
        else SyncedRepositoryListsDialog(repo, StarListAccount(login, Session.generation), onDismiss) { local = true }
    }
}

/** Compatibility for the parent's already wired menu; the default is now synced GitHub lists. */
@Composable
fun AddToLocalStarListDialog(repo: Repo, onDismiss: () -> Unit) = AddToStarListsDialog(repo, onDismiss)

@Composable
private fun SyncedRepositoryListsDialog(repo: Repo, account: StarListAccount, onDismiss: () -> Unit, onLocal: () -> Unit) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<RepositoryListMemberships?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    fun load() {
        if (loading || saving) return
        loading = true
        scope.launch {
            try {
                val data = UserLists.memberships(account, repo.owner.login, repo.name)
                snapshot = data; selected = data.memberships; error = null
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { loading = false }
        }
    }
    // 自己还没 Star 的自有仓库也能建列表：GitHub 的列表不要求 Star，界面据此提示
    var mine by remember { mutableStateOf(false) }
    LaunchedEffect(account, repo.owner.login, repo.name) {
        mine = repo.owner.login.equals(account.login, true) && try {
            GitHub.repo(repo.owner.login, repo.name, force = true).permissions?.admin == true
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
    }
    val canAdd = snapshot?.starred != false || mine
    LaunchedEffect(account) { load() }
    val original = snapshot?.memberships.orEmpty()
    if (creating) {
        GitHubListEditor(null, saving, error, onDismiss = { if (!saving) { creating = false; error = null } }) { name, description, privateList ->
            if (!saving) {
                saving = true
                error = null
                scope.launch {
                    try {
                        val created = UserLists.create(account, name, description, privateList)
                        snapshot = snapshot?.let { it.copy(lists = it.lists + created) }
                        if (canAdd) selected = selected + created.id
                        creating = false
                    } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
                    finally { saving = false }
                }
            }
        }
        return
    }
    GhDialog("GitHub 列表", onDismiss = { if (!saving) onDismiss() }, confirm = if (saving) "保存中…" else "保存到 GitHub", confirmEnabled = !saving && !loading && snapshot != null && selected != original && (canAdd || selected.all { it in original }), onConfirm = {
        saving = true
        error = null
        scope.launch {
            try {
                UserLists.setRepositoryLists(account, repo.owner.login, repo.name, selected, original, allowUnstarred = mine)
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { saving = false }
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(repo.fullName.ifBlank { "${repo.owner.login}/${repo.name}" }, fontWeight = FontWeight.SemiBold)
            Text("分类会同步到 GitHub；移除分类不会取消 Star。", color = Gh.c.fgMuted, fontSize = 12.sp)
            if (snapshot?.starred == false) Text(
                if (mine) "这是你拥有的仓库，可以直接加入列表。" else "请先 Star 仓库再添加；仍可移除已有分类。",
                color = Gh.c.fgMuted, fontSize = 12.sp,
            )
            if (error != null) {
                Text(error!!, color = Gh.c.danger, fontSize = 12.sp)
                TextButton(onClick = { load() }, enabled = !saving && !loading) { Text("重新加载") }
            }
            if (loading) Loading(Modifier.fillMaxWidth().height(40.dp))
            if (snapshot != null && snapshot!!.lists.isEmpty()) Text("还没有 GitHub 列表。", color = Gh.c.fgMuted, fontSize = 13.sp)
            if (snapshot != null) Text(
                if (snapshot!!.lists.isEmpty()) "先新建一个列表，再保存仓库分类。" else "勾选要加入的列表，取消勾选可移除分类。",
                color = Gh.c.fgMuted, fontSize = 12.sp,
            )
            GhButton("新建 GitHub 列表", enabled = !saving && !loading && snapshot != null && snapshot!!.lists.size < UserListsClient.MAX_LISTS, icon = R.drawable.oc_plus) {
                error = null
                creating = true
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(snapshot?.lists.orEmpty(), key = { it.id }) { list ->
                    val checked = list.id in selected
                    val enabled = !loading && !saving && (canAdd || checked || list.id in original)
                    fun toggle() { selected = if (checked) selected - list.id else selected + list.id }
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = { toggle() }, enabled = enabled)
                        Text(list.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Pill(if (list.isPrivate) "私有" else "公开")
                    }
                }
            }
            TextButton(onClick = { onDismiss(); nav.push(Screen.StarLists(account.login)) }, enabled = !saving) { Text("管理 / 新建 GitHub 列表") }
            TextButton(onClick = onLocal, enabled = !saving && !loading) { Text("改用本机列表（不与 GitHub 同步）") }
        }
    }
}
