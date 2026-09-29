package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import androidx.compose.ui.text.input.TextFieldValue
import com.mobilegh.data.Comment
import com.mobilegh.ui.components.MarkdownPreview
import com.mobilegh.ui.components.MarkdownToolbar
import com.mobilegh.ui.components.MarkdownEditor
import com.mobilegh.data.GitHub
import com.mobilegh.data.Issue
import com.mobilegh.data.Pull
import com.mobilegh.data.Review
import com.mobilegh.data.User
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.act
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.IssueItem
import com.mobilegh.ui.components.LabelChip
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.PullItem
import com.mobilegh.ui.components.StateBadge
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.escapeHtml
import com.mobilegh.ui.components.issueState
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.parsePatch
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.share
import com.mobilegh.ui.components.sizedAvatar
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.components.MdText
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

@Composable
fun IssuesScreen(owner: String, name: String, pulls: Boolean) {
    val nav = LocalNav.current
    var st by rememberSaveable { mutableIntStateOf(0) }
    val states = listOf("open" to "开启", "closed" to "已关闭", "all" to "全部")
    val issues = rememberPager("issues:$st") { p, f -> GitHub.issues(owner, name, states[st].first, p, f) }
    val prs = rememberPager("pulls:$st") { p, f -> GitHub.pulls(owner, name, states[st].first, p, f) }
    Page(
        if (pulls) "Pull Requests" else "Issues", subtitle = "$owner/$name",
        floating = {
            if (!pulls) FloatingActionButton({ nav.push(Screen.NewIssue(owner, name)) }, containerColor = Gh.c.btnPrimary, contentColor = Color.White) {
                Oc(R.drawable.oc_plus, Color.White, 20.dp)
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Chips(states.map { it.second }, st) { st = it }
            HDivider()
            if (pulls) {
                PagedList(prs, empty = "没有 Pull Request") { pr -> PullItem(pr) { nav.push(Screen.IssueDetail(owner, name, pr.number, true)) } }
            } else {
                PagedList(issues, empty = "没有 Issue") { i -> IssueItem(i) { nav.push(Screen.IssueDetail(owner, name, i.number, false)) } }
            }
        }
    }
}

class IssueThread(val issue: Issue, val pull: Pull?, val comments: List<Comment>, val reviews: List<Review>)

@Composable
fun IssueDetailScreen(owner: String, name: String, number: Int, isPull: Boolean) {
    val g = Gh.c
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val data = rememberLoader("thread") { f ->
        coroutineScope {
            val i = async { GitHub.issue(owner, name, number, f) }
            val c = async { GitHub.issueComments(owner, name, number, f) }
            val p = if (isPull) async { GitHub.pull(owner, name, number, f) } else null
            val r = if (isPull) async { runCatching { GitHub.pullReviews(owner, name, number, f) }.getOrDefault(emptyList<Review>()) } else null
            IssueThread(i.await(), p?.await(), c.await(), r?.await().orEmpty())
        }
    }
    var comment by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var previewComment by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var mergeDialog by remember { mutableStateOf(false) }
    var reviewDialog by remember { mutableStateOf(false) }
    val url = "https://github.com/$owner/$name/${if (isPull) "pull" else "issues"}/$number"
    val t = data.data

    fun reload() = data.load(true)

    Page(
        "#$number", subtitle = "$owner/$name",
        actions = {
            MoreMenu(buildList {
                if (t != null) {
                    val open = t.issue.state == "open"
                    if (isPull) {
                        val pr = t.pull
                        if (open && pr != null && !pr.merged) {
                            add(MenuAction("合并 Pull Request…") { mergeDialog = true })
                            add(MenuAction("提交审查…") { reviewDialog = true })
                            add(MenuAction("关闭 Pull Request", danger = true) {
                                entry.act({ ctx.toast(it) }, ::reload) { GitHub.setPullState(owner, name, number, false) }
                            })
                        } else if (pr != null && !pr.merged) {
                            add(MenuAction("重新开启") { entry.act({ ctx.toast(it) }, ::reload) { GitHub.setPullState(owner, name, number, true) } })
                        }
                        add(MenuAction("查看文件变更") { nav.push(Screen.PullFiles(owner, name, number)) })
                    } else if (open) {
                        add(MenuAction("关闭（已完成）") { entry.act({ ctx.toast(it) }, ::reload) { GitHub.setIssueState(owner, name, number, false, "completed") } })
                        add(MenuAction("关闭（不计划）") { entry.act({ ctx.toast(it) }, ::reload) { GitHub.setIssueState(owner, name, number, false, "not_planned") } })
                    } else {
                        add(MenuAction("重新开启") { entry.act({ ctx.toast(it) }, ::reload) { GitHub.setIssueState(owner, name, number, true, "reopened") } })
                    }
                }
                add(MenuAction("复制链接") { ctx.copy(url) })
                add(MenuAction("分享") { ctx.share(url) })
                add(MenuAction("在浏览器打开") { ctx.openBrowser(url) })
            })
        },
        bottomBar = {
            if (t != null && !t.issue.locked) {
                Column(Modifier.background(g.header).navigationBarsPadding().imePadding()) {
                    HDivider()
                    MarkdownToolbar(
                        comment, { comment = it }, previewComment, { previewComment = it },
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (previewComment) {
                            Box(Modifier.weight(1f).heightIn(min = 44.dp, max = 200.dp).verticalScroll(rememberScrollState())) {
                                MarkdownPreview(comment.text, "$owner/$name")
                            }
                        } else {
                            androidx.compose.material3.OutlinedTextField(
                                comment, { comment = it },
                                Modifier.weight(1f).heightIn(max = 160.dp),
                                placeholder = { Text("发表评论（支持 Markdown）", color = g.fgMuted) },
                                singleLine = false,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        OcButton(R.drawable.oc_upload, {
                            if (comment.text.isBlank() || sending) return@OcButton
                            sending = true
                            entry.act({ sending = false; ctx.toast(it) }) {
                                GitHub.addComment(owner, name, number, comment.text.trim())
                                comment = TextFieldValue()
                                previewComment = false
                                sending = false
                                ctx.toast("评论已发布")
                                reload()
                            }
                        }, if (comment.text.isBlank()) g.fgMuted else g.accent, enabled = !sending)
                    }
                }
            }
        },
    ) { pad ->
        LoadBox(data, Modifier.padding(pad).fillMaxSize()) { th ->
            LazyColumn(Modifier.fillMaxSize()) {
                item { ThreadHeader(owner, name, th) }
                item {
                    HtmlView(threadHtml(th), Modifier.padding(top = 12.dp), baseUrl = "https://github.com/$owner/$name/")
                }
                if (th.comments.size >= 100) item {
                    GhButton("评论较多，在浏览器查看全部", Modifier.padding(16.dp).fillMaxWidth()) { ctx.openBrowser(url) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    val pr = t?.pull
    if (mergeDialog && pr != null) MergeDialog(pr, onDismiss = { mergeDialog = false }) { method ->
        mergeDialog = false
        entry.act({ ctx.toast(it) }, ::reload) { GitHub.merge(owner, name, number, method); ctx.toast("已合并") }
    }
    if (reviewDialog) ReviewDialog(onDismiss = { reviewDialog = false }) { ev, body ->
        reviewDialog = false
        entry.act({ ctx.toast(it) }, ::reload) { GitHub.review(owner, name, number, ev, body); ctx.toast("审查已提交") }
    }
}

@Composable
private fun ThreadHeader(owner: String, name: String, th: IssueThread) {
    val g = Gh.c
    val nav = LocalNav.current
    val i = th.issue
    val pr = th.pull
    val st = issueState(i.state, i.stateReason, pr != null, pr?.merged == true || i.pullRequest?.mergedAt != null, pr?.draft ?: i.draft)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            "$owner/$name", fontSize = 13.sp, color = g.fgMuted,
            modifier = Modifier.clickable { nav.push(Screen.Repo(owner, name)) },
        )
        Spacer(Modifier.height(4.dp))
        MdText(i.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateBadge(st)
            Spacer(Modifier.width(8.dp))
            Text(
                "${i.user?.login ?: ""} 开启于 ${relTime(i.createdAt)} · ${i.comments} 条评论",
                fontSize = 13.sp, color = g.fgMuted, maxLines = 2,
            )
        }
        if (pr != null) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().background(g.canvasSubtle, RoundedCornerShape(8.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "${pr.commits} 个提交：${pr.head.label} → ${pr.base.ref}",
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = g.accent,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${pr.changedFiles} 个文件  ", fontSize = 13.sp, color = g.fg)
                    Text("+${pr.additions} ", fontSize = 13.sp, color = g.success, fontWeight = FontWeight.SemiBold)
                    Text("−${pr.deletions}", fontSize = 13.sp, color = g.danger, fontWeight = FontWeight.SemiBold)
                }
                if (pr.state == "open") {
                    val (txt, c) = when (pr.mergeableState) {
                        "clean" -> "✓ 可以合并" to g.success
                        "dirty" -> "✗ 存在冲突，需要解决后才能合并" to g.danger
                        "blocked" -> "⊘ 被分支保护规则阻止（需要审查或检查通过）" to g.attention
                        "behind" -> "↓ 落后于目标分支" to g.attention
                        "unstable" -> "! 有检查未通过" to g.attention
                        "draft" -> "草稿状态" to g.fgMuted
                        else -> "正在检查合并状态…" to g.fgMuted
                    }
                    Text(txt, fontSize = 13.sp, color = c, fontWeight = FontWeight.Medium)
                } else if (pr.merged) {
                    Text("已由 ${pr.mergedBy?.login ?: ""} 于 ${relTime(pr.mergedAt)} 合并", fontSize = 13.sp, color = g.done)
                }
                if (pr.requestedReviewers.isNotEmpty()) {
                    Text("等待审查：" + pr.requestedReviewers.joinToString { it.login }, fontSize = 12.sp, color = g.fgMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhButton("文件变更", icon = R.drawable.oc_file_diff) { nav.push(Screen.PullFiles(owner, name, pr.number)) }
                    GhButton("浏览分支", icon = R.drawable.oc_code) {
                        val repo = pr.head.repo
                        if (repo != null) nav.push(Screen.Files(repo.owner.login, repo.name, "", pr.head.ref))
                    }
                }
            }
        }
        if (i.labels.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                i.labels.forEach { LabelChip(it.name, it.color) }
            }
        }
        if (i.assignees.isNotEmpty() || i.milestone != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (i.assignees.isNotEmpty()) {
                    Text("指派给 ", fontSize = 12.sp, color = g.fgMuted)
                    i.assignees.forEach { a ->
                        Avatar(a.avatarUrl, 20.dp, Modifier.padding(end = 4.dp).clickable { nav.push(Screen.Profile(a.login)) })
                    }
                }
                i.milestone?.let {
                    Spacer(Modifier.width(8.dp))
                    Oc(R.drawable.oc_milestone, g.fgMuted, 14.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(it.title, fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    HDivider()
}

private fun assoc(a: String?): String? = when (a) {
    "OWNER" -> "所有者"
    "MEMBER" -> "成员"
    "COLLABORATOR" -> "协作者"
    "CONTRIBUTOR" -> "贡献者"
    "FIRST_TIME_CONTRIBUTOR" -> "首次贡献"
    else -> null
}

private fun card(user: User?, time: String?, association: String?, body: String?, extra: String? = null): String {
    val login = escapeHtml(user?.login ?: "ghost")
    val avatar = user?.avatarUrl?.let { sizedAvatar(it, 48) } ?: ""
    val badge = (extra ?: assoc(association))?.let { "<span class='tl-badge'>${escapeHtml(it)}</span>" } ?: ""
    val content = body?.takeIf { it.isNotBlank() } ?: "<p class='tl-empty'>没有提供描述。</p>"
    return "<div class='tl-item'><div class='tl-head'><img src='$avatar'><a href='https://github.com/$login'><b>$login</b></a><span>${relTime(time)}</span>$badge</div><div class='tl-body'>$content</div></div>"
}

private fun threadHtml(t: IssueThread): String = buildString {
    append("<div class='tl'>")
    append(card(t.issue.user, t.issue.createdAt, t.issue.authorAssociation, t.issue.bodyHtml ?: t.pull?.bodyHtml))
    val entries = t.comments.map { it.createdAt to it } + t.reviews.filter { it.state != "PENDING" }.map { it.submittedAt to it }
    entries.sortedBy { it.first ?: "" }.forEach { (_, e) ->
        when (e) {
            is Comment -> append(card(e.user, e.createdAt, e.authorAssociation, e.bodyHtml))
            is Review -> {
                val (label, cls) = when (e.state) {
                    "APPROVED" -> "批准了这些更改" to "approved"
                    "CHANGES_REQUESTED" -> "请求修改" to "changes"
                    "DISMISSED" -> "的审查已被驳回" to ""
                    else -> "审查了代码" to ""
                }
                if (!e.bodyHtml.isNullOrBlank()) append(card(e.user, e.submittedAt, null, e.bodyHtml, label))
                else append("<div class='tl-event $cls'><span class='dot'></span><b>${escapeHtml(e.user?.login ?: "")}</b> $label · ${relTime(e.submittedAt)}</div>")
            }
        }
    }
    append("</div>")
}

@Composable
private fun MergeDialog(pr: Pull, onDismiss: () -> Unit, onMerge: (String) -> Unit) {
    val g = Gh.c
    var method by remember { mutableStateOf("merge") }
    GhDialog("合并 #${pr.number}", onDismiss, confirm = "确认合并", onConfirm = { onMerge(method) }) {
        Column {
            listOf("merge" to "创建合并提交", "squash" to "压缩合并（Squash）", "rebase" to "变基合并（Rebase）").forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().clickable { method = k }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(method == k, { method = k }, colors = RadioButtonDefaults.colors(selectedColor = g.btnPrimary))
                    Text(v, color = g.fg)
                }
            }
            if (pr.mergeableState == "dirty") Text("注意：存在冲突，合并可能会失败", color = g.danger, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ReviewDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    val g = Gh.c
    var ev by remember { mutableStateOf("APPROVE") }
    var body by remember { mutableStateOf("") }
    GhDialog(
        "提交审查", onDismiss, confirm = "提交",
        confirmEnabled = ev == "APPROVE" || body.isNotBlank(),
        onConfirm = { onSubmit(ev, body) },
    ) {
        Column {
            GhField(body, { body = it }, "审查意见", singleLine = false, minLines = 3)
            Spacer(Modifier.height(8.dp))
            listOf("APPROVE" to "批准", "REQUEST_CHANGES" to "请求修改", "COMMENT" to "仅评论").forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().clickable { ev = k }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(ev == k, { ev = k }, colors = RadioButtonDefaults.colors(selectedColor = g.btnPrimary))
                    Text(v, color = g.fg)
                }
            }
        }
    }
}

@Composable
fun PullFilesScreen(owner: String, name: String, number: Int) {
    val g = Gh.c
    val nav = LocalNav.current
    val files = rememberLoader("files") {
        val all = ArrayList<com.mobilegh.data.CommitFile>()
        var page = 1
        while (page <= 20) {
            val l = GitHub.pullFiles(owner, name, number, page)
            all.addAll(l)
            if (l.size < 50) break
            page++
        }
        withContext(Dispatchers.Default) { all.map { ParsedFile(it, it.patch?.let(::parsePatch).orEmpty()) } }
    }
    val pr = rememberLoader("pr") { GitHub.pull(owner, name, number, it) }
    val collapsed = retain("collapsed") { mutableStateMapOf<String, Boolean>() }
    Page("文件变更", subtitle = "$owner/$name #$number") { pad ->
        LoadBox(files, Modifier.padding(pad).fillMaxSize()) { list ->
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${list.size} 个文件  ", fontSize = 14.sp, color = g.fg, fontWeight = FontWeight.SemiBold)
                        Text("+${list.sumOf { it.file.additions }} ", fontSize = 14.sp, color = g.success, fontWeight = FontWeight.SemiBold)
                        Text("−${list.sumOf { it.file.deletions }}", fontSize = 14.sp, color = g.danger, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        com.mobilegh.ui.components.TextLink("全部折叠") { list.forEach { collapsed[it.file.filename] = true } }
                    }
                }
                fileDiffs(list, collapsed, g) { f ->
                    val head = pr.data?.head
                    nav.push(Screen.FileView(head?.repo?.owner?.login ?: owner, head?.repo?.name ?: name, f.filename, head?.sha))
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
fun NewIssueScreen(owner: String, name: String) {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var busy by remember { mutableStateOf(false) }
    Page("新建 Issue", subtitle = "$owner/$name") { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            GhField(title, { title = it }, "标题")
            Spacer(Modifier.height(12.dp))
            MarkdownEditor(body, { body = it }, placeholder = "描述（支持 Markdown）", contextRepo = "$owner/$name", minLines = 8)
            Spacer(Modifier.height(16.dp))
            GhButton("提交", Modifier.fillMaxWidth(), primary = true, enabled = title.isNotBlank() && !busy) {
                busy = true
                entry.act({ busy = false; ctx.toast(it) }) {
                    val i = GitHub.createIssue(owner, name, title.trim(), body.text)
                    nav.replace(Screen.IssueDetail(owner, name, i.number, false))
                }
            }
        }
    }
}
