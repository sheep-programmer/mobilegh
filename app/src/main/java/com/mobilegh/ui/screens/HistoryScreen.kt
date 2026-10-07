package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

@Composable
fun SearchHistorySection(
    entries: List<SearchHistoryEntry>,
    onOpen: (SearchHistoryEntry) -> Unit,
    onRemove: (SearchHistoryEntry) -> Unit,
    onClear: () -> Unit,
) {
    if (entries.isEmpty()) return
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("搜索历史", Modifier.weight(1f), color = Gh.c.fg, fontSize = 14.sp)
            TextButton(onClear) { Text("清空", color = Gh.c.fgMuted) }
        }
        entries.take(6).forEach { entry ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(entry) }.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Oc(R.drawable.oc_history, Gh.c.fgMuted)
                Text(entry.query, Modifier.weight(1f).padding(horizontal = 12.dp), color = Gh.c.fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf("仓库", "用户", "Issue", "PR", "代码")[entry.type], color = Gh.c.fgMuted, fontSize = 11.sp)
                IconButton({ onRemove(entry) }, Modifier.semantics { contentDescription = "移除搜索历史：${entry.query}" }) { Oc(R.drawable.oc_x) }
            }
        }
        HDivider()
    }
}

@Composable
fun RecentRepositoriesScreen() {
    val nav = LocalNav.current
    var filter by rememberSaveable { mutableStateOf("") }
    val all = History.data().repositories
    val shown = all.filter { it.fullName.contains(filter.trim(), true) }
    Page("最近浏览", actions = { if (all.isNotEmpty()) TextButton({ History.clearRepositories() }) { Text("清空") } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            GhField(filter, { filter = it }, "搜索最近浏览的仓库", Modifier.padding(12.dp))
            Text("仅保存在本机，按账号区分", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
            if (shown.isEmpty()) EmptyState(if (all.isEmpty()) "还没有浏览过仓库" else "没有匹配的仓库")
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
