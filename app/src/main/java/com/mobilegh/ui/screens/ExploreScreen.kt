package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.History
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Links
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.IssueItem
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.RepoItem
import com.mobilegh.ui.components.UserItem
import com.mobilegh.ui.theme.Gh
import java.time.LocalDate

@Composable
fun ExploreScreen() {
    val nav = LocalNav.current
    val g = Gh.c
    val focus = LocalFocusManager.current
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var trend by rememberSaveable { mutableIntStateOf(0) }
    val sorts = listOf(null to "最佳匹配", "stars" to "最多 Star", "updated" to "最近更新", "forks" to "最多 Fork")
    val trendRanges = listOf(7L to "本周", 30L to "本月", 1L to "今天")
    val state = rememberLazyListState()
    fun submit(text: String = input.trim(), kind: Int = type) {
        input = text
        type = kind
        focus.clearFocus()
        val target = Links.fromInput(text)
        History.search(Links.inputLink(text)?.url ?: text, kind)
        if (target != null) nav.push(target) else query = text
    }
    LaunchedEffect(query, type, sort, trend) { state.scrollToItem(0) }
    LaunchedEffect(nav.reselect) { if (nav.reselect > 0 && nav.tab == Tab.Explore) state.animateScrollToItem(0) }

    Page("探索", back = false, contentWindowInsets = WindowInsets(0)) { pad ->
        Column(Modifier.padding(pad)) {
            GhField(
                input, { input = it }, "搜索 GitHub 或打开链接", Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
                placeholder = "粘贴 GitHub 地址，或搜索 language:kotlin stars:>100",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                trailing = {
                    IconButton(onClick = { submit() }) {
                        Oc(R.drawable.oc_search, g.fgMuted, 20.dp, Modifier.semantics { contentDescription = "搜索或打开链接" })
                    }
                },
            )
            LaunchedEffect(input) { if (input.isEmpty()) query = "" }
            Chips(listOf("仓库", "用户", "Issue", "PR", "代码"), type) { type = it }
            HDivider()
            val q = query
            if (q.isEmpty()) {
                val days = trendRanges[trend].first
                val since = LocalDate.now().minusDays(days)
                val tq = "created:>$since"
                val trending = rememberPager("trend:$trend") { p, _ -> GitHub.searchRepos(tq, "stars", p).items }
                PagedList(trending, state = state, header = {
                    item {
                        SearchHistorySection(History.data().searches, { submit(it.query, it.type) }, History::removeSearch, History::clearSearches)
                    }
                    item {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("🔥 新晋热门仓库", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = g.fg, modifier = Modifier.weight(1f))
                            Dropdown(trendRanges[trend].second, trendRanges.map { it.second }, { trend = it })
                        }
                        HDivider()
                    }
                }) { RepoItem(it, extra = "创建于 ${it.createdAt?.take(10)}") }
            } else when (type) {
                0 -> {
                    val pager = rememberPager("s:repo:$q:$sort") { p, _ -> GitHub.searchRepos(q, sorts[sort].first, p).items }
                    PagedList(pager, state = state, header = {
                        item { Row(Modifier.padding(12.dp)) { Dropdown(sorts[sort].second, sorts.map { it.second }, { sort = it }) }; HDivider() }
                    }) { RepoItem(it) }
                }
                1 -> {
                    val pager = rememberPager("s:user:$q") { p, _ -> GitHub.searchUsers(q, p).items }
                    PagedList(pager, state = state) { UserItem(it) }
                }
                4 -> CodeSearchResults(q)
                else -> {
                    val isPr = type == 3
                    val pager = rememberPager("s:issue:$q:$isPr") { p, _ -> GitHub.searchIssues("$q is:${if (isPr) "pr" else "issue"}", p).items }
                    PagedList(pager, state = state) { issue ->
                        val repo = issue.repositoryUrl?.substringAfter("/repos/") ?: ""
                        IssueItem(issue, repoName = repo) {
                            val (o, r) = repo.split('/').let { it[0] to it.getOrElse(1) { "" } }
                            nav.push(Screen.IssueDetail(o, r, issue.number, isPr))
                        }
                    }
                }
            }
        }
    }
}
