package com.mobilegh.ui.screens

import com.mobilegh.ui.components.Loading
import com.mobilegh.data.ContentEdits
import com.mobilegh.ui.components.attachmentMarkdown
import com.mobilegh.ui.components.insertAtCursor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.mobilegh.ui.components.cappedFlingBehavior
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
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
import com.mobilegh.data.CommentAttachments
import com.mobilegh.data.RepoGovernance
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
import com.mobilegh.data.DraftController
import com.mobilegh.data.DraftValue
import com.mobilegh.data.Drafts
import com.mobilegh.nav.refreshRetained
import com.mobilegh.ui.components.rememberDraft
import com.mobilegh.ui.components.DraftBinding
import com.mobilegh.data.Issue
import com.mobilegh.data.IssueQuery
import com.mobilegh.data.relatedIssueLabel
import com.mobilegh.data.relatedIssueRepo
import com.mobilegh.data.PendingReview
import com.mobilegh.data.Pull
import com.mobilegh.data.Review
import com.mobilegh.data.ThreadAccess
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
import com.mobilegh.ui.components.rememberCommentAttachments
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
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

@Composable
fun IssuesScreen(owner: String, name: String, pulls: Boolean, preset: IssueQuery? = null) {
    val nav = LocalNav.current
    val query = rememberIssueQuery((preset ?: IssueQuery()).copy(pulls = pulls, repo = "$owner/$name"))
    var saving by remember { mutableStateOf(false) }
    val q = query.value
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    // 归档或关闭了 Issues 的仓库不能新建；仓库信息未读到时照常显示
    val canOpenIssue = repo.data?.let { !it.archived && it.hasIssues } ?: true
    Page(
        if (pulls) "Pull Requests" else "Issues", subtitle = "$owner/$name",
        actions = {
            MoreMenu(buildList {
                add(MenuAction(tr("保存到首页…")) { saving = true })
                if (q.hasFilters) add(MenuAction(tr("清除筛选")) { query.value = q.cleared() })
            })
        },
        floating = {
            if (!pulls && canOpenIssue) FloatingActionButton({ nav.push(Screen.NewIssue(owner, name)) }, containerColor = Gh.c.btnPrimary, contentColor = Color.White) {
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

/** 关闭 / 重新开启 Issue 或 PR 前的确认内容；action 执行真正的写入 */
private class StateChange(
    val title: String,
    val confirm: String,
    val message: String,
    val danger: Boolean,
    val action: suspend () -> Unit,
)

/**
 * 在本地检出 PR 的命令（GitHub 的 pull/<n>/head 引用）。把 PR 拉成本地分支 pr-<n>，不做远程检出。
 */
internal fun prCheckoutCommand(number: Int): String = "git fetch origin pull/$number/head:pr-$number && git checkout pr-$number"

@Composable
fun IssueDetailScreen(owner: String, name: String, number: Int, isPull: Boolean, commentId: Long? = null) {
    val g = Gh.c
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val listState = rememberLazyListState()
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
    // 审查意见按 PR 保存草稿，关闭对话框或离开页面后再打开仍在
    val reviewDraft = rememberDraft("review:${owner.lowercase()}/${name.lowercase()}:$number")
    var comment by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.initial.body)) }
    var previewComment by remember { mutableStateOf(false) }
    var sending by retain("thread:sending") { mutableStateOf(false) }
    var mergeDialog by remember { mutableStateOf(false) }
    var autoMergeDialog by remember { mutableStateOf(false) }
    var reviewDialog by remember { mutableStateOf(false) }
    /** 待确认的关闭 / 重新开启操作；null 表示没有待确认的操作 */
    var stateChange by remember { mutableStateOf<StateChange?>(null) }
    var metaKind by remember { mutableStateOf<String?>(null) }
    /** 正在编辑的评论：id=null 表示编辑 Issue/PR 正文 */
    var editing by remember { mutableStateOf<Pair<Long?, String>?>(null) }
    /** 编辑记录：非空时打开对话框，值为 (GraphQL 节点 id, 标题) */
    var revisionsOf by remember { mutableStateOf<Pair<String, String>?>(null) }
    revisionsOf?.let { (id, title) -> EditHistoryDialog(id, title) { revisionsOf = null } }
    /** 标题栏就地编辑；null 表示未进入编辑 */
    var editTitle by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    var copilotConfirm by remember { mutableStateOf(false) }
    var copilotBusy by remember { mutableStateOf(false) }
    var copilotUnavailable by remember { mutableStateOf(false) }
    var writing by retain("thread:writing") { mutableStateOf(false) }
    val url = "https://github.com/$owner/$name/${if (isPull) "pull" else "issues"}/$number"
    val t = data.data
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    val access = ThreadAccess.of(repo.data?.permissions, repo.data?.archived == true, t?.issue?.user?.login, Session.login)
    // 插入到光标处；视频用原生播放器的链接形式，图片保持 GitHub 的 Markdown 图语法（与新建 Issue 共用）
    val attachLauncher = rememberCommentAttachments(repo?.data?.id) { url, alt ->
        comment = insertAtCursor(comment, attachmentMarkdown(url, alt))
    }
    /** 相关议题（网页版 Issue 侧栏的 Relationships）；接口不可用时整块隐藏，不影响正文 */
    val related = rememberLoader("related") { f -> GitHub.relatedIssues(owner, name, number, f) }
    var relatedPicker by remember { mutableStateOf(false) }

    fun reload() = data.load(true)

    /** 写入成功后刷新导航栈里同一仓库的列表（状态、新建会改变列表内容） */
    fun refreshIssueList() = nav.refreshRetained { s -> s is Screen.Issues && s.owner.equals(owner, true) && s.name.equals(name, true) }

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
            ctx.toast(if (draft) tr("已转为草稿") else tr("已标记为可评审"))
        }
    }

    /** 添加相关议题后同时刷新议题正文（write 会刷新）与相关列表 */
    fun addRelated(issue: Issue) {
        if (writing) return
        write(onSuccess = {
            relatedPicker = false
            ctx.toast(tr("已添加相关议题"))
            related.load(true)
        }) { GitHub.addRelatedIssue(owner, name, number, issue.id) }
    }

    fun removeRelated(issue: Issue) {
        if (writing) return
        write(onSuccess = {
            ctx.toast(tr("已移除相关议题"))
            related.load(true)
        }) { GitHub.removeRelatedIssue(owner, name, number, issue.id) }
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
                            if (pr.draft) { if (access.toggleDraft) add(MenuAction(tr("标记为可评审")) { setDraft(false) }) }
                            else if (access.merge) add(MenuAction(tr("合并 Pull Request…")) { mergeDialog = true })
                            // 自动合并：草稿不能开启；取消时作者本人也可以操作
                            if (!pr.draft && access.merge && pr.autoMerge == null) {
                                add(MenuAction(tr("开启自动合并…")) { autoMergeDialog = true })
                            }
                            if (pr.autoMerge != null && (access.merge || access.author)) {
                                add(MenuAction(tr("取消自动合并")) {
                                    write { RepoGovernance.setAutoMerge(pr.nodeId, null) }
                                })
                            }
                            // 不能审查自己的 PR
                            if (!access.author) add(MenuAction(tr("提交审查…")) { reviewDialog = true })
                            if (access.editMeta) add(MenuAction(tr("请求审查…")) { metaKind = "reviewers" })
                            if (!pr.draft && access.toggleDraft) add(MenuAction(tr("转为草稿")) { setDraft(true) })
                            if (access.changeState) add(MenuAction(tr("关闭 Pull Request"), danger = true) {
                                stateChange = StateChange(
                                    tr("关闭 Pull Request？"), tr("关闭"), tr("关闭后仍可在网页或 App 中重新开启。"), danger = true,
                                ) { GitHub.setPullState(owner, name, number, false) }
                            })
                        } else if (pr != null && !pr.merged && access.changeState) {
                            add(MenuAction(tr("重新开启")) {
                                stateChange = StateChange(tr("重新开启 Pull Request？"), tr("重新开启"), tr("重新开启后会再次出现在开放列表中。"), danger = false) {
                                    GitHub.setPullState(owner, name, number, true)
                                }
                            })
                        }
                        add(MenuAction(tr("查看文件变更")) { nav.push(Screen.PullFiles(owner, name, number)) })
                        add(MenuAction(tr("复制检出命令")) { ctx.copy(prCheckoutCommand(number), tr("检出命令已复制")) })
                    } else if (access.changeState) {
                        if (open) {
                            add(MenuAction(tr("关闭（已完成）")) {
                                stateChange = StateChange(tr("关闭 Issue？"), tr("关闭"), tr("关闭后仍可在网页或 App 中重新开启。"), danger = true) {
                                    GitHub.setIssueState(owner, name, number, false, "completed")
                                }
                            })
                            add(MenuAction(tr("关闭（不计划）")) {
                                stateChange = StateChange(tr("关闭 Issue？"), tr("关闭"), tr("关闭后仍可在网页或 App 中重新开启。"), danger = true) {
                                    GitHub.setIssueState(owner, name, number, false, "not_planned")
                                }
                            })
                        } else {
                            add(MenuAction(tr("重新开启")) {
                                stateChange = StateChange(tr("重新开启 Issue？"), tr("重新开启"), tr("重新开启后会再次出现在开放列表中。"), danger = false) {
                                    GitHub.setIssueState(owner, name, number, true, "reopened")
                                }
                            })
                        }
                    }
                }
                add(MenuAction(tr("复制链接")) { ctx.copy(url) })
                add(MenuAction(tr("分享")) { ctx.share(url) })
                add(MenuAction(tr("在浏览器打开")) { ctx.openBrowser(url) })
            })
        },
        bottomBar = {
            if (t != null && !t.issue.locked) {
                CommentComposerBar(
                    comment, { comment = it }, previewComment, { previewComment = it },
                    "$owner/$name", sending, draft,
                    onAttach = if (!isPull || access.write || access.triage || access.author) ({ attachLauncher.pick() }) else null,
                    onSend = {
                        if (comment.text.isBlank() || sending) return@CommentComposerBar
                        sending = true
                        val submitted = DraftValue(body = comment.text)
                        draft.update(submitted)
                        // 不跟随页面的作用域：离开页面时请求照常完成。若仍绑定页面，离开会取消请求，
                        // 而服务器可能已经写入，用户再发一次就会重复。草稿只在服务器确认后清除。
                        Drafts.scope.launch {
                            try {
                                GitHub.addComment(owner, name, number, submitted.body.trim())
                                withContext(Dispatchers.Main.immediate) {
                                    draft.submitted(submitted)
                                    if (comment.text == submitted.body) comment = TextFieldValue()
                                    previewComment = false
                                    ctx.toast(tr("评论已发布"))
                                    reload()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                withContext(Dispatchers.Main.immediate) { ctx.toast(e.friendly()) }
                            } finally {
                                withContext(Dispatchers.Main.immediate) { sending = false }
                            }
                        }
                    },
                )
            }
        },

    ) { pad ->
        LoadBox(data, Modifier.padding(pad).fillMaxSize()) { th ->
            // 从 #issuecomment-N 链接进来：评论加载后滚动到它。
            // 列表项顺序固定为：标题、元数据、（Issue 才有的）相关议题、间距、正文，之后才是按时间排好的评论与审查
            val anchorIndex = commentId?.let { id ->
                val before = 4 + if (!isPull && related.data != null) 1 else 0
                val timeline = (th.comments.map { it.createdAt to it as Any } + th.reviews.filter { it.state != "PENDING" }.map { it.submittedAt to it as Any })
                    .sortedBy { it.first ?: "" }
                timeline.indexOfFirst { val e = it.second; (e as? Comment)?.id == id || (e as? Review)?.id == id }.takeIf { it >= 0 }?.let { before + it }
            }
            LaunchedEffect(anchorIndex) { anchorIndex?.let { listState.animateScrollToItem(it) } }
            LazyColumn(Modifier.fillMaxSize(), state = listState, flingBehavior = cappedFlingBehavior()) {
                item {
                    ThreadHeader(
                        owner, name, th,
                        editable = (!isPull || th.pull != null) && access.editTitle,
                        onEditTitle = { editTitle = th.issue.title },
                        onReady = { setDraft(false) },
                    )
                }
                val copilot = if (!isPull && th.issue.state == "open" && access.write) CopilotAction(copilotBusy) { copilotConfirm = true } else null
                item { MetaBlock(th.issue, reviewers = th.pull?.let { reviewerStates(it, th.reviews) }, copilot = copilot, onEdit = if (access.editMeta) ({ metaKind = it }) else null) }
                // 相关议题只在 Issue 上展示（与网页版 Issue 侧栏一致，没有关联时也显示空状态）
                val rel = related.data
                if (!isPull && rel != null) {
                    item(key = "related") {
                        RelatedCard(
                            owner = owner, name = name, issues = rel,
                            canEdit = access.triage, busy = writing,
                            onAdd = { relatedPicker = true },
                            onRemove = ::removeRelated,
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                val base = "https://github.com/$owner/$name/"
                val me = Session.login
                fun quote(login: String?, text: String?) {
                    val quoted = appendQuotedReply(comment.text, login, text)
                    comment = TextFieldValue(quoted, androidx.compose.ui.text.TextRange(quoted.length))
                    previewComment = false
                    ctx.toast(tr("已引用到评论框"))
                }
                item(key = "body") {
                    val body = th.issue.body ?: th.pull?.body
                    TimelineCard(
                        th.issue.user, th.issue.createdAt, assoc(th.issue.authorAssociation), th.issue.bodyHtml ?: th.pull?.bodyHtml, base,
                        menu = buildList {
                            add(MenuAction(tr("引用回复")) { quote(th.issue.user?.login, body) })
                            th.issue.nodeId?.takeIf { th.issue.updatedAt != null && th.issue.updatedAt != th.issue.createdAt }
                                ?.let { id -> add(MenuAction(tr("编辑记录")) { revisionsOf = id to tr("正文编辑记录") }) }
                            // 这里只编辑正文；标题改由标题旁的铅笔直接编辑（见 ThreadHeader）
                            if (th.issue.user?.login == me) add(MenuAction(tr("编辑正文")) { editing = null to body.orEmpty() })
                            add(MenuAction(tr("复制链接")) { ctx.copy(url) })
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
                                add(MenuAction(tr("引用回复")) { quote(e.user?.login, e.body) })
                                if (e.user?.login == me) {
                                    add(MenuAction(tr("编辑")) { editing = e.id to e.body.orEmpty() })
                                    add(MenuAction(tr("删除"), danger = true) { deleting = e.id })
                                }
                                e.nodeId?.takeIf { e.updatedAt != null && e.updatedAt != e.createdAt }
                                    ?.let { id -> add(MenuAction(tr("编辑记录")) { revisionsOf = id to tr("评论编辑记录") }) }
                                add(MenuAction(tr("复制链接")) { ctx.copy(e.htmlUrl) })
                            },
                        ) {
                            ReactionBar(e.reactions) { GitHub.toggleReaction(owner, name, number, e.id, it) }
                        }
                        is Review -> {
                            val (label, color) = when (e.state) {
                                "APPROVED" -> tr("批准了这些更改") to g.success
                                "CHANGES_REQUESTED" -> tr("请求修改") to g.danger
                                "DISMISSED" -> tr("的审查已被驳回") to g.fgMuted
                                else -> tr("审查了代码") to g.fgMuted
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
                if (cid == null) trf("编辑{0}", if (isPull) " Pull Request" else " Issue") else tr("编辑评论"),
                if (cid == null) t?.issue?.title else null, text, "$owner/$name",
                onDismiss = { editing = null }, busy = writing,
            ) { newTitle, newBody ->
                write(onSuccess = { editing = null }) {
                    // 只编辑正文时新标题为空，必须沿用原标题，否则会把标题清空
                    if (cid == null) GitHub.editIssue(owner, name, number, newTitle.ifBlank { t?.issue?.title.orEmpty() }, newBody)
                    else GitHub.editComment(owner, name, cid, newBody)
                    ctx.toast(tr("已保存"))
                }
            }
        }
    }
    // 标题栏直接编辑：只改标题时不动正文，避免把已有正文再次提交
    editTitle?.let { initial ->
        var value by remember { mutableStateOf(initial) }
        GhDialog(
            tr("编辑标题"), { if (!writing) editTitle = null }, confirm = if (writing) tr("保存中…") else tr("保存"),
            confirmEnabled = !writing && value.isNotBlank() && value.trim() != t?.issue?.title,
            onConfirm = {
                val title = value.trim()
                write(onSuccess = { editTitle = null }) {
                    Api.exec("PATCH", "/repos/$owner/$name/issues/$number", buildJsonObject { put("title", title) })
                    ctx.toast(tr("标题已更新"))
                }
            },
        ) {
            GhField(value, { if (!writing) value = it }, tr("标题"))
        }
    }
    if (copilotConfirm) GhDialog(tr("指派给 Copilot"), { copilotConfirm = false }, confirm = tr("指派"), onConfirm = {
        copilotConfirm = false
        copilotBusy = true
        entry.act({ copilotBusy = false; ctx.toast(it) }) {
            when (CopilotAssign.assign(owner, name, number)) {
                CopilotAssign.Result.Assigned -> { ctx.toast(tr("已指派给 Copilot，它会创建分支并提交 Pull Request")); reload() }
                CopilotAssign.Result.AlreadyAssigned -> ctx.toast(tr("Copilot 已是指派人"))
                CopilotAssign.Result.Unavailable -> copilotUnavailable = true
            }
            copilotBusy = false
        }
    }) {
        Row(verticalAlignment = Alignment.Top) {
            Oc(R.drawable.oc_copilot, g.fg, 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(tr("Copilot 编程助手会根据此 Issue 的内容开始工作，完成后提交 Pull Request 并请你审查。每次任务会消耗 Copilot 高级请求额度。"), fontSize = 14.sp, color = g.fg)
        }
    }
    if (copilotUnavailable) GhDialog(tr("无法指派给 Copilot"), { copilotUnavailable = false }, confirm = tr("查看文档"), onConfirm = {
        copilotUnavailable = false
        ctx.openBrowser(CopilotAssign.DOCS_URL)
    }) {
        Text(
            tr("此仓库未启用 Copilot 编程助手，或你的账号暂无使用权限。需要 Copilot Pro、Pro+、Business 或 Enterprise 订阅；组织仓库还需管理员在策略中启用 Copilot 编程助手。"),
            fontSize = 14.sp, color = g.fg,
        )
    }
    deleting?.let { cid ->
        GhDialog(tr("删除评论"), { if (!writing) deleting = null }, confirm = if (writing) tr("删除中…") else tr("删除"), danger = true, confirmEnabled = !writing, onConfirm = {
            write(onSuccess = { deleting = null }) { GitHub.deleteComment(owner, name, cid); ctx.toast(tr("已删除")) }
        }) { Text(tr("删除后无法恢复。"), color = g.fg) }
    }
    if (relatedPicker) RelatedIssuePicker(
        owner, name, number, related.data.orEmpty(), busy = writing,
        onDismiss = { if (!writing) relatedPicker = false },
        onPick = ::addRelated,
    )
    stateChange?.let { change ->
        GhDialog(
            change.title, { if (!writing) stateChange = null }, confirm = if (writing) tr("处理中…") else change.confirm,
            danger = change.danger, confirmEnabled = !writing,
            onConfirm = {
                write(onSuccess = { stateChange = null }) {
                    change.action()
                    refreshIssueList()
                }
            },
        ) { Text(change.message, color = g.fg) }
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
                ctx.toast(if (err == null) trf("已合并，并删除分支 {0}", pr.head.ref) else trf("已合并，但删除分支失败：{0}", err.message))
            } else {
                ctx.toast(tr("已合并"))
            }
        }
    }
    if (autoMergeDialog && pr != null) AutoMergeDialog(busy = writing, onDismiss = { autoMergeDialog = false }) { method ->
        autoMergeDialog = false
        write {
            RepoGovernance.setAutoMerge(pr.nodeId, method)
            ctx.toast(tr("已开启自动合并"))
        }
    }
    if (reviewDialog) ReviewDialog(
        owner, name, number, busy = writing, draft = reviewDraft,
        onDismiss = { reviewDialog = false },
        onDiscard = {
            write(onSuccess = { reviewDialog = false }) {
                GitHub.discardPendingReview(owner, name, number)
                ctx.toast(tr("已放弃待提交审查"))
            }
        },
    ) { ev, body ->
        write(onSuccess = {
            reviewDialog = false
            // 只有提交成功后才清除草稿
            reviewDraft.submitted(DraftValue(body = body))
        }) { GitHub.submitReview(owner, name, number, ev, body); ctx.toast(tr("审查已提交")) }
    }
}

/**
 * Issue 侧栏的「相关」卡片：列出与当前议题关联的议题，可跳转、添加、移除。
 * 跨仓库的关联显示 owner/name#123（与 GitHub 网页一致）。
 */
@Composable
private fun RelatedCard(
    owner: String,
    name: String,
    issues: List<Issue>,
    canEdit: Boolean,
    busy: Boolean,
    onAdd: () -> Unit,
    onRemove: (Issue) -> Unit,
) {
    val g = Gh.c
    val nav = LocalNav.current
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .border(1.dp, g.border, RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oc(R.drawable.oc_link, g.fgMuted, 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(tr("相关"), fontSize = 13.sp, color = g.fgMuted, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (canEdit) Text(
                tr("添加"),
                Modifier.clickable(enabled = !busy) { onAdd() }.padding(horizontal = 8.dp, vertical = 6.dp),
                fontSize = 13.sp, color = if (busy) g.fgMuted else g.accent, fontWeight = FontWeight.Medium,
            )
        }
        if (issues.isEmpty()) {
            Text(
                tr("暂无相关议题"),
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                fontSize = 13.sp, color = g.fgMuted,
            )
        } else {
            issues.forEachIndexed { index, issue ->
                if (index > 0) HDivider(Modifier.padding(start = 12.dp))
                val open = issue.state == "open"
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val (o, n) = relatedIssueRepo(issue, owner, name)
                        nav.push(Screen.IssueDetail(o, n, issue.number, issue.pullRequest != null))
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Oc(if (open) R.drawable.oc_issue_opened else R.drawable.oc_issue_closed, if (open) g.success else g.done, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            relatedIssueLabel(issue, owner, name),
                            fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(issue.title, fontSize = 14.sp, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (canEdit) {
                        Spacer(Modifier.width(4.dp))
                        OcButton(
                            R.drawable.oc_x_circle, { onRemove(issue) },
                            tint = g.fgMuted, enabled = !busy, description = tr("移除相关议题"),
                        )
                    }
                }
            }
        }
    }
}

/** 选择要关联的议题：只在当前仓库内搜索，排除当前议题与已经关联的条目 */
@Composable
private fun RelatedIssuePicker(
    owner: String,
    name: String,
    current: Int,
    existing: List<Issue>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPick: (Issue) -> Unit,
) {
    val g = Gh.c
    val ctx = rememberCtx()
    val scope = LocalEntry.current.scope
    var text by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Issue>?>(null) }
    var searching by remember { mutableStateOf(false) }

    fun search() {
        if (searching) return
        searching = true
        scope.launch {
            try {
                val query = "repo:$owner/$name ${text.trim()} is:issue".trim()
                results = GitHub.searchIssues(query, 1).items
                    .filter { it.number != current && existing.none { e -> e.id == it.id } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ctx.toast(e.friendly())
            } finally {
                searching = false
            }
        }
    }

    GhDialog(
        tr("添加相关议题"), { if (!busy) onDismiss() },
        confirm = if (searching) tr("搜索中…") else tr("搜索"),
        confirmEnabled = !searching && !busy,
        onConfirm = { search() },
    ) {
        Column(Modifier.heightIn(max = 420.dp)) {
            GhField(text, { text = it }, tr("搜索仓库内的 Issue"), enabled = !busy)
            Spacer(Modifier.height(8.dp))
            val found = results
            when {
                searching -> Text(tr("搜索中…"), fontSize = 13.sp, color = g.fgMuted)
                found == null -> Text(tr("输入关键词后点「搜索」，留空列出全部"), fontSize = 13.sp, color = g.fgMuted)
                found.isEmpty() -> Text(tr("没有可添加的 Issue"), fontSize = 13.sp, color = g.fgMuted)
                else -> LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(found, key = { it.id }) { issue ->
                        val open = issue.state == "open"
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy) { onPick(issue) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Oc(if (open) R.drawable.oc_issue_opened else R.drawable.oc_issue_closed, if (open) g.success else g.done, 16.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("#${issue.number}", fontSize = 12.sp, color = g.fgMuted)
                                Text(issue.title, fontSize = 14.sp, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
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
            if (editable) OcButton(R.drawable.oc_pencil, onEditTitle, description = tr("编辑标题和正文"))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateBadge(st)
            Spacer(Modifier.width(8.dp))
            Text(
                trf("{0} 开启于 {1} · {2} 条评论", i.user?.login ?: "", relTime(i.createdAt), i.comments),
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
                    trf("{0} 个提交：{1} → {2}", pr.commits, pr.head.label, pr.base.ref),
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = g.accent,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(trf("{0} 个文件  ", pr.changedFiles), fontSize = 13.sp, color = g.fg)
                    Text("+${pr.additions} ", fontSize = 13.sp, color = g.success, fontWeight = FontWeight.SemiBold)
                    Text("−${pr.deletions}", fontSize = 13.sp, color = g.danger, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    // 本机记录的「已查看」文件数（文件页里逐个勾选）
                    val viewed = remember(Session.viewedRevision, Session.login) { Session.viewedFiles(owner, name, pr.number).size }
                    if (pr.changedFiles > 0) Text(
                        trf("{0}/{1} 个文件已查看", viewed.coerceAtMost(pr.changedFiles), pr.changedFiles),
                        fontSize = 12.sp, color = if (viewed >= pr.changedFiles) g.success else g.fgMuted,
                    )
                }
                th.checks?.let { c ->
                    ChecksSummaryRow(c) { nav.push(Screen.PullChecks(owner, name, pr.number, pr.head.sha)) }
                }
                if (pr.state == "open") {
                    val (txt, c, icon) = when (pr.mergeableState) {
                        "clean" -> Triple(tr("可以合并"), g.success, R.drawable.oc_check)
                        "dirty" -> Triple(tr("存在冲突，需要解决后才能合并"), g.danger, R.drawable.oc_x)
                        "blocked" -> Triple(tr("被分支保护规则阻止（需要审查或检查通过）"), g.attention, R.drawable.oc_circle_slash)
                        "behind" -> Triple(tr("落后于目标分支"), g.attention, R.drawable.oc_git_branch)
                        "unstable" -> Triple(tr("有检查未通过"), g.attention, R.drawable.oc_alert)
                        "draft" -> Triple(tr("草稿状态"), g.fgMuted, R.drawable.oc_git_pull_request_draft)
                        else -> Triple(tr("正在检查合并状态…"), g.fgMuted, R.drawable.oc_clock)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Oc(icon, c, 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(txt, fontSize = 13.sp, color = c, fontWeight = FontWeight.Medium)
                    }
                } else if (pr.merged) {
                    Text(trf("已由 {0} 于 {1} 合并", pr.mergedBy?.login ?: "", relTime(pr.mergedAt)), fontSize = 13.sp, color = g.done)
                }
                if (pr.draft && pr.state == "open") {
                    Text(tr("这是一个草稿 PR，标记为可评审后才能合并，并会通知审查者。"), fontSize = 12.sp, color = g.fgMuted)
                    GhButton(tr("标记为可评审"), Modifier.fillMaxWidth(), primary = true, icon = R.drawable.oc_eye, onClick = onReady)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhButton(tr("文件变更"), icon = R.drawable.oc_file_diff) { nav.push(Screen.PullFiles(owner, name, pr.number)) }
                    GhButton(tr("浏览分支"), icon = R.drawable.oc_code) {
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
    "OWNER" -> tr("所有者")
    "MEMBER" -> tr("成员")
    "COLLABORATOR" -> tr("协作者")
    "CONTRIBUTOR" -> tr("贡献者")
    "FIRST_TIME_CONTRIBUTOR" -> tr("首次贡献")
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
        trf("合并 #{0}", pr.number), { if (!busy) onDismiss() }, confirm = if (busy) tr("合并中…") else tr("确认合并"),
        confirmEnabled = !busy && (method == "rebase" || title.isNotBlank()),
        onConfirm = { onMerge(MergeRequest(method, if (method == "rebase") "" else title.trim(), if (method == "rebase") "" else message, deleteBranch && canDelete)) },
    ) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            listOf("merge" to tr("创建合并提交"), "squash" to tr("压缩合并（Squash）"), "rebase" to tr("变基合并（Rebase）")).forEach { (k, v) ->
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
                GhField(title, { if (!busy) { title = it; edited = true } }, tr("提交标题"))
                Spacer(Modifier.height(8.dp))
                GhField(message, { if (!busy) { message = it; edited = true } }, tr("提交说明（可选）"), singleLine = false, minLines = 2)
            } else {
                Text(tr("变基合并会把每个提交依次应用到目标分支，不生成合并提交。"), fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 4.dp))
            }
            if (canDelete) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { deleteBranch = !deleteBranch }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(deleteBranch, { deleteBranch = it }, enabled = !busy)
                    Column {
                        Text(tr("合并后删除分支"), fontSize = 14.sp, color = g.fg)
                        Text(pr.head.ref, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (pr.mergeableState == "dirty") Text(tr("注意：存在冲突，合并可能会失败"), color = g.danger, fontSize = 13.sp)
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
private fun ReviewDialog(
    owner: String, name: String, number: Int,
    busy: Boolean, draft: DraftController,
    onDismiss: () -> Unit, onDiscard: () -> Unit, onSubmit: (String, String) -> Unit,
) {
    val g = Gh.c
    var ev by remember { mutableStateOf("APPROVE") }
    var body by remember { mutableStateOf(draft.initial.body) }
    // 当前用户的待提交审查：提交时其中的行内评论会一并发布。读取失败时不显示提示行
    var pending by remember { mutableStateOf<PendingReview?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(owner, name, number) {
        pending = try {
            GitHub.pendingReview(owner, name, number)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }
    GhDialog(
        tr("提交审查"), { if (!busy) onDismiss() }, confirm = if (busy) tr("提交中…") else tr("提交"),
        confirmEnabled = !busy && (ev == "APPROVE" || body.isNotBlank()),
        onConfirm = { onSubmit(ev, body) },
    ) {
        Column {
            pending?.let { p ->
                PendingReviewLine(p.commentCount) { if (!busy) confirmDiscard = true }
                Spacer(Modifier.height(8.dp))
            }
            GhField(body, { if (!busy) body = it }, tr("审查意见"), singleLine = false, minLines = 3)
            DraftBinding(draft, DraftValue(body = body))
            Spacer(Modifier.height(8.dp))
            listOf("APPROVE" to tr("批准"), "REQUEST_CHANGES" to tr("请求修改"), "COMMENT" to tr("仅评论")).forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { ev = k }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(ev == k, { ev = k }, enabled = !busy)
                    Text(v, color = g.fg)
                }
            }
        }
    }
    if (confirmDiscard) DiscardPendingReviewDialog(
        onDismiss = { confirmDiscard = false },
        onConfirm = {
            confirmDiscard = false
            pending = null
            onDiscard()
        },
    )
}

/** 编辑记录：列出正文 / 评论的历史版本，最新在前；内容与网页版 "Viewing edit" 一致 */
@Composable
private fun EditHistoryDialog(nodeId: String, title: String, onDismiss: () -> Unit) {
    val g = Gh.c
    var result by remember(nodeId) { mutableStateOf<Result<List<ContentEdits.Revision>>?>(null) }
    LaunchedEffect(nodeId) { result = runCatching { ContentEdits.load(nodeId) } }
    val loaded = result
    GhDialog(title, onDismiss, confirm = tr("关闭"), onConfirm = onDismiss) {
        when {
            loaded == null -> Loading(Modifier.fillMaxWidth().heightIn(min = 80.dp))
            loaded.isFailure -> Text(loaded.exceptionOrNull()?.message ?: tr("加载失败"), fontSize = 13.sp, color = g.danger)
            loaded.getOrNull().isNullOrEmpty() -> Text(tr("没有编辑记录"), fontSize = 13.sp, color = g.fgMuted)
            else -> Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                loaded.getOrThrow().forEachIndexed { i, r ->
                    if (i > 0) HDivider()
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(trf("{0} 编辑于 {1}", r.editor ?: "—", relTime(r.createdAt)), fontSize = 12.sp, color = g.fgMuted)
                        Spacer(Modifier.height(4.dp))
                        MdText(r.body.orEmpty(), fontSize = 14.sp, lineHeight = 20.sp, color = g.fg)
                    }
                }
            }
        }
    }
}
