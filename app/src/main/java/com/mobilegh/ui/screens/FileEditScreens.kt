package com.mobilegh.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.ApiException
import com.mobilegh.data.CodeReading
import com.mobilegh.data.Content
import com.mobilegh.data.DraftValue
import com.mobilegh.data.GitHub
import com.mobilegh.data.RepoWrite
import com.mobilegh.data.Session
import com.mobilegh.data.WriteTarget
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.DraftBinding
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.MarkdownEditor
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.RadioRow
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.rememberDraft
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.CodeStyle
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 无写入权限时的统一提示：GitHub 网页会引导先 Fork 再提交，本应用不代为 Fork */
@Composable
fun NoPushNotice(archived: Boolean, webUrl: String, modifier: Modifier = Modifier) {
    val ctx = rememberCtx()
    val g = Gh.c
    Card(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Oc(R.drawable.oc_alert, g.attention)
                Spacer(Modifier.width(8.dp))
                Text(if (archived) "仓库已归档，只读" else "没有推送权限", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (archived) "归档仓库不能提交任何更改。"
                else "你的账号对该仓库没有写入权限，无法直接提交。GitHub 网页上的做法是先 Fork 该仓库，在 Fork 中提交后再发起拉取请求；本应用不会代为 Fork，请在网页上完成。",
                fontSize = 12.sp, lineHeight = 18.sp, color = g.fgMuted,
            )
            if (!archived) {
                Spacer(Modifier.height(8.dp))
                GhButton("在浏览器中编辑", icon = R.drawable.oc_link_external) { ctx.openBrowser(webUrl) }
            }
        }
    }
}

/** 编辑器读取到的文件：text 已统一成 \n 换行 */
private class Editable(val text: String, val sha: String?, val crlf: Boolean) {
    companion object {
        val New = Editable("", null, false)
        fun of(c: Content): Editable {
            if (c.type != "file") throw ApiException(415, "只能编辑普通文件")
            if (c.size > RepoWrite.MAX_EDIT_BYTES) throw ApiException(413, "文件超过 1MB，无法在手机上编辑")
            val raw = c.content?.takeIf { c.encoding == "base64" } ?: throw ApiException(415, "无法读取该文件的内容")
            val text = RepoWrite.decode(raw) ?: throw ApiException(415, "该文件不是 UTF-8 文本，无法编辑")
            return Editable(RepoWrite.toEditor(text), c.sha, RepoWrite.usesCrlf(text))
        }
    }
}

/** 编辑器在页面条目上保留的状态（文件可能很大，不放进 saved state） */
private class EditorState {
    var text by mutableStateOf(TextFieldValue(""))
    var path by mutableStateOf("")
    var loadedFor by mutableStateOf<String?>(null)
    var restored by mutableStateOf(false)
}

// ======================= 编辑 / 新建文件 =======================

/** path == null 表示在 dir 目录下新建文件 */
@Composable
fun EditFileScreen(owner: String, name: String, ref: String?, path: String?, dir: String) {
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val g = Gh.c
    val creating = path == null
    val target = rememberLoader("target") { WriteTarget.load(owner, name, ref) }
    val src = rememberLoader("source") { if (path == null) Editable.New else Editable.of(GitHub.fileContent(owner, name, path, ref, force = true)) }
    val t = target.data
    val e = src.data
    val st = retain("editor") { EditorState().also { s -> if (creating) s.path = if (dir.isEmpty()) "" else "$dir/" } }

    // 草稿按「仓库 + ref + 路径 + 文件 sha」区分，文件在 GitHub 上变了就不会套用旧草稿
    val repoKey = "${owner.lowercase()}/${name.lowercase()}:${ref.orEmpty()}"
    val draftKey = if (creating) "new-file:$repoKey:$dir" else "edit-file:$repoKey:$path:${e?.sha.orEmpty()}"
    val draft = rememberDraft(draftKey)
    LaunchedEffect(e, draftKey) {
        val data = e ?: return@LaunchedEffect
        if (st.loadedFor == draftKey) return@LaunchedEffect
        val saved = draft.initial
        val restore = !saved.empty && st.loadedFor == null
        val body = if (restore) saved.body else data.text
        st.text = TextFieldValue(body, TextRange(0))
        if (restore && creating && saved.title.isNotBlank()) st.path = saved.title
        st.restored = restore
        st.loadedFor = draftKey
    }
    val ready = e != null && st.loadedFor == draftKey
    val original = e?.text.orEmpty()
    val dirty = ready && (st.text.text != original || (creating && st.path.trim().substringAfterLast('/').isNotEmpty()))
    val tooLarge = st.text.text.length > RepoWrite.MAX_DRAFT_CHARS
    val newPath = st.path.trim().trim('/')
    val pathOk = !creating || CodeReading.validPath(newPath)
    val canCommit = t?.canPush == true && ready && dirty && pathOk

    var commitOpen by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    if (ready) DraftBinding(draft, if (!dirty || tooLarge) DraftValue() else DraftValue(if (creating) st.path else "", st.text.text))
    BackHandler(enabled = dirty) { leaving = true }

    val webUrl = if (creating) "https://github.com/$owner/$name/new/${ref ?: t?.repo?.defaultBranch ?: "HEAD"}/$dir"
    else "https://github.com/$owner/$name/edit/${ref ?: t?.repo?.defaultBranch ?: "HEAD"}/$path"
    Page(
        title = if (creating) "新建文件" else "编辑 ${path!!.substringAfterLast('/')}",
        subtitle = "${t?.baseRef ?: ref ?: "默认分支"} · ${if (creating) "$owner/$name" else path}",
        onBack = { if (dirty) leaving = true else nav.pop() },
        actions = { OcButton(R.drawable.oc_check, { commitOpen = true }, g.accent, enabled = canCommit) },
        bottomBar = {
            if (ready) Column(Modifier.background(g.header).navigationBarsPadding().imePadding()) {
                HDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val v = st.text
                        val caret = v.selection.start.coerceIn(0, v.text.length)
                        var line = 1
                        for (k in 0 until caret) if (v.text[k] == '\n') line++
                        Text(
                            "${RepoWrite.lineCount(v.text)} 行 · 第 $line 行 · ${fmtSize(v.text.length.toLong())}" + (if (e?.crlf == true) " · CRLF" else "") + (if (dirty) " · 已修改" else ""),
                            fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (tooLarge) Text("文件较大，草稿不会保存在本机", fontSize = 11.sp, color = g.attention)
                        else if (dirty) Text("草稿自动保存在本机", fontSize = 11.sp, color = g.fgMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    GhButton("提交更改", primary = true, enabled = canCommit) { commitOpen = true }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                src.error != null && e == null -> ErrorState(src.error!!) { src.load(true) }
                target.error != null && t == null -> ErrorState(target.error!!) { target.load(true) }
                !ready || t == null -> Loading()
                else -> {
                    if (!t.canPush) NoPushNotice(t.repo.archived, webUrl)
                    if (t.branch == null && !t.emptyRepo && t.canPush) {
                        Text(
                            "当前查看的是标签或提交 ${t.baseRef}，不能直接提交，只能新建分支后提交。",
                            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = g.attention,
                        )
                    }
                    if (t.emptyRepo && t.canPush) {
                        Text(
                            "这是一个空仓库：第一个文件会直接提交到 ${t.baseRef}，不能再选“新建分支”（没有提交可以指向新分支）。",
                            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, lineHeight = 17.sp, color = g.attention,
                        )
                    }
                    if (creating) {
                        GhField(st.path, { st.path = it }, "文件路径", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), placeholder = "例如 docs/notes.md")
                        if (st.path.isNotBlank() && !pathOk) Text("路径无效：不能包含空的目录名、. 或 ..", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = g.danger)
                    }
                    if (st.restored) {
                        Row(Modifier.fillMaxWidth().background(g.accentSubtle).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Oc(R.drawable.oc_history, g.accent, 14.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("已恢复上次未提交的草稿", Modifier.weight(1f), fontSize = 13.sp, color = g.accent)
                            Text("放弃草稿", fontSize = 13.sp, color = g.fgMuted, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                                st.text = TextFieldValue(original)
                                if (creating) st.path = if (dir.isEmpty()) "" else "$dir/"
                                st.restored = false
                            }.padding(horizontal = 6.dp, vertical = 8.dp))
                        }
                    }
                    HDivider()
                    BasicTextField(
                        value = st.text,
                        onValueChange = { st.text = it },
                        modifier = Modifier.fillMaxWidth().weight(1f).background(g.canvas).padding(horizontal = 16.dp, vertical = 10.dp),
                        textStyle = CodeStyle.copy(color = g.fg),
                        cursorBrush = SolidColor(g.accent),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Default,
                        ),
                    )
                }
            }
        }
    }

    if (commitOpen && t != null && e != null) {
        CommitDialog(
            owner, name, t, if (creating) newPath else path!!, creating,
            content = { RepoWrite.fromEditor(st.text.text, e.crlf) }, sha = e.sha,
            onDismiss = { commitOpen = false },
            onConflict = { commitOpen = false; conflict = true },
            onDone = { branch, pr, summary, body ->
                commitOpen = false
                draft.submitted(DraftValue(if (creating) st.path else "", st.text.text))
                ctx.toast("已提交到 $branch")
                val shownRef = if (branch == t.baseRef) ref else branch
                val finalPath = if (creating) newPath else path!!
                when {
                    pr -> nav.replace(Screen.NewPull(owner, name, branch, t.prBase, summary, body))
                    !creating && shownRef == ref && nav.stack.dropLast(1).lastOrNull()?.screen == Screen.FileView(owner, name, finalPath, ref) -> nav.pop()
                    else -> nav.replace(Screen.FileView(owner, name, finalPath, shownRef))
                }
            },
        )
    }

    if (conflict && creating) {
        GhDialog("文件已存在", onDismiss = { conflict = false }) {
            Text("目标分支上已经有同名文件。请修改文件路径，或打开该文件后再编辑。", fontSize = 14.sp, color = g.fg)
        }
    } else if (conflict) {
        GhDialog(
            "文件已被修改",
            onDismiss = { conflict = false },
            confirm = "重新加载",
            onConfirm = {
                conflict = false
                ctx.copy(st.text.text, "你的修改已复制到剪贴板")
                st.loadedFor = null
                st.restored = false
                src.load(true)
            },
        ) {
            Text(
                "在你编辑期间，GitHub 上的这个文件已有新的提交，直接提交会覆盖他人的修改。重新加载会读取最新版本；你当前的内容会先复制到剪贴板，方便对照后重新修改。",
                fontSize = 14.sp, color = g.fg,
            )
        }
    }

    if (leaving) {
        GhDialog("放弃修改？", { leaving = false }, confirm = "离开", danger = true, onConfirm = { leaving = false; draft.flush(); nav.pop() }) {
            Text(
                if (tooLarge) "修改尚未提交。文件较大，草稿没有保存在本机，离开后改动将丢失。"
                else "修改尚未提交，草稿已保存在本机，下次编辑该文件时会自动恢复。",
                fontSize = 14.sp, color = g.fg,
            )
        }
    }
}

@Composable
private fun CommitDialog(
    owner: String,
    name: String,
    target: WriteTarget,
    path: String,
    creating: Boolean,
    content: () -> String,
    sha: String?,
    onDismiss: () -> Unit,
    onConflict: () -> Unit,
    onDone: (branch: String, pr: Boolean, summary: String, body: String) -> Unit,
) {
    val g = Gh.c
    val entry = LocalEntry.current
    val fileName = path.substringAfterLast('/')
    var summary by rememberSaveable { mutableStateOf((if (creating) "Create " else "Update ") + fileName) }
    var description by rememberSaveable { mutableStateOf("") }
    var newBranch by rememberSaveable { mutableStateOf(target.branch == null && !target.emptyRepo) }
    var branchName by rememberSaveable { mutableStateOf("") }
    var openPr by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val existing = rememberLoader("existing-branches") { GitHub.branches(owner, name, 1, it).map { b -> b.name } }
    LaunchedEffect(existing.data) {
        existing.data?.let { if (branchName.isBlank()) branchName = RepoWrite.patchBranch(Session.login, it) }
    }
    val branch = branchName.trim()
    val branchTaken = existing.data?.any { it.equals(branch, ignoreCase = true) } == true
    val valid = summary.isNotBlank() && (!newBranch || (RepoWrite.validBranch(branch) && !branchTaken))

    fun submit() {
        busy = true
        error = null
        val message = RepoWrite.commitMessage(summary, description)
        entry.scope.launch {
            var created: String? = null
            try {
                val to = if (newBranch) {
                    GitHub.createBranch(owner, name, branch, GitHub.resolveSha(owner, name, target.baseRef))
                    created = branch
                    branch
                } else target.baseRef
                GitHub.putFile(owner, name, path, content(), message, if (creating) null else sha, to)
                RepoWrite.changed(owner, name)
                busy = false
                onDone(to, newBranch && openPr, summary.trim(), description.trim())
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                busy = false
                // 响应丢失不代表远端没有提交，保留新分支，避免删除已经写入的内容。
                when {
                    created != null -> error = "提交结果需确认，分支 $created 已保留。请先查看该分支的文件和提交记录，再决定是否重试。\n${err.friendly()}"
                    newBranch && RepoWrite.isAlreadyExists(err) -> error = "分支 $branch 已存在，请换一个名称"
                    RepoWrite.isShaConflict(err) -> onConflict()
                    else -> error = "提交失败：" + err.friendly()
                }
            }
        }
    }

    GhDialog(
        if (creating) "提交新文件" else "提交更改",
        onDismiss = { if (!busy) onDismiss() },
        confirm = if (busy) "提交中…" else "提交",
        confirmEnabled = valid && !busy,
        onConfirm = ::submit,
    ) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text(path, fontSize = 12.sp, color = g.fgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            GhField(summary, { summary = it }, "提交信息")
            Spacer(Modifier.height(8.dp))
            GhField(description, { description = it }, "扩展描述（可选）", singleLine = false, minLines = 2)
            Spacer(Modifier.height(10.dp))
            RadioRow(
                !newBranch, "直接提交到 ${target.baseRef}",
                when {
                    // 空仓库连一个提交都没有，不能先建分支（没有 SHA 可指）；contents 接口允许直接向默认分支写入
                    target.emptyRepo -> "空仓库的第一个提交会直接建在 ${target.baseRef} 上"
                    target.branch == null -> "${target.baseRef} 不是分支，无法直接提交"
                    target.branch.protected -> "该分支受保护，提交可能被拒绝"
                    else -> null
                },
                enabled = (target.branch != null || target.emptyRepo) && !busy,
            ) { newBranch = false }
            RadioRow(newBranch, "新建分支并提交", "从 ${target.baseRef} 创建新分支", enabled = !busy && !target.emptyRepo) { newBranch = true }
            if (newBranch) {
                Spacer(Modifier.height(4.dp))
                GhField(branchName, { branchName = it }, "新分支名称")
                when {
                    branch.isNotEmpty() && !RepoWrite.validBranch(branch) -> Text("分支名称无效", fontSize = 12.sp, color = g.danger)
                    branchTaken -> Text("已存在同名分支", fontSize = 12.sp, color = g.danger)
                }
                SwitchRow("提交后创建拉取请求", "合并到 ${target.prBase}", openPr, enabled = !busy) { openPr = it }
            }
            error?.let { InlineError(it) }
        }
    }
}

// ======================= 删除文件 =======================

/** 删除文件并直接提交到当前分支 */
@Composable
fun DeleteFileDialog(owner: String, name: String, path: String, ref: String?, onDismiss: () -> Unit) {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val entry = LocalEntry.current
    val target = rememberLoader("delete-target") { WriteTarget.load(owner, name, ref) }
    var message by remember { mutableStateOf("Delete ${path.substringAfterLast('/')}") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val t = target.data
    val ok = t != null && t.canPush && t.branch != null
    GhDialog(
        "删除文件", onDismiss = { if (!busy) onDismiss() },
        confirm = if (busy) "删除中…" else "删除并提交", danger = true,
        confirmEnabled = ok && message.isNotBlank() && !busy,
        onConfirm = {
            val branch = t?.branch?.name ?: return@GhDialog
            busy = true
            error = null
            entry.scope.launch {
                try {
                    val sha = GitHub.fileContent(owner, name, path, branch).sha
                    GitHub.deleteFile(owner, name, path, sha, message.trim(), branch)
                    RepoWrite.changed(owner, name)
                    ctx.toast("已删除 ${path.substringAfterLast('/')}")
                    onDismiss()
                    nav.parentFiles(owner, name, path, ref)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    busy = false
                    error = if (RepoWrite.isShaConflict(e)) "文件已被他人修改，请返回刷新后重试" else "删除失败：" + e.friendly()
                }
            }
        },
    ) {
        Column {
            Text(path, fontSize = 13.sp, color = g.fg, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            when {
                t == null && target.error != null -> InlineError(target.error!!) { target.load(true) }
                t == null -> Loading(Modifier.fillMaxWidth().height(60.dp))
                !t.canPush -> Text(if (t.repo.archived) "仓库已归档，不能删除文件。" else "你没有该仓库的推送权限。GitHub 会要求先 Fork 再提交，本应用暂不支持。", fontSize = 13.sp, color = g.attention)
                t.branch == null -> Text("当前查看的是标签或提交，请先切换到分支再删除。", fontSize = 13.sp, color = g.attention)
                else -> {
                    Text("将直接提交到 ${t.branch.name}。删除后可以在提交历史中找回。", fontSize = 13.sp, color = g.fgMuted)
                    if (t.branch.protected) Text("该分支受保护，提交可能被拒绝。", fontSize = 12.sp, color = g.attention)
                    Spacer(Modifier.height(8.dp))
                    GhField(message, { message = it }, "提交信息")
                }
            }
            error?.let { InlineError(it) }
        }
    }
}

// ======================= 新建拉取请求 =======================

@Composable
fun NewPullScreen(owner: String, name: String, head: String, base: String, title: String, body: String) {
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val entry = LocalEntry.current
    val g = Gh.c
    val draft = rememberDraft("new-pull:${owner.lowercase()}/${name.lowercase()}:$head")
    var prTitle by rememberSaveable { mutableStateOf(draft.initial.title.ifBlank { title }) }
    var prBody by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.initial.body.ifBlank { body })) }
    var into by rememberSaveable { mutableStateOf(base) }
    var asDraft by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Page("创建拉取请求", subtitle = "$owner/$name") { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
          Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().border(1.dp, g.border, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Oc(R.drawable.oc_git_pull_request, g.fgMuted)
                Spacer(Modifier.width(8.dp))
                Text(
                    into, color = g.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, false).clip(RoundedCornerShape(4.dp)).clickable(enabled = !busy) { picking = true }.padding(2.dp),
                )
                Spacer(Modifier.width(6.dp))
                Oc(R.drawable.oc_arrow_left, g.fgMuted, 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(head, color = g.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
            }
            Text("点击目标分支可以更换", Modifier.padding(top = 4.dp), fontSize = 11.sp, color = g.fgMuted)
            Spacer(Modifier.height(12.dp))
            GhField(prTitle, { prTitle = it }, "标题")
            Spacer(Modifier.height(12.dp))
            MarkdownEditor(prBody, { prBody = it }, placeholder = "描述（支持 Markdown）", contextRepo = "$owner/$name", minLines = 6)
            DraftBinding(draft, DraftValue(prTitle, prBody.text))
          }
            Spacer(Modifier.height(4.dp))
            SwitchRow("创建为草稿", "草稿 PR 需要标记为可审查后才能合并", asDraft, enabled = !busy) { asDraft = it }
          Column(Modifier.padding(horizontal = 16.dp)) {
            error?.let { InlineError(it) }
            Spacer(Modifier.height(12.dp))
            GhButton(if (busy) "创建中…" else "创建拉取请求", Modifier.fillMaxWidth(), primary = true, enabled = prTitle.isNotBlank() && into != head && !busy) {
                busy = true
                error = null
                val submitted = DraftValue(prTitle, prBody.text)
                draft.update(submitted)
                entry.scope.launch {
                    try {
                        val pr = GitHub.createPull(owner, name, prTitle.trim(), prBody.text, head, into, asDraft)
                        draft.submitted(submitted)
                        ctx.toast("已创建 #${pr.number}")
                        nav.replace(Screen.IssueDetail(owner, name, pr.number, true))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        busy = false
                        error = when {
                            e.message?.contains("No commits between", true) == true -> "$head 与 $into 之间没有差异，无法创建拉取请求"
                            e.message?.contains("already exists", true) == true -> "$head 已经有一个打开的拉取请求"
                            else -> e.friendly()
                        }
                    }
                }
            }
          }
        }
    }
    if (picking) BranchPicker(owner, name, into, onDismiss = { picking = false }) { into = it; picking = false }
}
