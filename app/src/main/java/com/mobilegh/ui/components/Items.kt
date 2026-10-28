package com.mobilegh.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Event
import com.mobilegh.data.Issue
import com.mobilegh.data.Pull
import com.mobilegh.data.Repo
import com.mobilegh.data.User
import com.mobilegh.data.int
import com.mobilegh.data.obj
import com.mobilegh.data.str
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trc
import com.mobilegh.i18n.trf

@Composable
fun RepoItem(repo: Repo, showOwner: Boolean = true, extra: String? = null) {
    val nav = LocalNav.current
    val g = Gh.c
    Column(
        Modifier.fillMaxWidth().clickable { nav.push(Screen.Repo(repo.owner.login, repo.name)) }.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showOwner) {
                Avatar(repo.owner.avatarUrl, 18.dp, square = repo.owner.type == "Organization")
                Spacer(Modifier.width(8.dp))
            }
            Text(
                buildAnnotatedString {
                    if (showOwner) withStyle(SpanStyle(color = g.fgMuted)) { append(repo.owner.login + " / ") }
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = g.fg)) { append(repo.name) }
                },
                fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            when {
                repo.archived -> Pill(tr("归档"), g.attention)
                repo.isPrivate -> VisibilityBadge(true)
                repo.isTemplate -> Pill(tr("模板"))
            }
        }
        if (repo.fork && repo.parent != null) {
            Text(trf("Fork 自 {0}", repo.parent.fullName), fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 2.dp))
        }
        if (!repo.description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            MdText(repo.description, fontSize = 14.sp, color = g.fgMuted, maxLines = 2)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (repo.language != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LangDot(repo.language)
                    Spacer(Modifier.width(5.dp))
                    Text(repo.language, fontSize = 12.sp, color = g.fgMuted)
                }
            }
            IconText(R.drawable.oc_star, fmtCount(repo.stargazersCount))
            if (repo.forksCount > 0) IconText(R.drawable.oc_repo_forked, fmtCount(repo.forksCount))
            if (repo.fork && repo.parent == null) IconText(R.drawable.oc_repo_forked, "Fork")
            Text(extra ?: (tr("更新于 ") + relTime(repo.pushedAt ?: repo.updatedAt)), fontSize = 12.sp, color = g.fgMuted)
        }
    }
}

@Composable
fun UserItem(user: User, subtitle: String? = null) {
    val nav = LocalNav.current
    Row(
        Modifier.fillMaxWidth().clickable {
            nav.push(if (user.type == "Organization") Screen.Org(user.login) else Screen.Profile(user.login))
        }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 多行文字时头像与第一行对齐（GitHub 的用户列表同样顶部对齐）
        Avatar(user.avatarUrl, 40.dp, square = user.type == "Organization")
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (!user.name.isNullOrBlank()) {
                Text(user.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Gh.c.fg, maxLines = 1)
                Text(user.login, fontSize = 13.sp, color = Gh.c.fgMuted, maxLines = 1)
            } else {
                Text(user.login, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Gh.c.fg, maxLines = 1)
            }
            val sub = subtitle ?: user.bio ?: user.description
            if (!sub.isNullOrBlank()) MdText(sub, fontSize = 13.sp, color = Gh.c.fgMuted, maxLines = 2)
        }
    }
}

@Composable
fun IssueItem(issue: Issue, repoName: String? = null, onClick: () -> Unit) {
    val g = Gh.c
    val isPull = issue.pullRequest != null
    val st = issueState(issue.state, issue.stateReason, isPull, issue.pullRequest?.mergedAt != null, issue.draft)
    val (icon, color, _) = stateVisual(st)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Oc(icon, color, 16.dp, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (repoName != null) Text("$repoName #${issue.number}", fontSize = 12.sp, color = g.fgMuted)
            MdText(issue.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 3)
            if (issue.labels.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    issue.labels.forEach { LabelChip(it.name, it.color) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                (if (repoName == null) "#${issue.number} · " else "") + trf("{0} 开启于 {1}", issue.user?.login ?: "", relTime(issue.createdAt)),
                fontSize = 12.sp, color = g.fgMuted,
            )
        }
        if (issue.comments > 0) {
            Spacer(Modifier.width(8.dp))
            IconText(R.drawable.oc_comment, issue.comments.toString())
        }
    }
}

@Composable
fun PullItem(pr: Pull, onClick: () -> Unit) {
    val g = Gh.c
    val st = issueState(pr.state, null, true, pr.mergedAt != null, pr.draft)
    val (icon, color, _) = stateVisual(st)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Oc(icon, color, 16.dp, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            MdText(pr.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 3)
            if (pr.labels.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    pr.labels.forEach { LabelChip(it.name, it.color) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(trf("#{0} · {1} 开启于 {2}", pr.number, pr.user?.login ?: "", relTime(pr.createdAt)), fontSize = 12.sp, color = g.fgMuted)
            if (pr.head.label.isNotBlank() && pr.base.ref.isNotBlank())
                Text("${pr.head.label} → ${pr.base.ref}", fontSize = 12.sp, color = g.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 动态流条目（关注的人 star/fork/push 等） */
@Composable
fun EventItem(e: Event) {
    val nav = LocalNav.current
    val g = Gh.c
    val (o, r) = e.repo.name.split('/').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
    val (icon, action, detail, target) = describeEvent(e, o, r)
    Row(
        Modifier.fillMaxWidth().clickable { nav.push(target) }.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Avatar(e.actor.avatarUrl, 32.dp, Modifier.clickable { nav.push(Screen.Profile(e.actor.login)) })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = g.fg)) { append(e.actor.displayLogin ?: e.actor.login) }
                    withStyle(SpanStyle(color = g.fgMuted)) { append(" $action ") }
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = g.fg)) { append(e.repo.name) }
                },
                fontSize = 14.sp, lineHeight = 20.sp,
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                MdText(detail, fontSize = 13.sp, color = g.fgMuted, maxLines = 3)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Oc(icon, g.fgMuted, 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(relTime(e.createdAt), fontSize = 12.sp, color = g.fgMuted)
            }
        }
    }
}

/** 动态分类（用于按类型筛选信息流） */
enum class EventCat(private val labelZh: String) { All("全部"), Push("提交"), Pr("PR"), Issue("Issue"), Release("发布"), Social("Star/Fork"), Other("其他");
    val label: String get() = trc(labelZh, "cat") }

fun eventCat(e: Event): EventCat = when (e.type) {
    "PushEvent", "CreateEvent", "DeleteEvent", "GollumEvent", "CommitCommentEvent" -> EventCat.Push
    "PullRequestEvent", "PullRequestReviewEvent", "PullRequestReviewCommentEvent" -> EventCat.Pr
    "IssuesEvent" -> EventCat.Issue
    "IssueCommentEvent" -> if (e.payload.obj("issue")?.obj("pull_request") != null) EventCat.Pr else EventCat.Issue
    "ReleaseEvent" -> EventCat.Release
    "WatchEvent", "ForkEvent", "PublicEvent" -> EventCat.Social
    else -> EventCat.Other
}

private data class EventDesc(val icon: Int, val action: String, val detail: String?, val target: Screen)

private fun describeEvent(e: Event, o: String, r: String): EventDesc {
    val p = e.payload
    val repo = Screen.Repo(o, r)
    return when (e.type) {
        "WatchEvent" -> EventDesc(R.drawable.oc_star, tr("star 了"), null, repo)
        "ForkEvent" -> EventDesc(R.drawable.oc_repo_forked, tr("fork 了"), p.obj("forkee").str("full_name")?.let { "→ $it" }, repo)
        "CreateEvent" -> {
            val t = p.str("ref_type")
            if (t == "repository") EventDesc(R.drawable.oc_repo, tr("创建了仓库"), p.str("description"), repo)
            else EventDesc(if (t == "tag") R.drawable.oc_tag else R.drawable.oc_git_branch, trf("创建了{0} {1} 于", if (t == "tag") trc("标签", "event") else trc("分支", "event"), p.str("ref") ?: ""), null, repo)
        }
        "DeleteEvent" -> EventDesc(R.drawable.oc_trash, trf("删除了{0} {1} 于", if (p.str("ref_type") == "tag") tr("标签") else tr("分支"), p.str("ref") ?: ""), null, repo)
        "PushEvent" -> {
            val branch = p.str("ref")?.removePrefix("refs/heads/")
            val commits = (p["commits"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.str("message")?.lineSequence()?.firstOrNull() }
            val size = p.int("size") ?: commits?.size
            EventDesc(
                R.drawable.oc_repo_push,
                trf("推送{0}到 {1} @", size?.let { trf(" {0} 个提交", it) } ?: "", branch ?: ""),
                commits?.take(3)?.joinToString("\n") { "• $it" },
                Screen.Commits(o, r, branch),
            )
        }
        "IssuesEvent" -> {
            val n = p.obj("issue").int("number") ?: 0
            EventDesc(R.drawable.oc_issue_opened, trf("{0}了 Issue #{1} 于", actionZh(p.str("action")), n), p.obj("issue").str("title"), Screen.IssueDetail(o, r, n, false))
        }
        "IssueCommentEvent" -> {
            val issue = p.obj("issue")
            val n = issue.int("number") ?: 0
            val isPr = issue.obj("pull_request") != null
            EventDesc(R.drawable.oc_comment, trf("评论了 #{0} 于", n), p.obj("comment").str("body")?.take(200), Screen.IssueDetail(o, r, n, isPr))
        }
        "PullRequestEvent" -> {
            val pr = p.obj("pull_request")
            val n = p.int("number") ?: pr.int("number") ?: 0
            val merged = pr.str("merged") == "true"
            val act = if (p.str("action") == "closed" && merged) tr("合并") else actionZh(p.str("action"))
            EventDesc(R.drawable.oc_git_pull_request, trf("{0}了 PR #{1} 于", act, n), pr.str("title"), Screen.IssueDetail(o, r, n, true))
        }
        "PullRequestReviewEvent" -> {
            val n = p.obj("pull_request").int("number") ?: 0
            EventDesc(R.drawable.oc_eye, trf("审查了 PR #{0} 于", n), p.obj("pull_request").str("title"), Screen.IssueDetail(o, r, n, true))
        }
        "PullRequestReviewCommentEvent" -> {
            val n = p.obj("pull_request").int("number") ?: 0
            EventDesc(R.drawable.oc_comment, trf("评论了 PR #{0} 于", n), p.obj("comment").str("body")?.take(200), Screen.IssueDetail(o, r, n, true))
        }
        "ReleaseEvent" -> EventDesc(R.drawable.oc_tag, trf("发布了 {0} 于", p.obj("release").str("tag_name") ?: ""), p.obj("release").str("name"), Screen.Releases(o, r))
        "PublicEvent" -> EventDesc(R.drawable.oc_globe, tr("公开了"), null, repo)
        "MemberEvent" -> EventDesc(R.drawable.oc_person, trf("添加了协作者 {0} 到", p.obj("member").str("login") ?: ""), null, repo)
        "GollumEvent" -> EventDesc(R.drawable.oc_book, tr("编辑了 Wiki"), null, repo)
        "CommitCommentEvent" -> EventDesc(R.drawable.oc_comment, tr("评论了提交于"), p.obj("comment").str("body")?.take(200), repo)
        "DiscussionEvent" -> EventDesc(R.drawable.oc_comment_discussion, tr("发起了讨论于"), p.obj("discussion").str("title"), repo)
        "SponsorshipEvent" -> EventDesc(R.drawable.oc_heart, tr("赞助了"), null, repo)
        else -> EventDesc(R.drawable.oc_pulse, e.type?.removeSuffix("Event") ?: tr("动态"), null, repo)
    }
}

private fun actionZh(a: String?) = when (a) {
    "opened" -> trc("开启", "event")
    "closed" -> trc("关闭", "event")
    "reopened" -> tr("重新开启")
    "edited" -> trc("编辑", "event")
    "assigned" -> trc("指派", "event")
    "labeled" -> trc("标记", "event")
    "synchronize" -> trc("更新", "event")
    "review_requested" -> trc("请求审查", "event")
    else -> a ?: ""
}
