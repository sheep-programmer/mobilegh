package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.data.MergeQueueEntry
import com.mobilegh.data.MergeQueues
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.theme.Gh

/** 网页 /owner/repo/queue 的应用内版本：列出合并队列中的 PR，点按进入讨论页 */
@Composable
fun MergeQueueScreen(owner: String, name: String) {
    val g = Gh.c
    val nav = LocalNav.current
    val queue = rememberLoader("merge-queue") { MergeQueues.load(owner, name) }
    val info = queue.data
    val error = queue.error
    Page(tr("合并队列"), subtitle = "$owner/$name") { pad ->
        PullToRefreshBox(queue.refreshing, { queue.refresh() }, Modifier.padding(pad)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                when {
                    error != null -> Text(error, Modifier.padding(16.dp), fontSize = 13.sp, color = g.danger)
                    info == null -> Loading(Modifier.fillMaxWidth().heightIn(min = 200.dp))
                    !info.enabled -> Text(
                        tr("这个仓库的默认分支没有启用合并队列。"),
                        Modifier.padding(16.dp), fontSize = 13.sp, color = g.fgMuted,
                    )
                    info.entries.isEmpty() -> Text(
                        tr("合并队列是空的。"),
                        Modifier.padding(16.dp), fontSize = 13.sp, color = g.fgMuted,
                    )
                    else -> Card(Modifier.padding(horizontal = 16.dp)) {
                        Column {
                            info.entries.forEachIndexed { i, entry ->
                                if (i > 0) HDivider()
                                QueueRow(entry) {
                                    entry.pullRequest?.let { nav.push(Screen.IssueDetail(owner, name, it.number, true)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(entry: MergeQueueEntry, onClick: () -> Unit) {
    val g = Gh.c
    val pr = entry.pullRequest
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = pr != null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.position > 0) {
            Text("${entry.position}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.fgMuted)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                pr?.let { "#${it.number} ${it.title}" } ?: tr("（PR 已不可见）"),
                fontSize = 14.sp, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            entry.enqueuedAt?.let { Text(trf("入队 {0}", relTime(it)), fontSize = 12.sp, color = g.fgMuted) }
        }
        Spacer(Modifier.width(8.dp))
        Pill(queueStateLabel(entry.state), queueStateColor(entry.state))
    }
}

private fun queueStateLabel(state: String): String = when (state) {
    "QUEUED" -> tr("排队中")
    "AWAITING_CHECKS" -> tr("等待检查")
    "MERGEABLE" -> tr("可以合并")
    "UNMERGEABLE" -> tr("无法合并")
    "LOCKED" -> tr("已锁定")
    else -> state
}

@Composable
private fun queueStateColor(state: String) = when (state) {
    "MERGEABLE" -> Gh.c.success
    "AWAITING_CHECKS" -> Gh.c.attention
    "UNMERGEABLE" -> Gh.c.danger
    "LOCKED" -> Gh.c.severe
    else -> Gh.c.fgMuted
}
