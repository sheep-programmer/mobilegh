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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.ApiException
import com.mobilegh.data.GitHub
import com.mobilegh.data.History
import com.mobilegh.data.IssueSearchSort
import com.mobilegh.data.SEARCH_LANGUAGES
import com.mobilegh.data.SearchFilterCodec
import com.mobilegh.data.SearchFilters
import com.mobilegh.data.SearchHistoryEntry
import com.mobilegh.data.SearchQuery
import com.mobilegh.data.SearchType
import com.mobilegh.nav.GITHUB_SEARCH_RESULT_CAP
import com.mobilegh.nav.Links
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.PagerPage
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.searchHasNextPage
import com.mobilegh.nav.searchTotalPages
import com.mobilegh.ui.components.ActiveFilterChips
import com.mobilegh.ui.components.ChoicePicker
import com.mobilegh.ui.components.CompactSearchField
import com.mobilegh.ui.components.FieldAction
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.FilterCard
import com.mobilegh.ui.components.FilterEntry
import com.mobilegh.ui.components.FilterSheet
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
import com.mobilegh.ui.components.SearchText
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.UserItem
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trc
import com.mobilegh.i18n.trf
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton

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
    // 排序与关键词分开保存：改关键词、改筛选都不会把排序重置回默认
    var repoSort by rememberSaveable { mutableIntStateOf(0) }
    var issueSort by rememberSaveable(type) { mutableIntStateOf(0) }

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
        recordSearch(type, clean, filters)
    }

    val query = SearchQuery.build(type, submitted, filters)
    val ready = SearchQuery.hasCriteria(type, submitted, filters)
    val listState = rememberLazyListState()

    Page(tr("搜索"), back = true) { pad ->
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
                        recordSearch(type, submitted, next)
                    },
                    onClear = { filters = filters.cleared(type) },
                )
            }
            HDivider()
            when {
                !ready -> HintPane(type,
                    onHistory = { entry ->
                        focus.clearFocus()
                        type = entry.type.coerceIn(0, SearchType.labels.lastIndex)
                        // 新版历史保存了纯关键词和筛选：关键词框只放关键词，筛选恢复到面板；旧版历史保持原样回填
                        val restored = SearchFilterCodec.decode(entry.filters)
                        if (restored != null) {
                            input = entry.keyword
                            filters = restored
                            submitted = entry.keyword
                        } else {
                            input = entry.query
                            filters = filters.cleared(type)
                            submitted = entry.query
                        }
                        History.search(entry.query, type, entry.keyword, entry.filters)
                    },
                    onExample = { input = it },
                )
                type == SearchType.REPO -> RepoResults(query, listState, repoSort) { repoSort = it }
                type == SearchType.USER -> UserResults(query, listState)
                type == SearchType.CODE -> CodeResults(query, listState)
                else -> IssueResults(query, type == SearchType.PR, listState, issueSort) { issueSort = it }
            }
        }
    }

    if (filterDialog) {
        SearchFilterDialog(type, filters, {
            filters = it
            filterDialog = false
            focus.clearFocus()
            recordSearch(type, submitted, it)
        }) { filterDialog = false }
    }
    // 新关键词或筛选条件都从首条结果展示，避免沿用上次搜索的滚动位置
    LaunchedEffect(type, query) { listState.scrollToItem(0) }
}

private fun placeholder(type: Int): String = when (type) {
    SearchType.REPO -> tr("关键词，如 android compose")
    SearchType.USER -> tr("用户名或昵称")
    SearchType.ISSUE, SearchType.PR -> tr("标题、正文或评论中的关键词（可留空只用筛选）")
    else -> tr("代码内容，如 fun main")
}

/** 顶部搜索栏：标准的圆角输入框（键盘搜索键即提交），右侧是筛选按钮 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // 从示例、历史或别的页面带关键词进来时，光标要停在末尾，方便直接接着输入
    var field by remember { mutableStateOf(TextFieldValue(input, TextRange(input.length))) }
    LaunchedEffect(input) {
        if (field.text != input) field = TextFieldValue(input, TextRange(input.length))
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = field,
            onValueChange = {
                field = it
                onInput(it.text)
            },
            modifier = Modifier.weight(1f).semantics { contentDescription = trf("搜索 {0}", SearchType.labels[type]) },
            placeholder = { Text(placeholder(type), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Oc(R.drawable.oc_search, MaterialTheme.colorScheme.onSurfaceVariant, 20.dp) },
            trailingIcon = {
                if (input.isNotEmpty()) IconButton(
                    onClick = onClearInput,
                    modifier = Modifier.semantics { contentDescription = tr("清空关键词") },
                ) { Oc(R.drawable.oc_x, MaterialTheme.colorScheme.onSurfaceVariant, 18.dp) }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
        Spacer(Modifier.width(8.dp))
        FilterEntry(count, onOpenFilters)
    }
}

/** 类型切换：标准的可横向滚动标签栏，选中项用下划线标出 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeTabs(type: Int, onSelect: (Int) -> Unit) {
    PrimaryScrollableTabRow(selectedTabIndex = type, edgePadding = 12.dp) {
        SearchType.labels.forEachIndexed { i, label ->
            Tab(
                selected = i == type,
                onClick = { onSelect(i) },
                selectedContentColor = Gh.c.accent,
                unselectedContentColor = Gh.c.fgMuted,
                text = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** 记录一次搜索：完整查询用于显示，关键词和筛选分开存，回填时才能恢复到筛选面板。 */
private fun recordSearch(type: Int, keyword: String, filters: SearchFilters) {
    if (!SearchQuery.hasCriteria(type, keyword, filters)) return
    History.search(SearchQuery.build(type, keyword, filters, includeType = false), type, keyword, SearchFilterCodec.encode(filters))
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
        Text(tr("输入关键词后点搜索"), color = g.fgMuted)
        Spacer(Modifier.height(6.dp))
        Text(
            if (type == SearchType.CODE) tr("代码搜索必须有关键词，筛选条件只用来收窄范围")
            else tr("也可以用「筛选」组合条件"),
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
private fun RepoResults(query: String, state: LazyListState, sort: Int, onSort: (Int) -> Unit) {
    val g = Gh.c
    val sorts = listOf(null to tr("最佳匹配"), "stars" to tr("最多 Star"), "updated" to tr("最近更新"), "forks" to tr("最多 Fork"))
    val index = sort.coerceIn(0, sorts.lastIndex)
    // 服务端报告的总数：超过 GitHub 搜索的 1000 条上限时在列表顶部说明，不再请求超出的页
    var total by rememberSaveable(query, index) { mutableIntStateOf(0) }
    val pager = rememberPager("search-repo:$query:$index", fetchPage = { p, force ->
        val result = try {
            GitHub.searchRepos(query, sorts[index].first, p, force)
        } catch (e: ApiException) {
            // 超出上限的页会被服务端以 422 拒绝：给出明确说明，而不是通用的加载失败
            if (e.code == 422 && p > 1) throw ApiException(422, tr("GitHub 搜索最多返回 1000 条结果，请缩小关键词或使用筛选")) else throw e
        }
        PagerPage(
            result.items,
            hasMore = searchHasNextPage(p, GitHub.PER, result.totalCount),
            totalPages = searchTotalPages(GitHub.PER, result.totalCount),
        ) { total = result.totalCount }
    })
    PagedList(pager, state = state, empty = tr("没有找到匹配的仓库"), emptyIcon = R.drawable.oc_repo, header = {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("仓库"), Modifier.weight(1f), fontSize = 13.sp, color = g.fgMuted)
                Dropdown(sorts[index].second, sorts.map { it.second }, onSort, active = index != 0)
            }
            HDivider()
        }
        if (total > GITHUB_SEARCH_RESULT_CAP) item {
            Text(
                tr("GitHub 搜索最多返回 1000 条结果，请缩小关键词或使用筛选"),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = g.attention, fontSize = 12.sp,
            )
        }
    }) { RepoItem(it) }
}

/** Issue / PR 搜索的排序名称，与 [IssueSearchSort] 的 key 对应 */
private fun issueSortLabel(key: String): String = when (key) {
    IssueSearchSort.RELEVANCE -> tr("相关")
    "created" -> tr("最近创建")
    "updated" -> tr("最近更新")
    "comments" -> tr("评论最多")
    "reactions" -> tr("反应最多")
    "interactions" -> tr("互动最多")
    else -> key
}

@Composable
private fun UserResults(query: String, state: LazyListState) {
    val pager = rememberPager("search-user:$query") { p, _ -> GitHub.searchUsers(query, p).items }
    PagedList(pager, state = state, empty = tr("没有找到匹配的用户"), emptyIcon = R.drawable.oc_people) { UserItem(it) }
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
        empty = tr("没有找到匹配的代码。代码搜索需要仓库有代码索引，可尝试加上 repo: 或 language: 缩小范围"),
        emptyIcon = R.drawable.oc_code,
        header = {
            item {
                Text(
                    tr("搜索默认分支中的代码") + if (total > 0) trf(" · {0} 个结果", total) else "",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Gh.c.fgMuted, fontSize = 12.sp,
                )
                if (total > 0 && (incomplete || total > 1000)) {
                    Text(
                        tr("GitHub 只返回部分结果，请用 repo:、path: 或 language: 缩小范围"),
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.attention, fontSize = 12.sp,
                    )
                }
            }
        },
    ) { hit ->
        Column(Modifier.fillMaxWidth().clickable {
            val target = Links.route(hit.htmlUrl) ?: Screen.FileView(hit.repository.owner.login, hit.repository.name, hit.path, hit.repository.defaultBranch)
            nav.pushLink(target)
        }.padding(16.dp)) {
            // 命中的词加底色，限定符（repo:/language:）不参与高亮
            val mark = Gh.c.attention.copy(alpha = 0.32f)
            Text(hit.repository.fullName.ifBlank { "${hit.repository.owner.login}/${hit.repository.name}" }, color = Gh.c.fgMuted, fontSize = 12.sp)
            Text(SearchText.annotated(hit.path, query, mark), color = Gh.c.accent, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            hit.textMatches.firstOrNull()?.fragment?.let {
                Text(
                    SearchText.annotated(it.take(1200), query, mark),
                    Modifier.padding(top = 8.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Gh.c.fg, maxLines = 7, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Issue / PR 结果：复用现有 IssueItem，点击进入详情 */
@Composable
private fun IssueResults(query: String, pulls: Boolean, state: LazyListState, sort: Int, onSort: (Int) -> Unit) {
    val nav = LocalNav.current
    val g = Gh.c
    val keys = IssueSearchSort.keys(pulls)
    val index = sort.coerceIn(0, keys.lastIndex)
    val pager = rememberPager("search-${if (pulls) "pr" else "issue"}:$query:$index") { p, _ ->
        // 「相关」不传 sort/order，GitHub 会按相关度返回最匹配的结果
        val key = keys[index].takeIf { it != IssueSearchSort.RELEVANCE }
        GitHub.searchIssues(query, p, key, key?.let { "desc" }).items
    }
    PagedList(pager, state = state, itemKey = { it.id }, empty = trf("没有找到匹配的{0}", if (pulls) " PR" else " Issue"),
        emptyIcon = if (pulls) R.drawable.oc_git_pull_request else R.drawable.oc_issue_opened,
        header = {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(SearchType.labels[if (pulls) SearchType.PR else SearchType.ISSUE], Modifier.weight(1f), fontSize = 13.sp, color = g.fgMuted)
                    Dropdown(issueSortLabel(keys[index]), keys.map { issueSortLabel(it) }, onSort, active = index != 0)
                }
                HDivider()
            }
        },
    ) { issue ->
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
    FilterSheet(
        trf("筛选 · {0}", SearchType.labels[type]), tr("条件之间为「并且」，应用后立即收窄结果"), tr("关闭筛选"), onDismiss,
        footer = {
            TextButton(onClick = { f = f.cleared(type) }, enabled = f.hasActive(type)) { Text(tr("重置")) }
            TextButton(onClick = { onDismiss() }) { Text(tr("取消")) }
            GhButton(trc("应用", "action") + if (f.activeCount(type) > 0) " · ${f.activeCount(type)}" else "", primary = true) { onApply(f) }
        },
    ) {
        FilterGroup(tr("范围")) {
            when (type) {
                SearchType.REPO -> FilterField(tr("归属用户或组织"), f.repoOwner, { f = f.copy(repoOwner = it) }, tr("如 JetBrains"))
                SearchType.USER -> ChoicePicker(tr("账号类型"), listOf(tr("不限"), tr("用户"), tr("组织")), f.userType) { f = f.copy(userType = it) }
                SearchType.CODE -> {
                    FilterField(tr("仓库"), f.codeRepo, { f = f.copy(codeRepo = it) }, tr("owner/name，可留空搜索全站"))
                    FilterField(tr("归属用户或组织"), f.codeOwner, { f = f.copy(codeOwner = it) }, tr("未填写仓库时生效"))
                }
                else -> {
                    FilterField(tr("仓库"), f.issueRepo, { f = f.copy(issueRepo = it) }, "owner/name")
                    FilterField(tr("归属用户或组织"), f.issueOwner, { f = f.copy(issueOwner = it) }, tr("未填写仓库时生效"))
                }
            }
        }
        FilterGroup(tr("属性")) {
            when (type) {
                SearchType.REPO -> {
                    LanguagePicker(f.repoLanguage) { f = f.copy(repoLanguage = it) }
                    FilterField("Topic", f.topic, { f = f.copy(topic = it) }, tr("单个 topic，如 android"))
                    ChoicePicker(tr("归档状态"), listOf(tr("不限"), tr("仅归档"), tr("排除归档")), f.archived) { f = f.copy(archived = it) }
                }
                SearchType.USER -> {
                    FilterField(tr("所在地"), f.location, { f = f.copy(location = it) }, tr("如 China / Tokyo"))
                    LanguagePicker(f.userLanguage) { f = f.copy(userLanguage = it) }
                }
                SearchType.CODE -> {
                    LanguagePicker(f.codeLanguage) { f = f.copy(codeLanguage = it) }
                    FilterField(tr("路径"), f.path, { f = f.copy(path = it) }, tr("如 src/main"))
                    FilterField(tr("文件名"), f.filename, { f = f.copy(filename = it) }, tr("如 build.gradle"))
                }
                else -> {
                    ChoicePicker(tr("状态"), listOf(tr("不限"), tr("开放"), tr("已关闭")), f.state) { f = f.copy(state = it) }
                    FilterField(tr("作者"), f.author, { f = f.copy(author = it) }, tr("GitHub 用户名"))
                    FilterField(tr("标签"), f.labels, { f = f.copy(labels = it) }, tr("多个用英文逗号分隔"))
                    LanguagePicker(f.issueLanguage) { f = f.copy(issueLanguage = it) }
                }
            }
        }
        // 代码搜索没有数量/时间条件，空分组会画出空标题，直接省略
        if (type != SearchType.CODE) FilterGroup(tr("数量与时间")) {
            when (type) {
                SearchType.REPO -> {
                    NumberPicker(tr("最少 Star"), f.minStars, listOf(0, 10, 100, 500, 1000, 5000, 10000)) { f = f.copy(minStars = it) }
                    NumberPicker(tr("最少 Fork"), f.minForks, listOf(0, 10, 50, 100, 500, 1000)) { f = f.copy(minForks = it) }
                }
                SearchType.USER -> NumberPicker(tr("最少关注者"), f.minFollowers, listOf(0, 10, 50, 100, 1000)) { f = f.copy(minFollowers = it) }
                else -> {
                    NumberPicker(tr("最少评论"), f.minComments, listOf(0, 5, 10, 20, 50)) { f = f.copy(minComments = it) }
                    NumberPicker(tr("更新时间"), f.updatedDays, listOf(0, 7, 30, 90, 365), unit = tr("天内")) { f = f.copy(updatedDays = it) }
                }
            }
        }
    }
}

/** 筛选分区：灰色小标题 + 白色分组卡片 */
@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    FilterCard(title) { content() }
}

@Composable
private fun FilterField(label: String, value: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(),
        label = { Text(label) }, placeholder = { Text(placeholder) }, singleLine = true,
    )
}

/** 标签 + 下拉：标准的只读输入框加菜单，选中值显示在框里 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerRow(label: String, value: String, options: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, text ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(i); expanded = false })
            }
        }
    }
}

@Composable
private fun LanguagePicker(value: String, onSelect: (String) -> Unit) {
    // 语言候选太多，用下拉而不是平铺 chips；用 language:xxx 输入了列表外的语言时也如实显示
    val custom = value.isNotBlank() && value !in SEARCH_LANGUAGES
    val options = listOf(tr("不限")) + (if (custom) listOf(value) else emptyList()) + SEARCH_LANGUAGES
    PickerRow(tr("语言"), if (value.isBlank()) tr("不限") else value, options) { onSelect(if (it == 0) "" else options[it]) }
}

/** 数量阈值用下拉，避免七八个胶囊平铺把弹层撑得又长又密 */
@Composable
private fun NumberPicker(label: String, value: Int, options: List<Int>, unit: String = tr("以上"), onSelect: (Int) -> Unit) {
    val texts = options.map { if (it == 0) tr("不限") else "$it $unit" }
    PickerRow(label, texts[options.indexOf(value).coerceAtLeast(0)], listOf(tr("不限")) + texts.drop(1)) { onSelect(if (it == 0) 0 else options[it]) }
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
