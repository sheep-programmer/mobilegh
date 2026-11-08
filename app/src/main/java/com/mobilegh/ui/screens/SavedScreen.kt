package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.SavedItem
import com.mobilegh.data.SavedItems
import com.mobilegh.data.SavedKind
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.Gh

@Composable
fun SavedScreen() {
    val nav = LocalNav.current
    val g = Gh.c
    val state = rememberLazyListState()
    var kindFilter by remember { mutableIntStateOf(0) }
    val filters = listOf(tr("全部"), "Issues", "Pull Requests", tr("仓库"), tr("讨论"))

    val all = SavedItems.all()
    val filtered = when (kindFilter) {
        1 -> all.filter { it.kind == SavedKind.Issue }
        2 -> all.filter { it.kind == SavedKind.PullRequest }
        3 -> all.filter { it.kind == SavedKind.Repo }
        4 -> all.filter { it.kind == SavedKind.Discussion }
        else -> all
    }

    Page(tr("稍后阅读"), actions = {
        if (all.isNotEmpty()) {
            MoreMenu(listOf(
                MenuAction(tr("清空稍后阅读"), danger = true) {
                    all.forEach { SavedItems.remove(it.id) }
                }
            ))
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("类型"), color = g.fgMuted, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Dropdown(
                    label = filters.getOrElse(kindFilter) { tr("全部") },
                    options = filters,
                    onSelect = { kindFilter = it },
                    active = kindFilter != 0,
                )
            }
            HDivider()
            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                EmptyState(
                    msg = tr("暂无稍后阅读内容，可以在 Issue、PR、讨论或仓库的右上角菜单中选择「保存到稍后阅读」。"),
                    icon = R.drawable.oc_bookmark,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ScrollbarLazyColumn(Modifier.weight(1f), state = state) {
                    items(filtered, key = { it.id }) { item ->
                        SavedItemRow(item, onClick = {
                            when (item.kind) {
                                SavedKind.Issue -> nav.push(Screen.IssueDetail(item.owner, item.name, item.number ?: 0, false))
                                SavedKind.PullRequest -> nav.push(Screen.IssueDetail(item.owner, item.name, item.number ?: 0, true))
                                SavedKind.Repo -> nav.push(Screen.Repo(item.owner, item.name))
                                SavedKind.Discussion -> nav.push(Screen.DiscussionDetail(item.owner, item.name, item.number ?: 0))
                            }
                        }, onRemove = {
                            SavedItems.remove(item.id)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedItemRow(item: SavedItem, onClick: () -> Unit, onRemove: () -> Unit) {
    val g = Gh.c
    val (icon, color) = when (item.kind) {
        SavedKind.Issue -> if (item.state == "closed") (R.drawable.oc_issue_closed to g.done) else (R.drawable.oc_issue_opened to g.success)
        SavedKind.PullRequest -> if (item.state == "merged") (R.drawable.oc_git_merge to g.done) else if (item.state == "closed") (R.drawable.oc_git_pull_request_closed to g.danger) else (R.drawable.oc_git_pull_request to g.success)
        SavedKind.Repo -> (R.drawable.oc_repo to g.fgMuted)
        SavedKind.Discussion -> (R.drawable.oc_comment_discussion to g.accent)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(icon, color, 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = g.fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            val sub = when (item.kind) {
                SavedKind.Repo -> item.owner
                else -> "${item.owner}/${item.name}" + (item.number?.let { " #$it" } ?: "")
            }
            Text(sub, fontSize = 12.sp, color = g.fgMuted)
        }
        Spacer(Modifier.width(8.dp))
        OcButton(R.drawable.oc_trash, onRemove, g.fgMuted, description = tr("移出稍后阅读"))
    }
    HDivider()
}
