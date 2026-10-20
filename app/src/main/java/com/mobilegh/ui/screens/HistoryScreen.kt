package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.History
import com.mobilegh.data.SearchHistoryEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.Gh

private val searchTypeLabels = listOf("仓库", "用户", "Issue", "PR", "代码")

/**
 * 搜索历史列表。
 * [collapsed] 为 true 时只露出最近几条，并给出「查看全部」入口；
 * 全部历史放在可滚动的 [SearchHistoryScreen]，不在首页无限拉长。
 */
@Composable
fun SearchHistorySection(
    entries: List<SearchHistoryEntry>,
    onOpen: (SearchHistoryEntry) -> Unit,
    onRemove: (SearchHistoryEntry) -> Unit,
    onClear: () -> Unit,
    collapsed: Boolean = false,
) {
    if (entries.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (collapsed && !expanded) entries.take(3) else entries
    val nav = LocalNav.current
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("搜索历史", Modifier.weight(1f), color = Gh.c.fg, fontSize = 14.sp)
            if (collapsed) {
                TextButton({ nav.push(Screen.SearchHistory) }) { Text("查看全部", color = Gh.c.accent) }
            } else if (entries.isNotEmpty()) {
                TextButton(onClear) { Text("清空", color = Gh.c.fgMuted) }
            }
        }
        shown.forEach { entry ->
            SearchHistoryRow(entry, onOpen, onRemove)
        }
        if (collapsed) {
            if (!expanded && entries.size > 3) {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("展开其余 ${entries.size - 3} 条", color = Gh.c.accent, fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Oc(R.drawable.oc_chevron_down, Gh.c.accent, 12.dp)
                }
            } else if (expanded) {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = false }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("收起", color = Gh.c.fgMuted, fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Oc(R.drawable.oc_chevron_down, Gh.c.fgMuted, 12.dp, Modifier.rotate(180f))
                }
            }
        }
        HDivider()
    }
}

@Composable
private fun SearchHistoryRow(entry: SearchHistoryEntry, onOpen: (SearchHistoryEntry) -> Unit, onRemove: (SearchHistoryEntry) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(entry) }.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Oc(R.drawable.oc_history, Gh.c.fgMuted)
        Text(entry.query, Modifier.weight(1f).padding(horizontal = 12.dp), color = Gh.c.fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(searchTypeLabels.getOrElse(entry.type) { "" }, color = Gh.c.fgMuted, fontSize = 11.sp)
        IconButton({ onRemove(entry) }, Modifier.semantics { contentDescription = "移除搜索历史：${entry.query}" }) { Oc(R.drawable.oc_x) }
    }
}

/** 独立的搜索历史页：分类型查看，可逐条删除或清空 */
@Composable
fun SearchHistoryScreen() {
    val nav = LocalNav.current
    val all = History.data().searches
    var type by rememberSaveable { mutableStateOf(-1) }
    val shown = if (type < 0) all else all.filter { it.type == type }

    Page("搜索历史", actions = { if (all.isNotEmpty()) OcButton(R.drawable.oc_trash, { History.clearSearches() }, description = "清空搜索历史") }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Chips(listOf("全部") + searchTypeLabels, type + 1) { type = it - 1 }
            HDivider()
            if (all.isEmpty()) {
                EmptyState("还没有搜索记录", R.drawable.oc_history)
            } else if (shown.isEmpty()) {
                EmptyState("这个类型还没有搜索记录", R.drawable.oc_search, action = "查看全部") { type = -1 }
            } else {
                LazyColumn {
                    items(shown, key = { "${it.type}:${it.query}" }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.push(Screen.Search(entry.type, entry.query)) }
                                .padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Oc(R.drawable.oc_history, Gh.c.fgMuted)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(entry.query, color = Gh.c.fg, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(searchTypeLabels.getOrElse(entry.type) { "" }, color = Gh.c.fgMuted, fontSize = 11.sp)
                            }
                            IconButton(
                                { History.removeSearch(entry) },
                                Modifier.semantics { contentDescription = "移除搜索历史：${entry.query}" },
                            ) { Oc(R.drawable.oc_x) }
                        }
                        HDivider()
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
fun RecentRepositoriesScreen() {
    val nav = LocalNav.current
    var filter by rememberSaveable { mutableStateOf("") }
    val all = History.data().repositories
    val shown = all.filter { it.fullName.contains(filter.trim(), true) }
    Page("最近浏览", actions = { if (all.isNotEmpty()) OcButton(R.drawable.oc_trash, { History.clearRepositories() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            GhField(filter, { filter = it }, "搜索最近浏览的仓库", Modifier.padding(12.dp))
            Text("仅保存在本机，按账号区分", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
            if (all.isEmpty()) EmptyState("还没有浏览过仓库", R.drawable.oc_history)
            else if (shown.isEmpty()) EmptyState("没有匹配的仓库", R.drawable.oc_search, action = "清除搜索") { filter = "" }
            else LazyColumn {
                items(shown, key = { it.fullName.lowercase() }) { repo ->
                    Row(Modifier.fillMaxWidth().clickable { nav.push(Screen.Repo(repo.owner, repo.name)) }.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Oc(R.drawable.oc_repo)
                        Text(repo.fullName, Modifier.weight(1f).padding(12.dp), color = Gh.c.accent, fontSize = 14.sp)
                        IconButton({ History.removeRepository(repo) }, Modifier.semantics { contentDescription = "移除最近浏览：${repo.fullName}" }) { Oc(R.drawable.oc_x) }
                    }
                }
            }
        }
    }
}
