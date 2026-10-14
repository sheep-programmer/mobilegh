package com.mobilegh.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.mobilegh.data.GraphQlCursors
import com.mobilegh.data.ProjectColumn
import com.mobilegh.data.ProjectDetail
import com.mobilegh.data.ProjectErrorKind
import com.mobilegh.data.ProjectErrors
import com.mobilegh.data.ProjectField
import com.mobilegh.data.ProjectFieldValue
import com.mobilegh.data.ProjectItem
import com.mobilegh.data.ProjectItemType
import com.mobilegh.data.ProjectLayout
import com.mobilegh.data.ProjectOption
import com.mobilegh.data.ProjectOwnerKind
import com.mobilegh.data.ProjectSummary
import com.mobilegh.data.ProjectValueKind
import com.mobilegh.data.Projects
import com.mobilegh.data.ProjectsClient
import com.mobilegh.data.Session
import com.mobilegh.data.applyProjectEdits
import com.mobilegh.data.boardGroupField
import com.mobilegh.data.cardFieldValues
import com.mobilegh.data.groupProjectItems
import com.mobilegh.data.withSingleSelect
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.Counter
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.RadioRow
import com.mobilegh.ui.components.Segmented
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.boxedItems
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.emptyItem
import com.mobilegh.ui.components.issueState
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.stateVisual
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import com.mobilegh.ui.theme.projectBg
import com.mobilegh.ui.theme.projectFg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ======================= 项目列表 =======================

@Composable
fun ProjectsScreen(kind: ProjectOwnerKind, login: String, name: String) {
    val nav = LocalNav.current
    val cursors = retain("projects:cursors:$kind:$login:$name") { GraphQlCursors() }
    val pager = rememberPager("projects:$kind:$login:$name", ProjectsClient.LIST_PAGE) { page, _ ->
        cursors.page(page) { after -> Projects.list(kind, login, name, after) }
    }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    Page("项目", subtitle = if (kind == ProjectOwnerKind.Repo) "$login/$name" else login) { pad ->
        val err = pager.error
        if (err != null && pager.items.isEmpty() && ProjectErrors.ofMessage(err) in setOf(ProjectErrorKind.Scope, ProjectErrorKind.Forbidden)) {
            ProjectErrorState(err, Modifier.padding(pad).fillMaxSize()) { pager.error = null; pager.refresh() }
        } else {
            Column(Modifier.padding(pad)) {
                Chips(listOf("开启", "已关闭", "全部"), filter) { filter = it }
                HDivider()
                PagedList(
                    pager,
                    empty = if (kind == ProjectOwnerKind.Repo) "此仓库没有关联的项目" else "暂无可见的项目",
                    emptyIcon = R.drawable.oc_table,
                    itemKey = { it.id },
                    itemFilter = { p -> when (filter) { 0 -> !p.closed; 1 -> p.closed; else -> true } },
                ) { p -> ProjectRow(p) { nav.push(Screen.ProjectDetail(p.id, p.title)) } }
            }
        }
    }
}

@Composable
private fun ProjectRow(p: ProjectSummary, onClick: () -> Unit) {
    val g = Gh.c
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Oc(R.drawable.oc_table, if (p.closed) g.done else g.success, 16.dp, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, Modifier.weight(1f, false), color = g.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p.closed) { Spacer(Modifier.width(6.dp)); Pill("已关闭", g.done) }
                if (!p.public) { Spacer(Modifier.width(6.dp)); Pill("私有") }
            }
            p.shortDescription?.let { Text(it, color = g.fgMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            val parts = listOfNotNull("#${p.number}", p.itemCount?.let { "$it 个条目" }, p.updatedAt?.let { "更新于 ${relTime(it)}" })
            Text(parts.joinToString(" · "), color = g.fgMuted, fontSize = 12.sp)
        }
    }
}

/** Projects 专用错误：缺少 scope / 被组织限制时说明原因并提供重新授权 */
@Composable
private fun ProjectErrorState(msg: String, modifier: Modifier = Modifier.fillMaxSize(), retry: () -> Unit) {
    val nav = LocalNav.current
    when (ProjectErrors.ofMessage(msg)) {
        ProjectErrorKind.Scope -> ErrorState(msg, modifier, retry, action = "重新授权") { nav.push(Screen.Login) }
        ProjectErrorKind.Forbidden -> ErrorState(
            "$msg\n\n若组织启用了 OAuth 应用访问限制，需由组织管理员批准本应用；也可以重新授权后再试。",
            modifier, retry, action = "重新授权",
        ) { nav.push(Screen.Login) }
        else -> ErrorState(msg, modifier, retry)
    }
}

/** 弹窗内的写入错误；缺少 project scope 时附带重新授权入口 */
@Composable
private fun WriteError(msg: String) {
    val nav = LocalNav.current
    Column {
        InlineError(msg)
        if (ProjectErrors.ofMessage(msg) == ProjectErrorKind.Scope) TextLink("重新授权") { nav.push(Screen.Login) }
    }
}

// ======================= 项目详情 =======================

private data class AddTarget(val option: ProjectOption?)

@Composable
fun ProjectDetailScreen(id: String, title: String) {
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val project = rememberLoader("project:$id") { Projects.detail(id) }
    val items = retain("project:items:$id") { e -> DiscussionCursorPager<ProjectItem>(e.scope, ProjectsClient.MAX_ITEMS) { it.id } }
    val edits = retain("project:edits:$id") { mutableStateMapOf<String, ProjectItem?>() }
    val added = retain("project:added:$id") { mutableStateListOf<ProjectItem>() }
    val seeded = retain("project:seeded:$id") { mutableStateOf<ProjectDetail?>(null) }
    val d = project.data
    LaunchedEffect(d) {
        if (d != null && seeded.value !== d) {
            seeded.value = d
            edits.clear()
            added.clear()
            items.restart({ cursor -> Projects.items(id, cursor) }, d.items)
        }
    }
    // 看板分列需要完整数据：逐页自动加载，直至上限
    LaunchedEffect(items.items.size, items.hasMore, items.loading, items.error) {
        if (seeded.value != null && items.loaded && items.hasMore && !items.loading && items.error == null) items.loadMore()
    }
    Page(
        d?.title ?: title, subtitle = d?.let { "项目 #${it.number}" + if (it.closed) " · 已关闭" else "" },
        actions = {
            if (d != null && d.url.isNotBlank()) MoreMenu(listOf(
                MenuAction("刷新") { project.refresh() },
                MenuAction("复制链接") { ctx.copy(d.url) },
                MenuAction("在浏览器打开") { ctx.openBrowser(d.url) },
            ))
        },
    ) { pad ->
        when {
            d == null && project.error != null -> ProjectErrorState(project.error!!, Modifier.padding(pad).fillMaxSize()) { project.load(true) }
            d == null -> Loading(Modifier.padding(pad).fillMaxSize())
            else -> Box(Modifier.padding(pad)) {
                ProjectBody(d, applyProjectEdits(items.items, edits, added), items, edits, added, refreshing = project.refreshing) { project.refresh() }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectBody(
    d: ProjectDetail,
    all: List<ProjectItem>,
    pager: DiscussionCursorPager<ProjectItem>,
    edits: MutableMap<String, ProjectItem?>,
    added: MutableList<ProjectItem>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()
    val account = remember { Session.login to Session.generation }
    var viewIndex by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf<Int?>(null) }
    var moving by remember { mutableStateOf<ProjectItem?>(null) }
    var removing by remember { mutableStateOf<ProjectItem?>(null) }
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var viewingDraft by remember { mutableStateOf<ProjectItem?>(null) }

    val view = d.views.getOrNull(viewIndex) ?: d.views.firstOrNull()
    val groupField = boardGroupField(d.fields, view)
    val board = (mode ?: if (view == null) (if (groupField != null) 0 else 1) else if (view.layout == ProjectLayout.Board) 0 else 1) == 0
    val columns = remember(all, groupField) { groupProjectItems(all, groupField) }
    val canUpdate = d.viewerCanUpdate && Session.token != null && !Session.authExpired

    fun sameAccount() = Session.login == account.first && Session.generation == account.second && Session.token != null

    /** 写入：发出前与返回后都校验账号未切换，避免旧账号的结果写进当前页面 */
    fun guarded(onError: (String) -> Unit, onFinally: () -> Unit, block: suspend () -> Unit, onDone: () -> Unit) {
        scope.launch {
            try {
                check(sameAccount()) { "账号已切换，请重新打开项目" }
                block()
                check(sameAccount()) { "账号已切换，旧账号的修改结果不再更新当前页面" }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.friendly())
            } finally {
                onFinally()
            }
        }
    }

    fun open(item: ProjectItem) {
        val repo = item.repoParts
        when {
            item.type == ProjectItemType.DraftIssue -> viewingDraft = item
            repo != null && item.number != null -> nav.push(Screen.IssueDetail(repo.first, repo.second, item.number, item.type == ProjectItemType.PullRequest))
            item.url != null -> ctx.openBrowser(item.url)
            else -> ctx.toast("当前账号无权查看此条目")
        }
    }

    fun actions(item: ProjectItem): List<MenuAction> = buildList {
        if (canUpdate && groupField != null) add(MenuAction("移动到…") { moving = item })
        if (item.url != null) {
            add(MenuAction("在浏览器打开") { ctx.openBrowser(item.url) })
            add(MenuAction("复制链接") { ctx.copy(item.url) })
        }
        if (canUpdate) add(MenuAction("从项目移除", danger = true) { removing = item })
    }

    val longPress: (ProjectItem) -> Unit = { item -> if (canUpdate && groupField != null) moving = item }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (d.views.isNotEmpty() && view != null) {
                    Dropdown(view.name.ifBlank { "视图 ${view.number}" }, d.views.map { "${it.name.ifBlank { "视图 ${it.number}" }} · ${layoutLabel(it.layout)}" }, {
                        viewIndex = it
                        mode = null
                    }, Modifier.weight(1f, false))
                }
                Spacer(Modifier.weight(1f))
                Segmented(listOf("看板", "列表"), if (board) 0 else 1, Modifier.width(150.dp)) { mode = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val status = buildList {
                    add("已加载 ${all.size}/${pager.totalCount.coerceAtLeast(all.size)} 个条目")
                    if (pager.loading) add("加载中…")
                    if (!d.viewerCanUpdate) add("只读")
                }
                Text(status.joinToString(" · "), Modifier.weight(1f), color = g.fgMuted, fontSize = 12.sp)
                if (canUpdate && !board) TextLink("添加草稿") { addTarget = AddTarget(null) }
            }
            if (view?.layout == ProjectLayout.Roadmap && mode == null) Text("路线图视图暂以列表显示", color = g.fgMuted, fontSize = 12.sp)
            view?.filter?.let { Text("此视图的筛选条件「$it」未应用，显示全部条目", color = g.fgMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (pager.limited) Text("条目较多，仅显示前 ${ProjectsClient.MAX_ITEMS} 个", color = g.fgMuted, fontSize = 12.sp)
            pager.error?.let { InlineError(it, retry = pager::retry) }
        }
        HDivider()
        PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize()) {
            if (board) {
                LazyRow(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(columns, key = { it.key }) { col ->
                        BoardColumn(col, groupField, d, canUpdate, onAdd = { addTarget = AddTarget(col.option) }) { item ->
                            ItemCard(item, d, groupField, actions(item), Modifier.combinedClickable(onClick = { open(item) }, onLongClick = { longPress(item) }))
                        }
                    }
                }
            } else {
                val listState = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
                    boxedItems(all, key = { it.id }) { item ->
                        ItemRow(item, d, groupField, actions(item), Modifier.combinedClickable(onClick = { open(item) }, onLongClick = { longPress(item) }))
                    }
                    if (all.isEmpty() && !pager.loading && pager.error == null) {
                        emptyItem(listState, "项目中暂无条目", R.drawable.oc_table, if (canUpdate) "添加草稿" else null) { addTarget = AddTarget(null) }
                    }
                }
            }
        }
    }

    moving?.let { item ->
        val field = groupField ?: return@let
        val current = item.value(field.id)?.optionId?.takeIf { id -> field.options.any { it.id == id } }
        var selected by remember(item.id) { mutableStateOf(current) }
        var busy by remember(item.id) { mutableStateOf(false) }
        var error by remember(item.id) { mutableStateOf<String?>(null) }
        GhDialog(
            "移动到…", { if (!busy) moving = null }, confirm = if (busy) "移动中…" else "移动",
            confirmEnabled = !busy && selected != current,
            onConfirm = {
                val option = field.options.firstOrNull { it.id == selected }
                busy = true
                error = null
                guarded({ error = it }, { busy = false }, { Projects.setSingleSelect(d.id, item.id, field.id, option?.id) }) {
                    edits[item.id] = item.withSingleSelect(field, option)
                    moving = null
                }
            },
        ) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(item.title, color = g.fgMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                RadioRow(selected == null, "无 ${field.name}", enabled = !busy) { selected = null }
                field.options.forEach { o -> RadioRow(selected == o.id, o.name, enabled = !busy) { selected = o.id } }
                error?.let { WriteError(it) }
            }
        }
    }

    removing?.let { item ->
        var busy by remember(item.id) { mutableStateOf(false) }
        var error by remember(item.id) { mutableStateOf<String?>(null) }
        GhDialog(
            "从项目移除", { if (!busy) removing = null }, confirm = if (busy) "移除中…" else "移除", danger = true, confirmEnabled = !busy,
            onConfirm = {
                busy = true
                error = null
                guarded({ error = it }, { busy = false }, { Projects.delete(d.id, item.id) }) {
                    edits[item.id] = null
                    removing = null
                    ctx.toast("已从项目移除")
                }
            },
        ) {
            Column {
                Text(
                    if (item.type == ProjectItemType.DraftIssue) "草稿「${item.title}」将被永久删除，无法恢复。"
                    else "将「${item.title}」从项目中移除。议题或拉取请求本身不会被删除。",
                    fontSize = 14.sp,
                )
                error?.let { WriteError(it) }
            }
        }
    }

    addTarget?.let { target ->
        var draftTitle by rememberSaveable { mutableStateOf("") }
        var draftBody by rememberSaveable { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val option = target.option
        GhDialog(
            "添加草稿", { if (!busy) addTarget = null }, confirm = if (busy) "添加中…" else "添加",
            confirmEnabled = !busy && draftTitle.isNotBlank(),
            onConfirm = {
                busy = true
                error = null
                var created: ProjectItem? = null
                var statusError: String? = null
                guarded({ error = it }, { busy = false }, {
                    val item = Projects.addDraft(d.id, draftTitle, draftBody)
                    created = item
                    if (option != null && groupField != null) {
                        try {
                            Projects.setSingleSelect(d.id, item.id, groupField.id, option.id)
                            created = item.withSingleSelect(groupField, option)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            statusError = e.friendly()
                        }
                    }
                }) {
                    created?.let { added.add(0, it) }
                    statusError?.let { ctx.toast("草稿已添加，但未能设置${groupField?.name ?: "状态"}：$it") }
                    draftTitle = ""
                    draftBody = ""
                    addTarget = null
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (option != null && groupField != null) Text("添加到「${option.name}」", color = g.fgMuted, fontSize = 13.sp)
                GhField(draftTitle, { if (!busy) draftTitle = it }, "标题")
                GhField(draftBody, { if (!busy) draftBody = it }, "正文（可选）", singleLine = false, minLines = 3)
                error?.let { WriteError(it) }
            }
        }
    }

    viewingDraft?.let { item ->
        GhDialog(item.title, { viewingDraft = null }) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Oc(R.drawable.oc_issue_draft, g.fgMuted, 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("草稿", color = g.fgMuted, fontSize = 13.sp)
                }
                val body = item.body?.takeIf { it.isNotBlank() }
                Text(body ?: "没有正文", color = if (body == null) g.fgMuted else g.fg, fontSize = 14.sp)
                val values = cardFieldValues(item, limit = 8)
                if (values.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    values.forEach { ValuePill(it, d) }
                }
            }
        }
    }
}

private fun layoutLabel(l: ProjectLayout) = when (l) {
    ProjectLayout.Board -> "看板"
    ProjectLayout.Table -> "表格"
    ProjectLayout.Roadmap -> "路线图"
}

@Composable
private fun OptionDot(option: ProjectOption?) {
    val g = Gh.c
    val color = option?.color
    Box(
        Modifier.size(12.dp).clip(CircleShape)
            .background(if (color == null) g.canvas else g.projectBg(color))
            .border(1.5.dp, if (color == null) g.fgMuted else g.projectFg(color), CircleShape),
    )
}

@Composable
private fun BoardColumn(
    col: ProjectColumn,
    field: ProjectField?,
    d: ProjectDetail,
    canUpdate: Boolean,
    onAdd: () -> Unit,
    card: @Composable (ProjectItem) -> Unit,
) {
    val g = Gh.c
    Column(
        Modifier.width(280.dp).fillMaxHeight()
            .background(g.canvasSubtle, RoundedCornerShape(8.dp))
            .border(1.dp, g.borderMuted, RoundedCornerShape(8.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            OptionDot(col.option)
            Spacer(Modifier.width(8.dp))
            Text(
                col.option?.name ?: if (field == null) "全部条目" else "无 ${field.name}",
                Modifier.weight(1f, false), color = g.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Counter(col.items.size)
            Spacer(Modifier.weight(1f))
            if (canUpdate && !d.closed) OcButton(R.drawable.oc_plus, onAdd, g.fgMuted)
        }
        HDivider()
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(col.items, key = { it.id }) { card(it) }
            if (col.items.isEmpty()) item {
                Text("暂无条目", Modifier.fillMaxWidth().padding(vertical = 16.dp), color = g.fgMuted, fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun itemIcon(item: ProjectItem): Pair<Int, Color> {
    val g = Gh.c
    return when (item.type) {
        ProjectItemType.DraftIssue -> R.drawable.oc_issue_draft to g.fgMuted
        ProjectItemType.Redacted -> R.drawable.oc_lock to g.fgMuted
        else -> {
            val s = issueState(item.state.orEmpty().lowercase(), item.stateReason?.lowercase(), item.type == ProjectItemType.PullRequest, item.merged, item.isDraft)
            stateVisual(s).let { it.first to it.second }
        }
    }
}

private fun itemSubtitle(item: ProjectItem) = item.reference ?: when (item.type) {
    ProjectItemType.DraftIssue -> "草稿"
    ProjectItemType.Redacted -> "无权访问"
    else -> ""
}

@Composable
private fun ItemCard(item: ProjectItem, d: ProjectDetail, groupField: ProjectField?, actions: List<MenuAction>, click: Modifier) {
    val g = Gh.c
    val (icon, color) = itemIcon(item)
    Card(Modifier.clip(RoundedCornerShape(8.dp)).then(click)) {
        Column(Modifier.padding(start = 12.dp, bottom = 10.dp, top = if (actions.isEmpty()) 10.dp else 0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Oc(icon, color, 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(itemSubtitle(item), Modifier.weight(1f), color = g.fgMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (actions.isNotEmpty()) MoreMenu(actions, g.fgMuted) else Spacer(Modifier.width(12.dp))
            }
            Text(item.title, Modifier.padding(end = 12.dp), color = g.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val values = cardFieldValues(item, setOfNotNull(groupField?.id))
            if (values.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 8.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    values.forEach { ValuePill(it, d) }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: ProjectItem, d: ProjectDetail, groupField: ProjectField?, actions: List<MenuAction>, click: Modifier) {
    val g = Gh.c
    val (icon, color) = itemIcon(item)
    Row(Modifier.fillMaxWidth().then(click).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)) {
        Oc(icon, color, 16.dp, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, color = g.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(itemSubtitle(item), color = g.fgMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = groupField?.let { item.value(it.id) }
            val values = listOfNotNull(status) + cardFieldValues(item, setOfNotNull(groupField?.id))
            if (values.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    values.forEach { ValuePill(it, d) }
                }
            }
        }
        if (actions.isNotEmpty()) MoreMenu(actions, g.fgMuted) else Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun ValuePill(v: ProjectFieldValue, d: ProjectDetail) {
    val g = Gh.c
    val color = if (v.kind == ProjectValueKind.SingleSelect) {
        d.fields.firstOrNull { it.id == v.fieldId }?.options?.firstOrNull { it.id == v.optionId }?.color?.let { g.projectFg(it) }
    } else null
    val text = if (v.text.length > 28) v.text.take(27) + "…" else v.text
    val label = when (v.kind) {
        ProjectValueKind.SingleSelect, ProjectValueKind.Labels, ProjectValueKind.Users, ProjectValueKind.Milestone -> text
        else -> "${v.fieldName}: $text"
    }
    Pill(label, color ?: g.fgMuted)
}
