package com.mobilegh.ui.screens

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
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
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.DropdownButton
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
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.delay

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
        GhField(
            input, { input = it }, "搜索 $noun",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            placeholder = "标题、正文或评论中的关键词",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onChange(query.copy(text = input.trim())) }),
            trailing = if (input.isNotEmpty()) ({ OcButton(R.drawable.oc_x, { input = ""; onChange(query.copy(text = "")) }, g.fgMuted) }) else null,
        )
        Chips(IssueStateFilter.entries.map { it.label }, IssueStateFilter.entries.indexOf(query.state)) { onChange(query.copy(state = IssueStateFilter.entries[it])) }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Dropdown("排序：${query.sort.label}", IssueSort.entries.map { it.label }, { onChange(query.copy(sort = IssueSort.entries[it])) }, active = query.sort != IssueSort.Newest)
            if (owner != null && name != null) {
                DropdownButton(query.author?.let { "作者：$it" } ?: "作者", active = query.author != null) { dialog = FilterDialog.Author }
                DropdownButton(query.assignee?.let { "指派人：$it" } ?: "指派人", active = query.assignee != null) { dialog = FilterDialog.Assignee }
                DropdownButton(if (query.labels.isEmpty()) "标签" else "标签 · ${query.labels.size}", active = query.labels.isNotEmpty()) { dialog = FilterDialog.Labels }
                DropdownButton(query.milestone?.let { "里程碑：$it" } ?: "里程碑", active = query.milestone != null) { dialog = FilterDialog.Milestone }
            }
            if (query.pulls) {
                val reviews = listOf<ReviewFilter?>(null) + ReviewFilter.entries
                Dropdown(query.review?.label ?: "评审状态", reviews.map { it?.label ?: "不限" }, { onChange(query.copy(review = reviews[it])) }, active = query.review != null)
                val drafts = listOf(null, true, false)
                Dropdown(
                    when (query.draft) { true -> "仅草稿"; false -> "排除草稿"; null -> "草稿" },
                    listOf("不限", "仅草稿", "排除草稿"), { onChange(query.copy(draft = drafts[it])) }, active = query.draft != null,
                )
            }
        }
        ActiveFilters(query, onChange)
        HDivider()
    }

    if (owner != null && name != null) when (dialog) {
        FilterDialog.Author -> MetaPicker(
            "assignees", owner, name, "按作者筛选", listOfNotNull(query.author), { dialog = null },
            single = true, noneLabel = "不限", freeText = true,
        ) { onChange(query.copy(author = it.firstOrNull()?.key)) }
        FilterDialog.Assignee -> MetaPicker(
            "assignees", owner, name, "按指派人筛选", listOfNotNull(query.assignee), { dialog = null },
            single = true, noneLabel = "不限", freeText = true,
        ) { onChange(query.copy(assignee = it.firstOrNull()?.key)) }
        FilterDialog.Labels -> MetaPicker("labels", owner, name, "按标签筛选", query.labels, { dialog = null }, single = false) {
            onChange(query.copy(labels = it.map { o -> o.key }.take(IssueQuery.MAX_LABELS)))
        }
        FilterDialog.Milestone -> MetaPicker(
            "milestone", owner, name, "按里程碑筛选", listOfNotNull(query.milestone), { dialog = null },
            single = true, noneLabel = "不限", milestoneByTitle = true,
        ) { onChange(query.copy(milestone = it.firstOrNull()?.key)) }
        null -> {}
    }
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
        active.forEach { f ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(g.accentSubtle)
                    .border(1.dp, g.accent.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .clickable(role = Role.Button) { onChange(query.without(f)) }
                    .semantics(mergeDescendants = true) { contentDescription = "移除筛选：${f.text}" }
                    .padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(f.text, fontSize = 12.sp, color = g.accent, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                Oc(R.drawable.oc_x, g.accent, 12.dp)
            }
        }
        TextLink("清除筛选") { onChange(query.cleared()) }
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
        filtered -> "没有符合筛选条件的 $noun"
        q.involves == Involvement.Created -> "没有你创建的 $noun"
        q.involves == Involvement.Assigned -> "没有指派给你的 $noun"
        q.involves == Involvement.Mentioned -> "没有提及你的 $noun"
        q.involves == Involvement.ReviewRequested -> "没有请求你评审的 $noun"
        q.state == IssueStateFilter.Open -> "没有开放的 $noun"
        q.state == IssueStateFilter.Closed -> "没有已关闭的 $noun"
        else -> "没有 $noun"
    }
    val emptyIcon = if (filtered) R.drawable.oc_filter else if (q.pulls) R.drawable.oc_git_pull_request else R.drawable.oc_issue_opened
    val clear: (() -> Unit)? = if (filtered) ({ onChange(query.cleared()) }) else null
    val emptyAction = if (filtered) "清除筛选" else "刷新"
    val openIssue: (Issue) -> Unit = { issue ->
        val repo = issue.repositoryUrl?.substringAfter("/repos/")?.takeIf { it.contains('/') } ?: "$owner/$name"
        nav.push(Screen.IssueDetail(repo.substringBefore('/'), repo.substringAfter('/'), issue.number, issue.pullRequest != null))
    }
    when {
        q.needsSearch -> {
            val pager = rememberPager("search:${q.key()}") { p, f -> GitHub.searchIssues(q.searchQuery(), p, q.sort.field, q.sort.order, f).items }
            PagedList(pager, empty = empty, emptyIcon = emptyIcon, emptyAction = emptyAction, onEmptyAction = clear, itemKey = { it.id }) { issue ->
                IssueItem(issue, repoName = if (q.repo == null) issue.repositoryUrl?.substringAfter("/repos/") else null) { openIssue(issue) }
            }
        }
        owner == null || name == null -> {}
        q.pulls -> {
            val sort = if (q.sort == IssueSort.Updated) "updated" else "created"
            val pager = rememberPager("pulls:${q.key()}") { p, f -> GitHub.pulls(owner, name, q.state.api, p, f, sort, q.sort.order) }
            PagedList(pager, empty = empty, emptyIcon = emptyIcon, emptyAction = emptyAction, onEmptyAction = clear, itemKey = { it.id }) { pr ->
                PullItem(pr) { nav.push(Screen.IssueDetail(owner, name, pr.number, true)) }
            }
        }
        else -> {
            val pager = rememberPager("issues:${q.key()}") { p, f -> GitHub.issues(owner, name, q.state.api, p, f, q.sort.field, q.sort.order) }
            PagedList(
                pager, empty = empty, emptyIcon = emptyIcon, emptyAction = emptyAction, onEmptyAction = clear,
                itemFilter = { it.pullRequest == null }, itemKey = { it.id },
            ) { i -> IssueItem(i) { nav.push(Screen.IssueDetail(owner, name, i.number, false)) } }
        }
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
    GhDialog("保存到首页", onDismiss, confirm = "保存", confirmEnabled = valid, onConfirm = {
        runCatching { HomePrefs.addSearch(name, icon, query) ?: error("请先登录") }
            .onSuccess { ctx.toast("已添加到首页「我的工作」"); onDismiss() }
            .onFailure { ctx.toast(it.message ?: "保存失败") }
    }) {
        Column {
            GhField(name, { name = it.take(HomeLayout.MAX_NAME + 8) }, "名称")
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
        Text("图标", fontSize = 13.sp, color = g.fgMuted)
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
