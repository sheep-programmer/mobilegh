package com.mobilegh.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.Notification
import com.mobilegh.data.Session
import com.mobilegh.nav.Links
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.nav.act
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.retain
import com.mobilegh.ui.Badges
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.Counter
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.MdText
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.boxedItems
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

// ---------------- 文案映射 ----------------

/** reason 筛选下拉里的选项：null 表示不筛选 */
private val REASON_FILTERS = listOf(
    null to "全部原因",
    "assign" to "被指派",
    "mention" to "被提及",
    "review_requested" to "评审请求",
    "author" to "作者",
    "comment" to "评论",
    "team_mention" to "团队提及",
    "state_change" to "状态变化",
    "ci_activity" to "CI 活动",
    "security_alert" to "安全告警",
    "subscribed" to "订阅",
)

/** 行内 Pill 的文案：比筛选项多一些 GitHub 会返回的原因 */
private val REASON_EXTRA = mapOf(
    "manual" to "手动订阅",
    "invitation" to "邀请",
    "approval_requested" to "等待批准",
)

/** 需要优先处理的提及类原因：Pill 用强调色 */
private val REASON_ATTENTION = setOf("assign", "mention", "team_mention", "review_requested")

private fun reasonZh(r: String) = REASON_FILTERS.firstOrNull { it.first == r }?.second ?: REASON_EXTRA[r] ?: r

private fun subjectTypeZh(type: String) = when (type) {
    "Issue" -> "Issue"
    "PullRequest" -> "Pull Request"
    "Release" -> "Release"
    "Commit" -> "Commit"
    "Discussion" -> "讨论"
    "CheckSuite", "WorkflowRun" -> "Actions"
    "RepositoryInvitation" -> "仓库邀请"
    "RepositoryVulnerabilityAlert", "RepositoryDependabotAlertsThread" -> "安全告警"
    else -> type
}

private fun subjectIcon(type: String) = when (type) {
    "Issue" -> R.drawable.oc_issue_opened
    "PullRequest" -> R.drawable.oc_git_pull_request
    "Release" -> R.drawable.oc_tag
    "Commit" -> R.drawable.oc_git_commit
    "Discussion" -> R.drawable.oc_comment_discussion
    "CheckSuite", "WorkflowRun" -> R.drawable.oc_play
    "RepositoryInvitation" -> R.drawable.oc_mail
    "RepositoryVulnerabilityAlert", "RepositoryDependabotAlertsThread" -> R.drawable.oc_shield_lock
    else -> R.drawable.oc_bell
}

/** 从 subject.url 取 #编号（仅 issue / PR 有） */
private fun subjectNumber(n: Notification): Int? =
    Regex("/+\\d+$").find(n.subject.url.orEmpty())?.value?.trimStart('/')?.toIntOrNull()

// ---------------- 本机状态 ----------------

/**
 * 通知列表的本地状态。
 * GitHub 的 REST 只提供「标为已读」（PATCH）与「完成」（DELETE）；
 * 标为未读、取消完成、已完成列表都没有服务端接口，只能保存在本机。
 * 实例放在页面 Entry 的 bag 里，切走再回来不会丢。
 */
private class NotifState(saved: com.mobilegh.data.LocalNotifications) {
    val known = mutableStateListOf<Notification>().apply { addAll(saved.known) }
    /** 服务端仍为 unread，但本机已点开或标为已读 */
    val read = mutableStateListOf<String>().apply { addAll(saved.read) }

    /** 服务端已是已读，本机又改回未读 */
    val markedUnread = mutableStateListOf<String>().apply { addAll(saved.unread) }

    /** 已完成：不再出现在收件箱里 */
    val done = mutableStateListOf<String>().apply { addAll(saved.done.map { it.id }) }

    /** 已完成条目本身，供「已完成」视图使用 */
    val doneItems = mutableStateListOf<Notification>().apply { addAll(saved.done) }

    /** 本机刚取消订阅 / 忽略的线程 */
    val unsubscribed = mutableStateListOf<String>()

    /** 正在等撤销窗口结束的「完成」操作：id -> 撤销所需信息 */
    val pending = mutableStateMapOf<String, Pending>()

    var busyLabel by mutableStateOf<String?>(null)
    var busyDone by mutableIntStateOf(0)
    var busyTotal by mutableIntStateOf(0)

    class Pending(val wasRead: Boolean, val wasUnread: Boolean) {
        var job: Job? = null
    }

    fun unread(n: Notification): Boolean = when {
        n.id in done -> false
        n.id in markedUnread -> true
        else -> n.unread && n.id !in read
    }

    fun markRead(id: String) {
        markedUnread.remove(id)
        if (id !in read) read.add(id)
    }

    fun markUnread(id: String) {
        if (id !in markedUnread) markedUnread.add(id)
        read.remove(id)
    }

    /** 撤销一次「完成」：恢复条目并还原它原本的已读标记 */
    fun restore(id: String) {
        val p = pending.remove(id)
        done.remove(id)
        doneItems.removeAll { it.id == id }
        read.remove(id)
        markedUnread.remove(id)
        if (p != null) {
            if (p.wasRead) read.add(id)
            if (p.wasUnread) markedUnread.add(id)
        }
    }

    /** 失败回滚：条目仍在列表里，只需还原已读标记 */
    fun rollbackRead(id: String, wasRead: Boolean, wasUnread: Boolean) {
        read.remove(id)
        markedUnread.remove(id)
        if (wasRead) read.add(id)
        if (wasUnread) markedUnread.add(id)
    }

    fun complete(n: Notification) {
        done.add(n.id)
        doneItems.removeAll { it.id == n.id }
        doneItems.add(0, n)
    }
}

/** 本地未读数变化时同步底部 Tab 角标；未读筛选下由精确统计覆盖 */
private fun bumpBadge(delta: Int) {
    if (delta != 0) Badges.unread = (Badges.unread + delta).coerceAtLeast(0)
}

// ---------------- 批量操作 ----------------

private enum class BulkKind(val label: String) { Done("完成"), Read("标为已读"), Ignore("忽略") }

private class BulkResult(val ok: Int, val failed: Int, val failedIds: List<String>, val firstError: String?)

/** 每批同时最多调用多少个接口 */
private const val BULK_CONCURRENCY = 4

/** 有界并发地逐条调用接口，返回成功/失败数并报告进度 */
private suspend fun runBulk(ids: List<String>, onProgress: (Int) -> Unit, run: suspend (String) -> Unit): BulkResult {
    val sem = Semaphore(BULK_CONCURRENCY)
    var finished = 0
    var firstError: String? = null
    val failures = java.util.Collections.synchronizedList(ArrayList<String>())
    coroutineScope {
        ids.map { id ->
            async {
                try {
                    sem.withPermit { run(id) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failures.add(id)
                    if (firstError == null) firstError = e.friendly()
                } finally {
                    finished++
                    onProgress(finished)
                }
            }
        }.awaitAll()
    }
    return BulkResult(ids.size - failures.size, failures.size, failures.toList(), firstError)
}

// ---------------- 小部件 ----------------

/** 批量执行期间的进度条 */
@Composable
private fun BulkBanner(label: String, finished: Int, total: Int) {
    val g = Gh.c
    Column(Modifier.fillMaxWidth().background(g.canvasSubtle).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("正在$label：$finished/$total", fontSize = 12.sp, color = g.fg)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else finished.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = g.accent,
            trackColor = g.neutralMuted,
        )
    }
}

/** 仓库分组头：owner 头像 + 完整仓库名 + 条数 */
@Composable
private fun RepoHeader(list: List<Notification>, onClick: () -> Unit) {
    val repo = list.first().repository
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(repo.owner.avatarUrl, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            repo.fullName,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Gh.c.fg,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Counter(list.size)
    }
}

/** 多选模式的勾选标记（自绘，避免 Material 勾选框的固定尺寸打乱行高） */
@Composable
private fun CheckMark(checked: Boolean) {
    if (checked) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Gh.c.accent), contentAlignment = Alignment.Center) {
            Oc(R.drawable.oc_check, Gh.c.canvas, 12.dp)
        }
    } else {
        Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, Gh.c.border, CircleShape))
    }
}

/** 滑动露出的动作底色：图标 + 文案 */
@Composable
private fun SwipeAction(icon: Int, label: String, bg: Color, fg: Color, atEnd: Boolean) {
    Row(
        Modifier.fillMaxSize().background(bg).padding(horizontal = 16.dp),
        horizontalArrangement = if (atEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(icon, fg, 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

// ---------------- 列表行 ----------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NotificationRow(
    n: Notification,
    grouped: Boolean,
    state: NotifState,
    selection: Boolean,
    selected: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onDone: () -> Unit,
    onToggleRead: () -> Unit,
    onUnsubscribe: () -> Unit,
    onIgnore: () -> Unit,
) {
    val g = Gh.c
    val scope = rememberCoroutineScope()
    val unread = state.unread(n)
    val dismiss = rememberSwipeToDismissBoxState()
    val enabled = !selection && !busy

    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        gesturesEnabled = enabled,
        backgroundContent = {
            // 用 dismissDirection 而不是 targetValue：后者在越过阈值前一直是 Settled
            if (dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                SwipeAction(R.drawable.oc_check, "完成", g.doneSubtle, g.done, atEnd = true)
            } else {
                SwipeAction(
                    if (unread) R.drawable.oc_dot_fill else R.drawable.oc_eye,
                    if (unread) "标为已读" else "标为未读",
                    g.accentSubtle, g.accent, atEnd = false,
                )
            }
        },
        onDismiss = { value ->
            // 完成：条目随即被移出列表，滑动状态随条目一起消失
            // 已读 / 未读：条目保留，需要回弹复位
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDone()
            } else {
                onToggleRead()
                scope.launch { dismiss.reset() }
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(g.canvas)
                .combinedClickable(enabled = enabled, onClick = onOpen, onLongClick = onToggleSelect)
                .padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                if (selection) CheckMark(selected)
                else if (unread) Box(Modifier.size(8.dp).background(g.accent, CircleShape))
            }
            Oc(subjectIcon(n.subject.type), if (unread) g.fg else g.fgMuted, 16.dp, Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (!grouped) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(n.repository.owner.avatarUrl, 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            n.repository.fullName,
                            fontSize = 12.sp, color = g.fgMuted,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(relTime(n.updatedAt), fontSize = 12.sp, color = g.fgMuted)
                    }
                }
                MdText(
                    n.subject.title,
                    fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (unread) g.fg else g.fgMuted,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val num = subjectNumber(n)
                    Text(
                        if (grouped) subjectTypeZh(n.subject.type) + (num?.let { " #$it" } ?: "") else subjectTypeZh(n.subject.type),
                        fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Pill(reasonZh(n.reason), if (n.reason in REASON_ATTENTION) g.accent else g.fgMuted)
                    if (grouped) {
                        Spacer(Modifier.width(6.dp))
                        Text(relTime(n.updatedAt), fontSize = 12.sp, color = g.fgMuted)
                    }
                    if (n.id in state.unsubscribed) {
                        Spacer(Modifier.width(6.dp))
                        Oc(R.drawable.oc_bell_slash, g.fgMuted, 12.dp)
                    }
                }
            }
            if (!selection) {
                MoreMenu(
                    listOf(
                        MenuAction(if (unread) "标为已读" else "标为未读", onClick = onToggleRead),
                        MenuAction("完成", onClick = onDone),
                        MenuAction("取消订阅此线程", onClick = onUnsubscribe),
                        MenuAction("忽略此线程", danger = true, onClick = onIgnore),
                    ),
                    tint = g.fgMuted,
                )
            }
        }
    }
}

/** 已完成的条目：点开查看，撤销窗口内还能回到收件箱 */
@Composable
private fun DoneRow(n: Notification, pending: Boolean, onOpen: () -> Unit, onUndo: (() -> Unit)?) {
    val g = Gh.c
    Row(
        Modifier
            .fillMaxWidth()
            .background(g.canvas)
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).background(g.done, CircleShape))
        }
        Oc(subjectIcon(n.subject.type), g.fgMuted, 16.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(n.repository.fullName, fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MdText(n.subject.title, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, color = g.fgMuted)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Oc(R.drawable.oc_check, g.done, 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(if (pending) "已完成 · 待同步" else "已完成 · ${relTime(n.updatedAt)}", fontSize = 12.sp, color = g.done)
            }
        }
        if (onUndo != null) TextLink("撤销", onClick = onUndo)
    }
}

/** 「已完成」视图：只来自本机状态 */
@Composable
private fun DoneView(items: List<Notification>, state: NotifState, onOpen: (Notification) -> Unit, onUndo: (String) -> Unit) {
    val g = Gh.c
    val listState = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            Text(
                "已完成列表只保留在本机本次会话（GitHub 接口不提供已完成通知的查询）",
                fontSize = 11.sp, color = g.fgMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (items.isEmpty()) {
            item {
                EmptyState("还没有已完成的通知", R.drawable.oc_check_circle, Modifier.fillMaxWidth().height(240.dp))
            }
        } else {
            boxedItems(items, key = { it.id }) { n ->
                DoneRow(
                    n = n,
                    pending = n.id in state.pending,
                    onOpen = { onOpen(n) },
                    onUndo = if (n.id in state.pending) ({ onUndo(n.id) }) else null,
                )
            }
        }
    }
}

// ---------------- 页面 ----------------

/** 顶部筛选：未读 / 全部 / 参与的 / 已完成 */
private const val TAB_DONE = 3

/** 「完成」的撤销窗口 */
private const val UNDO_MS = 5000L

@Composable
fun NotificationsScreen() {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val g = Gh.c

    var filter by rememberSaveable { mutableIntStateOf(0) }
    var reason by rememberSaveable { mutableIntStateOf(0) }
    var grouped by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    var askBulk by remember { mutableStateOf<BulkKind?>(null) }
    val selected = remember { mutableStateListOf<String>() }

    // 放在 Entry 的 bag 里：切到别的 Tab 再回来，本机的已读 / 已完成状态不会丢
    val state = retain("notif:state") { NotifState(com.mobilegh.data.NotificationStore.read(it.login.orEmpty())) }
    val snackbar = retain("notif:snackbar") { SnackbarHostState() }

    // 「已完成」视图不需要单独的分页：沿用上一次的服务端筛选
    var apiFilter by rememberSaveable { mutableIntStateOf(0) }
    val af = apiFilter // 固定到当前 Pager，切换筛选时不影响进行中的请求
    val pager = rememberPager("notif:$af") { p, f -> GitHub.notifications(af == 1, af == 2, p, f) }
    val listState = rememberLazyListState()
    com.mobilegh.nav.OnTabReselect(Tab.Notifications) { listState.animateScrollToItem(0) }

    LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.items.toList() }.collect { loaded ->
            val merged = (loaded + state.known).distinctBy { it.id }.take(1000)
            if (merged != state.known.toList()) { state.known.clear(); state.known.addAll(merged) }
        }
    }
    // 在 Entry 作用域保存：切换 Tab 不取消保存；本机状态与撤销中的操作分开处理。
    retain("notif:persistence") {
        entry.scope.launch {
            androidx.compose.runtime.snapshotFlow {
                val pending = state.pending.toMap()
                val reads = state.read.filter { it !in pending } + pending.filterValues { it.wasRead }.keys
                val unreads = state.markedUnread.filter { it !in pending } + pending.filterValues { it.wasUnread }.keys
                com.mobilegh.data.LocalNotifications(reads.takeLast(5000), unreads.takeLast(5000),
                    state.doneItems.filter { it.id !in pending }.take(500), state.known.toList())
            }.collect { saved ->
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        entry.login?.let { com.mobilegh.data.NotificationStore.save(it, saved) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { com.mobilegh.data.AppLog.warn("notifications", "本机通知状态保存失败", e) }
            }
        }
    }
    val localUnread = (pager.items.toList() + state.known.filter { it.id in state.markedUnread })
        .distinctBy { it.id }.filter { state.unread(it) }
    LaunchedEffect(af, localUnread, pager.loadedOnce, pager.end) {
        if (af == 0 && pager.loadedOnce) {
            Badges.unread = localUnread.size
            entry.login?.let { com.mobilegh.data.WidgetStore.saveInbox(it, pager.items.toList(), !pager.end) }
        }
    }

    /** 还原一次「完成」：条目回到列表，已读标记与角标一并还原 */
    fun restoreDone(id: String) {
        val n = state.doneItems.firstOrNull { it.id == id } ?: return
        state.restore(id)
        if (state.unread(n)) bumpBadge(1)
    }

    /** 用户点了「撤销」：取消等待中的 DELETE 并还原 */
    fun undoDone(id: String) {
        val p = state.pending[id] ?: return
        p.job?.cancel()
        restoreDone(id)
    }

    /** 完成一条：先本地生效，撤销窗口结束后才真正 DELETE */
    fun finish(n: Notification) {
        if (n.id in state.done) return
        val p = NotifState.Pending(wasRead = n.id in state.read, wasUnread = n.id in state.markedUnread)
        if (state.unread(n)) bumpBadge(-1)
        state.complete(n)
        state.markRead(n.id)
        state.pending[n.id] = p
        selected.remove(n.id)

        // 放在 Entry 作用域：切走页面也会按时提交
        p.job = entry.scope.launch {
            // 新的一条顶掉旧提示：旧的立即提交，新的重新计时
            snackbar.currentSnackbarData?.dismiss()
            val result = try {
                withTimeout(UNDO_MS) {
                    snackbar.showSnackbar(
                        "已完成「${n.subject.title.take(24)}」",
                        actionLabel = "撤销",
                        withDismissAction = true,
                        duration = SnackbarDuration.Indefinite,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                SnackbarResult.Dismissed
            }
            if (result == SnackbarResult.ActionPerformed) {
                restoreDone(n.id)
                return@launch
            }
            try {
                GitHub.markDone(n.id)
                state.pending.remove(n.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 服务端失败：本地也撤回，条目回到列表
                restoreDone(n.id)
                ctx.toast("完成失败：${e.friendly()}")
            }
        }
    }

    /** 单条已读 / 未读切换 */
    fun toggleRead(n: Notification) {
        if (state.unread(n)) {
            val wasRead = n.id in state.read
            val wasUnread = n.id in state.markedUnread
            state.markRead(n.id)
            bumpBadge(-1)
            if (n.unread && !wasRead) {
                entry.act(
                    onError = {
                        state.rollbackRead(n.id, wasRead, wasUnread)
                        bumpBadge(1)
                        ctx.toast(it)
                    },
                ) { GitHub.markRead(n.id) }
            }
        } else {
            // GitHub 没有「标为未读」接口，只能在本机模拟
            state.markUnread(n.id)
            bumpBadge(1)
            ctx.toast("已在本机标为未读（GitHub 不支持远程标为未读）")
        }
    }

    /** 打开通知：未读的顺带标为已读 */
    fun open(n: Notification) {
        if (state.unread(n)) {
            val wasRead = n.id in state.read
            state.markRead(n.id)
            bumpBadge(-1)
            // 打开失败不回滚：用户已经看过这条
            if (n.unread && !wasRead) entry.act({ ctx.toast(it) }) { GitHub.markRead(n.id) }
        }
        val (o, r) = n.repository.owner.login to n.repository.name
        val target = n.subject.url?.let { Links.route(it) }
            ?: when (n.subject.type) {
                "CheckSuite", "WorkflowRun" -> Screen.Actions(o, r)
                "Release" -> Screen.Releases(o, r)
                "RepositoryInvitation" -> Screen.Invitations
                else -> Screen.Repo(o, r)
            }
        nav.push(target)
    }

    fun unsubscribe(ids: List<String>, ignore: Boolean) {
        if (ids.isEmpty()) return
        ids.forEach { if (it !in state.unsubscribed) state.unsubscribed.add(it) }
        entry.act(
            onError = {
                ids.forEach { id -> state.unsubscribed.remove(id) }
                ctx.toast(it)
            },
            onDone = { ctx.toast(if (ignore) "已忽略所选线程" else "已取消订阅所选线程") },
        ) {
            ids.forEach { id -> if (ignore) GitHub.ignoreThread(id) else GitHub.unsubscribeThread(id) }
        }
    }

    /** 批量操作：有界并发逐条调用接口，显示进度与部分失败数 */
    fun runBatch(kind: BulkKind, items: List<Notification>) {
        if (items.isEmpty() || state.busyLabel != null) return
        val ids = items.map { it.id }
        val shownUnread = items.filter { state.unread(it) }.map { it.id }.toSet()
        state.busyLabel = kind.label
        state.busyDone = 0
        state.busyTotal = ids.size
        fun report(res: BulkResult, verb: String) = ctx.toast(
            if (res.failed == 0) "已$verb ${res.ok} 条"
            else "已$verb ${res.ok} 条，${res.failed} 条失败：${res.firstError ?: ""}",
        )
        entry.scope.launch {
            when (kind) {
                BulkKind.Done -> {
                    val res = runBulk(ids, { state.busyDone = it }) { GitHub.markDone(it) }
                    val failed = res.failedIds.toSet()
                    items.forEach { n -> if (n.id !in failed) state.complete(n) }
                    bumpBadge(-shownUnread.count { it !in failed })
                    report(res, "完成")
                }
                BulkKind.Read -> {
                    val before = items.associate { it.id to ((it.id in state.read) to (it.id in state.markedUnread)) }
                    // 服务端本来就是已读的（或本机已 PATCH 过）不必再请求
                    val needApi = items.filter { it.unread && it.id !in state.read }.map { it.id }
                    items.forEach { state.markRead(it.id) }
                    bumpBadge(-shownUnread.size)
                    state.busyTotal = needApi.size
                    val res = runBulk(needApi, { state.busyDone = it }) { GitHub.markRead(it) }
                    res.failedIds.forEach { id ->
                        val (r, u) = before.getValue(id)
                        state.rollbackRead(id, r, u)
                    }
                    bumpBadge(items.count { it.id in res.failedIds && state.unread(it) })
                    report(BulkResult(items.size - res.failed, res.failed, res.failedIds, res.firstError), "标为已读")
                }
                BulkKind.Ignore -> {
                    ids.forEach { if (it !in state.unsubscribed) state.unsubscribed.add(it) }
                    val res = runBulk(ids, { state.busyDone = it }) { GitHub.ignoreThread(it) }
                    res.failedIds.forEach { state.unsubscribed.remove(it) }
                    report(res, "忽略")
                }
            }
            state.busyLabel = null
        }
    }

    // 本地筛掉已完成的条目：如果留给 item lambda 提前 return，会渲染成空白块并撑坏分页
    val extraUnread = state.known.filter { it.id in state.markedUnread && it.id !in state.done }
    val visible = (pager.items + extraUnread).distinctBy { it.id }.filter { it.id !in state.done }
    val matched = visible.filter { reason == 0 || REASON_FILTERS[reason].first == it.reason }
    val doneTab = filter == TAB_DONE
    val busy = state.busyLabel != null

    fun clearSelection() {
        selection = false
        selected.clear()
    }

    fun toggleSelect(n: Notification) {
        selection = true
        if (n.id in selected) selected.remove(n.id) else selected.add(n.id)
    }

    BackHandler(enabled = selection) { clearSelection() }

    Page(
        if (selection) "已选择 ${selected.size} 条" else "通知",
        back = false,
        contentWindowInsets = WindowInsets(0),
        subtitle = when {
            doneTab || selection -> null
            busy -> "正在${state.busyLabel}：${state.busyDone}/${state.busyTotal}"
            matched.isEmpty() -> null
            filter == 0 -> "${matched.count { state.unread(it) }} 条未读"
            else -> "${matched.size} 条"
        },
        actions = {
            when {
                selection -> {
                    val all = matched.isNotEmpty() && matched.all { it.id in selected }
                    TextLink(if (all) "取消全选" else "全选") {
                        val ids = matched.map { it.id }
                        if (ids.isNotEmpty() && ids.all { it in selected }) selected.removeAll(ids)
                        else selected.addAll(ids.filterNot { it in selected })
                    }
                    val canAct = selected.isNotEmpty() && !busy
                    OcButton(R.drawable.oc_eye, {
                        runBatch(BulkKind.Read, visible.filter { it.id in selected })
                        clearSelection()
                    }, enabled = canAct)
                    OcButton(R.drawable.oc_check, { askBulk = BulkKind.Done }, enabled = canAct)
                    MoreMenu(
                        listOf(
                            MenuAction("标为已读") {
                                if (canAct) {
                                    runBatch(BulkKind.Read, visible.filter { it.id in selected })
                                    clearSelection()
                                }
                            },
                            MenuAction("完成") { if (canAct) askBulk = BulkKind.Done },
                            MenuAction("标为未读（仅本机）") {
                                val items = visible.filter { it.id in selected }
                                val wasUnread = items.count { state.unread(it) }
                                items.forEach { state.markUnread(it.id) }
                                bumpBadge(items.size - wasUnread)
                                if (items.isNotEmpty()) ctx.toast("已在本机将 ${items.size} 条标为未读")
                                clearSelection()
                            },
                            MenuAction("忽略所选线程", danger = true) { if (canAct) askBulk = BulkKind.Ignore },
                            MenuAction("取消") { clearSelection() },
                        ),
                    )
                }
                doneTab -> Unit
                else -> {
                    // 按仓库分组 / 按时间排列
                    OcButton(R.drawable.oc_repo, { grouped = !grouped }, if (grouped) g.accent else g.fg)
                    OcButton(R.drawable.oc_checklist, { confirmAll = true })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Chips(listOf("未读", "全部", "参与的", "已完成"), filter) {
                filter = it
                if (it != TAB_DONE) apiFilter = it
                reason = 0
                clearSelection()
            }
            HDivider()
            if (!doneTab) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dropdown(REASON_FILTERS[reason].second, REASON_FILTERS.map { it.second }, { reason = it })
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            reason != 0 -> "只筛选已加载的通知：GitHub 接口不支持按原因过滤"
                            grouped -> "按仓库分组"
                            else -> "按时间排列"
                        },
                        Modifier.weight(1f),
                        fontSize = 11.sp, lineHeight = 14.sp, color = g.fgMuted, maxLines = 2,
                    )
                }
                HDivider()
            }
            Box(Modifier.weight(1f)) {
                if (doneTab) {
                    DoneView(
                        items = state.doneItems,
                        state = state,
                        onOpen = { n -> open(n) },
                        onUndo = { id -> undoDone(id) },
                    )
                } else {
                    PagedList(
                        pager,
                        state = listState,
                        empty = if (filter == 0) "没有未读通知，全部处理完了" else "暂无通知",
                        emptyIcon = R.drawable.oc_bell,
                        itemKey = { it.id },
                        extraItems = extraUnread,
                        // 不筛 read：点开/标为已读后条目留在列表里（变灰），与 GitHub 客户端一致
                        itemFilter = { it.id !in state.done && (reason == 0 || REASON_FILTERS[reason].first == it.reason) },
                        filteredEmpty = if (reason != 0) "已加载的通知里没有这个原因" else "没有匹配的通知",
                        groupKey = if (grouped) { n -> n.repository.id } else null,
                        groupHeader = { list ->
                            RepoHeader(list) { nav.push(Screen.Repo(list.first().repository.owner.login, list.first().repository.name)) }
                        },
                        header = { if (busy) item(key = "bulk") { BulkBanner(state.busyLabel ?: "", state.busyDone, state.busyTotal) } },
                    ) { n ->
                        NotificationRow(
                            n = n,
                            grouped = grouped,
                            state = state,
                            selection = selection,
                            selected = n.id in selected,
                            busy = busy,
                            onOpen = { if (selection) toggleSelect(n) else open(n) },
                            onToggleSelect = { toggleSelect(n) },
                            onDone = { finish(n) },
                            onToggleRead = { toggleRead(n) },
                            onUnsubscribe = { unsubscribe(listOf(n.id), false) },
                            onIgnore = { unsubscribe(listOf(n.id), true) },
                        )
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp)) { data ->
                    Snackbar(
                        data,
                        shape = RoundedCornerShape(8.dp),
                        containerColor = g.canvasSubtle,
                        contentColor = g.fg,
                        actionColor = g.accent,
                        dismissActionContentColor = g.fgMuted,
                    )
                }
            }
        }
    }

    if (confirmAll) {
        GhDialog("全部标为已读", { confirmAll = false }, onConfirm = {
            confirmAll = false
            entry.act({ ctx.toast(it) }) {
                GitHub.markAllRead()
                state.markedUnread.clear()
                pager.items.forEach { state.markRead(it.id) }
                Badges.unread = 0
                Session.login?.let { com.mobilegh.data.WidgetStore.saveInbox(it, emptyList(), false) }
                ctx.toast("已全部标为已读")
                pager.refresh()
            }
        }) { Text("确定将所有通知标记为已读吗？", color = g.fg) }
    }

    askBulk?.let { kind ->
        val items = visible.filter { it.id in selected }
        if (items.isNotEmpty()) {
            GhDialog(
                if (kind == BulkKind.Done) "完成所选通知" else "忽略所选线程",
                onDismiss = { askBulk = null },
                confirm = kind.label,
                onConfirm = {
                    askBulk = null
                    runBatch(kind, items)
                    clearSelection()
                },
            ) {
                Text(
                    if (kind == BulkKind.Done) {
                        "将把所选 ${items.size} 条通知从收件箱移除（DELETE /notifications/threads/{id}）。批量完成无法逐条撤销。"
                    } else {
                        "将忽略所选 ${items.size} 个线程（PUT .../subscription {ignored:true}），之后即使被 @ 也不再收到通知。"
                    },
                    color = g.fg,
                )
            }
        }
    }
}
