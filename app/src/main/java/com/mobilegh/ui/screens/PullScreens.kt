package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.ApiException
import com.mobilegh.data.BodySegment
import com.mobilegh.data.Checks
import com.mobilegh.data.CheckRun
import com.mobilegh.data.CommitFile
import com.mobilegh.data.CommitStatus
import com.mobilegh.data.GitHub
import com.mobilegh.data.ReviewComment
import com.mobilegh.data.Suggestion
import com.mobilegh.data.Session
import com.mobilegh.data.ThreadAccess
import com.mobilegh.data.splitSuggestions
import com.mobilegh.nav.Links
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.act
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.DiffLine
import com.mobilegh.ui.components.DiffModeToggle
import com.mobilegh.ui.components.DiffRow
import com.mobilegh.ui.components.SplitDiffRow
import com.mobilegh.ui.components.SplitScroll
import com.mobilegh.ui.components.splitRows
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.MarkdownEditor
import com.mobilegh.ui.components.MarkdownPreview
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.fmtDuration
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.parsePatch
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.components.MdText
import com.mobilegh.ui.theme.CodeStyle
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

// ============ 行内审查评论 ============

/** 讨论串：根评论 + 按时间排序的回复 */
class ReviewThread(val root: ReviewComment, val replies: List<ReviewComment>) {
    val path: String get() = root.path.orEmpty()
    val resolved: Boolean get() = root.resolved
    val outdated: Boolean get() = root.outdated
    val id: Long get() = root.id
    val size: Int get() = replies.size + 1

    /** 评论锚定的行号：side=LEFT 指旧文件的行，RIGHT（默认）指新文件的行 */
    val side: String get() = (root.side ?: "RIGHT").uppercase()
    val line: Int? get() = root.line
    val startLine: Int? get() = root.startLine
    val diffHunk: String? get() = root.diffHunk
}

/** 按 inReplyToId 组装讨论串（REST 里回复是独立的一条评论） */
fun groupReviewThreads(comments: List<ReviewComment>): List<ReviewThread> {
    if (comments.isEmpty()) return emptyList()
    val byId = comments.associateBy { it.id }
    val replies = HashMap<Long, MutableList<ReviewComment>>()
    val roots = ArrayList<ReviewComment>()
    comments.forEach { c ->
        val parent = c.inReplyToId
        if (parent != null && byId.containsKey(parent)) replies.getOrPut(parent) { ArrayList() }.add(c) else roots.add(c)
    }
    return roots.map { r ->
        ReviewThread(r, replies[r.id].orEmpty().sortedBy { it.createdAt ?: "" })
    }.sortedBy { it.root.createdAt ?: "" }
}

private class FileThreads(
    val level: List<ReviewThread>,
    /** 键为 "R:新行号" 或 "L:旧行号" */
    val byLine: Map<String, List<ReviewThread>>,
    val outdated: List<ReviewThread>,
) {
    val comments: Int get() = level.sumOf { it.size } + byLine.values.sumOf { l -> l.sumOf { it.size } } + outdated.sumOf { it.size }
}

private fun lineKeys(d: DiffLine): List<String> = when (d.kind) {
    '+' -> listOfNotNull(d.newNo?.let { "R:$it" })
    '-' -> listOfNotNull(d.oldNo?.let { "L:$it" })
    ' ' -> listOfNotNull(d.newNo?.let { "R:$it" }, d.oldNo?.let { "L:$it" })
    else -> emptyList()
}

/** 把讨论串挂到 diff 行上；行不在当前 diff 中（已过时或超出 hunk）的放到文件末尾 */
private fun anchorThreads(threads: List<ReviewThread>, pf: ParsedFile): FileThreads {
    val scope = threads.filter { it.path == pf.file.filename }
    if (scope.isEmpty()) return FileThreads(emptyList(), emptyMap(), emptyList())
    val visible = pf.lines.flatMap(::lineKeys).toHashSet()
    val level = ArrayList<ReviewThread>()
    val byLine = HashMap<String, MutableList<ReviewThread>>()
    val outdated = ArrayList<ReviewThread>()
    scope.forEach { t ->
        val line = t.line
        val key = line?.let { (if (t.side == "LEFT") "L:" else "R:") + it }
        when {
            t.root.fileLevel && !t.outdated -> level.add(t)
            !t.outdated && key != null && key in visible -> byLine.getOrPut(key) { ArrayList() }.add(t)
            else -> outdated.add(t)
        }
    }
    return FileThreads(level, byLine, outdated)
}

/** 评论所在行在原文件中的内容（用于「应用建议」的对照） */
private fun originalLines(pf: ParsedFile, t: ReviewThread): List<String>? {
    if (t.side == "LEFT" || t.outdated) return null
    val line = t.line ?: return null
    val from = t.startLine?.takeIf { it in 1..line } ?: line
    val picked = pf.lines.filter { it.newNo != null && it.newNo in from..line && it.kind != '-' }.map { it.text }
    return picked.takeIf { it.isNotEmpty() }
}

@Composable
private fun ReviewThreadCard(
    owner: String,
    name: String,
    thread: ReviewThread,
    original: List<String>?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onReply: () -> Unit,
    /** null 表示没有权限（需要写权限或是 PR 作者），不显示「标记为已解决」 */
    onResolve: ((Boolean) -> Unit)?,
    onEdit: (ReviewComment) -> Unit,
    onDelete: (ReviewComment) -> Unit,
    onApply: (String) -> Unit,
) {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val base = "https://github.com/$owner/$name/blob/HEAD/"
    val me = Session.login
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (!expanded) {
            Row(
                Modifier.fillMaxWidth().clip(shape).background(g.canvasSubtle)
                    .border(1.dp, g.border, shape).clickable(onClick = onToggle)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Oc(
                    when {
                        thread.outdated -> R.drawable.oc_clock
                        thread.resolved -> R.drawable.oc_check_circle
                        else -> R.drawable.oc_comment
                    },
                    if (thread.resolved) g.done else if (thread.outdated) g.fgMuted else g.accent, 14.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    trf("{0} 的{1}评论", thread.root.user?.login ?: "ghost", if (thread.size > 1) trf("{0} 条", thread.size) else "") +
                        if (thread.resolved) tr("（已解决）") else if (thread.outdated) tr("（已过时）") else "",
                    Modifier.weight(1f), fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(tr("展开"), fontSize = 12.sp, color = g.accent)
            }
            return@Column
        }
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, g.border, shape)) {
            Row(
                Modifier.fillMaxWidth().background(g.canvasSubtle)
                    .padding(start = 10.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (thread.resolved) { Pill(tr("已解决"), g.done); Spacer(Modifier.width(6.dp)) }
                if (thread.outdated) { Pill(tr("已过时"), g.attention); Spacer(Modifier.width(6.dp)) }
                Text(
                    when {
                        thread.root.fileLevel -> tr("文件评论")
                        thread.line != null -> trf("第 {0} 行", thread.line) + if (thread.side == "LEFT") tr("（删除）") else ""
                        else -> thread.root.originalLine?.let { trf("原第 {0} 行", it) } ?: ""
                    },
                    Modifier.weight(1f), fontSize = 12.sp, color = g.fgMuted, maxLines = 1,
                )
                TextLink(tr("收起"), g.fgMuted, onToggle)
            }
            // 已过时的评论无法挂到当前 diff，用评论时的 diff 片段（最后几行）还原上下文
            if (thread.outdated) thread.diffHunk?.let { hunk ->
                val tail = remember(hunk) { parsePatch(hunk).filter { it.kind != '@' }.takeLast(4) }
                tail.forEach { d -> DiffRow(d, g, thread.path) }
            }
            val all = listOf(thread.root) + thread.replies
            all.forEachIndexed { i, c ->
                if (i > 0) HDivider()
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.user?.avatarUrl, 22.dp, Modifier.clickable { c.user?.let { nav.push(Screen.Profile(it.login)) } })
                    Spacer(Modifier.width(8.dp))
                    Text(c.user?.login ?: "ghost", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.fg, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Text(relTime(c.createdAt), fontSize = 12.sp, color = g.fgMuted, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    com.mobilegh.ui.components.MoreMenu(buildList {
                        add(MenuAction(tr("复制链接")) { ctx.copy(c.htmlUrl) })
                        if (c.user?.login == me) {
                            add(MenuAction(tr("编辑")) { onEdit(c) })
                            add(MenuAction(tr("删除"), danger = true) { onDelete(c) })
                        }
                    }, g.fgMuted)
                }
                CommentBody(c, "$owner/$name", base, original, onApply)
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextLink(tr("回复")) { onReply() }
                if (thread.root.threadId != null && onResolve != null) {
                    TextLink(if (thread.resolved) tr("重新打开") else tr("标记为已解决")) { onResolve(!thread.resolved) }
                }
            }
        }
    }
}

/** 一条评论的正文：含```suggestion 块时按「文字段 + 建议框」渲染 */
@Composable
private fun CommentBody(c: ReviewComment, repo: String, baseUrl: String, original: List<String>?, onApply: (String) -> Unit) {
    val g = Gh.c
    val body = c.body.orEmpty()
    val segments = remember(body) {
        if (body.contains("```")) splitSuggestions(body, c.line ?: 1).takeIf { l -> l.any { it is BodySegment.Suggest } }.orEmpty() else emptyList()
    }
    if (segments.isEmpty()) {
        val html = c.bodyHtml
        if (html.isNullOrBlank()) Text(body, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 14.sp, color = g.fg)
        else HtmlView(html, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), baseUrl = baseUrl)
        return
    }
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        segments.forEach { seg ->
            when (seg) {
                // 文字部分交给 GitHub 渲染（与网页一致）；建议块在本地绘制成对照框
                is BodySegment.Text -> if (seg.text.isNotBlank()) MarkdownPreview(seg.text.trim(), repo)
                is BodySegment.Suggest -> SuggestionBox(seg.suggestion, original) { onApply(seg.suggestion.newText) }
            }
        }
    }
}

/** 建议更改：左为原文（红），右为建议（绿），底部「应用建议 / 复制建议」 */
@Composable
private fun SuggestionBox(s: Suggestion, original: List<String>?, onApply: () -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    val canvas = g.canvas
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, g.border, RoundedCornerShape(6.dp))) {
        Row(
            Modifier.fillMaxWidth().background(g.canvasSubtle).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oc(R.drawable.oc_light_bulb, g.attention, 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(tr("建议的更改"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
            Spacer(Modifier.weight(1f))
            if (original != null && original.isNotEmpty()) Text(trf("第 {0} 行", s.startLine), fontSize = 11.sp, color = g.fgMuted)
        }
        original?.forEach { line ->
            Row(Modifier.fillMaxWidth().background(g.diffDel).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("−", Modifier.width(12.dp), style = CodeStyle, color = g.danger)
                Text(line, Modifier.weight(1f), style = CodeStyle, color = g.fg)
            }
        }
        s.newText.split('\n').forEach { line ->
            Row(Modifier.fillMaxWidth().background(g.diffAdd).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("+", Modifier.width(12.dp), style = CodeStyle, color = g.success)
                Text(line, Modifier.weight(1f), style = CodeStyle, color = g.fg)
            }
        }
        Row(Modifier.fillMaxWidth().background(canvas).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (original != null && original.isNotEmpty()) {
                GhButton(tr("应用建议"), icon = R.drawable.oc_check) { onApply() }
            }
            GhButton(tr("复制建议"), icon = R.drawable.oc_copy) { ctx.copy(s.newText, tr("建议代码已复制")) }
        }
    }
}

// ============ 发表评论的输入框 ============

/** 行内评论的目标：文件 / 行号 / 回复对象 */
class CommentTarget(
    val path: String,
    val line: Int?,
    val side: String = "RIGHT",
    val preview: String? = null,
    val replyTo: ReviewComment? = null,
    val edit: ReviewComment? = null,
)

@Composable
private fun CommentComposer(
    owner: String,
    name: String,
    number: Int,
    commitId: String?,
    target: CommentTarget,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val g = Gh.c
    val ctx = rememberCtx()
    val entry = LocalEntry.current
    var body by remember { mutableStateOf(TextFieldValue(target.edit?.body.orEmpty())) }
    var sending by remember { mutableStateOf(false) }
    val line = target.line
    val canSuggest = target.replyTo == null && line != null && target.side != "LEFT" && !target.preview.isNullOrEmpty()

    fun submit() {
        val text = body.text
        if (text.isBlank() || sending) return
        sending = true
        entry.act({
            sending = false
            ctx.toast(it)
        }) {
            when {
                target.edit != null -> GitHub.editReviewComment(owner, name, target.edit.id, text)
                target.replyTo != null -> GitHub.replyReviewComment(owner, name, number, target.replyTo.id, text)
                else -> {
                    val sha = commitId
                    if (sha.isNullOrBlank()) throw ApiException(400, tr("PR 信息尚未加载完成，请稍后重试"))
                    GitHub.addReviewComment(owner, name, number, text, sha, target.path, line, target.side)
                }
            }
            sending = false
            onDone()
        }
    }

    GhDialog(
        when {
            target.edit != null -> tr("编辑评论")
            target.replyTo != null -> tr("回复评论")
            line == null -> tr("评论文件")
            target.side == "LEFT" -> trf("评论删除的第 {0} 行", line)
            else -> trf("评论第 {0} 行", line)
        },
        onDismiss,
        confirm = if (sending) tr("发送中…") else tr("发表评论"),
        confirmEnabled = body.text.isNotBlank() && !sending,
        onConfirm = { submit() },
    ) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text(target.path, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = g.fgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            target.preview?.takeIf { it.isNotBlank() }?.let { p ->
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().background(if (target.side == "LEFT") g.diffDel else g.canvasSubtle, RoundedCornerShape(4.dp))
                        .border(1.dp, g.borderMuted, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(p, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = g.fg, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            MarkdownEditor(body, { body = it }, placeholder = tr("发表评论（支持 Markdown）"), contextRepo = "$owner/$name", minLines = 4)
            if (canSuggest) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextLink(tr("插入代码建议")) {
                        val block = "```suggestion\n${target.preview}\n```"
                        val text = body.text
                        body = TextFieldValue(
                            if (text.isBlank()) block else "$text\n$block",
                            androidx.compose.ui.text.TextRange(Int.MAX_VALUE),
                        )
                    }
                    Text(tr("可让作者一键采纳这一行"), fontSize = 11.sp, color = g.fgMuted)
                }
            }
        }
    }
}

// ============ PR 文件（含行内评论） ============

/**
 * PR 文件变更页：diff + 行内审查评论 + 逐文件「已查看」。
 * 点按某一行发表单行评论；文件标题右侧 ⋯ 可对整份文件评论或查看原文件。
 */
@Composable
fun PullFilesScreen(owner: String, name: String, number: Int) {
    val g = Gh.c
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val files = rememberLoader("files") {
        val all = ArrayList<CommitFile>()
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
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    val access = ThreadAccess.of(repo.data?.permissions, repo.data?.archived == true, pr.data?.user?.login, Session.login)
    val comments = rememberLoader("review-comments") {
        runCatching { GitHub.reviewComments(owner, name, number, it) }.getOrElse { e ->
            if (e is ApiException && e.code == 404) emptyList() else throw e
        }
    }
    val collapsed = retain("collapsed") { mutableStateMapOf<String, Boolean>() }
    val expandedThreads = retain("thread-expanded") { mutableStateMapOf<Long, Boolean>() }
    var target by remember { mutableStateOf<CommentTarget?>(null) }
    var applying by remember { mutableStateOf<Pair<ReviewThread, String>?>(null) }
    var deleting by remember { mutableStateOf<ReviewComment?>(null) }
    val headSha = pr.data?.head?.sha
    val viewed = remember(Session.viewedRevision, Session.login) { Session.viewedFiles(owner, name, number) }
    val threads = remember(comments.data) { groupReviewThreads(comments.data.orEmpty()) }

    fun setViewed(file: CommitFile, on: Boolean) {
        val map = LinkedHashMap(Session.viewedFiles(owner, name, number))
        if (on) {
            map[file.filename] = file.sha.orEmpty()
            collapsed[file.filename] = true
        } else {
            map.remove(file.filename)
        }
        Session.setViewedFiles(owner, name, number, map)
    }

    fun reloadComments() = comments.load(true)

    Page(tr("文件变更"), subtitle = "$owner/$name #$number") { pad ->
        LoadBox(files, Modifier.padding(pad).fillMaxSize()) { list ->
            // 文件内容变了（sha 不同）就不再算已查看
            val current = list.mapNotNull { pf -> pf.file.sha?.let { pf.file.filename to it } }.toMap()
            val stale = current.any { (path, sha) -> viewed[path] != null && viewed[path] != sha }
            val count = current.count { (path, sha) -> viewed[path] == sha }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(trf("{0} 个文件  ", list.size), fontSize = 14.sp, color = g.fg, fontWeight = FontWeight.SemiBold)
                            Text("+${list.sumOf { it.file.additions }} ", fontSize = 14.sp, color = g.success, fontWeight = FontWeight.SemiBold)
                            Text("−${list.sumOf { it.file.deletions }}", fontSize = 14.sp, color = g.danger, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            TextLink(tr("全部折叠")) { list.forEach { collapsed[it.file.filename] = true } }
                        }
                        Spacer(Modifier.height(8.dp))
                        DiffModeToggle()
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(trf("{0}/{1} 个文件已查看", count, list.size), fontSize = 13.sp, color = if (count == list.size && list.isNotEmpty()) g.success else g.fgMuted)
                            Spacer(Modifier.width(8.dp))
                            LinearProgressIndicator(
                                progress = { if (list.isEmpty()) 0f else count.toFloat() / list.size },
                                modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = if (count == list.size && list.isNotEmpty()) g.success else g.accent,
                                trackColor = g.neutralMuted,
                            )
                        }
                        if (comments.data?.isNotEmpty() == true) {
                            Spacer(Modifier.height(6.dp))
                            Text(trf("共 {0} 条行内评论 · 点按代码行可发表评论", threads.size), fontSize = 12.sp, color = g.fgMuted)
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Text(tr("点按代码行可发表行内评论"), fontSize = 12.sp, color = g.fgMuted)
                        }
                        if (stale) Text(tr("部分文件在查看后又有改动，已重新计为未查看"), fontSize = 12.sp, color = g.attention)
                        if (comments.error != null) {
                            Spacer(Modifier.height(4.dp))
                            InlineError(trf("行内评论加载失败：{0}", comments.error)) { reloadComments() }
                        }
                    }
                    HDivider()
                }
                list.forEach { pf ->
                    pullFileDiff(
                        owner, name, pf, collapsed, viewed, ::setViewed,
                        anchorThreads(threads, pf), expandedThreads,
                        onLineClick = { d ->
                            target = if (d.kind == '-') CommentTarget(pf.file.filename, d.oldNo, "LEFT", d.text)
                            else CommentTarget(pf.file.filename, d.newNo, "RIGHT", d.text)
                        },
                        onCopyPath = { ctx.copy(pf.file.filename) },
                        onFileComment = { target = CommentTarget(pf.file.filename, null) },
                        onOpen = {
                            val head = pr.data?.head
                            nav.push(Screen.FileView(head?.repo?.owner?.login ?: owner, head?.repo?.name ?: name, pf.file.filename, head?.sha))
                        },
                        onReply = { t -> target = CommentTarget(t.path, t.line, t.side, replyTo = t.root) },
                        onEdit = { c -> target = CommentTarget(c.path.orEmpty(), c.line, c.side ?: "RIGHT", edit = c) },
                        onDelete = { c -> deleting = c },
                        onApply = { t, text -> applying = t to text },
                        onToggleThread = { t -> expandedThreads[t.id] = !threadExpanded(expandedThreads, t) },
                        onResolve = if (access.write || access.author) { t, resolve ->
                            entry.act({ ctx.toast(it) }, ::reloadComments) {
                                GitHub.resolveThread(t.root.threadId!!, resolve)
                                ctx.toast(if (resolve) tr("已标记为已解决") else tr("已重新打开"))
                            }
                        } else null,
                    )
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }

    target?.let { t ->
        CommentComposer(owner, name, number, headSha, t, { target = null }) {
            target = null
            ctx.toast(if (t.edit != null) tr("已保存") else tr("评论已发表"))
            reloadComments()
        }
    }
    applying?.let { (t, text) ->
        val line = t.line
        GhDialog(
            tr("应用建议"), { applying = null }, confirm = tr("发表建议"),
            confirmEnabled = headSha != null && line != null,
            onConfirm = {
                applying = null
                val sha = headSha
                if (sha != null && line != null) entry.act({ ctx.toast(it) }, ::reloadComments) {
                    GitHub.addReviewComment(owner, name, number, "```suggestion\n$text\n```", sha, t.path, line, t.side, t.startLine, t.side)
                    ctx.toast(tr("建议已发表"))
                }
            },
        ) {
            Column {
                Text(
                    tr("将以你的名义在同一位置发表这条建议，PR 作者可在 GitHub 上一键提交。"),
                    fontSize = 13.sp, color = g.fg,
                )
                Spacer(Modifier.height(8.dp))
                Text("${t.path}:${t.line ?: ""}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = g.fgMuted)
            }
        }
    }
    deleting?.let { c ->
        GhDialog(tr("删除评论"), { deleting = null }, confirm = tr("删除"), danger = true, onConfirm = {
            deleting = null
            entry.act({ ctx.toast(it) }, ::reloadComments) { GitHub.deleteReviewComment(owner, name, c.id); ctx.toast(tr("已删除")) }
        }) { Text(tr("删除后无法恢复。"), color = g.fg) }
    }
}

/** 单个文件的 diff：标题（含「已查看」）+ 行内评论 + 行 */
private fun LazyListScope.pullFileDiff(
    owner: String,
    name: String,
    pf: ParsedFile,
    collapsed: MutableMap<String, Boolean>,
    viewed: Map<String, String>,
    onViewed: (CommitFile, Boolean) -> Unit,
    threads: FileThreads,
    expanded: MutableMap<Long, Boolean>,
    onLineClick: (DiffLine) -> Unit,
    onCopyPath: () -> Unit,
    onFileComment: () -> Unit,
    onOpen: () -> Unit,
    onReply: (ReviewThread) -> Unit,
    onEdit: (ReviewComment) -> Unit,
    onDelete: (ReviewComment) -> Unit,
    onApply: (ReviewThread, String) -> Unit,
    onToggleThread: (ReviewThread) -> Unit,
    onResolve: ((ReviewThread, Boolean) -> Unit)?,
) {
    val f = pf.file
    val path = f.filename
    val isViewed = f.sha != null && viewed[path] == f.sha
    // 与提交详情一致，默认只显示文件清单，点标题展开
    val isCollapsed = collapsed[path] ?: true
    @Composable
    fun thread(t: ReviewThread) {
        ReviewThreadCard(
            owner, name, t, originalLines(pf, t),
            threadExpanded(expanded, t),
            { onToggleThread(t) },
            { onReply(t) },
            onResolve?.let { r -> { resolve -> r(t, resolve) } },
            onEdit,
            onDelete,
            { onApply(t, it) },
        )
    }
    item(key = "h:$path") {
        FileDiffHeader(
            f, isCollapsed, { collapsed[path] = !isCollapsed }, onOpen,
            viewed = isViewed,
            onViewed = { onViewed(f, it) },
            comments = threads.comments,
            menu = buildList {
                if (f.status != "removed") add(MenuAction(tr("查看文件")) { onOpen() })
                add(MenuAction(tr("评论此文件")) { onFileComment() })
                add(MenuAction(tr("复制文件路径")) { onCopyPath() })
            },
        )
    }
    if (isCollapsed) return
    if (pf.lines.isEmpty()) {
        item(key = "e:$path") {
            val g = Gh.c
            Text(
                if (f.status == "renamed") tr("文件已重命名（无内容变化）") else tr("二进制文件或变更过大，无法显示 diff"),
                Modifier.padding(16.dp), fontSize = 13.sp, color = g.fgMuted,
            )
        }
    } else {
        if (threads.level.isNotEmpty()) item(key = "fl:$path", contentType = "thread") {
            Column(Modifier.padding(vertical = 4.dp)) { threads.level.forEach { thread(it) } }
        }
        if (Session.diffSplit) {
            val rows = splitRows(pf.lines)
            val scroll = SplitScroll.of("pr:$path")
            itemsIndexed(rows, key = { i, _ -> "$path:$i" }) { _, r ->
                Column {
                    SplitDiffRow(r, Gh.c, path, scroll, onLineClick = onLineClick)
                    // 行内评论挂在对应那一行的下方：左右两侧各自的评论都显示
                    listOfNotNull(r.left, r.right).distinct().forEach { d ->
                        lineKeys(d).forEach { k -> threads.byLine[k]?.forEach { thread(it) } }
                    }
                }
            }
        } else itemsIndexed(pf.lines, key = { i, _ -> "$path:$i" }) { _, d ->
            Column {
                DiffRow(d, Gh.c, path, onLineClick = { onLineClick(d) })
                lineKeys(d).forEach { k -> threads.byLine[k]?.forEach { thread(it) } }
            }
        }
    }
    if (threads.outdated.isNotEmpty()) item(key = "ot:$path") {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                trf("{0} 个讨论所在的代码已改动", threads.outdated.size),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, color = Gh.c.fgMuted,
            )
            threads.outdated.forEach { thread(it) }
        }
    }
}

/** 已解决、已过时的讨论默认收起，与 GitHub 网页一致 */
private fun threadExpanded(state: Map<Long, Boolean>, t: ReviewThread) = state[t.id] ?: !(t.resolved || t.outdated)

// ============ 检查结果 ============

/** 检查与状态汇总的紧凑一行，点按进入详情 */
@Composable
fun ChecksSummaryRow(checks: Checks, onOpen: (() -> Unit)? = null) {
    val g = Gh.c
    val (icon, color, text) = when {
        checks.total == 0 -> Triple(R.drawable.oc_skip, g.fgMuted, tr("未配置检查"))
        checks.failed > 0 -> Triple(R.drawable.oc_x_circle_fill, g.danger, trf("{0} 项检查未通过", checks.failed))
        checks.pending > 0 -> Triple(R.drawable.oc_dot_fill, g.attention, trf("{0} 项检查进行中", checks.pending))
        else -> Triple(R.drawable.oc_check_circle_fill, g.success, trf("全部 {0} 项检查通过", checks.total))
    }
    Row(
        Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(icon, color, 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, Modifier.weight(1f), fontSize = 13.sp, color = color, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (checks.total > 0) Text(trf("{0} 通过 · {1} 失败 · {2} 进行中", checks.passed, checks.failed, checks.pending), fontSize = 11.sp, color = g.fgMuted, maxLines = 1)
        if (onOpen != null) {
            Spacer(Modifier.width(4.dp))
            Oc(R.drawable.oc_chevron_right, g.fgMuted, 14.dp)
        }
    }
}

@Composable
fun PullChecksScreen(owner: String, name: String, number: Int, sha: String) {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val checks = rememberLoader("checks") { GitHub.checks(owner, name, sha, it) }
    val pr = rememberLoader("pr") { GitHub.pull(owner, name, number, it) }
    Page(tr("检查"), subtitle = "$owner/$name #$number") { pad ->
        LoadBox(checks, Modifier.padding(pad).fillMaxSize()) { c ->
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        pr.data?.title?.let { MdText(it, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.height(6.dp))
                        ChecksSummaryRow(c)
                        Spacer(Modifier.height(4.dp))
                        Text(trf("提交 {0}", sha.take(7)), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = g.fgMuted)
                    }
                    HDivider()
                }
                if (c.runs.isNotEmpty()) {
                    item { Text(tr("检查"), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.fg) }
                    items(c.runs, key = { "r${it.id}" }) { r -> CheckRunRow(r) { openCheck(ctx, nav, r.htmlUrl ?: r.detailsUrl) } }
                }
                if (c.statuses.isNotEmpty()) {
                    item { Text(tr("状态"), Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.fg) }
                    items(c.statuses, key = { "s${it.id}" }) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { openCheck(ctx, nav, s.targetUrl) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val (icon, color) = statusVisual(s.state)
                            Oc(icon, color, 16.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.context, fontSize = 14.sp, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(s.description?.takeIf { it.isNotBlank() }, relTime(s.createdAt).takeIf { it.isNotBlank() }).joinToString(" · "),
                                    fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (s.targetUrl != null) Oc(R.drawable.oc_link_external, g.fgMuted, 14.dp)
                        }
                    }
                }
                if (c.total == 0) item {
                    Text(tr("这个提交没有检查结果。"), Modifier.padding(16.dp), fontSize = 13.sp, color = g.fgMuted)
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun CheckRunRow(r: CheckRun, onOpen: () -> Unit) {
    val g = Gh.c
    val (icon, color) = runVisual(r.status, r.conclusion)
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Oc(icon, color, 16.dp)
            Spacer(Modifier.width(10.dp))
            Text(r.name, Modifier.weight(1f), fontSize = 14.sp, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (r.htmlUrl != null || r.detailsUrl != null) Oc(R.drawable.oc_link_external, g.fgMuted, 14.dp)
        }
        r.output?.title?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(start = 26.dp, top = 2.dp), fontSize = 12.sp, color = g.fgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            listOfNotNull(
                r.app?.name?.takeIf { it.isNotBlank() },
                statusZh(r.status, r.conclusion).takeIf { it.isNotBlank() },
                duration(r).takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            Modifier.padding(start = 26.dp, top = 2.dp),
            fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 运行中显示已耗时，排队时显示已等待，完成时显示总耗时 */
private fun duration(r: CheckRun): String {
    val start = r.startedAt ?: r.completedAt
    return if (start == null) "" else when (r.status) {
        "completed" -> tr("耗时 ") + fmtDuration(start, r.completedAt)
        "in_progress" -> tr("已运行 ") + fmtDuration(start, null)
        else -> tr("已等待 ") + fmtDuration(start, null)
    }
}

@Composable
private fun statusVisual(state: String): Pair<Int, Color> {
    val g = Gh.c
    return when (state) {
        "success" -> R.drawable.oc_check_circle_fill to g.success
        "failure", "error" -> R.drawable.oc_x_circle_fill to g.danger
        "pending" -> R.drawable.oc_dot_fill to g.attention
        else -> R.drawable.oc_alert to g.attention
    }
}

/** 详情链接优先在应用内打开（Actions 运行页），否则交给浏览器 */
private fun openCheck(ctx: android.content.Context, nav: com.mobilegh.nav.Navigator, url: String?) {
    if (url.isNullOrBlank()) {
        ctx.toast(tr("该项检查没有提供详情链接"))
        return
    }
    val route = runCatching { Links.route(url) }.getOrNull()
    if (route != null) nav.push(route) else ctx.openBrowser(url)
}
