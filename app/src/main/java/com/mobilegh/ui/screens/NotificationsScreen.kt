package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mobilegh.data.Notification
import com.mobilegh.nav.Links
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.nav.act
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.Badges
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.components.MdText
import com.mobilegh.ui.theme.Gh

private fun reasonZh(r: String) = when (r) {
    "assign" -> "指派给你"
    "author" -> "你创建的"
    "comment" -> "你评论过"
    "ci_activity" -> "CI 运行"
    "invitation" -> "邀请"
    "manual" -> "你订阅了"
    "mention" -> "提及你"
    "review_requested" -> "请求你审查"
    "security_alert" -> "安全警报"
    "state_change" -> "状态变化"
    "subscribed" -> "Watching"
    "team_mention" -> "提及你的团队"
    "approval_requested" -> "等待你批准"
    else -> r
}

@Composable
fun NotificationsScreen() {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val g = Gh.c
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val pager = rememberPager("notif:$filter") { p, f -> GitHub.notifications(filter == 1, filter == 2, p, f) }
    val read = remember { mutableStateOf(setOf<String>()) }
    val done = remember { mutableStateOf(setOf<String>()) }
    var confirmAll by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    LaunchedEffect(nav.reselect) { if (nav.reselect > 0 && nav.tab == Tab.Notifications) state.animateScrollToItem(0) }
    LaunchedEffect(pager.items.size, read.value, filter) {
        if (filter == 0 && pager.loadedOnce) Badges.unread = pager.items.count { it.unread && it.id !in read.value && it.id !in done.value }
    }

    fun open(n: Notification) {
        if (n.unread && n.id !in read.value) {
            read.value = read.value + n.id
            entry.act { GitHub.markRead(n.id) }
        }
        val (o, r) = n.repository.owner.login to n.repository.name
        val target = n.subject.url?.let { Links.route(it) }
            ?: when (n.subject.type) {
                "CheckSuite", "WorkflowRun" -> Screen.Actions(o, r)
                "Release" -> Screen.Releases(o, r)
                "RepositoryInvitation" -> Screen.Invitations
                else -> Screen.Repo(o, r)
            }
        nav.push(target)
    }

    Page(
        "通知", back = false, contentWindowInsets = WindowInsets(0),
        actions = { OcButton(R.drawable.oc_checklist, { confirmAll = true }) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Chips(listOf("未读", "全部", "参与的"), filter) { filter = it }
            HDivider()
            PagedList(pager, state = state, empty = "全部处理完了 🎉") { n ->
                if (n.id in done.value) return@PagedList
                val unread = n.unread && n.id !in read.value
                val icon = when (n.subject.type) {
                    "Issue" -> R.drawable.oc_issue_opened
                    "PullRequest" -> R.drawable.oc_git_pull_request
                    "Release" -> R.drawable.oc_tag
                    "Commit" -> R.drawable.oc_git_commit
                    "Discussion" -> R.drawable.oc_comment_discussion
                    "CheckSuite", "WorkflowRun" -> R.drawable.oc_play
                    "RepositoryInvitation" -> R.drawable.oc_mail
                    "RepositoryVulnerabilityAlert", "RepositoryDependabotAlertsThread" -> R.drawable.oc_shield_lock
                    else -> R.drawable.oc_bell
                }
                Row(Modifier.fillMaxWidth().clickable { open(n) }.padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
                    Box(Modifier.width(12.dp).padding(top = 6.dp), contentAlignment = Alignment.Center) {
                        if (unread) Box(Modifier.size(8.dp).background(g.accent, CircleShape))
                    }
                    Spacer(Modifier.width(4.dp))
                    Oc(icon, if (unread) g.fg else g.fgMuted, 16.dp, Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.repository.fullName, fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(relTime(n.updatedAt), fontSize = 12.sp, color = g.fgMuted)
                        }
                        MdText(n.subject.title, fontSize = 15.sp, maxLines = 3, fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal)
                        Text(reasonZh(n.reason), fontSize = 12.sp, color = g.fgMuted)
                    }
                    OcButton(R.drawable.oc_check, {
                        done.value = done.value + n.id
                        read.value = read.value + n.id
                        entry.act({ ctx.toast(it) }) { GitHub.markDone(n.id) }
                    }, g.fgMuted)
                }
            }
        }
    }
    if (confirmAll) {
        GhDialog("全部标为已读", { confirmAll = false }, onConfirm = {
            confirmAll = false
            entry.act({ ctx.toast(it) }) {
                GitHub.markAllRead()
                Badges.unread = 0
                ctx.toast("已全部标为已读")
                pager.refresh()
            }
        }) { Text("确定将所有通知标记为已读吗？", color = g.fg) }
    }
}
