package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Branch
import com.mobilegh.data.CommitPush
import com.mobilegh.data.GitHub
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.OnRepoChanged
import com.mobilegh.nav.Screen
import com.mobilegh.nav.friendly
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.ScrollbarLazyColumn
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException

/**
 * 仓库最近的推送：每一行是一次推送，说明是谁、推到了哪个分支、包含几个提交、最新的提交是什么。
 *
 * 数据来自提交历史（每个分支读一段提交，按作者和时间切分），而不是仓库事件接口——
 * 后者对大仓库不完整，经常看不到最近的推送，于是页面顶部要挂一条「事件记录不完整」的提示。
 */
@Composable
fun PushesScreen(owner: String, name: String) {
    val g = Gh.c
    val nav = LocalNav.current
    var pushes by remember { mutableStateOf<List<CommitPush>?>(null) }
    var branches by remember { mutableStateOf<List<Branch>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var revision by remember { mutableStateOf(0) }
    val state = rememberLazyListState()

    LaunchedEffect(revision) {
        loading = true
        error = null
        try {
            val (list, br) = GitHub.commitPushes(owner, name, force = revision > 0, limit = com.mobilegh.data.PUSH_PAGE)
            pushes = list
            branches = br
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            error = e.friendly()
        } finally {
            loading = false
        }
    }
    OnRepoChanged(owner, name) { revision++ }

    Page(tr("最近推送"), subtitle = "$owner/$name", onSubtitleClick = { nav.toRepoHome(owner, name) }) { pad ->
        val list = pushes
        when {
            list == null && loading -> Column(Modifier.padding(pad).fillMaxWidth().padding(24.dp)) { Loading(Modifier.fillMaxWidth()) }
            list == null && error != null -> Column(Modifier.padding(pad).fillMaxWidth().padding(24.dp)) {
                Text(error!!, color = g.danger, fontSize = 13.sp)
                Spacer(Modifier.padding(top = 12.dp))
                GhButton(tr("重试"), primary = true) { revision++ }
            }
            list.isNullOrEmpty() -> Column(Modifier.padding(pad).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Oc(R.drawable.oc_history, g.fgMuted, 28.dp)
                Text(tr("还没有提交记录"), Modifier.padding(top = 8.dp), color = g.fgMuted, fontSize = 13.sp)
            }
            else -> ScrollbarLazyColumn(Modifier.padding(pad).fillMaxWidth(), state = state) {
                items(list.size, key = { i -> list[i].headSha }) { i ->
                    PushRow(owner, name, list[i])
                }
                item {
                    Text(
                        trf("来自 {0} 个分支的提交历史", branches.size),
                        Modifier.fillMaxWidth().padding(16.dp),
                        color = g.fgMuted, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PushRow(owner: String, name: String, push: CommitPush) {
    val g = Gh.c
    val nav = LocalNav.current
    val actor = push.authorLogin ?: tr("未知用户")
    val target = if (push.isTag) tr("标签") else tr("分支")
    Row(
        Modifier.fillMaxWidth()
            .clickable {
                if (push.baseSha != null) nav.push(Screen.Compare(owner, name, base = push.baseSha, head = push.headSha))
                else nav.push(Screen.CommitDetail(owner, name, push.headSha))
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(push.authorAvatar, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (push.commitCount > 1) trf("{0} 推送了 {1} 个提交到{2} {3}", actor, push.commitCount, target, push.ref)
                else trf("{0} 推送了 1 个提交到{1} {2}", actor, target, push.ref),
                color = g.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (push.headMessage.isNotBlank()) Text(
                push.headMessage, color = g.fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Oc(R.drawable.oc_clock, g.fgMuted, 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(push.at.takeIf { it.isNotBlank() }?.let { relTime(it) }.orEmpty(), color = g.fgMuted, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                Text(push.headSha.take(7), color = g.fgMuted, fontSize = 12.sp)
            }
        }
    }
}
