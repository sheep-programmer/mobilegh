package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.IssueItem
import com.mobilegh.ui.components.OcButton
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
    LaunchedEffect(nav.reselect) { if (nav.reselect > 0 && nav.tab == Tab.Explore) state.animateScrollToItem(0) }

    Page("探索", back = false, contentWindowInsets = WindowInsets(0)) { pad ->
        Column(Modifier.padding(pad)) {
            GhField(
                input, { input = it }, "搜索 GitHub", Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
                placeholder = "支持 GitHub 搜索语法，如 language:kotlin stars:>100",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { query = input.trim(); focus.clearFocus() }),
                trailing = { OcButton(R.drawable.oc_search, { query = input.trim(); focus.clearFocus() }, g.fgMuted) },
            )
            LaunchedEffect(input) { if (input.isEmpty()) query = "" }
            Chips(listOf("仓库", "用户", "Issue", "PR"), type) { type = it }
            HDivider()
            val q = query
            if (q.isEmpty()) {
                val days = trendRanges[trend].first
                val since = LocalDate.now().minusDays(days)
                val tq = "created:>$since"
                val trending = rememberPager("trend:$trend") { p, _ -> GitHub.searchRepos(tq, "stars", p).items }
                PagedList(trending, state = state, header = {
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
