package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Event
import com.mobilegh.data.GitHub
import com.mobilegh.data.pushSummary
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.OnRepoChanged
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.theme.Gh

/** 仓库最近的推送：每一行是一次推送，说明是谁、推到了哪个分支、推了几个提交、最新的提交是什么。 */
@Composable
fun PushesScreen(owner: String, name: String) {
    val nav = LocalNav.current
    val pager = rememberPager("pushes", 30) { p, f -> GitHub.repoEvents(owner, name, p, f) }
    OnRepoChanged(owner, name) { pager.refresh() }
    Page(tr("最近推送"), subtitle = "$owner/$name", onSubtitleClick = { nav.toRepoHome(owner, name) }) { pad ->
        PagedList(
            pager, Modifier.padding(pad), empty = tr("最近没有推送记录"),
        ) { e -> pushSummary(e)?.let { PushRow(owner, name, e) } }
    }
}

@Composable
private fun PushRow(owner: String, name: String, event: Event) {
    val g = Gh.c
    val nav = LocalNav.current
    val s = pushSummary(event) ?: return
    val actor = event.actor.displayLogin ?: event.actor.login
    val target = if (s.isTag) tr("标签") else tr("分支")
    Row(
        Modifier.fillMaxWidth()
            // 点开即看这次推送的内容：推送前到推送后之间的提交列表与文件改动
            .clickable(enabled = s.headSha != null && s.baseSha != null) {
                nav.push(Screen.Compare(owner, name, base = s.baseSha!!, head = s.headSha!!))
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(event.actor.avatarUrl, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.commitCount?.let { trf("{0} 推送了 {1} 个提交到{2} {3}", actor, it, target, s.ref) }
                    ?: trf("{0} 推送到{1} {2}", actor, target, s.ref),
                color = g.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (s.headMessage.isNotBlank()) Text(
                s.headMessage, color = g.fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Oc(R.drawable.oc_clock, g.fgMuted, 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(event.createdAt?.let { relTime(it) }.orEmpty(), color = g.fgMuted, fontSize = 12.sp)
                s.headSha?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(it.take(7), color = g.fgMuted, fontSize = 12.sp)
                }
            }
        }
    }
}
