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
import androidx.compose.foundation.lazy.LazyColumn
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
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.Loading
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
private fun AchievementBadge(badge: Achievement, modifier: Modifier = Modifier) {
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
        Text(badge.name, fontSize = 10.sp, lineHeight = 12.sp, color = Gh.c.fgMuted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (tier.isNotEmpty()) Text(tier, fontSize = 10.sp, color = Gh.c.fgMuted)
    }
}

/** All local detail/edit/picker states live inside the existing Screen.StarLists(login) route. */
@Composable
fun StarListsScreen(login: String) {
    key(login.lowercase(Locale.ROOT), Session.generation) {
        var local by rememberSaveable(login, Session.generation) { mutableStateOf(false) }
        val self = Session.token != null && login.equals(Session.login, true)
        BackHandler(enabled = local) { local = false }
        if (local && self) StarListsContent(login, Session.generation) { local = false }
        else GitHubListsContent(login, Session.generation, onLocal = if (self) ({ local = true }) else null)
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
    Page(selected?.name ?: "Star 列表", subtitle = "$login · 本机列表", actions = {
        TextButton(onClick = onGitHub, enabled = !saving) { Text("GitHub") }
        TextButton(onClick = { ctx.openBrowser("https://github.com/${Api.encodePath(login)}?tab=stars") }) { Text("网页") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (selectedId == null && self) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onGitHub, enabled = !saving) { Text("GitHub", color = Gh.c.fgMuted) }
                TextButton(onClick = {}, enabled = false) { Text("本机", color = Gh.c.accent) }
            }
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
                        if (lists!!.isEmpty()) EmptyState("暂无本机列表，创建一个来整理 Stars。", modifier = Modifier.weight(1f))
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
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                            TextButton(onClick = { selectedId = null; query = "" }) { Text("所有列表") }
                            TextButton(onClick = { pickerOpen = true }, enabled = !saving && selected.repositories.size < LocalStarListsStore.MAX_REPOSITORIES) { Text("添加仓库") }
                            TextButton(onClick = { editing = selected; editorOpen = true }, enabled = !saving) { Text("编辑") }
                            TextButton(onClick = { deleting = selected }, enabled = !saving) { Text("删除", color = Gh.c.danger) }
                        }
                        if (selected.description.isNotEmpty()) Text(selected.description, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Gh.c.fgMuted, fontSize = 13.sp)
                        GhField(query, { query = it }, "搜索此列表", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        val filtered = selected.repositories.filter { it.fullName.contains(query.trim(), true) }
                        if (filtered.isEmpty()) EmptyState(if (selected.repositories.isEmpty()) "列表为空，添加已 Star 的仓库。" else "没有匹配的仓库", modifier = Modifier.weight(1f))
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

@Composable
private fun GitHubListsContent(login: String, generation: Int, onLocal: (() -> Unit)?) {
    val ctx = rememberCtx()
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val account = remember { StarListAccount(Session.login.orEmpty(), generation) }
    val self = login.equals(account.login, true)
    val revision by UserLists.changes.collectAsState()
    var lists by remember { mutableStateOf<List<GitHubStarList>?>(null) }
    var selectedId by rememberSaveable(account, login) { mutableStateOf<String?>(null) }
    var repositories by remember { mutableStateOf<List<UserListRepository>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var detailTotal by remember { mutableIntStateOf(0) }
    var detailLoaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GitHubStarList?>(null) }
    var deleting by remember { mutableStateOf<GitHubStarList?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable(account, login) { mutableStateOf("") }
    val selected = lists?.firstOrNull { it.id == selectedId }

    suspend fun reload() {
        lists = UserLists.lists(account, login)
        if (selectedId != null && lists!!.none { it.id == selectedId }) selectedId = null
    }
    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            try { reload(); error = null } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { loading = false }
        }
    }
    fun loadItems(reset: Boolean = false, clearError: Boolean = true) {
        val list = selected ?: return
        if (loading || (!reset && detailLoaded && cursor == null) || repositories.size >= UserListsClient.MAX_DETAIL_ITEMS) return
        loading = true
        scope.launch {
            try {
                val page = UserLists.items(account, list, if (reset) null else cursor)
                val next = if (reset) page.items else repositories + page.items
                check(next.map { it.nodeId }.distinct().size == next.size && (reset || page.nextCursor == null || page.nextCursor != cursor)) { "GitHub 仓库分页重复，请刷新" }
                repositories = next
                detailTotal = page.totalCount
                cursor = page.nextCursor
                detailLoaded = true
                if (clearError) error = null
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { loading = false }
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
                reload()
                // Revision reloads the current detail after a successful remote mutation.
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { saving = false }
        }
    }
    LaunchedEffect(account, login) { load() }
    LaunchedEffect(selected?.id, revision) {
        if (selected != null) {
            repositories = emptyList(); cursor = null; detailLoaded = false
            while (loading || saving) kotlinx.coroutines.delay(30)
            loadItems(reset = true, clearError = error == null)
        } else if (selectedId == null && lists != null && !saving && !loading) load()
    }
    BackHandler(enabled = selectedId != null && !editorOpen && !pickerOpen && deleting == null) {
        selectedId = null; query = ""
    }
    Page(selected?.name ?: "Star 列表", subtitle = "$login · GitHub${if (!self) " · 只读" else " · 已同步"}", actions = {
        if (onLocal != null) TextButton(onClick = onLocal, enabled = !saving && !loading) { Text("本机") }
        TextButton(onClick = { ctx.openBrowser("https://github.com/${Api.encodePath(login)}?tab=stars") }) { Text("网页") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (selected == null && onLocal != null) Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = {}, enabled = false) { Text("GitHub", color = Gh.c.accent) }
                TextButton(onClick = onLocal, enabled = !saving && !loading) { Text("本机", color = Gh.c.fgMuted) }
            }
            if (error != null) Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(error!!, fontSize = 13.sp, color = Gh.c.danger)
                if (!saving && !loading) TextButton(onClick = { if (selected == null) load() else loadItems(reset = !detailLoaded) }) { Text("重新加载") }
            }
            if (lists == null) {
                if (loading) Loading(Modifier.weight(1f).fillMaxWidth())
            } else if (selected == null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (self) GhButton("新建 GitHub 列表", icon = R.drawable.oc_plus, enabled = !saving && !loading && lists!!.size < UserListsClient.MAX_LISTS) { editing = null; editorOpen = true }
                    GhButton("刷新", enabled = !saving && !loading) { load() }
                }
                Text(if (self) "列表与 GitHub 同步；公开列表可被其他人查看。" else "查看公开的 GitHub 列表。", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = Gh.c.fgMuted)
                if (lists!!.isEmpty()) EmptyState("暂无 GitHub 列表", modifier = Modifier.weight(1f))
                else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(lists!!, key = { it.id }) { list ->
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
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    TextButton(onClick = { selectedId = null; query = "" }, enabled = !saving) { Text("所有列表") }
                    if (self) {
                        TextButton(onClick = { pickerOpen = true }, enabled = !saving && !loading) { Text("添加仓库") }
                        TextButton(onClick = { editing = selected; editorOpen = true }, enabled = !saving && !loading) { Text("编辑") }
                        TextButton(onClick = { deleting = selected }, enabled = !saving && !loading) { Text("删除", color = Gh.c.danger) }
                    }
                    TextButton(onClick = { repositories = emptyList(); detailLoaded = false; loadItems(reset = true) }, enabled = !saving && !loading) { Text("刷新") }
                }
                Text("${if (selected.isPrivate) "私有" else "公开"} · 已加载 ${repositories.size} / $detailTotal", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
                if (selected.description.isNotEmpty()) Text(selected.description, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.fgMuted, fontSize = 13.sp)
                GhField(query, { query = it }, "搜索已加载的列表仓库", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                val filtered = repositories.filter { it.repo.fullName.contains(query.trim(), true) }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(filtered, key = { it.nodeId }) { item ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { RepoItem(item.repo) }
                            if (self) TextButton(onClick = {
                                mutate { UserLists.setRepositoryLists(account, item.repo.owner.login, item.repo.name, emptySet(), setOf(selected.id)) }
                            }, enabled = !saving && !loading) { Text("移除") }
                        }
                    }
                    if (filtered.isEmpty() && detailLoaded) item { Text(if (repositories.isEmpty()) "列表暂无仓库" else "没有匹配的已加载仓库", Modifier.padding(16.dp), color = Gh.c.fgMuted) }
                    item {
                        if (loading) Loading(Modifier.fillMaxWidth().height(40.dp))
                        else if (cursor != null && repositories.size < UserListsClient.MAX_DETAIL_ITEMS) GhButton("加载更多", modifier = Modifier.padding(16.dp), enabled = !saving) { loadItems() }
                        else if (cursor != null) Text("已达 ${UserListsClient.MAX_DETAIL_ITEMS} 个仓库的显示上限，请在网页中继续查看。", Modifier.padding(16.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
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
private fun GitHubListEditor(list: GitHubStarList?, saving: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String, String, Boolean) -> Unit) {
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
    LaunchedEffect(account) { load() }
    val original = snapshot?.memberships.orEmpty()
    GhDialog("GitHub 列表", onDismiss = { if (!saving) onDismiss() }, confirm = if (saving) "保存中…" else "保存到 GitHub", confirmEnabled = !saving && !loading && snapshot != null && selected != original, onConfirm = {
        saving = true
        error = null
        scope.launch {
            try {
                UserLists.setRepositoryLists(account, repo.owner.login, repo.name, selected, original)
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { saving = false }
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(repo.fullName.ifBlank { "${repo.owner.login}/${repo.name}" }, fontWeight = FontWeight.SemiBold)
            Text("分类会同步到 GitHub；移除分类不会取消 Star。", color = Gh.c.fgMuted, fontSize = 12.sp)
            if (snapshot?.starred == false) Text("请先 Star 仓库再添加；仍可移除已有分类。", color = Gh.c.fgMuted, fontSize = 12.sp)
            if (error != null) {
                Text(error!!, color = Gh.c.danger, fontSize = 12.sp)
                TextButton(onClick = { load() }, enabled = !saving && !loading) { Text("重新加载") }
            }
            if (loading) Loading(Modifier.fillMaxWidth().height(40.dp))
            if (snapshot?.lists?.isEmpty() == true) Text("暂无 GitHub 列表，先创建一个。", color = Gh.c.fgMuted, fontSize = 13.sp)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(snapshot?.lists.orEmpty(), key = { it.id }) { list ->
                    val checked = list.id in selected
                    val enabled = !loading && !saving && (snapshot?.starred == true || checked || list.id in original)
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
