package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.History
import com.mobilegh.data.SEARCH_LANGUAGES
import com.mobilegh.data.SearchFilters
import com.mobilegh.data.SearchHistoryEntry
import com.mobilegh.data.SearchQuery
import com.mobilegh.data.SearchType
import com.mobilegh.nav.Links
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.ActiveFilterChips
import com.mobilegh.ui.components.ChoicePicker
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.FilterEntry
import com.mobilegh.ui.components.DropdownButton
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.IssueItem
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.RepoItem
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.UserItem
import com.mobilegh.ui.theme.Gh

/**
 * 独立的搜索大页面：类型切换、关键词、组合筛选、结果都在同一页。
 * 搜索历史只在这里出现，不占用探索页空间。
 */
@Composable
fun SearchScreen(initialType: Int = SearchType.REPO, initialQuery: String = "") {
    val nav = LocalNav.current
    val g = Gh.c
    val focus = LocalFocusManager.current

    var type by rememberSaveable { mutableIntStateOf(initialType.coerceIn(0, SearchType.labels.lastIndex)) }
    var input by rememberSaveable { mutableStateOf(initialQuery) }
    // 只有点击搜索（或键盘搜索/历史）才提交，输入过程中不触发请求
    var submitted by rememberSaveable { mutableStateOf(initialQuery.trim()) }
    var filters by rememberSaveable(stateSaver = SearchFiltersSaver) { mutableStateOf(SearchFilters()) }
    var filterDialog by rememberSaveable { mutableStateOf(false) }

    fun submit(text: String = input) {
        val clean = text.trim()
        focus.clearFocus()
        // 粘贴 GitHub 链接时直接打开对应页面
        val link = Links.fromInput(clean)
        if (link != null) {
            input = clean
            submitted = ""
            nav.push(link)
            return
        }
        input = clean
        submitted = clean
        if (SearchQuery.hasCriteria(type, clean, filters)) {
            History.search(SearchQuery.build(type, clean, filters, includeType = false), type)
        }
    }

    val query = SearchQuery.build(type, submitted, filters)
    val ready = SearchQuery.hasCriteria(type, submitted, filters)
    val listState = rememberLazyListState()

    Page("搜索", back = true) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            SearchBar(
                input = input,
                type = type,
                count = filters.activeCount(type),
                onInput = { input = it },
                onClearInput = { input = ""; submitted = "" },
                onSubmit = { submit() },
                onOpenFilters = { filterDialog = true },
            )
            TypeTabs(type) { type = it }
            if (filters.hasActive(type)) {
                ActiveFilterChips(
                    chips = filters.activeChips(type),
                    onRemove = { key ->
                        val next = filters.remove(type, key)
                        filters = next
                        if (SearchQuery.hasCriteria(type, submitted, next)) {
                            History.search(SearchQuery.build(type, submitted, next, includeType = false), type)
                        }
                    },
                    onClear = { filters = filters.cleared(type) },
                )
            }
            HDivider()
            when {
                !ready -> HintPane(type,
                    onHistory = { entry ->
                        focus.clearFocus()
                        input = entry.query
                        type = entry.type.coerceIn(0, SearchType.labels.lastIndex)
                        filters = filters.cleared(type)
                        submitted = entry.query
                        History.search(entry.query, type)
                    },
                    onExample = { input = it },
                )
                type == SearchType.REPO -> RepoResults(query, listState)
                type == SearchType.USER -> UserResults(query, listState)
                type == SearchType.CODE -> CodeResults(query, listState)
                else -> IssueResults(query, type == SearchType.PR, listState)
            }
        }
    }

    if (filterDialog) {
        SearchFilterDialog(type, filters, {
            filters = it
            filterDialog = false
            focus.clearFocus()
            if (SearchQuery.hasCriteria(type, submitted, it)) {
                History.search(SearchQuery.build(type, submitted, it, includeType = false), type)
            }
        }) { filterDialog = false }
    }
    // 新关键词或筛选条件都从首条结果展示，避免沿用上次搜索的滚动位置
    LaunchedEffect(type, query) { listState.scrollToItem(0) }
}

private fun placeholder(type: Int): String = when (type) {
    SearchType.REPO -> "关键词，如 android compose"
    SearchType.USER -> "用户名或昵称"
    SearchType.ISSUE, SearchType.PR -> "标题、正文或评论中的关键词（可留空只用筛选）"
    else -> "代码内容，如 fun main"
}

/** 顶部单行搜索栏：输入框和筛选入口同一行，不再各占一行 */
@Composable
private fun SearchBar(
    input: String,
    type: Int,
    count: Int,
    onInput: (String) -> Unit,
    onClearInput: () -> Unit,
    onSubmit: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhField(
            input, onInput, "搜索 ${SearchType.labels[type]}",
            Modifier.weight(1f),
            placeholder = placeholder(type),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            leading = { Oc(R.drawable.oc_search, g.fgMuted, 18.dp) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (input.isNotEmpty()) {
                        OcButton(R.drawable.oc_x, onClearInput, g.fgMuted, description = "清空关键词")
                    }
                    OcButton(R.drawable.oc_arrow_right, { onSubmit() }, g.accent, description = "搜索")
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        FilterEntry(count, onOpenFilters)
    }
}

/** 类型切换：单行等分，选中项实底，和下方的条件标签区分开 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeTabs(type: Int, onSelect: (Int) -> Unit) {
    val g = Gh.c
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SearchType.labels.forEachIndexed { i, label ->
            val sel = i == type
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel) g.btnPrimary else g.canvasSubtle, RoundedCornerShape(6.dp))
                    .border(1.dp, if (sel) g.btnPrimary else g.border, RoundedCornerShape(6.dp))
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .heightIn(min = 36.dp)
                    .semantics { selected = sel },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    Modifier.padding(vertical = 8.dp),
                    fontSize = 13.sp,
                    maxLines = 1,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) Color.White else g.fg,
                )
            }
        }
    }
}

/** 没有关键词也没有筛选时的引导：搜索历史 + 可点的语法示例 */
@Composable
private fun HintPane(type: Int, onHistory: (SearchHistoryEntry) -> Unit, onExample: (String) -> Unit) {
    val g = Gh.c
    val examples = when (type) {
        SearchType.REPO -> listOf("language:Kotlin stars:>1000", "topic:android", "pushed:>2026-01-01")
        SearchType.USER -> listOf("location:China followers:>100", "type:org", "language:Rust")
        SearchType.ISSUE, SearchType.PR -> listOf("is:open label:bug", "comments:>10", "author:octocat")
        else -> listOf("repo:owner/name fun main", "path:src fun main", "language:Kotlin fun main")
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Oc(R.drawable.oc_search, g.fgMuted, 32.dp)
        Spacer(Modifier.height(12.dp))
        Text("输入关键词后点搜索", color = g.fgMuted)
        Spacer(Modifier.height(6.dp))
        Text(
            if (type == SearchType.CODE) "代码搜索必须有关键词，筛选条件只用来收窄范围"
            else "也可以用「筛选」组合条件",
            color = g.fgMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        // 点示例只填入输入框，由用户确认后再点搜索
        FlowExamples(examples, onExample)
        Spacer(Modifier.height(18.dp))
        SearchHistorySection(History.data().searches, onHistory, History::removeSearch, History::clearSearches)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowExamples(examples: List<String>, onPick: (String) -> Unit) {
    val g = Gh.c
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        examples.forEach { text ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(g.canvasSubtle)
                    .border(1.dp, g.border, RoundedCornerShape(50))
                    .clickable { onPick(text) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(text, fontSize = 12.sp, color = g.fgMuted, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun RepoResults(query: String, state: LazyListState) {
    val g = Gh.c
    var sort by rememberSaveable(query) { mutableIntStateOf(0) }
    val sorts = listOf(null to "最佳匹配", "stars" to "最多 Star", "updated" to "最近更新", "forks" to "最多 Fork")
    val pager = rememberPager("search-repo:$query:$sort") { p, _ -> GitHub.searchRepos(query, sorts[sort].first, p).items }
    PagedList(pager, state = state, empty = "没有找到匹配的仓库", emptyIcon = R.drawable.oc_repo, header = {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("仓库", Modifier.weight(1f), fontSize = 13.sp, color = g.fgMuted)
                Dropdown(sorts[sort].second, sorts.map { it.second }, { sort = it }, active = sort != 0)
            }
            HDivider()
        }
    }) { RepoItem(it) }
}

@Composable
private fun UserResults(query: String, state: LazyListState) {
    val pager = rememberPager("search-user:$query") { p, _ -> GitHub.searchUsers(query, p).items }
    PagedList(pager, state = state, empty = "没有找到匹配的用户", emptyIcon = R.drawable.oc_people) { UserItem(it) }
}

@Composable
private fun CodeResults(query: String, state: LazyListState) {
    val nav = LocalNav.current
    var total by rememberSaveable(query) { mutableIntStateOf(0) }
    var incomplete by rememberSaveable(query) { mutableStateOf(false) }
    val pager = rememberPager("search-code:$query") { page, force ->
        com.mobilegh.data.CodeReading.search(query, page, force).also {
            total = it.totalCount
            incomplete = it.incompleteResults
        }.items
    }
    PagedList(
        pager, state = state,
        itemKey = { "${it.repository.fullName}:${it.path}" },
        empty = "没有找到匹配的代码。代码搜索需要仓库有代码索引，可尝试加上 repo: 或 language: 缩小范围",
        emptyIcon = R.drawable.oc_code,
        header = {
            item {
                Text(
                    "搜索默认分支中的代码" + if (total > 0) " · $total 个结果" else "",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Gh.c.fgMuted, fontSize = 12.sp,
                )
                if (total > 0 && (incomplete || total > 1000)) {
                    Text(
                        "GitHub 只返回部分结果，请用 repo:、path: 或 language: 缩小范围",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.attention, fontSize = 12.sp,
                    )
                }
            }
        },
    ) { hit ->
        Column(Modifier.fillMaxWidth().clickable {
            val target = Links.route(hit.htmlUrl) ?: Screen.FileView(hit.repository.owner.login, hit.repository.name, hit.path, hit.repository.defaultBranch)
            nav.push(target)
        }.padding(16.dp)) {
            Text(hit.repository.fullName.ifBlank { "${hit.repository.owner.login}/${hit.repository.name}" }, color = Gh.c.fgMuted, fontSize = 12.sp)
            Text(hit.path, color = Gh.c.accent, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            hit.textMatches.firstOrNull()?.fragment?.let {
                Text(it.take(1200), Modifier.padding(top = 8.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Gh.c.fg, maxLines = 7, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Issue / PR 结果：复用现有 IssueItem，点击进入详情 */
@Composable
private fun IssueResults(query: String, pulls: Boolean, state: LazyListState) {
    val nav = LocalNav.current
    val pager = rememberPager("search-${if (pulls) "pr" else "issue"}:$query") { p, _ ->
        GitHub.searchIssues(query, p, if (pulls) "updated" else "created", "desc").items
    }
    PagedList(pager, state = state, itemKey = { it.id }, empty = "没有找到匹配的${if (pulls) " PR" else " Issue"}",
        emptyIcon = if (pulls) R.drawable.oc_git_pull_request else R.drawable.oc_issue_opened) { issue ->
        val repo = issue.repositoryUrl?.substringAfter("/repos/") ?: ""
        IssueItem(issue, repoName = repo) {
            val (o, r) = repo.split('/').let { it[0] to it.getOrElse(1) { "" } }
            if (o.isNotEmpty() && r.isNotEmpty()) nav.push(Screen.IssueDetail(o, r, issue.number, pulls))
        }
    }
}

/**
 * 组合筛选：底部工作表，字段按搜索类型分组，应用后立即重新搜索。
 * 比 AlertDialog 更适合多字段表单，输入时也不会被窄弹窗挤成一列长条。
 */
@Composable
internal fun SearchFilterDialog(type: Int, initial: SearchFilters, onApply: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    val g = Gh.c
    var f by rememberSaveable(type, stateSaver = SearchFiltersSaver) { mutableStateOf(initial) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = g.canvas,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("筛选 · ${SearchType.labels[type]}", color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("条件之间为「并且」，应用后立即收窄结果", color = g.fgMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                OcButton(R.drawable.oc_x, onDismiss, description = "关闭筛选")
            }
            HDivider(Modifier.padding(top = 8.dp))
            // 滚动区用 weight 而不是估算的固定高度：弹层高度被键盘或矮屏压缩时，
            // 「应用」按钮仍然在屏幕内，只有中间内容滚动
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterGroup("范围") {
                    when (type) {
                        SearchType.REPO -> FilterField("归属用户或组织", f.repoOwner, { f = f.copy(repoOwner = it) }, "如 JetBrains")
                        SearchType.USER -> ChoicePicker("账号类型", listOf("不限", "用户", "组织"), f.userType) { f = f.copy(userType = it) }
                        SearchType.CODE -> {
                            FilterField("仓库", f.codeRepo, { f = f.copy(codeRepo = it) }, "owner/name，可留空搜索全站")
                            FilterField("归属用户或组织", f.codeOwner, { f = f.copy(codeOwner = it) }, "未填写仓库时生效")
                        }
                        else -> {
                            FilterField("仓库", f.issueRepo, { f = f.copy(issueRepo = it) }, "owner/name")
                            FilterField("归属用户或组织", f.issueOwner, { f = f.copy(issueOwner = it) }, "未填写仓库时生效")
                        }
                    }
                }
                FilterGroup("属性") {
                    when (type) {
                        SearchType.REPO -> {
                            LanguagePicker(f.repoLanguage) { f = f.copy(repoLanguage = it) }
                            FilterField("Topic", f.topic, { f = f.copy(topic = it) }, "单个 topic，如 android")
                            ChoicePicker("归档状态", listOf("不限", "仅归档", "排除归档"), f.archived) { f = f.copy(archived = it) }
                        }
                        SearchType.USER -> {
                            FilterField("所在地", f.location, { f = f.copy(location = it) }, "如 China / Tokyo")
                            LanguagePicker(f.userLanguage) { f = f.copy(userLanguage = it) }
                        }
                        SearchType.CODE -> {
                            LanguagePicker(f.codeLanguage) { f = f.copy(codeLanguage = it) }
                            FilterField("路径", f.path, { f = f.copy(path = it) }, "如 src/main")
                            FilterField("文件名", f.filename, { f = f.copy(filename = it) }, "如 build.gradle")
                        }
                        else -> {
                            ChoicePicker("状态", listOf("不限", "开放", "已关闭"), f.state) { f = f.copy(state = it) }
                            FilterField("作者", f.author, { f = f.copy(author = it) }, "GitHub 用户名")
                            FilterField("标签", f.labels, { f = f.copy(labels = it) }, "多个用英文逗号分隔")
                            LanguagePicker(f.issueLanguage) { f = f.copy(issueLanguage = it) }
                        }
                    }
                }
                // 代码搜索没有数量/时间条件，空分组会画出空标题，直接省略
                if (type != SearchType.CODE) FilterGroup("数量与时间") {
                    when (type) {
                        SearchType.REPO -> {
                            NumberPicker("最少 Star", f.minStars, listOf(0, 10, 100, 500, 1000, 5000, 10000)) { f = f.copy(minStars = it) }
                            NumberPicker("最少 Fork", f.minForks, listOf(0, 10, 50, 100, 500, 1000)) { f = f.copy(minForks = it) }
                        }
                        SearchType.USER -> NumberPicker("最少关注者", f.minFollowers, listOf(0, 10, 50, 100, 1000)) { f = f.copy(minFollowers = it) }
                        else -> {
                            NumberPicker("最少评论", f.minComments, listOf(0, 5, 10, 20, 50)) { f = f.copy(minComments = it) }
                            NumberPicker("更新时间", f.updatedDays, listOf(0, 7, 30, 90, 365), unit = "天内") { f = f.copy(updatedDays = it) }
                        }
                    }
                }
            }
            HDivider()
            // 三个按钮等宽排布，窄屏和大字体下也不会错落换行
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhButton("重置", Modifier.weight(1f), enabled = f.hasActive(type)) { f = f.cleared(type) }
                GhButton("取消", Modifier.weight(1f)) { onDismiss() }
                GhButton("应用" + if (f.activeCount(type) > 0) " · ${f.activeCount(type)}" else "", Modifier.weight(1.3f), primary = true) {
                    onApply(f)
                }
            }
        }
    }
}

/** 筛选分区：标题 + 控件。不再套一层卡片，避免和输入框自身的边框叠成双层 */
@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    val g = Gh.c
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(title, color = g.fgMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun FilterField(label: String, value: String, onChange: (String) -> Unit, placeholder: String) {
    GhField(value, onChange, label, placeholder = placeholder, labelAbove = true)
}

/** 一行标签 + 下拉，输入类字段和枚举类字段用同一套视觉结构 */
@Composable
private fun PickerRow(label: String, value: String, options: List<String>, onSelect: (Int) -> Unit, active: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = Gh.c.fg)
        // 读屏和测试能按字段名找到下拉，而不是只读到当前值
        Dropdown(value, options, onSelect, Modifier.semantics { contentDescription = "$label：$value" }, active = active)
    }
}

@Composable
private fun LanguagePicker(value: String, onSelect: (String) -> Unit) {
    // 语言候选太多，用下拉而不是平铺 chips；用 language:xxx 输入了列表外的语言时也如实显示
    val custom = value.isNotBlank() && value !in SEARCH_LANGUAGES
    val options = listOf("不限") + (if (custom) listOf(value) else emptyList()) + SEARCH_LANGUAGES
    PickerRow("语言", if (value.isBlank()) "不限" else value, options, { onSelect(if (it == 0) "" else options[it]) }, value.isNotBlank())
}

/** 数量阈值用下拉，避免七八个胶囊平铺把弹层撑得又长又密 */
@Composable
private fun NumberPicker(label: String, value: Int, options: List<Int>, unit: String = "以上", onSelect: (Int) -> Unit) {
    val texts = options.map { if (it == 0) "不限" else "$it $unit" }
    PickerRow(label, texts[options.indexOf(value).coerceAtLeast(0)], listOf("不限") + texts.drop(1), { onSelect(if (it == 0) 0 else options[it]) }, value > 0)
}

private val SearchFiltersSaver = androidx.compose.runtime.saveable.listSaver<SearchFilters, Any>(
    save = { f -> listOf(f.repoOwner, f.repoLanguage, f.minStars, f.minForks, f.topic, f.archived,
        f.userType, f.location, f.minFollowers, f.userLanguage,
        f.state, f.author, f.labels, f.minComments, f.issueRepo, f.issueOwner, f.issueLanguage, f.updatedDays,
        f.codeRepo, f.codeOwner, f.codeLanguage, f.path, f.filename) },
    restore = { v -> SearchFilters(
        repoOwner = v[0] as String, repoLanguage = v[1] as String, minStars = v[2] as Int, minForks = v[3] as Int,
        topic = v[4] as String, archived = v[5] as Int,
        userType = v[6] as Int, location = v[7] as String, minFollowers = v[8] as Int, userLanguage = v[9] as String,
        state = v[10] as Int, author = v[11] as String, labels = v[12] as String, minComments = v[13] as Int,
        issueRepo = v[14] as String, issueOwner = v[15] as String, issueLanguage = v[16] as String, updatedDays = v[17] as Int,
        codeRepo = v[18] as String, codeOwner = v[19] as String, codeLanguage = v[20] as String,
        path = v[21] as String, filename = v[22] as String,
    ) },
)
