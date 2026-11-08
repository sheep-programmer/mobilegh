package com.mobilegh.ui.screens

import com.mobilegh.nav.rememberLoader
import androidx.compose.ui.text.style.TextOverflow
import com.mobilegh.data.RepoWrite
import com.mobilegh.nav.OnRepoChanged
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.HomeLayout
import com.mobilegh.data.HomePrefs
import com.mobilegh.data.Involvement
import com.mobilegh.data.Issue
import com.mobilegh.data.IssueQuery
import com.mobilegh.data.IssueSort
import com.mobilegh.data.IssueStateFilter
import com.mobilegh.data.ReviewFilter
import com.mobilegh.data.SAVED_SEARCH_ICONS
import com.mobilegh.data.Session
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Pager
import com.mobilegh.nav.Screen
import com.mobilegh.nav.friendly
import com.mobilegh.nav.retain
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.offerUndo
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.IssueItem
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.PullItem
import com.mobilegh.ui.components.Segmented
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.rememberTick
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.delay
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalContentColor
import com.mobilegh.ui.components.CompactSearchField
import com.mobilegh.ui.components.FieldAction

/** 保存的搜索图标 key → Octicon */
@DrawableRes
fun savedSearchIcon(key: String): Int = when (key) {
    "issue" -> R.drawable.oc_issue_opened
    "pr" -> R.drawable.oc_git_pull_request
    "bug" -> R.drawable.oc_bug
    "tag" -> R.drawable.oc_tag
    "milestone" -> R.drawable.oc_milestone
    "mention" -> R.drawable.oc_mention
    "eye" -> R.drawable.oc_eye
    "flame" -> R.drawable.oc_flame
    "zap" -> R.drawable.oc_zap
    "star" -> R.drawable.oc_star
    "bookmark" -> R.drawable.oc_bookmark
    else -> R.drawable.oc_search
}

/** 打开保存的搜索对应的列表 */
fun savedSearchScreen(q: IssueQuery): Screen {
    val repo = q.repo
    return if (repo != null) Screen.Issues(repo.substringBefore('/'), repo.substringAfter('/'), q.pulls, q)
    else Screen.MyIssues(q.pulls, q)
}

private enum class FilterDialog { Author, Assignee, Labels, Milestone }

/**
 * Issue / PR 列表顶部的搜索与筛选栏：搜索框、状态、排序与各项筛选，下方是可移除的已生效筛选。
 * owner / name 为空时（跨仓库）不显示需要仓库候选项的筛选。
 */
@Composable
fun IssueFilterBar(query: IssueQuery, onChange: (IssueQuery) -> Unit, owner: String? = null, name: String? = null, segments: Boolean = false) {
    val g = Gh.c
    var input by rememberSaveable { mutableStateOf(query.text) }
    var dialog by remember { mutableStateOf<FilterDialog?>(null) }
    val noun = if (query.pulls) "Pull Request" else "Issue"
    val latest by rememberUpdatedState(query)
    val change by rememberUpdatedState(onChange)
    // 停止输入片刻后再搜索，减少 Search API 调用
    LaunchedEffect(input) {
        if (input.trim() == latest.text.trim()) return@LaunchedEffect
        delay(700)
        change(latest.copy(text = input.trim()))
    }
    LaunchedEffect(query.text) { if (query.text.trim() != input.trim()) input = query.text }

    Column {
        if (segments) {
            val kinds = Involvement.entries.filter { query.pulls || !it.pullsOnly }
            Segmented(kinds.map { it.label }, kinds.indexOf(query.involves ?: Involvement.Created), Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                onChange(query.copy(involves = kinds[it]))
            }
        }
        // 与仓库、搜索页一致：全圆角胶囊搜索框
        CompactSearchField(
            input, { input = it }, trf("搜索 {0}", noun),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            hint = tr("标题、正文或评论中的关键词"),
            onSearch = { onChange(query.copy(text = input.trim())) },
            trailing = if (input.isNotEmpty()) ({ FieldAction(R.drawable.oc_x, tr("清空关键词")) { input = ""; onChange(query.copy(text = "")) } }) else null,
        )
        // 状态用标准的筛选胶囊，一排平铺，选中项高亮
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IssueStateFilter.entries.forEach { st ->
                FilterChip(selected = query.state == st, onClick = { onChange(query.copy(state = st)) }, label = { Text(st.label) })
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 固定选项的字段点开即下拉菜单；作者、指派人、标签、里程碑需要仓库候选项，点开是选择弹窗
            // 「我的 Issues」里「相关」已经是顶部分段，这里的排序不再提供「相关」（没有关键词时按相关度排序没有意义）
            val sorts = if (segments) IssueSort.entries.filter { it != IssueSort.Relevance } else IssueSort.entries
            FilterMenuChip(
                label = trf("排序：{0}", query.sort.label),
                description = trf("{0}：{1}", tr("排序"), query.sort.label),
                selected = query.sort != IssueSort.Newest,
                options = sorts.map { it.label },
            ) { onChange(query.copy(sort = sorts[it])) }
            if (owner != null && name != null) {
                FilterValueChip(
                    label = query.author?.let { trf("作者：{0}", it) } ?: tr("作者"),
                    description = trf("{0}：{1}", tr("作者"), query.author ?: tr("不限")),
                    selected = query.author != null,
                ) { dialog = FilterDialog.Author }
                FilterValueChip(
                    label = query.assignee?.let { trf("指派人：{0}", it) } ?: tr("指派人"),
                    description = trf("{0}：{1}", tr("指派人"), query.assignee ?: tr("不限")),
                    selected = query.assignee != null,
                ) { dialog = FilterDialog.Assignee }
                FilterValueChip(
                    label = if (query.labels.isEmpty()) tr("标签") else trf("标签 · {0}", query.labels.size),
                    description = trf("{0}：{1}", tr("标签"), if (query.labels.isEmpty()) tr("不限") else trf("已选 {0} 项", query.labels.size)),
                    selected = query.labels.isNotEmpty(),
                ) { dialog = FilterDialog.Labels }
                FilterValueChip(
                    label = query.milestone?.let { trf("里程碑：{0}", it) } ?: tr("里程碑"),
                    description = trf("{0}：{1}", tr("里程碑"), query.milestone ?: tr("不限")),
                    selected = query.milestone != null,
                ) { dialog = FilterDialog.Milestone }
            }
            if (query.pulls) {
                val reviews = listOf<ReviewFilter?>(null) + ReviewFilter.entries
                FilterMenuChip(
                    label = query.review?.label ?: tr("评审状态"),
                    description = trf("{0}：{1}", tr("评审状态"), query.review?.label ?: tr("不限")),
                    selected = query.review != null,
                    options = reviews.map { it?.label ?: tr("不限") },
                ) { onChange(query.copy(review = reviews[it])) }
                val drafts = listOf(null, true, false)
                val draftLabel = when (query.draft) { true -> tr("仅草稿"); false -> tr("排除草稿"); null -> tr("草稿") }
                FilterMenuChip(
                    label = draftLabel,
                    description = trf("{0}：{1}", tr("草稿"), if (query.draft == null) tr("不限") else draftLabel),
                    selected = query.draft != null,
                    options = listOf(tr("不限"), tr("仅草稿"), tr("排除草稿")),
                ) { onChange(query.copy(draft = drafts[it])) }
            }
        }
        ActiveFilters(query, onChange)
        HDivider()
    }

    if (owner != null && name != null) when (dialog) {
        FilterDialog.Author -> MetaPicker(
            "assignees", owner, name, tr("按作者筛选"), listOfNotNull(query.author), { dialog = null },
            single = true, noneLabel = tr("不限"), freeText = true,
        ) { onChange(query.copy(author = it.firstOrNull()?.key)) }
        FilterDialog.Assignee -> MetaPicker(
            "assignees", owner, name, tr("按指派人筛选"), listOfNotNull(query.assignee), { dialog = null },
            single = true, noneLabel = tr("不限"), freeText = true,
        ) { onChange(query.copy(assignee = it.firstOrNull()?.key)) }
        FilterDialog.Labels -> MetaPicker(
            "labels", owner, name, tr("按标签筛选"), query.labels, { dialog = null }, single = false,
            maxSelect = IssueQuery.MAX_LABELS, limitNote = trf("最多可同时筛选 {0} 个标签", IssueQuery.MAX_LABELS),
        ) {
            // 选择时已在界面上限制数量，这里的 take 只是兜底
            onChange(query.copy(labels = it.map { o -> o.key }.take(IssueQuery.MAX_LABELS)))
        }
        FilterDialog.Milestone -> MetaPicker(
            "milestone", owner, name, tr("按里程碑筛选"), listOfNotNull(query.milestone), { dialog = null },
            single = true, noneLabel = tr("不限"), milestoneByTitle = true,
        ) { onChange(query.copy(milestone = it.firstOrNull()?.key)) }
        null -> {}
    }
}

/**
 * 筛选胶囊：标准 FilterChip 加向下箭头。description 为无障碍描述（字段名 + 当前值）。
 * menu 与胶囊同在一个 Box 里，所以菜单锚定在这个胶囊上。
 */
@Composable
private fun FilterValueChip(
    label: String,
    description: String,
    selected: Boolean,
    menu: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    Box {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Oc(R.drawable.oc_chevron_down, LocalContentColor.current, 14.dp) },
            modifier = Modifier.semantics { contentDescription = description },
        )
        menu()
    }
}

/** 点按展开固定选项的筛选胶囊，选中后回调选项下标；菜单锚定在胶囊上 */
@Composable
private fun FilterMenuChip(
    label: String,
    description: String,
    selected: Boolean,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    val g = Gh.c
    var open by remember { mutableStateOf(false) }
    FilterValueChip(
        label = label,
        description = description,
        selected = selected,
        menu = {
            DropdownMenu(open, { open = false }, containerColor = g.canvas) {
                options.forEachIndexed { i, s ->
                    DropdownMenuItem(
                        text = { Text(s, color = g.fg) },
                        onClick = { open = false; onSelect(i) },
                    )
                }
            }
        },
        onClick = { open = true },
    )
}

/** 已生效筛选：每个都可单独移除，末尾是「清除筛选」 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveFilters(query: IssueQuery, onChange: (IssueQuery) -> Unit) {
    val g = Gh.c
    val active = query.activeFilters()
    if (active.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        // 每个生效条件是可点掉的输入胶囊，末尾是标准的文字按钮
        active.forEach { f ->
            InputChip(
                selected = true,
                onClick = { onChange(query.without(f)) },
                label = { Text(f.text, maxLines = 1) },
                trailingIcon = { Oc(R.drawable.oc_x, LocalContentColor.current, 14.dp) },
                modifier = Modifier.semantics { contentDescription = trf("移除筛选：{0}", f.text) },
            )
        }
        TextButton(onClick = { onChange(query.cleared()) }) { Text(tr("清除筛选")) }
    }
}

/**
 * 筛选结果列表：只有状态与排序时走仓库列表接口，否则走 Search API。
 * 空状态区分「没有内容」与「没有符合筛选的内容」。
 */
@Composable
fun IssueResults(query: IssueQuery, onChange: (IssueQuery) -> Unit, owner: String?, name: String?) {
    val nav = LocalNav.current
    val q = query.normalized()
    val noun = if (q.pulls) "Pull Request" else "Issue"
    val filtered = q.hasFilters
    val empty = when {
        filtered -> trf("没有符合筛选条件的 {0}", noun)
        q.involves == Involvement.Created -> trf("没有你创建的 {0}", noun)
        q.involves == Involvement.Assigned -> trf("没有指派给你的 {0}", noun)
        q.involves == Involvement.Mentioned -> trf("没有提及你的 {0}", noun)
        q.involves == Involvement.Involved -> trf("没有与你相关的 {0}", noun)
        q.involves == Involvement.ReviewRequested -> trf("没有请求你评审的 {0}", noun)
        q.state == IssueStateFilter.Open -> trf("没有开放的 {0}", noun)
        q.state == IssueStateFilter.Closed -> trf("没有已关闭的 {0}", noun)
        else -> trf("没有 {0}", noun)
    }
    val emptyIcon = if (filtered) R.drawable.oc_filter else if (q.pulls) R.drawable.oc_git_pull_request else R.drawable.oc_issue_opened
    val clear: (() -> Unit)? = if (filtered) ({ onChange(query.cleared()) }) else null
    val emptyAction = if (filtered) tr("清除筛选") else tr("刷新")
    val openIssue: (Issue) -> Unit = { issue ->
        val repo = issue.repositoryUrl?.substringAfter("/repos/")?.takeIf { it.contains('/') } ?: "$owner/$name"
        nav.push(Screen.IssueDetail(repo.substringBefore('/'), repo.substringAfter('/'), issue.number, issue.pullRequest != null))
    }
    val snackbar = retain("issue-results:snackbar") { SnackbarHostState() }
    Box(Modifier.fillMaxSize()) {
    when {
        q.needsSearch -> {
            val pager = rememberPager("search:${q.key()}") { p, f -> GitHub.searchIssues(q.searchQuery(), p, q.sort.field, q.sort.order, f).items }
            // 新建、关闭、评论等写入后列表自动刷新，不用手动下拉（跨仓库的列表监听任意仓库的变化）
            OnRepoChanged(owner ?: RepoWrite.ANY, name ?: RepoWrite.ANY, RepoWrite.ISSUES) { pager.refresh() }
            val close = rememberListCloser(pager, snackbar, key = { it.id }, message = { trf("已关闭 #{0}", it.number) }) { issue ->
                closeFromAnywhere(issue, owner, name)
            }
            PagedList(pager, empty = empty, emptyIcon = emptyIcon, emptyAction = emptyAction, onEmptyAction = clear, itemKey = { it.id }) { issue ->
                SwipeCloseRow(enabled = canCloseHere(issue.user?.login, issue.state), onClose = { close(issue) }) {
                    IssueItem(issue, repoName = if (q.repo == null) issue.repositoryUrl?.substringAfter("/repos/") else null) { openIssue(issue) }
                }
            }
        }
        owner == null || name == null -> {}
        q.pulls -> {
            val sort = if (q.sort == IssueSort.Updated) "updated" else "created"
            val pager = rememberPager("pulls:${q.key()}") { p, f -> GitHub.pulls(owner, name, q.state.api, p, f, sort, q.sort.order ?: "desc") }
            // 新建、关闭、评论等写入后列表自动刷新，不用手动下拉（跨仓库的列表监听任意仓库的变化）
            OnRepoChanged(owner ?: RepoWrite.ANY, name ?: RepoWrite.ANY, RepoWrite.ISSUES) { pager.refresh() }
            val close = rememberListCloser(pager, snackbar, key = { it.id }, message = { trf("已关闭 #{0}", it.number) }) { pr ->
                GitHub.setPullState(owner, name, pr.number, false)
            }
            PagedList(pager, empty = empty, emptyIcon = emptyIcon, emptyAction = emptyAction, onEmptyAction = clear, itemKey = { it.id }) { pr ->
                SwipeCloseRow(enabled = canCloseHere(pr.user?.login, pr.state), onClose = { close(pr) }) {
                    PullItem(pr) { nav.push(Screen.IssueDetail(owner, name, pr.number, true)) }
                }
            }
        }
        else -> {
            val pager = rememberPager("issues:${q.key()}") { p, f -> GitHub.issues(owner, name, q.state.api, p, f, q.sort.field ?: "created", q.sort.order ?: "desc") }
            // 新建、关闭、评论等写入后列表自动刷新，不用手动下拉（跨仓库的列表监听任意仓库的变化）
            OnRepoChanged(owner ?: RepoWrite.ANY, name ?: RepoWrite.ANY, RepoWrite.ISSUES) { pager.refresh() }
            val close = rememberListCloser(pager, snackbar, key = { it.id }, message = { trf("已关闭 #{0}", it.number) }) { issue ->
                GitHub.setIssueState(owner, name, issue.number, false)
            }
            // 与网页版一致：仓库置顶的 Issue（最多 3 个）显示在列表最上面；读取失败时不显示
            val pinned = rememberLoader("pinned-issues") { _ -> runCatching { GitHub.pinnedIssues(owner, name) }.getOrDefault(emptyList()) }
            OnRepoChanged(owner, name, RepoWrite.ISSUES) { pinned.refresh() }
            val pins = pinned.data.orEmpty().takeIf { !q.hasFilters && q.state != IssueStateFilter.Closed }.orEmpty()
            PagedList(
                pager, empty = empty, emptyIcon = emptyIcon, emptyAction = emptyAction, onEmptyAction = clear,
                itemFilter = { it.pullRequest == null }, itemKey = { it.id },
                header = {
                    if (pins.isNotEmpty()) item(key = "pinned") { PinnedIssues(pins) { n -> nav.push(Screen.IssueDetail(owner, name, n, false)) } }
                },
            ) { i ->
                SwipeCloseRow(enabled = canCloseHere(i.user?.login, i.state), onClose = { close(i) }) {
                    IssueItem(i) { nav.push(Screen.IssueDetail(owner, name, i.number, false)) }
                }
            }
        }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}

/** 仓库置顶的 Issue：横向卡片，点开详情 */
@Composable
private fun PinnedIssues(pins: List<GitHub.PinnedIssue>, onOpen: (Int) -> Unit) {
    val g = Gh.c
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Oc(R.drawable.oc_pin, g.fgMuted, 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(tr("置顶的 Issue"), color = g.fgMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pins.forEach { p ->
                Column(
                    Modifier.width(220.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, g.border, RoundedCornerShape(8.dp))
                        .clickable { onOpen(p.number) }.padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Oc(if (p.open) R.drawable.oc_issue_opened else R.drawable.oc_issue_closed, if (p.open) g.success else g.done, 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("#${p.number}", color = g.fgMuted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(p.title, color = g.fg, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HDivider()
    }
}

/** 只有自己创建、仍开放的 Issue / PR 才能在列表里左滑关闭；别人的请进详情页操作 */
private fun canCloseHere(author: String?, state: String): Boolean =
    state == "open" && author != null && author.equals(Session.login, ignoreCase = true)

/** 搜索结果可能跨仓库：优先用 repository_url 解析出真正的仓库，拿不到时回退到当前页面的仓库 */
private suspend fun closeFromAnywhere(issue: Issue, owner: String?, name: String?) {
    val full = issue.repositoryUrl?.substringAfter("/repos/")?.takeIf { it.contains('/') } ?: "${owner.orEmpty()}/${name.orEmpty()}"
    val o = full.substringBefore('/')
    val n = full.substringAfter('/')
    if (issue.pullRequest != null) GitHub.setPullState(o, n, issue.number, false)
    else GitHub.setIssueState(o, n, issue.number, false)
}

/** 左滑关闭：可关闭的行才挂手势，其余行保持普通点按 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeCloseRow(enabled: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val g = Gh.c
    val tick = rememberTick()
    val dismiss = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = { SwipeAction(R.drawable.oc_issue_closed, tr("关闭"), g.doneSubtle, g.done, atEnd = true) },
        onDismiss = { tick(); onClose() },
    ) {
        // 前景必须有不透明底色，否则左滑背景（关闭按钮）会透过行的空白处显示
        Box(Modifier.fillMaxWidth().background(g.canvas)) { content() }
    }
}

/**
 * 左滑关闭的提交流程：先从列表移走，撤销窗口结束后才真正请求 GitHub；
 * 窗口内点撤销则放回原位，请求失败也放回原位并提示。离开页面时仍会按时提交（与通知页一致）。
 */
@Composable
private fun <T> rememberListCloser(
    pager: Pager<T>,
    snackbar: SnackbarHostState,
    key: (T) -> Any,
    message: (T) -> String,
    close: suspend (T) -> Unit,
): (T) -> Unit {
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    return remember(pager, snackbar, entry, ctx, close) {
        val closer: (T) -> Unit = { item ->
            val id = key(item)
            val index = pager.items.indexOfFirst { key(it) == id }
            if (index >= 0) {
                pager.update { it.removeAt(index) }
                entry.scope.launch {
                    fun restore() = pager.update { list -> list.add(index.coerceAtMost(list.size), item) }
                    if (snackbar.offerUndo(message(item))) {
                        restore()
                        return@launch
                    }
                    try {
                        close(item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        restore()
                        ctx.toast(trf("关闭失败：{0}", e.friendly()))
                    }
                }
            }
        }
        closer
    }
}

/** 页面级的查询状态：保留在页面条目上，返回页面时筛选不丢失 */
@Composable
fun rememberIssueQuery(initial: IssueQuery): MutableState<IssueQuery> =
    com.mobilegh.nav.retain("issue-query") { mutableStateOf(initial.normalized()) }

/** 把当前筛选保存为首页「我的工作」中的快捷入口 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveSearchDialog(query: IssueQuery, defaultName: String, onDismiss: () -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    var name by remember { mutableStateOf(defaultName.take(HomeLayout.MAX_NAME)) }
    var icon by remember { mutableStateOf(if (query.pulls) "pr" else "issue") }
    val valid = name.isNotBlank() && name.trim().length <= HomeLayout.MAX_NAME
    GhDialog(tr("保存到首页"), onDismiss, confirm = tr("保存"), confirmEnabled = valid, onConfirm = {
        runCatching { HomePrefs.addSearch(name, icon, query) ?: error(tr("请先登录")) }
            .onSuccess { ctx.toast(tr("已添加到首页「我的工作」")); onDismiss() }
            .onFailure { ctx.toast(it.message ?: tr("保存失败")) }
    }) {
        Column {
            GhField(name, { name = it.take(HomeLayout.MAX_NAME + 8) }, tr("名称"))
            Spacer(Modifier.height(4.dp))
            Text(query.normalized().summary(), fontSize = 12.sp, color = g.fgMuted, maxLines = 3)
            Spacer(Modifier.height(12.dp))
            SavedSearchIconPicker(icon) { icon = it }
        }
    }
}

/** 保存的搜索的图标选择：固定的一组 Octicon */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedSearchIconPicker(icon: String, onPick: (String) -> Unit) {
    val g = Gh.c
    Column {
        Text(tr("图标"), fontSize = 13.sp, color = g.fgMuted)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SAVED_SEARCH_ICONS.forEach { k ->
                val sel = k == icon
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (sel) g.accentSubtle else g.canvasSubtle)
                        .border(1.dp, if (sel) g.accent else g.borderMuted, RoundedCornerShape(10.dp))
                        .clickable(role = Role.RadioButton) { onPick(k) },
                    contentAlignment = Alignment.Center,
                ) { Oc(savedSearchIcon(k), if (sel) g.accent else g.fgMuted, 18.dp) }
            }
        }
    }
}
