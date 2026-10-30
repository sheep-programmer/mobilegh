package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.GitHubStarList
import com.mobilegh.data.ListedRepository
import com.mobilegh.data.LocalStarList
import com.mobilegh.data.Repo
import com.mobilegh.data.RepositoryListMemberships
import com.mobilegh.data.Session
import com.mobilegh.data.StarListAccount
import com.mobilegh.data.StarLists
import com.mobilegh.data.UserLists
import com.mobilegh.data.UserListsClient
import com.mobilegh.data.starListsForRepository
import com.mobilegh.nav.Loader
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.FilterSheet
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/**
 * 首页「收藏列表」和仓库长按弹窗共用同一份 Star 列表读取入口：
 * 同一个 GraphQL 查询、同一个账号校验，列表顺序也保持一致。
 */
@Composable
internal fun rememberStarListCatalog(login: String): Loader<List<GitHubStarList>> {
    val active = Session.login ?: login
    val account = remember(active, login, Session.generation) { StarListAccount(active, Session.generation) }
    return rememberLoader("star-lists:${active.lowercase(Locale.ROOT)}:${login.lowercase(Locale.ROOT)}:${account.generation}") {
        UserLists.lists(account, login)
    }
}

internal fun starListPickedAfterWrite(wanted: Set<String>, picked: Set<String>, confirmed: Set<String>): Set<String> =
    if (picked == wanted) confirmed else picked

internal fun canCreateListWithRepository(snapshot: RepositoryListMemberships?, mine: Boolean): Boolean =
    snapshot != null && (snapshot.starred || mine)

/** 仓库菜单 / Star 长按的唯一入口：与首页同源的 Star 列表多选。 */
@Composable
fun AddToStarListsDialog(repo: Repo, onDismiss: () -> Unit) {
    val login = Session.login ?: return
    if (Session.token == null) return
    key(login.lowercase(Locale.ROOT), Session.generation, repo.id) {
        var local by remember { mutableStateOf(false) }
        if (local) LocalOnlyStarListDialog(repo, StarListAccount(login, Session.generation), onDismiss)
        else StarListsPickerDialog(repo, StarListAccount(login, Session.generation), onDismiss) { local = true }
    }
}

/**
 * 多选弹窗，与官方客户端的列表选择面板一致：勾上就写，没有保存/取消按钮。
 *
 * 三个容易踩的点：
 * 1. 勾选状态一律在「行」里直接读，不在外面读成普通变量再传进去——否则行的重组取决于
 *    外层是否恰好把新值传下来，可能出现点了没反应、或第二次勾选把第一次的勾选覆盖掉。
 * 2. 归属（仓库已在哪些列表）由 `Repository.lists` 一个请求给出；它没回来之前不允许勾选，
 *    因为没有仓库节点 id 就写不了。
 * 3. 连点几下只保留最后一次结果：写入是串行的，写的时候界面先按最新选择显示；失败保留选择并允许重试，
 *    因为响应丢失不代表服务端没有提交，不能直接回滚到可能已过期的旧归属。
 */
@Composable
private fun StarListsPickerDialog(repo: Repo, account: StarListAccount, onDismiss: () -> Unit, onLocal: () -> Unit) {
    val g = Gh.c
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val catalog = rememberStarListCatalog(account.login)
    LaunchedEffect(catalog) {
        if (catalog.data != null || catalog.error != null) catalog.refresh()
    }
    var snapshot by remember { mutableStateOf<RepositoryListMemberships?>(null) }
    // 界面上的选择；null 表示还没读到归属
    var picked by remember { mutableStateOf<Set<String>?>(null) }
    // 最后一次服务端确认的完整归属；requested 单独记录用户上一次提交的选择。
    var applied by remember { mutableStateOf<Set<String>>(emptySet()) }
    var writing by remember { mutableStateOf(false) }
    // 写入中点了遮罩收起：等写完再关，不打断正在进行的写入
    var closeAfterWrite by remember { mutableStateOf(false) }
    var membershipError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf(false) }

    fun loadMembership() {
        if (writing || saving || snapshot != null) return
        membershipError = null
        scope.launch {
            try {
                val data = UserLists.memberships(account, repo.owner.login, repo.name)
                if (Session.generation != account.generation || !account.login.equals(Session.login, true)) return@launch
                snapshot = data
                applied = data.memberships
                picked = data.memberships
                error = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                membershipError = failure.friendly()
            }
        }
    }

    /** 串行写入用户增量；服务端同时出现的其它归属不纳入下一次删除范围。 */
    fun writePending() {
        if (snapshot == null || writing) return
        writing = true
        scope.launch {
            // requested 只含用户提交的选择；applied 保存服务端实际确认的完整归属。
            var requested = applied
            try {
                while (true) {
                    val wanted = picked ?: break
                    if (wanted == requested) break
                    val confirmed = UserLists.setRepositoryLists(
                        account, repo.owner.login, repo.name,
                        selected = wanted, original = requested, allowUnstarred = mine,
                    )
                    requested = wanted
                    applied = confirmed
                    picked = starListPickedAfterWrite(wanted, picked ?: wanted, confirmed)
                    if (picked == confirmed) {
                        error = null
                        break
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.friendly()
            } finally {
                writing = false
                if (closeAfterWrite) onDismiss()
            }
        }
    }

    /** 勾选 => 立刻写入；正在写的时候只更新界面，写完会用最新的选择再写一次。 */
    fun toggle(id: String) {
        if (snapshot == null) return
        val base = picked ?: applied
        picked = if (id in base) base - id else base + id
        error = null
        writePending()
    }

    LaunchedEffect(account, repo.id) { loadMembership() }
    // 自己拥有但尚未 Star 的仓库沿用旧行为：确认所有者后仍可加入列表；长按入口本身只对已 Star 开放。
    LaunchedEffect(snapshot?.starred, account, repo.owner.login, repo.name) {
        mine = when {
            snapshot?.starred != false -> false
            !repo.owner.login.equals(account.login, true) -> false
            repo.permissions?.admin == true -> true
            else -> try {
                GitHub.repo(repo.owner.login, repo.name, force = true).permissions?.admin == true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                false
            }
        }
    }

    // Repository.lists 只含已加入的列表，不能在目录读取失败时冒充完整目录。
    val base = catalog.data
    val lists = base.orEmpty()
    // 用打开弹窗时的归属排序，勾选过程中行不会跳动
    val ordered = remember(lists, snapshot) { starListsForRepository(lists, snapshot?.memberships.orEmpty()) }

    if (creating) {
        GitHubListEditor(null, saving, error, onDismiss = { if (!saving) { creating = false; error = null } }) { name, description, privateList ->
            if (!saving) {
                saving = true
                error = null
                scope.launch {
                    if (!canCreateListWithRepository(snapshot, mine)) {
                        error = tr("仓库归属尚未确认，请返回后重试")
                        saving = false
                        return@launch
                    }
                    try {
                        val list = UserLists.create(account, name, description, privateList)
                        if (Session.generation != account.generation || !account.login.equals(Session.login, true)) return@launch
                        val current = catalog.data
                        if (current != null) catalog.accept((current + list).distinctBy { it.id })
                        else catalog.refresh()
                        // 创建入口已确认归属和可添加状态；新列表与手动勾选走同一条写入通道。
                        picked = (picked ?: applied) + list.id
                        writePending()
                        creating = false
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        error = failure.friendly()
                    } finally {
                        saving = false
                    }
                }
            }
        }
        return
    }

    // 底部弹层：点遮罩或下滑即可收起；勾选即生效，没有保存/取消按钮。写入中收起会等这次写入结束再关，避免写一半被取消
    FilterSheet(
        title = tr("Star 列表"),
        subtitle = repo.fullName.ifBlank { "${repo.owner.login}/${repo.name}" },
        closeDescription = tr("关闭"),
        onDismiss = { if (saving || writing) closeAfterWrite = true else onDismiss() },
        footer = {
            if (lists.size < UserListsClient.MAX_LISTS) GhButton(
                tr("新建列表"), Modifier.weight(1f), icon = R.drawable.oc_plus,
                enabled = !saving && !writing && canCreateListWithRepository(snapshot, mine),
            ) {
                error = null
                creating = true
            }
            GhButton(tr("管理"), Modifier.weight(1f), icon = R.drawable.oc_checklist, enabled = !saving && !writing) {
                onDismiss()
                nav.push(Screen.StarLists(account.login))
            }
        },
    ) {
        if (snapshot?.starred == false) Text(
            if (mine) tr("这是你拥有的仓库，可以直接加入列表。") else tr("请先 Star 仓库再添加；仍可移除已有分类。"),
            fontSize = 12.sp, color = g.fgMuted,
        )
        error?.let {
            InlineError(it, Modifier.fillMaxWidth()) {
                if (!saving && !writing) {
                    error = null
                    writePending()
                }
            }
        }
        if (base != null && catalog.error != null) {
            InlineError(trf("列表刷新失败：{0}", catalog.error), Modifier.fillMaxWidth()) { catalog.refresh() }
        }

        when {
            base == null && catalog.error != null -> InlineError(trf("列表读取失败：{0}", catalog.error), Modifier.fillMaxWidth()) { catalog.refresh() }
            base == null -> Loading(Modifier.fillMaxWidth().height(72.dp))
            else -> {
                // 归属是后台读的，不占版面；只有真失败了才提示并给重试
                val failure = membershipError
                if (snapshot == null && failure != null) InlineError(trf("列表归属读取失败：{0}", failure), Modifier.fillMaxWidth()) { loadMembership() }
                if (ordered.isEmpty()) Text(tr("还没有 GitHub 列表，新建一个后即可加入。"), fontSize = 13.sp, color = g.fgMuted)
                else Column(Modifier.fillMaxWidth()) {
                    ordered.forEach { list ->
                        // 直接在行内读状态：勾选变化一定让这一行自己重组，不依赖外层是否传下新值
                        val chosen = picked ?: applied
                        val checked = list.id in chosen
                        val addable = snapshot?.starred != false || mine
                        // 未 Star 时只允许移除已有分类；移除后不能绕过数据层校验再次添加。
                        val enabled = snapshot != null && !saving && (addable || checked)
                        Row(
                            Modifier.fillMaxWidth()
                                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { toggle(list.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 复选框自身不处理点击，整行只有这一个点击入口
                            Checkbox(checked, null, enabled = enabled)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(list.name, color = if (enabled) g.fg else g.fgMuted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (list.isPrivate) trf("{0} 个仓库 · 私有", list.count) else trf("{0} 个仓库", list.count),
                                    color = g.fgMuted, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = onLocal, enabled = !saving && !writing, modifier = Modifier.fillMaxWidth()) {
            Text(tr("改用本机列表（不同步 GitHub）"))
        }
    }
}

/** 本机分类是明确独立的存储，不把它包装成会同步到 GitHub 的列表。 */
@Composable
private fun LocalOnlyStarListDialog(repo: Repo, account: StarListAccount, onDismiss: () -> Unit) {
    val ctx = rememberCtx()
    val nav = LocalNav.current
    val store = remember { StarLists.store(ctx) }
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<LocalStarList>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var starred by remember { mutableStateOf<Boolean?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val original = lists?.filter { list -> list.repositories.any { it.id == repo.id } }?.map { it.id }?.toSet().orEmpty()

    fun load() {
        if (loading || saving) return
        loading = true
        error = null
        scope.launch {
            try {
                val local = withContext(Dispatchers.IO) { store.read(account) }
                lists = local
                selected = local.filter { list -> list.repositories.any { it.id == repo.id } }.map { it.id }.toSet()
                starred = GitHub.isStarred(repo.owner.login, repo.name)
                check(Session.generation == account.generation && account.login.equals(Session.login, true) && Session.token != null) {
                    tr("账号已切换，请重新打开列表")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.friendly()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(account, repo.id) { load() }
    GhDialog(
        tr("加入本机列表"),
        onDismiss = { if (!saving) onDismiss() },
        confirm = if (saving) tr("保存中…") else tr("保存"),
        confirmEnabled = !saving && !loading && lists != null && selected != original &&
            (starred == true || selected.all { it in original }),
        onConfirm = {
            saving = true
            error = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        store.setRepositoryLists(account, ListedRepository.from(repo), selected, original)
                    }
                    onDismiss()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.friendly()
                } finally {
                    saving = false
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(repo.fullName.ifBlank { "${repo.owner.login}/${repo.name}" }, fontWeight = FontWeight.SemiBold)
            Text(tr("仅保存在当前设备，不会同步到 GitHub。"), color = Gh.c.fgMuted, fontSize = 12.sp)
            if (starred == false) Text(tr("请先 Star 此仓库再添加；仍可移除已有的本机分类。"), color = Gh.c.fgMuted, fontSize = 12.sp)
            error?.let { InlineError(it, Modifier.fillMaxWidth()) { load() } }
            if (loading) Loading(Modifier.fillMaxWidth().height(48.dp))
            if (lists?.isEmpty() == true) Text(tr("暂无本机列表，先在管理页创建一个。"), color = Gh.c.fgMuted, fontSize = 13.sp)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(lists.orEmpty(), key = { it.id }) { list ->
                    val checked = list.id in selected
                    val enabled = !saving && !loading && (starred == true || checked)
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) {
                                selected = if (checked) selected - list.id else selected + list.id
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked, null, enabled = enabled)
                        Spacer(Modifier.width(10.dp))
                        Text(list.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            TextButton(
                onClick = { onDismiss(); nav.push(Screen.StarLists(account.login, local = true)) },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("管理 / 新建本机列表")) }
        }
    }
}
