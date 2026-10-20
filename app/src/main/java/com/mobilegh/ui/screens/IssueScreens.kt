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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import androidx.compose.ui.text.input.TextFieldValue
import com.mobilegh.data.Api
import com.mobilegh.data.Checks
import com.mobilegh.data.Comment
import com.mobilegh.data.CopilotAssign
import com.mobilegh.data.Session
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.mobilegh.ui.components.MarkdownEditor
import com.mobilegh.data.GitHub
import com.mobilegh.data.DraftValue
import com.mobilegh.ui.components.rememberDraft
import com.mobilegh.ui.components.DraftBinding
import com.mobilegh.data.Issue
import com.mobilegh.data.IssueQuery
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
import com.mobilegh.ui.components.RadioRow
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.mobilegh.nav.friendly
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun IssuesScreen(owner: String, name: String, pulls: Boolean, preset: IssueQuery? = null) {
    val nav = LocalNav.current
    val query = rememberIssueQuery((preset ?: IssueQuery()).copy(pulls = pulls, repo = "$owner/$name"))
    var saving by remember { mutableStateOf(false) }
    val q = query.value
    Page(
        if (pulls) "Pull Requests" else "Issues", subtitle = "$owner/$name",
        actions = {
            MoreMenu(buildList {
                add(MenuAction("保存到首页…") { saving = true })
                if (q.hasFilters) add(MenuAction("清除筛选") { query.value = q.cleared() })
            })
        },
        floating = {
            if (!pulls) FloatingActionButton({ nav.push(Screen.NewIssue(owner, name)) }, containerColor = Gh.c.btnPrimary, contentColor = Color.White) {
                Oc(R.drawable.oc_plus, Color.White, 20.dp)
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            IssueFilterBar(q, { query.value = it.normalized() }, owner, name)
            IssueResults(q, { query.value = it.normalized() }, owner, name)
        }
    }
    if (saving) SaveSearchDialog(q, "$name ${if (pulls) "PR" else "Issues"}") { saving = false }
}

class IssueThread(val issue: Issue, val pull: Pull?, val comments: List<Comment>, val reviews: List<Review>, val checks: Checks? = null)

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
            val p = if (isPull) async {
                val pr = GitHub.pull(owner, name, number, f)
                // 检查结果依赖 head 提交；读取失败（如 Token 没有 checks 权限）时只是不显示
                pr to runCatching { GitHub.checks(owner, name, pr.head.sha, f) }.getOrNull()
            } else null
            val r = if (isPull) async { runCatching { GitHub.pullReviews(owner, name, number, f) }.getOrDefault(emptyList<Review>()) } else null
            val pc = p?.await()
            IssueThread(i.await(), pc?.first, c.await(), r?.await().orEmpty(), pc?.second)
        }
    }
    val draft = rememberDraft("comment:${owner.lowercase()}/${name.lowercase()}:$number")
    var comment by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.initial.body)) }
    var previewComment by remember { mutableStateOf(false) }
    var sending by retain("thread:sending") { mutableStateOf(false) }
    var mergeDialog by remember { mutableStateOf(false) }
    var reviewDialog by remember { mutableStateOf(false) }
    var metaKind by remember { mutableStateOf<String?>(null) }
    /** 正在编辑的评论：id=null 表示编辑 Issue/PR 正文 */
    var editing by remember { mutableStateOf<Pair<Long?, String>?>(null) }
    /** 标题栏就地编辑；null 表示未进入编辑 */
    var editTitle by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    var copilotConfirm by remember { mutableStateOf(false) }
    var copilotBusy by remember { mutableStateOf(false) }
    var copilotUnavailable by remember { mutableStateOf(false) }
    var writing by retain("thread:writing") { mutableStateOf(false) }
    val url = "https://github.com/$owner/$name/${if (isPull) "pull" else "issues"}/$number"
    val t = data.data

    fun reload() = data.load(true)

    fun write(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        if (writing) return
        writing = true
        entry.scope.launch {
            try {
                block()
                onSuccess()
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ctx.toast(e.friendly())
            } finally {
                writing = false
            }
        }
    }

    fun setDraft(draft: Boolean) {
        val id = t?.pull?.nodeId
        if (id.isNullOrBlank()) return
        write {
            GitHub.setPullDraft(id, draft)
            ctx.toast(if (draft) "已转为草稿" else "已标记为可评审")
        }
    }

    Page(
        "#$number", subtitle = "$owner/$name",
        actions = {
            MoreMenu(buildList {
                if (t != null) {
                    val open = t.issue.state == "open"
                    if (isPull) {
                        val pr = t.pull
                        if (open && pr != null && !pr.merged) {
                            if (pr.draft) add(MenuAction("标记为可评审") { setDraft(false) })
                            else add(MenuAction("合并 Pull Request…") { mergeDialog = true })
                            add(MenuAction("提交审查…") { reviewDialog = true })
                            add(MenuAction("请求审查…") { metaKind = "reviewers" })
                            if (!pr.draft) add(MenuAction("转为草稿") { setDraft(true) })
                            add(MenuAction("关闭 Pull Request", danger = true) {
                                write { GitHub.setPullState(owner, name, number, false) }
                            })
                        } else if (pr != null && !pr.merged) {
                            add(MenuAction("重新开启") { write { GitHub.setPullState(owner, name, number, true) } })
                        }
                        add(MenuAction("查看文件变更") { nav.push(Screen.PullFiles(owner, name, number)) })
                    } else if (open) {
                        add(MenuAction("关闭（已完成）") { write { GitHub.setIssueState(owner, name, number, false, "completed") } })
                        add(MenuAction("关闭（不计划）") { write { GitHub.setIssueState(owner, name, number, false, "not_planned") } })
                    } else {
                        add(MenuAction("重新开启") { write { GitHub.setIssueState(owner, name, number, true, "reopened") } })
                    }
                }
                add(MenuAction("复制链接") { ctx.copy(url) })
                add(MenuAction("分享") { ctx.share(url) })
                add(MenuAction("在浏览器打开") { ctx.openBrowser(url) })
            })
        },
        bottomBar = {
            if (t != null && !t.issue.locked) {
                CommentComposerBar(comment, { comment = it }, previewComment, { previewComment = it }, "$owner/$name", sending, draft) {
                    if (comment.text.isBlank() || sending) return@CommentComposerBar
                    sending = true
                    val submitted = DraftValue(body = comment.text)
                    draft.update(submitted)
                    entry.scope.launch {
                        try {
                            GitHub.addComment(owner, name, number, submitted.body.trim())
                            draft.submitted(submitted)
                            if (comment.text == submitted.body) comment = TextFieldValue()
                            previewComment = false
                            ctx.toast("评论已发布")
                            reload()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            ctx.toast(e.friendly())
                        } finally {
                            sending = false
                        }
                    }
                }
            }
        },
    ) { pad ->
        LoadBox(data, Modifier.padding(pad).fillMaxSize()) { th ->
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    ThreadHeader(
                        owner, name, th,
                        editable = !isPull || th.pull != null,
                        onEditTitle = { editTitle = th.issue.title },
                        onReady = { setDraft(false) },
                    )
                }
                val copilot = if (!isPull && th.issue.state == "open") CopilotAction(copilotBusy) { copilotConfirm = true } else null
                item { MetaBlock(th.issue, reviewers = th.pull?.let { reviewerStates(it, th.reviews) }, copilot = copilot) { metaKind = it } }
                item { Spacer(Modifier.height(8.dp)) }
                val base = "https://github.com/$owner/$name/"
                val me = Session.login
                fun quote(login: String?, text: String?) {
                    val quoted = appendQuotedReply(comment.text, login, text)
                    comment = TextFieldValue(quoted, androidx.compose.ui.text.TextRange(quoted.length))
                    previewComment = false
                    ctx.toast("已引用到评论框")
                }
                item(key = "body") {
                    val body = th.issue.body ?: th.pull?.body
                    TimelineCard(
                        th.issue.user, th.issue.createdAt, assoc(th.issue.authorAssociation), th.issue.bodyHtml ?: th.pull?.bodyHtml, base,
                        menu = buildList {
                            add(MenuAction("引用回复") { quote(th.issue.user?.login, body) })
                            // 这里只编辑正文；标题改由标题旁的铅笔直接编辑（见 ThreadHeader）
                            if (th.issue.user?.login == me) add(MenuAction("编辑正文") { editing = null to body.orEmpty() })
                            add(MenuAction("复制链接") { ctx.copy(url) })
                        },
                    ) {
                        ReactionBar(th.issue.reactions) { GitHub.toggleReaction(owner, name, number, null, it) }
                    }
                }
                val entries = (th.comments.map { it.createdAt to it as Any } + th.reviews.filter { it.state != "PENDING" }.map { it.submittedAt to it as Any })
                    .sortedBy { it.first ?: "" }
                items(entries.size, key = { i ->
                    when (val e = entries[i].second) {
                        is Comment -> "c${e.id}"
                        is Review -> "r${e.id}"
                        else -> "x$i"
                    }
                }) { idx ->
                    when (val e = entries[idx].second) {
                        is Comment -> TimelineCard(
                            e.user, e.createdAt, assoc(e.authorAssociation), e.bodyHtml, base,
                            menu = buildList {
                                add(MenuAction("引用回复") { quote(e.user?.login, e.body) })
                                if (e.user?.login == me) {
                                    add(MenuAction("编辑") { editing = e.id to e.body.orEmpty() })
                                    add(MenuAction("删除", danger = true) { deleting = e.id })
                                }
                                add(MenuAction("复制链接") { ctx.copy(e.htmlUrl) })
                            },
                        ) {
                            ReactionBar(e.reactions) { GitHub.toggleReaction(owner, name, number, e.id, it) }
                        }
                        is Review -> {
                            val (label, color) = when (e.state) {
                                "APPROVED" -> "批准了这些更改" to g.success
                                "CHANGES_REQUESTED" -> "请求修改" to g.danger
                                "DISMISSED" -> "的审查已被驳回" to g.fgMuted
                                else -> "审查了代码" to g.fgMuted
                            }
                            if (!e.bodyHtml.isNullOrBlank()) TimelineCard(e.user, e.submittedAt, label, e.bodyHtml, base, menu = emptyList())
                            else Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                val icon = when (e.state) {
                                    "APPROVED" -> R.drawable.oc_check_circle
                                    "CHANGES_REQUESTED" -> R.drawable.oc_file_diff
                                    else -> R.drawable.oc_eye
                                }
                                Oc(icon, color, 16.dp)
                                Spacer(Modifier.width(8.dp))
                                Avatar(e.user?.avatarUrl, 20.dp, Modifier.clickable { e.user?.let { nav.push(Screen.Profile(it.login)) } })
                                Spacer(Modifier.width(6.dp))
                                Text("${e.user?.login ?: ""} $label · ${relTime(e.submittedAt)}", fontSize = 13.sp, color = g.fgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (th.comments.size >= 100) item {
                    GhButton("评论较多，在浏览器查看全部", Modifier.padding(16.dp).fillMaxWidth()) { ctx.openBrowser(url) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    metaKind?.let { k ->
        t?.let { th -> MetaDialog(k, owner, name, number, th.issue, pull = th.pull, onDismiss = { metaKind = null }, onSaved = ::reload) }
    }
    editing?.let { (cid, text) ->
        key(cid) {
            EditDialog(
                if (cid == null) "编辑${if (isPull) " Pull Request" else " Issue"}" else "编辑评论",
                if (cid == null) t?.issue?.title else null, text, "$owner/$name",
                onDismiss = { editing = null }, busy = writing,
            ) { newTitle, newBody ->
                write(onSuccess = { editing = null }) {
                    // 只编辑正文时新标题为空，必须沿用原标题，否则会把标题清空
                    if (cid == null) GitHub.editIssue(owner, name, number, newTitle.ifBlank { t?.issue?.title.orEmpty() }, newBody)
                    else GitHub.editComment(owner, name, cid, newBody)
                    ctx.toast("已保存")
                }
            }
        }
    }
    // 标题栏直接编辑：只改标题时不动正文，避免把已有正文再次提交
    editTitle?.let { initial ->
        var value by remember { mutableStateOf(initial) }
        GhDialog(
            "编辑标题", { if (!writing) editTitle = null }, confirm = if (writing) "保存中…" else "保存",
            confirmEnabled = !writing && value.isNotBlank() && value.trim() != t?.issue?.title,
            onConfirm = {
                val title = value.trim()
                write(onSuccess = { editTitle = null }) {
                    Api.exec("PATCH", "/repos/$owner/$name/issues/$number", buildJsonObject { put("title", title) })
                    ctx.toast("标题已更新")
                }
            },
        ) {
            GhField(value, { if (!writing) value = it }, "标题")
        }
    }
    if (copilotConfirm) GhDialog("指派给 Copilot", { copilotConfirm = false }, confirm = "指派", onConfirm = {
        copilotConfirm = false
        copilotBusy = true
        entry.act({ copilotBusy = false; ctx.toast(it) }) {
            when (CopilotAssign.assign(owner, name, number)) {
                CopilotAssign.Result.Assigned -> { ctx.toast("已指派给 Copilot，它会创建分支并提交 Pull Request"); reload() }
                CopilotAssign.Result.AlreadyAssigned -> ctx.toast("Copilot 已是指派人")
                CopilotAssign.Result.Unavailable -> copilotUnavailable = true
            }
            copilotBusy = false
        }
    }) {
        Row(verticalAlignment = Alignment.Top) {
            Oc(R.drawable.oc_copilot, g.fg, 20.dp)
            Spacer(Modifier.width(10.dp))
            Text("Copilot 编程助手会根据此 Issue 的内容开始工作，完成后提交 Pull Request 并请你审查。每次任务会消耗 Copilot 高级请求额度。", fontSize = 14.sp, color = g.fg)
        }
    }
    if (copilotUnavailable) GhDialog("无法指派给 Copilot", { copilotUnavailable = false }, confirm = "查看文档", onConfirm = {
        copilotUnavailable = false
        ctx.openBrowser(CopilotAssign.DOCS_URL)
    }) {
        Text(
            "此仓库未启用 Copilot 编程助手，或你的账号暂无使用权限。需要 Copilot Pro、Pro+、Business 或 Enterprise 订阅；组织仓库还需管理员在策略中启用 Copilot 编程助手。",
            fontSize = 14.sp, color = g.fg,
        )
    }
    deleting?.let { cid ->
        GhDialog("删除评论", { if (!writing) deleting = null }, confirm = if (writing) "删除中…" else "删除", danger = true, confirmEnabled = !writing, onConfirm = {
            write(onSuccess = { deleting = null }) { GitHub.deleteComment(owner, name, cid); ctx.toast("已删除") }
        }) { Text("删除后无法恢复。", color = g.fg) }
    }

    val pr = t?.pull
    if (mergeDialog && pr != null) MergeDialog(pr, busy = writing, onDismiss = { mergeDialog = false }) { m ->
        write {
            GitHub.merge(owner, name, number, m.method, m.title, m.message, pr.head.sha)
            mergeDialog = false
            val repo = pr.head.repo
            if (m.deleteBranch && repo != null) {
                val err = try {
                    GitHub.deleteBranch(repo.owner.login, repo.name, pr.head.ref)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    e
                }
                ctx.toast(if (err == null) "已合并，并删除分支 ${pr.head.ref}" else "已合并，但删除分支失败：${err.message}")
            } else {
                ctx.toast("已合并")
            }
        }
    }
    if (reviewDialog) ReviewDialog(busy = writing, onDismiss = { reviewDialog = false }) { ev, body ->
        write(onSuccess = { reviewDialog = false }) { GitHub.review(owner, name, number, ev, body); ctx.toast("审查已提交") }
    }
}

@Composable
private fun ThreadHeader(
    owner: String, name: String, th: IssueThread,
    editable: Boolean, onEditTitle: () -> Unit, onReady: () -> Unit,
) {
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
        Row(verticalAlignment = Alignment.Top) {
            MdText(i.title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (editable) OcButton(R.drawable.oc_pencil, onEditTitle, description = "编辑标题和正文")
        }
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
                    Spacer(Modifier.weight(1f))
                    // 本机记录的「已查看」文件数（文件页里逐个勾选）
                    val viewed = remember(Session.viewedRevision, Session.login) { Session.viewedFiles(owner, name, pr.number).size }
                    if (pr.changedFiles > 0) Text(
                        "${viewed.coerceAtMost(pr.changedFiles)}/${pr.changedFiles} 个文件已查看",
                        fontSize = 12.sp, color = if (viewed >= pr.changedFiles) g.success else g.fgMuted,
                    )
                }
                th.checks?.let { c ->
                    ChecksSummaryRow(c) { nav.push(Screen.PullChecks(owner, name, pr.number, pr.head.sha)) }
                }
                if (pr.state == "open") {
                    val (txt, c, icon) = when (pr.mergeableState) {
                        "clean" -> Triple("可以合并", g.success, R.drawable.oc_check)
                        "dirty" -> Triple("存在冲突，需要解决后才能合并", g.danger, R.drawable.oc_x)
                        "blocked" -> Triple("被分支保护规则阻止（需要审查或检查通过）", g.attention, R.drawable.oc_circle_slash)
                        "behind" -> Triple("落后于目标分支", g.attention, R.drawable.oc_git_branch)
                        "unstable" -> Triple("有检查未通过", g.attention, R.drawable.oc_alert)
                        "draft" -> Triple("草稿状态", g.fgMuted, R.drawable.oc_git_pull_request_draft)
                        else -> Triple("正在检查合并状态…", g.fgMuted, R.drawable.oc_clock)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Oc(icon, c, 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(txt, fontSize = 13.sp, color = c, fontWeight = FontWeight.Medium)
                    }
                } else if (pr.merged) {
                    Text("已由 ${pr.mergedBy?.login ?: ""} 于 ${relTime(pr.mergedAt)} 合并", fontSize = 13.sp, color = g.done)
                }
                if (pr.draft && pr.state == "open") {
                    Text("这是一个草稿 PR，标记为可评审后才能合并，并会通知审查者。", fontSize = 12.sp, color = g.fgMuted)
                    GhButton("标记为可评审", Modifier.fillMaxWidth(), primary = true, icon = R.drawable.oc_eye, onClick = onReady)
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

private class MergeRequest(val method: String, val title: String, val message: String, val deleteBranch: Boolean)

/** 合并：方式 + 提交标题/说明（变基合并不生成合并提交，故不显示）+ 合并后删除分支 */
@Composable
private fun MergeDialog(pr: Pull, busy: Boolean, onDismiss: () -> Unit, onMerge: (MergeRequest) -> Unit) {
    val g = Gh.c
    var method by remember { mutableStateOf("merge") }
    fun defaultTitle(m: String) = when (m) {
        "squash" -> "${pr.title} (#${pr.number})"
        else -> "Merge pull request #${pr.number} from ${pr.head.label.replaceFirst(':', '/')}"
    }
    fun defaultMessage(m: String) = if (m == "squash") "" else pr.title
    var title by remember { mutableStateOf(defaultTitle("merge")) }
    var message by remember { mutableStateOf(defaultMessage("merge")) }
    var edited by remember { mutableStateOf(false) }
    // 只有分支在同一仓库、或在自己的 Fork 里时才能删除
    val repo = pr.head.repo
    val canDelete = repo != null && (repo.fullName.equals(pr.base.repo?.fullName, true) || repo.owner.login.equals(Session.login, true)) &&
        pr.head.ref != repo.defaultBranch
    var deleteBranch by remember { mutableStateOf(false) }
    GhDialog(
        "合并 #${pr.number}", { if (!busy) onDismiss() }, confirm = if (busy) "合并中…" else "确认合并",
        confirmEnabled = !busy && (method == "rebase" || title.isNotBlank()),
        onConfirm = { onMerge(MergeRequest(method, if (method == "rebase") "" else title.trim(), if (method == "rebase") "" else message, deleteBranch && canDelete)) },
    ) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            listOf("merge" to "创建合并提交", "squash" to "压缩合并（Squash）", "rebase" to "变基合并（Rebase）").forEach { (k, v) ->
                RadioRow(method == k, v, enabled = !busy) {
                    method = k
                    if (!edited) {
                        title = defaultTitle(k)
                        message = defaultMessage(k)
                    }
                }
            }
            if (method != "rebase") {
                Spacer(Modifier.height(8.dp))
                GhField(title, { if (!busy) { title = it; edited = true } }, "提交标题")
                Spacer(Modifier.height(8.dp))
                GhField(message, { if (!busy) { message = it; edited = true } }, "提交说明（可选）", singleLine = false, minLines = 2)
            } else {
                Text("变基合并会把每个提交依次应用到目标分支，不生成合并提交。", fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 4.dp))
            }
            if (canDelete) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { deleteBranch = !deleteBranch }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(deleteBranch, { deleteBranch = it }, enabled = !busy, colors = CheckboxDefaults.colors(checkedColor = g.btnPrimary))
                    Column {
                        Text("合并后删除分支", fontSize = 14.sp, color = g.fg)
                        Text(pr.head.ref, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (pr.mergeableState == "dirty") Text("注意：存在冲突，合并可能会失败", color = g.danger, fontSize = 13.sp)
        }
    }
}

/** 审查者及其最新状态：已请求但未审查的为 null */
private fun reviewerStates(pr: Pull, reviews: List<Review>): List<Pair<User, String?>> {
    val latest = LinkedHashMap<String, Pair<User, String?>>()
    reviews.filter { it.state != "PENDING" && it.user != null && it.user.login != pr.user?.login }
        .sortedBy { it.submittedAt ?: "" }
        .forEach { r ->
            // 「仅评论」不覆盖之前的批准/请求修改，与 GitHub 侧栏一致
            val prev = latest[r.user!!.login]?.second
            if (r.state != "COMMENTED" || prev == null || prev == "COMMENTED") latest[r.user.login] = r.user to r.state
        }
    pr.requestedReviewers.forEach { u -> latest[u.login] = u to null }
    return latest.values.toList()
}

@Composable
private fun ReviewDialog(busy: Boolean, onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    val g = Gh.c
    var ev by remember { mutableStateOf("APPROVE") }
    var body by remember { mutableStateOf("") }
    GhDialog(
        "提交审查", { if (!busy) onDismiss() }, confirm = if (busy) "提交中…" else "提交",
        confirmEnabled = !busy && (ev == "APPROVE" || body.isNotBlank()),
        onConfirm = { onSubmit(ev, body) },
    ) {
        Column {
            GhField(body, { if (!busy) body = it }, "审查意见", singleLine = false, minLines = 3)
            Spacer(Modifier.height(8.dp))
            listOf("APPROVE" to "批准", "REQUEST_CHANGES" to "请求修改", "COMMENT" to "仅评论").forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { ev = k }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(ev == k, { ev = k }, enabled = !busy, colors = RadioButtonDefaults.colors(selectedColor = g.btnPrimary))
                    Text(v, color = g.fg)
                }
            }
        }
    }
}
