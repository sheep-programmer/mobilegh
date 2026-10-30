package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.SearchType
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.RepoItem
import com.mobilegh.ui.theme.Gh
import java.time.LocalDate
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/**
 * 探索页：热门仓库 + 进入搜索的入口。
 * 搜索本身有独立大页面（类型、关键词、组合筛选、结果都在那里），这里不再放搜索类型标签。
 */
@Composable
fun ExploreScreen() {
    val nav = LocalNav.current
    val g = Gh.c
    var trend by rememberSaveable { mutableIntStateOf(0) }
    val trendRanges = listOf(7L to tr("本周"), 30L to tr("本月"), 1L to tr("今天"))
    val state = rememberLazyListState()

    LaunchedEffect(trend) { state.scrollToItem(0) }
    com.mobilegh.nav.OnTabReselect(Tab.Explore) { state.animateScrollToItem(0) }
    fun openSearch() = nav.push(Screen.Search(SearchType.REPO))

    Page(tr("探索"), back = false, contentWindowInsets = WindowInsets(0),
        actions = { OcButton(R.drawable.oc_search, ::openSearch, description = tr("搜索")) }) { pad ->
        Column(Modifier.padding(pad)) {
            SearchEntry(::openSearch)
            val days = trendRanges[trend].first
            // 日期在每次取数时重新算：跨天或手动刷新后，「本周 / 本月」的起点都会跟着变
            val trending = rememberPager("trend:$trend") { p, force ->
                GitHub.searchRepos("created:>${LocalDate.now().minusDays(days)}", "stars", p, force).items
            }
            PagedList(trending, state = state, header = {
                item {
                    SearchHistorySection(
                        com.mobilegh.data.History.data().searches,
                        { entry -> nav.push(Screen.Search(entry.type, entry.query)) },
                        com.mobilegh.data.History::removeSearch,
                        com.mobilegh.data.History::clearSearches,
                        collapsed = true,
                    )
                }
                item {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Oc(R.drawable.oc_flame, g.attention, 18.dp, Modifier.padding(end = 6.dp))
                            Text(tr("新晋热门仓库"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = g.fg)
                        }
                        Dropdown(trendRanges[trend].second, trendRanges.map { it.second }, { trend = it })
                    }
                    HDivider()
                }
            }) { RepoItem(it, extra = it.createdAt?.take(10)?.let { d -> trf("创建于 {0}", d) }) }
        }
    }
}

/** 看起来像搜索框的按钮：点击后打开独立搜索页，不在原地展开输入 */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    val g = Gh.c
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, g.border, RoundedCornerShape(8.dp))
                .background(g.canvasSubtle, RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = tr("搜索 GitHub") }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oc(R.drawable.oc_search, g.fgMuted, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(tr("搜索仓库、用户、Issue、PR 或代码"), Modifier.weight(1f), color = g.fgMuted, fontSize = 14.sp)
            Oc(R.drawable.oc_chevron_right, g.fgMuted, 14.dp)
        }
    }
}
