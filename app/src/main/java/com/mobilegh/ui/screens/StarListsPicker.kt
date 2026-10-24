package com.mobilegh.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.window.Dialog
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.GitHubStarList
import com.mobilegh.data.Repo
import com.mobilegh.data.RepositoryListMemberships
import com.mobilegh.data.Session
import com.mobilegh.data.StarListAccount
import com.mobilegh.data.UserLists
import com.mobilegh.data.UserListsClient
import com.mobilegh.data.starListsForRepository
import com.mobilegh.nav.Loader
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

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

/** 仓库菜单 / Star 长按的唯一入口：与首页同源的 Star 列表多选。 */
@Composable
fun AddToStarListsDialog(repo: Repo, onDismiss: () -> Unit) {
    val login = Session.login ?: return
    if (Session.token == null) return
    key(login.lowercase(Locale.ROOT), Session.generation, repo.id) {
        StarListsPickerDialog(repo, StarListAccount(login, Session.generation), onDismiss)
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
 * 3. 连点几下只保留最后一次结果：写入是串行的，写的时候界面先按最新选择显示，失败了再回滚。
 */
@Composable
private fun StarListsPickerDialog(repo: Repo, account: StarListAccount, onDismiss: () -> Unit) {
    val g = Gh.c
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val catalog = rememberStarListCatalog(account.login)
    var snapshot by remember { mutableStateOf<RepositoryListMemberships?>(null) }
    // 界面上的选择；null 表示还没读到归属
    var picked by remember { mutableStateOf<Set<String>?>(null) }
    // GitHub 最后一次确认的选择，失败时回滚到它
    var applied by remember { mutableStateOf<Set<String>>(emptySet()) }
    var writing by remember { mutableStateOf(false) }
    var membershipError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    // 弹窗内新建的列表先留在这里，等列表目录刷新后再自然合并
    var created by remember { mutableStateOf<List<GitHubStarList>>(emptyList()) }
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

    /** 把界面上的选择写进 GitHub：串行、只保留最新一次；失败回滚到上一次确认过的结果。 */
    fun writePending() {
        val repositoryId = snapshot?.repositoryId ?: return
        if (writing) return
        writing = true
        scope.launch {
            try {
                while (true) {
                    val wanted = picked ?: break
                    if (wanted == applied) break
                    UserLists.setRepositoryMembership(account, repositoryId, wanted)
                    applied = wanted
                    if (picked == wanted) {
                        error = null
                        break
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.friendly()
                picked = applied
            } finally {
                writing = false
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
    // 勾选写的是仓库归属，列表目录没变；只有还没读到归属时才重新拉目录，免得每勾一下多一趟请求
    val catalogRevision by UserLists.changes.collectAsState()
    val observedRevision = remember { mutableLongStateOf(catalogRevision) }
    LaunchedEffect(catalogRevision) {
        if (observedRevision.longValue != catalogRevision) {
            observedRevision.longValue = catalogRevision
            if (snapshot == null) catalog.refresh()
        }
    }
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

    // 列表目录优先用与首页同源的读取结果；归属还在读取时先用归属快照里的列表兜底
    val base = catalog.data ?: snapshot?.lists
    val lists = remember(base, created) { (base.orEmpty() + created).distinctBy { it.id } }
    // 用打开弹窗时的归属排序，勾选过程中行不会跳动
    val ordered = remember(lists, snapshot) { starListsForRepository(lists, snapshot?.memberships.orEmpty()) }

    if (creating) {
        GitHubListEditor(null, saving, error, onDismiss = { if (!saving) { creating = false; error = null } }) { name, description, privateList ->
            if (!saving) {
                saving = true
                error = null
                scope.launch {
                    try {
                        val list = UserLists.create(account, name, description, privateList)
                        if (Session.generation != account.generation || !account.login.equals(Session.login, true)) return@launch
                        created = created + list
                        // 新列表直接勾上并写进去，和手动勾选走同一条通道
                        if (snapshot != null && (snapshot?.starred != false || mine)) {
                            picked = (picked ?: applied) + list.id
                            writePending()
                        }
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

    // 和官方客户端的选择面板一样：自己画卡片，底部不给「取消/保存」，勾选即生效
    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = g.canvas,
            border = BorderStroke(1.dp, g.border),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Star 列表", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = g.fg)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            repo.fullName.ifBlank { "${repo.owner.login}/${repo.name}" },
                            Modifier.weight(1f), fontSize = 13.sp, color = g.fg,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text("${ordered.size} 个列表", fontSize = 12.sp, color = g.fgMuted)
                    }
                }
                if (snapshot?.starred == false) Text(
                    if (mine) "这是你拥有的仓库，可以直接加入列表。" else "请先 Star 仓库再添加；仍可移除已有分类。",
                    fontSize = 12.sp, color = g.fgMuted,
                )
                error?.let { Text(it, color = g.danger, fontSize = 12.sp) }

                when {
                    base == null && catalog.error != null -> InlineError("列表读取失败：${catalog.error}", Modifier.fillMaxWidth()) { catalog.refresh() }
                    base == null -> Loading(Modifier.fillMaxWidth().height(72.dp))
                    else -> {
                        // 归属是后台读的，不占版面；只有真失败了才提示并给重试
                        val failure = membershipError
                        if (snapshot == null && failure != null) InlineError("列表归属读取失败：$failure", Modifier.fillMaxWidth()) { loadMembership() }
                        if (ordered.isEmpty()) Text("还没有 GitHub 列表，新建一个后即可加入。", fontSize = 13.sp, color = g.fgMuted)
                        else LazyColumn(Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 260.dp)) {
                            items(ordered, key = { it.id }) { list ->
                                // 直接在行内读状态：勾选变化一定让这一行自己重组，不依赖外层是否传下新值
                                val joined = snapshot?.memberships.orEmpty()
                                val chosen = picked ?: applied
                                val checked = list.id in chosen
                                val addable = snapshot?.starred != false || mine
                                val enabled = snapshot != null && !saving && (addable || checked || list.id in joined)
                                Row(
                                    Modifier.fillMaxWidth()
                                        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { toggle(list.id) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 复选框自身不处理点击，整行只有这一个点击入口
                                    Checkbox(checked, null, enabled = enabled)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(list.name, color = if (enabled) g.fg else g.fgMuted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (list.isPrivate) "${list.count} 个仓库 · 私有" else "${list.count} 个仓库",
                                            color = g.fgMuted, fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 新建与管理同一套外观，两个按钮等宽并排；列表满额（MAX_LISTS）就不给新建入口。
                // 这里的可用状态只跟「有没有满额」有关，勾选/写入过程不会让它变样。
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lists.size < UserListsClient.MAX_LISTS) GhButton(
                        "新建列表", Modifier.weight(1f), icon = R.drawable.oc_plus, enabled = !saving,
                    ) {
                        error = null
                        creating = true
                    }
                    GhButton("管理", Modifier.weight(1f), icon = R.drawable.oc_checklist, enabled = !saving) {
                        onDismiss()
                        nav.push(Screen.StarLists(account.login))
                    }
                }
            }
        }
    }
}
