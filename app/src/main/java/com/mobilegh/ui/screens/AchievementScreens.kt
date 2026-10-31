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
import androidx.compose.runtime.mutableLongStateOf
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
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.InlineError
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
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

@Composable
fun AchievementsStrip(login: String, self: Boolean) {
    val loader = rememberLoader("ach-public:${login.lowercase(Locale.ROOT)}") { Web.achievements(login, self) }
    var picked by remember { mutableStateOf<Achievement?>(null) }
    Column {
        SectionTitle(tr("成就"), trailing = { Text(tr("公开主页"), fontSize = 12.sp, color = Gh.c.fgMuted) })
        when {
            loader.data == null && loader.error != null -> Text(
                tr("成就暂时无法读取 · 点击重试"), color = Gh.c.fgMuted, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable { loader.load(true) }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            loader.data == null -> Loading(Modifier.fillMaxWidth().height(56.dp))
            loader.data!!.isEmpty() -> Text(
                tr("公开主页未显示成就，隐藏的成就无法读取。"), color = Gh.c.fgMuted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            else -> Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                loader.data!!.forEach { badge ->
                    AchievementBadge(badge, Modifier.clickable { picked = badge })
                }
                picked?.let { badge -> AchievementDetailDialog(badge, login) { picked = null } }
            }
        }
    }
}

@Composable
internal fun AchievementBadge(badge: Achievement, modifier: Modifier = Modifier) {
    val tier = when (badge.tier) { "bronze" -> tr("铜级"); "silver" -> tr("银级"); "gold" -> tr("金级"); else -> "" }
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
    var picked by remember { mutableStateOf<Achievement?>(null) }
    Page(if (self) tr("我的成就") else tr("成就"), subtitle = login, actions = {
        OcButton(R.drawable.oc_link_external, { ctx.openBrowser("https://github.com/${Api.encodePath(login)}?tab=achievements") })
    }) { pad ->
        LoadBox(badges, Modifier.padding(pad).fillMaxSize()) { data ->
            val listState = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp)) {
                item { Text(tr("公开主页的徽章、等级和倍数"), color = Gh.c.fgMuted, fontSize = 13.sp) }
                if (data.isEmpty()) emptyItem(listState, tr("公开主页尚未显示成就；隐藏的徽章无法读取"), R.drawable.oc_verified, tr("在 GitHub 查看")) {
                    ctx.openBrowser("https://github.com/${Api.encodePath(login)}?tab=achievements")
                }
                else item {
                    FlowRow(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(24.dp), maxItemsInEachRow = 3) {
                        data.forEach { badge -> AchievementBadge(badge, Modifier.clickable { picked = badge }) }
                        picked?.let { badge -> AchievementDetailDialog(badge, login) { picked = null } }
                    }
                }
            }
        }
    }
}

/** All local detail/edit/picker states live inside the existing Screen.StarLists(login) route. */
@Composable
fun StarListsScreen(login: String, initialListId: String? = null, initialLocal: Boolean = false) {
    key(login.lowercase(Locale.ROOT), Session.generation) {
        var local by rememberSaveable(login, Session.generation) { mutableStateOf(initialLocal) }
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
    Page(selected?.name ?: tr("Star 列表"), subtitle = trf("{0} · 本机列表", login), onBack = if (selected != null) ({ selectedId = null; query = "" }) else null, actions = {
        if (selected != null) {
            OcButton(R.drawable.oc_plus, { pickerOpen = true }, enabled = !saving && selected.repositories.size < LocalStarListsStore.MAX_REPOSITORIES)
            MoreMenu(listOf(MenuAction(tr("编辑列表")) { editing = selected; editorOpen = true }, MenuAction(tr("删除列表"), danger = true) { deleting = selected }))
        } else OcButton(R.drawable.oc_link_external, { ctx.openBrowser("https://github.com/${Api.encodePath(login)}?tab=stars") })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (selectedId == null && self) Segmented(listOf("GitHub", tr("本机")), 1, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), enabled = !saving) { onGitHub() }
            Column(Modifier.weight(1f)) {
                if (selected != null) Text(tr("按账号保存在当前设备，不会同步到 GitHub。"), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = Gh.c.fgMuted)
                if (error != null && lists != null) Text(error!!, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Gh.c.danger, fontSize = 13.sp)
                when {
                    lists == null && error != null -> ErrorState(error!!, Modifier.weight(1f)) { load() }
                    lists == null -> Loading(Modifier.weight(1f).fillMaxWidth())
                    selected == null -> {
                        val all = lists!!
                        val canCreate = !saving && all.size < LocalStarListsStore.MAX_LISTS
                        if (all.isEmpty()) ListsEmptyHero(
                            R.drawable.oc_device_mobile, tr("还没有本机列表"), tr("暂无本机列表，创建一个来整理 Stars。"),
                            Modifier.weight(1f).fillMaxWidth(), tr("新建本机列表"), R.drawable.oc_plus,
                        ) { editing = null; editorOpen = true }
                        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                            item(key = "summary") {
                                ListsSummaryCard(
                                    R.drawable.oc_device_mobile, trf("{0} 个本机列表", all.size), trf("共收纳 {0} 条仓库记录", all.sumOf { it.repositories.size }),
                                    tr("按账号保存在当前设备，不会同步到 GitHub。") + "\n" +
                                        trf("最多 {0} 个列表，每个 {1} 个仓库，总计 {2} 条记录。", LocalStarListsStore.MAX_LISTS, LocalStarListsStore.MAX_REPOSITORIES, LocalStarListsStore.MAX_MEMBERSHIPS),
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    action = tr("新建本机列表"), actionIcon = R.drawable.oc_plus, actionEnabled = canCreate,
                                ) { editing = null; editorOpen = true }
                            }
                            items(all, key = { it.id }) { list ->
                                StarListCard(list.name, list.description, list.repositories.size, isPrivate = false) { selectedId = list.id; query = "" }
                            }
                        }
                    }
                    else -> {
                        if (selected.description.isNotEmpty()) Text(selected.description, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Gh.c.fgMuted, fontSize = 13.sp)
                        GhField(query, { query = it }, tr("搜索此列表"), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        val filtered = selected.repositories.filter { it.fullName.contains(query.trim(), true) }
                        if (filtered.isEmpty()) {
                            if (selected.repositories.isEmpty()) EmptyState(tr("列表为空，添加已 Star 的仓库。"), R.drawable.oc_star, Modifier.weight(1f).fillMaxWidth(), tr("添加仓库")) { pickerOpen = true }
                            else EmptyState(tr("没有匹配的仓库"), R.drawable.oc_search, Modifier.weight(1f).fillMaxWidth(), tr("清除搜索")) { query = "" }
                        }
                        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(filtered, key = { it.id }) { repo ->
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable { nav.push(Screen.Repo(repo.owner, repo.name)) }.padding(vertical = 12.dp)) {
                                        Text(repo.fullName, fontWeight = FontWeight.SemiBold, color = Gh.c.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (repo.isPrivate) Text(tr("私有仓库"), fontSize = 11.sp, color = Gh.c.fgMuted)
                                    }
                                    TextButton(onClick = { change { store.removeRepository(account, selected.id, repo.id) } }, enabled = !saving) { Text(tr("移除")) }
                                }
                            }
                        }
                        Text(tr("移除仓库或删除本机列表不会取消 GitHub Star。"), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = Gh.c.fgMuted)
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
        GhDialog(tr("删除本机列表？"), onDismiss = { if (!saving) { deleting = null; error = null } }, confirm = tr("删除"), danger = true, confirmEnabled = !saving, onConfirm = {
            change(onDone = { deleting = null; selectedId = null }) { store.delete(account, list.id) }
        }) {
            Column {
                Text(trf("删除「{0}」及其本机分类记录。仓库的 GitHub Star 会保留。", list.name))
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
    GhDialog(if (list == null) tr("新建本机列表") else tr("编辑本机列表"), onDismiss = onDismiss, confirm = if (saving) tr("保存中…") else tr("保存"), confirmEnabled = !saving && name.isNotBlank(), onConfirm = { onSave(name, description) }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GhField(name, { if (it.length <= LocalStarListsStore.MAX_NAME) name = it }, tr("名称"))
            GhField(description, { if (it.length <= LocalStarListsStore.MAX_DESCRIPTION) description = it }, tr("描述（可选）"), singleLine = false, minLines = 2)
            Text(tr("仅保存在当前设备，不会同步到 GitHub。"), color = Gh.c.fgMuted, fontSize = 12.sp)
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
                check(Session.generation == account.generation && account.login.equals(Session.login, true) && Session.token != null) { tr("账号已切换，请重新打开列表") }
                val next = page + 1
                val loaded = GitHub.starred(null, next, false)
                check(Session.generation == account.generation && account.login.equals(Session.login, true) && Session.token != null) { tr("账号已切换，请重新打开列表") }
                repos = (repos + loaded).distinctBy { it.id }
                page = next
                end = loaded.size < GitHub.STARRED_PER || page >= maxPages
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.friendly() }
            finally { loading = false }
        }
    }

    LaunchedEffect(account) { loadMore() }
    val filtered = repos.filter { repo ->
        repo.fullName.contains(query.trim(), true) || repo.name.contains(query.trim(), true) || repo.owner.login.contains(query.trim(), true)
    }
    GhDialog(tr("添加已 Star 的仓库"), onDismiss = onDismiss, confirm = if (saving) tr("保存中…") else trf("添加 ({0})", selected.size), confirmEnabled = !saving && selected.isNotEmpty(), onConfirm = {
        onAdd(repos.filter { it.id in selected })
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GhField(query, { query = it }, tr("搜索已加载的 Stars"))
            Text(trf("已加载 {0} 个 · 可再添加 {1} 个。搜索较早的 Stars 时请继续加载。", repos.size, available), color = Gh.c.fgMuted, fontSize = 12.sp)
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
                            if (already) Text(tr("已在列表中"), color = Gh.c.fgMuted, fontSize = 11.sp)
                            else if (repo.isPrivate) Text(tr("私有仓库"), color = Gh.c.fgMuted, fontSize = 11.sp)
                        }
                    }
                }
                if (filtered.isEmpty() && !loading) item { Text(if (repos.isEmpty() && error == null) tr("暂无已 Star 的仓库") else tr("未找到匹配；可加载更多 Stars。"), color = Gh.c.fgMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp)) }
            }
            if (loading) Loading(Modifier.fillMaxWidth().height(36.dp))
            else if (!end) GhButton(if (error == null) tr("加载更多 Stars") else tr("重试加载"), enabled = !saving) { loadMore() }
            else if (page >= maxPages) Text(trf("已达到 {0} 个候选仓库的读取上限。", maxPages * GitHub.STARRED_PER), color = Gh.c.fgMuted, fontSize = 12.sp)
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
                check(next.map(id).distinct().size == next.size && (page.nextCursor == null || page.nextCursor != after)) { tr("GitHub 仓库分页重复，请刷新") }
                check(next.size <= page.totalCount && (page.nextCursor != null || next.size == page.totalCount)) { tr("GitHub 列表仓库读取不完整，请刷新") }
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
    var handledRevision by remember(account, login) { mutableLongStateOf(-1) }
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
                // 本次 mutation 已在这里刷新，revision 观察者只处理其他页面的修改。
                handledRevision = UserLists.changes.value
                try { reload() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
                    error = trf("已保存到 GitHub，但刷新失败：{0}", failure.friendly())
                }
                if (selected != null) detail.refresh()
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
        if (handledRevision == revision) return@LaunchedEffect
        handledRevision = revision
        load()
        if (selected != null) detail.refresh()
    }
    BackHandler(enabled = selectedId != null && !editorOpen && !pickerOpen && deleting == null) {
        selectedId = null; query = ""
    }
    Page(selected?.name ?: tr("Star 列表"), subtitle = "$login · GitHub${if (!self) tr(" · 只读") else tr(" · 已同步")}", onBack = if (selected != null) ({ selectedId = null; query = "" }) else null, actions = {
        if (selected != null) {
            if (self) OcButton(R.drawable.oc_plus, { pickerOpen = true }, enabled = !saving && !loading)
            MoreMenu(buildList {
                add(MenuAction(tr("刷新")) { if (!saving && !loading) { load(); detail.refresh() } })
                if (self) {
                    add(MenuAction(tr("编辑列表")) { editing = selected; editorOpen = true })
                    add(MenuAction(tr("删除列表"), danger = true) { deleting = selected })
                }
            })
        } else MoreMenu(listOf(
            MenuAction(tr("刷新")) { if (!saving && !loading) load() },
        ))
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (selected == null && onLocal != null) Segmented(listOf("GitHub", tr("本机")), 0, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), enabled = !saving && !loading) { onLocal() }
            val visibleError = error ?: detail.error
            if (visibleError != null && lists != null) Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(visibleError, fontSize = 13.sp, color = Gh.c.danger)
                if (!saving && !loading) TextButton(onClick = {
                    if (error != null || selected == null) load() else detail.retry()
                }) { Text(tr("重新加载")) }
            }
            if (lists == null) {
                if (error != null && !loading) ErrorState(error!!, Modifier.weight(1f).fillMaxWidth()) { load() }
                else Loading(Modifier.weight(1f).fillMaxWidth())
            } else if (selected == null) {
                // 列表目录：和首页卡片区共用同一份搜索/排序状态
                val browser = rememberSharedListBrowser("home-lists:$login")
                val all = lists!!
                val shownLists = browser.shown(all)
                val canCreate = self && !saving && !loading && all.size < UserListsClient.MAX_LISTS
                when {
                    all.isEmpty() -> if (self) ListsEmptyHero(
                        R.drawable.oc_checklist, tr("还没有 GitHub 列表"), tr("用列表给 Stars 分门别类，列表会同步到 GitHub。"),
                        Modifier.weight(1f).fillMaxWidth(), tr("新建 GitHub 列表"), R.drawable.oc_plus,
                    ) { editing = null; editorOpen = true }
                    else ListsEmptyHero(
                        R.drawable.oc_checklist, trf("{0} 还没有公开的 GitHub 列表", login), "",
                        Modifier.weight(1f).fillMaxWidth(), tr("刷新"),
                    ) { load() }
                    else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                        item(key = "summary") {
                            val privateCount = all.count { it.isPrivate }
                            ListsSummaryCard(
                                R.drawable.oc_checklist, trf("{0} 个列表", all.size), trf("{0} 个公开 · {1} 个私有", all.size - privateCount, privateCount),
                                if (self) tr("列表与 GitHub 同步；公开列表可被其他人查看。") else tr("查看公开的 GitHub 列表。"),
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                action = if (self) tr("新建 GitHub 列表") else null, actionIcon = R.drawable.oc_plus, actionEnabled = canCreate,
                            ) { editing = null; editorOpen = true }
                        }
                        item(key = "controls") {
                            Column(Modifier.padding(bottom = 8.dp)) {
                                SearchBox(
                                    browser.input, browser.query, { browser.input = it }, { browser.apply() }, { browser.clear() },
                                    tr("搜索列表"), tr("名称或描述"),
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                )
                                SortBar(
                                    LIST_SORTS, browser.sort, browser.descending,
                                    { browser.pick(it) }, { browser.descending = !browser.descending },
                                    enabled = !saving && !loading,
                                )
                            }
                        }
                        if (shownLists.isEmpty()) item(key = "no-match") {
                            ListsEmptyHero(R.drawable.oc_search, tr("没有匹配的列表"), "", Modifier.fillMaxWidth(), tr("清除搜索")) { browser.clear() }
                        } else items(shownLists, key = { it.id }) { list ->
                            StarListRow(list, enabled = !saving && !loading) { selectedId = list.id; query = "" }
                        }
                    }
                }
            } else {
                // 列表详情：和官方 FetchList 一样按 items 的原始顺序翻页，只提供正序 / 倒序
                var itemInput by rememberSaveable(selected.id) { mutableStateOf("") }
                var itemQuery by rememberSaveable(selected.id) { mutableStateOf("") }
                Text(
                    trf("{0} · 已加载 {1} / {2}", if (selected.isPrivate) tr("私有") else tr("公开"), repositories.size, detailTotal),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.fgMuted, fontSize = 12.sp,
                )
                if (selected.description.isNotEmpty()) Text(selected.description, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.fgMuted, fontSize = 13.sp)
                SearchBox(
                    itemInput, itemQuery, { itemInput = it }, { itemQuery = itemInput.trim() },
                    { itemInput = ""; itemQuery = "" },
                    tr("搜索列表内仓库"), tr("名称或描述"),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("GitHub 顺序"), Modifier.weight(1f), color = Gh.c.fgMuted, fontSize = 13.sp)
                    DirectionButton(itemDesc) { itemDesc = !itemDesc }
                }
                // 方向由服务端决定（正序 after 往后、倒序 before 往回），这里只做搜索过滤
                run {
                    val filtered = repositories.filter { starMatches(it.repo, itemQuery) }
                    if (filtered.isEmpty() && detailLoaded && !detail.hasMore && !loading) {
                        if (repositories.isNotEmpty()) EmptyState(tr("没有匹配的已加载仓库"), R.drawable.oc_search, Modifier.weight(1f).fillMaxWidth(), tr("清除搜索")) { itemInput = ""; itemQuery = "" }
                        else if (self) EmptyState(tr("列表暂无仓库，添加已 Star 的仓库。"), R.drawable.oc_star, Modifier.weight(1f).fillMaxWidth(), tr("添加仓库")) { pickerOpen = true }
                        else EmptyState(tr("列表暂无仓库"), R.drawable.oc_star, Modifier.weight(1f).fillMaxWidth())
                    } else if (filtered.isEmpty() && !detailLoaded) {
                        if (loading) Loading(Modifier.weight(1f).fillMaxWidth())
                        else Spacer(Modifier.weight(1f))
                    } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(filtered, key = { it.nodeId }) { item ->
                            RemovableRepoItem(
                                repo = item.repo,
                                enabled = !saving && !loading,
                                onRemove = if (self) ({
                                    mutate { UserLists.setRepositoryLists(account, item.repo.owner.login, item.repo.name, emptySet(), setOf(selected.id)) }
                                }) else null,
                            )
                        }
                        // 滚到底自动加载下一页，不放「加载更多」按钮
                        item(key = "list-footer") {
                            when {
                                loading -> Loading(Modifier.fillMaxWidth().height(40.dp))
                                detail.limited -> Text(trf("已达 {0} 个仓库的显示上限，请在网页中继续查看。", UserListsClient.MAX_DETAIL_ITEMS), Modifier.padding(16.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
                                detail.hasMore -> LaunchedEffect(detail.loaded, detail.hasMore) {
                                    // 尾部一旦进入组合（即滑到底）就继续拉下一页
                                    if (detail.hasMore && !detail.loading && !saving) detail.loadMore()
                                }
                            }
                        }
                    }
                }
                if (self) Text(tr("移除分类不会取消 Star。"), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
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
        GhDialog(tr("删除 GitHub 列表？"), onDismiss = { if (!saving) deleting = null }, confirm = tr("删除"), danger = true, confirmEnabled = !saving, onConfirm = {
            mutate(onDone = { deleting = null; selectedId = null }) { UserLists.delete(account, list) }
        }) {
            Column {
                Text(trf("从 GitHub 删除「{0}」。仓库的 Star 会保留。", list.name))
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
    GhDialog(if (list == null) tr("新建 GitHub 列表") else tr("编辑 GitHub 列表"), onDismiss = onDismiss, confirm = if (saving) tr("保存中…") else tr("保存到 GitHub"), confirmEnabled = !saving && name.isNotBlank(), onConfirm = { onSave(name, description, privateList) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GhField(name, { if (it.length <= UserListsClient.MAX_NAME) name = it }, tr("名称"))
            GhField(description, { if (it.length <= UserListsClient.MAX_DESCRIPTION) description = it }, tr("描述（可选）"), singleLine = false, minLines = 2)
            Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { privateList = !privateList }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(privateList, onCheckedChange = { privateList = it }, enabled = !saving)
                Text(tr("私有列表（仅自己可见）"), fontSize = 13.sp)
            }
            Text(if (privateList) tr("列表将保存在 GitHub，设为私有。") else tr("公开列表会显示在你的 GitHub Stars 页面。"), color = Gh.c.fgMuted, fontSize = 12.sp)
            if (error != null) Text(error, color = Gh.c.danger, fontSize = 12.sp)
        }
    }
}

/** 徽章详情：本地显示徽章图、等级、倍数、说明和解锁历史；网页按钮保留给需要完整页面的时候。 */
@Composable
internal fun AchievementDetailDialog(badge: Achievement, login: String, onDismiss: () -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    val tier = when (badge.tier) { "bronze" -> tr("铜级"); "silver" -> tr("银级"); "gold" -> tr("金级"); else -> "" }
    val detail = rememberLoader("ach-detail:${login.lowercase(Locale.ROOT)}:${badge.slug}") { Web.achievementDetail(login, badge.slug) }
    GhDialog(badge.name, onDismiss = onDismiss, confirm = tr("关闭"), onConfirm = onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NetImage(badge.imageUrl, Modifier.size(96.dp), contentScale = ContentScale.Fit, placeholder = false)
            if (tier.isNotEmpty()) Text(tier, fontSize = 14.sp, color = g.fg, fontWeight = FontWeight.SemiBold)
            if (badge.count > 1) Text(trf("倍数 x{0}", badge.count), fontSize = 13.sp, color = g.fgMuted)
            val data = detail.data
            when {
                data != null -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (data.description.isNotBlank()) Text(data.description, fontSize = 14.sp, color = g.fg)
                    if (data.history.isNotEmpty()) {
                        HDivider()
                        Text(tr("解锁历史"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = g.fgMuted)
                        data.history.forEach { Text(it, fontSize = 13.sp, color = g.fg) }
                    }
                }
                detail.error != null -> InlineError(trf("说明加载失败：{0}", detail.error!!), Modifier.fillMaxWidth()) { detail.refresh() }
                else -> Loading(Modifier.fillMaxWidth().height(48.dp))
            }
            GhButton(tr("在浏览器打开"), icon = R.drawable.oc_link_external) { ctx.openBrowser("https://github.com/${Api.encodePath(login)}?achievement=${Api.q(badge.slug)}&tab=achievements") }
        }
    }
}
