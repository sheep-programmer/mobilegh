package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.GitHubStarList
import com.mobilegh.data.Repo
import com.mobilegh.data.Session
import com.mobilegh.data.StarFeed
import com.mobilegh.data.StarFeedClient
import com.mobilegh.data.StarListAccount
import com.mobilegh.data.StarSearchUnavailable
import com.mobilegh.data.StarredRepos
import com.mobilegh.data.StarredReposClient
import com.mobilegh.data.UserLists
import com.mobilegh.data.UserListsClient
import com.mobilegh.data.starMatches
import com.mobilegh.nav.Loader
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.Pager
import com.mobilegh.nav.act
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.RepoItem
import com.mobilegh.ui.components.Segmented
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.theme.Gh

/**
 * 「已 Star」页：上面两个页签（全部 Star / 列表），两个页签各管各的状态。
 *
 * - 全部 Star：官方 GraphQL 星标连接（按 Star 时间，ASC / DESC 都由服务端排好）或 REST 排序，
 *   搜索走服务端；服务端拒绝 `query` 参数时自动退回本地过滤并说明原因。
 * - 列表：GitHub 列表（Star 的分类）的目录与详情，搜索与排序都在客户端完成。
 *
 * 页面本身只负责页签与数据加载，控件和列表都拆成了下面这些小组件，
 * 免得一个 Composable 把「加载 + 搜索 + 排序 + 翻页 + 增删」全揽在自己身上。
 */
@Composable
internal fun StarredRepoPage(login: String, name: String) {
    val isMe = login.equals(Session.login, true)
    val signedIn = Session.token != null
    // 列表是 Star 的子集，网页版也放在 Stars 页；别人的公开列表可以看，但只有本人能新建
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedList by rememberSaveable { mutableStateOf<String?>(null) }
    val listsTab = tab == 1 && signedIn
    val account = remember { StarListAccount(Session.login ?: login, Session.generation) }
    val lists = if (signedIn) rememberLoader("star-lists:$login") { UserLists.lists(account, login) } else null
    val revision by UserLists.changes.collectAsState()
    LaunchedEffect(revision) { if (lists?.data != null) lists.refresh() }
    val subtitle = if (name.isNotEmpty()) "$login/$name" else login
    Page(starredTitle(listsTab, selectedList, lists?.data), subtitle = subtitle) { pad ->
        Column(Modifier.padding(pad)) {
            if (signedIn) {
                Segmented(
                    listOf("全部 Star", "列表"), tab,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) { index ->
                    tab = index
                    if (index == 0) selectedList = null
                }
            }
            when {
                listsTab && lists != null -> StarListsTab(lists, isMe, login, account, selectedList) { selectedList = it }
                else -> StarFeedTab(login, isMe)
            }
        }
    }
}

/** 标题：列表页签里显示当前列表名，其余情况就是「已 Star」 */
private fun starredTitle(listsTab: Boolean, selectedList: String?, lists: List<GitHubStarList>?): String {
    if (!listsTab) return "已 Star"
    val id = selectedList ?: return "列表"
    return lists?.firstOrNull { it.id == id }?.name ?: "列表"
}

// ---------------- 全部 Star ----------------

/** 服务端搜索 + 三种服务端排序，每个都能切正序 / 倒序 */
@Composable
private fun StarFeedTab(login: String, isMe: Boolean) {
    // 输入框与已生效关键词分开：打字过程中列表不变，按回车或点放大镜才应用
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var descending by rememberSaveable { mutableStateOf(STAR_SORTS[0].descending) }
    var truncated by remember(query, index, descending) { mutableStateOf(false) }
    val supported by StarredRepos.searchSupported.collectAsState()
    // 换账号后重新尝试服务端搜索：上一个 Token 不支持不代表下一个也不支持
    LaunchedEffect(Session.generation) { StarredRepos.resetSupport() }
    val searching = query.isNotEmpty() && Session.token != null && supported
    val pager = rememberStarFeedPager(login, isMe, query, index, descending, supported) { truncated = true }
    StarSearchControls(
        input = input, query = query, index = index, descending = descending,
        searching = searching, status = starStatus(query, searching, truncated, pager.items.size),
        onInput = { input = it },
        onApply = { query = input.trim() },
        onClear = { input = ""; query = "" },
        onPick = { picked -> index = picked; descending = STAR_SORTS[picked].descending },
        onFlip = { descending = !descending },
    )
    PagedList(
        pager,
        empty = starFeedEmpty(login, isMe, query),
        emptyIcon = R.drawable.oc_star,
        filteredEmpty = "已加载的 Star 中没有匹配的仓库",
        // 服务端搜索已经筛过一遍，只有本地回退路径才需要再过滤
        itemFilter = { searching || starMatches(it, query) },
    ) { RepoItem(it) }
}

@Composable
private fun starFeedEmpty(login: String, isMe: Boolean, query: String): String = when {
    query.isNotEmpty() -> "没有匹配的 Star"
    isMe -> "还没有 Star 任何仓库"
    else -> "$login 还没有 Star 任何仓库"
}

/** 搜索框 + 排序下拉 + 方向按钮 + 状态说明 */
@Composable
private fun StarSearchControls(
    input: String,
    query: String,
    index: Int,
    descending: Boolean,
    searching: Boolean,
    status: String,
    onInput: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    onPick: (Int) -> Unit,
    onFlip: () -> Unit,
) {
    SearchBox(
        input, query, onInput, onApply, onClear,
        "搜索 Star", "仓库名称或描述",
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 服务端搜索只认 Star 时间，这时只留方向切换，不假装能按更新时间排
        if (searching) Text("搜索结果按 Star 时间排列", fontSize = 12.sp, color = Gh.c.fgMuted)
        else Dropdown(STAR_SORTS[index].label, STAR_SORTS.map { it.label }, onPick, active = index != 0)
        Spacer(Modifier.weight(1f))
        DirectionButton(descending, onFlip)
    }
    if (status.isNotEmpty()) {
        Text(
            status, Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            fontSize = 12.sp, color = Gh.c.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 状态说明：服务端搜索确实生效时如实告知，退回本地过滤时说明「只过滤已加载的」，
 * 别让人误以为已经搜过全部 Star。[loaded] 是本地回退路径下能过滤的条目数。
 */
private fun starStatus(query: String, searching: Boolean, truncated: Boolean, loaded: Int) = when {
    query.isEmpty() -> if (truncated) "GitHub 只返回前一部分 Star" else ""
    Session.token == null -> "登录后可搜索全部 Star · 当前仅过滤 $loaded 个"
    searching -> "搜索全部 Star 中的「$query」"
    else -> "仅过滤已加载的 $loaded 个"
}

/**
 * 星标页分页器。Star 时间走官方 GraphQL 连接（`orderBy: STARRED_AT` + ASC / DESC），游标由服务端给；
 * 更新时间与 Star 数走 REST。两者的每页条数必须和接口一致，否则最后一页会被判错。
 */
@Composable
private fun rememberStarFeedPager(
    login: String,
    isMe: Boolean,
    query: String,
    index: Int,
    descending: Boolean,
    searchSupported: Boolean,
    onTruncated: () -> Unit,
): Pager<Repo> {
    val searching = query.isNotEmpty() && Session.token != null && searchSupported
    // Star 时间用官方星标连接的游标分页（方向由服务端排好），逐页懒加载
    val timeFeeds = Session.token != null && index == 0
    val cursors = remember(query, index, descending, searchSupported) { HashMap<Int, String?>() }
    val pageSize = when {
        searching -> StarredReposClient.PAGE_SIZE
        timeFeeds -> StarFeedClient.PAGE_SIZE
        else -> GitHub.STARRED_PER
    }
    return rememberPager("rl-starred:$login:$index:$descending:$query:$searchSupported", pageSize) { page, force ->
        when {
            searching -> searchStarPage(login, query, descending, page, cursors, onTruncated)
            timeFeeds -> starTimePage(login, descending, page, cursors, onTruncated)
            // 「更新时间」「Star 数」不是星标连接的字段，仍用 REST 的 sort=updated / sort=stars
            else -> GitHub.starred(
                if (isMe) null else login, page, force,
                STAR_SORTS[index].value, if (descending) "desc" else "asc",
            )
        }
    }
}

/** 服务端搜索：与官方 App 同一条查询；服务端不认关键字时抛 [StarSearchUnavailable] */
private suspend fun searchStarPage(
    login: String,
    query: String,
    descending: Boolean,
    page: Int,
    cursors: MutableMap<Int, String?>,
    onTruncated: () -> Unit,
): List<Repo> {
    if (page > 1 && cursors[page] == null) return emptyList()
    val result = try {
        StarredRepos.page(login, query, cursors[page], if (descending) "DESC" else "ASC")
    } catch (unavailable: StarSearchUnavailable) {
        // searchSupported 已置 false，页面会用新 key 重建为本地过滤
        return emptyList()
    }
    cursors[page + 1] = result.nextCursor
    if (result.truncated) onTruncated()
    return result.repos
}

/** Star 时间：官方星标连接，ASC（最早在前）/ DESC（最近在前）都由服务端排好 */
private suspend fun starTimePage(
    login: String,
    descending: Boolean,
    page: Int,
    cursors: MutableMap<Int, String?>,
    onTruncated: () -> Unit,
): List<Repo> {
    if (page > 1 && cursors[page] == null) return emptyList()
    val result = StarFeed.page(login, cursors[page], if (descending) "DESC" else "ASC")
    cursors[page + 1] = result.nextCursor
    if (result.truncated) onTruncated()
    return result.items.map { it.repo }
}

// ---------------- 列表（Star 的分类） ----------------

@Composable
private fun StarListsTab(
    lists: Loader<List<GitHubStarList>>,
    isMe: Boolean,
    login: String,
    account: StarListAccount,
    selectedList: String?,
    onSelect: (String?) -> Unit,
) {
    // 列表条目默认倒序（从列表尾部往回读）；方向一变就换一整套请求
    var itemDesc by rememberSaveable(selectedList) { mutableStateOf(true) }
    val all = lists.data
    when {
        all == null && lists.error != null -> ErrorState(lists.error!!, Modifier.fillMaxWidth()) { lists.load(true) }
        all == null -> Loading(Modifier.fillMaxWidth().height(60.dp))
        selectedList == null -> StarListDirectory(lists, all, isMe, login, account) { onSelect(it) }
        else -> StarListDetail(
            pager = rememberListDetailPager(selectedList, itemDesc, lists, account),
            list = all.firstOrNull { it.id == selectedList },
            descending = itemDesc,
            onFlip = { itemDesc = !itemDesc },
            onBack = { onSelect(null) },
        )
    }
}

/** 列表目录：新建 / 刷新 + 搜索 + 排序 + 目录本身。列表最多 32 个，过滤与排序都在客户端完成 */
@Composable
private fun StarListDirectory(
    lists: Loader<List<GitHubStarList>>,
    all: List<GitHubStarList>,
    isMe: Boolean,
    login: String,
    account: StarListAccount,
    onSelect: (String) -> Unit,
) {
    val entry = LocalEntry.current
    var picker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val browser = rememberListBrowser("star-lists:$login")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isMe) GhButton("新建列表", enabled = all.size < UserListsClient.MAX_LISTS) { picker = true }
        GhButton("刷新", enabled = !lists.loading) { lists.load(true) }
    }
    lists.error?.let { InlineError(it, Modifier.padding(horizontal = 16.dp)) { lists.load(true) } }
    Text("列表是 Star 的分类，与 GitHub 同步。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, color = Gh.c.fgMuted)
    SearchBox(
        browser.input, browser.query, { browser.input = it }, { browser.apply() }, { browser.clear() },
        "搜索列表", "名称或描述",
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp),
    )
    SortBar(LIST_SORTS, browser.sort, browser.descending, { browser.pick(it) }, { browser.descending = !browser.descending })
    StarListRows(
        all = all,
        shown = browser.shown(all),
        isMe = isMe,
        login = login,
        onNewList = { picker = true },
        onRetry = { lists.load(true) },
        onClearSearch = { browser.clear() },
        onOpen = onSelect,
    )
    if (picker) GitHubListEditor(null, saving, error, onDismiss = { if (!saving) { picker = false; error = null } }) { nameOf, desc, priv ->
        saving = true
        error = null
        entry.act({ error = it; saving = false }) {
            val created = UserLists.create(account, nameOf, desc, priv)
            saving = false
            picker = false
            lists.refresh()
            // 新建后直接进入这个列表，省得再找一遍
            onSelect(created.id)
        }
    }
}

/** 目录的三种结果：完全没有列表、过滤后为空、正常列出 */
@Composable
private fun StarListRows(
    all: List<GitHubStarList>,
    shown: List<GitHubStarList>,
    isMe: Boolean,
    login: String,
    onNewList: () -> Unit,
    onRetry: () -> Unit,
    onClearSearch: () -> Unit,
    onOpen: (String) -> Unit,
) {
    when {
        all.isEmpty() -> EmptyState(
            if (isMe) "还没有 GitHub 列表" else "$login 还没有公开的 GitHub 列表",
            R.drawable.oc_checklist, Modifier.fillMaxSize(),
            if (isMe) "新建列表" else "刷新",
            if (isMe) onNewList else onRetry,
        )
        shown.isEmpty() -> EmptyState(
            "没有匹配的列表", R.drawable.oc_checklist, Modifier.fillMaxSize(), "清除搜索", onClearSearch,
        )
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(shown, key = { it.id }) { list -> StarListRow(list) { onOpen(list.id) } }
        }
    }
}

/** 目录里的一个列表：名称、可见性与仓库数 */
@Composable
private fun StarListRow(list: GitHubStarList, onClick: () -> Unit) {
    Card(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(list.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Pill(if (list.isPrivate) "私有" else "公开")
            Spacer(Modifier.width(6.dp))
            Pill("${list.count} 个仓库")
        }
    }
}

/** 列表详情：只保留 GitHub 顺序的正 / 倒序，加一个只在已加载内容里生效的搜索 */
@Composable
private fun StarListDetail(
    pager: Pager<Repo>,
    list: GitHubStarList?,
    descending: Boolean,
    onFlip: () -> Unit,
    onBack: () -> Unit,
) {
    var input by rememberSaveable(list?.id) { mutableStateOf("") }
    var query by rememberSaveable(list?.id) { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextLink("‹ 全部列表", onClick = onBack)
        Spacer(Modifier.weight(1f))
        Text("已加载 ${pager.items.size} / ${list?.count ?: 0} 个", fontSize = 12.sp, color = Gh.c.fgMuted)
    }
    if (list == null) {
        EmptyState("列表已被删除", R.drawable.oc_checklist, Modifier.fillMaxSize(), "返回", onBack)
        return
    }
    SearchBox(
        input, query, { input = it }, { query = input.trim() }, { input = ""; query = "" },
        "搜索列表内仓库", "名称或描述",
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("GitHub 顺序", Modifier.weight(1f), fontSize = 13.sp, color = Gh.c.fgMuted)
        DirectionButton(descending, onFlip)
    }
    // 倒序的每页在数据层已经倒着放好，这里不再反转，只做搜索过滤
    PagedList(
        pager,
        empty = "列表暂无仓库，在仓库页「加入自定义列表」中添加",
        emptyIcon = R.drawable.oc_checklist,
        filteredEmpty = "列表中没有匹配的仓库",
        itemFilter = { starMatches(it, query) },
    ) { RepoItem(it) }
}

/** 列表详情的分页器：正序读 items(first, after) 从头部，倒序读 items(last, before) 从尾部 */
@Composable
private fun rememberListDetailPager(
    selectedList: String,
    descending: Boolean,
    lists: Loader<List<GitHubStarList>>,
    account: StarListAccount,
): Pager<Repo> {
    // Pager 只按页号取数据，游标由这里按页递增记录；列表被删除时返回空页而不是崩溃
    val cursors = remember(selectedList, descending) { HashMap<Int, String?>() }
    return rememberPager("rl-list:$selectedList:$descending", pageSize = UserListsClient.DETAIL_PAGE_SIZE) { page, _ ->
        val list = lists.data?.firstOrNull { it.id == selectedList } ?: return@rememberPager emptyList()
        if (page > 1 && cursors[page] == null) return@rememberPager emptyList()
        val result = UserLists.items(account, list, cursors[page], descending = descending)
        cursors[page + 1] = result.nextCursor
        result.items.map { it.repo }
    }
}
