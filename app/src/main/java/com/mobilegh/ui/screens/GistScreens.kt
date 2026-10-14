package com.mobilegh.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Comment
import com.mobilegh.data.DraftValue
import com.mobilegh.data.Gist
import com.mobilegh.data.GistDraftFile
import com.mobilegh.data.GitHub
import com.mobilegh.data.Session
import com.mobilegh.data.gistCreatePayload
import com.mobilegh.data.gistDraftFiles
import com.mobilegh.data.gistFilesError
import com.mobilegh.data.gistPatchPayload
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.refreshRetained
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.retain
import com.mobilegh.nav.write
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.MdText
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.RadioRow
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.Segmented
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.escapeHtml
import com.mobilegh.ui.components.fmtDateTime
import com.mobilegh.ui.components.highlightLines
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.rememberDraft
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.share
import com.mobilegh.ui.components.syntaxColors
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.CodeStyle
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ======================= Gist 列表 =======================

@Composable
fun GistsScreen(login: String?) {
    val nav = LocalNav.current
    // 自己的 Gist：login 为空，或链接里打开的正好是当前账号
    val own = login == null || login.equals(Session.login, ignoreCase = true)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 自己的列表走 /gists，才能包含私密 Gist
    val mine = rememberPager("gists:$login") { p, f -> GitHub.gists(if (own) null else login, p, f) }
    val starred = rememberPager("gists:starred") { p, f -> GitHub.starredGists(p, f) }
    Page(
        "Gists", subtitle = login ?: Session.login,
        actions = {
            if (own && Session.token != null) OcButton(R.drawable.oc_plus, { nav.push(Screen.GistEditor()) })
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (own) {
                Segmented(listOf("我的", "已加星标"), tab, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { tab = it }
            }
            val showStarred = own && tab == 1
            PagedList(
                if (showStarred) starred else mine,
                empty = if (showStarred) "还没有加星标的 Gist" else "没有 Gist",
                emptyIcon = if (showStarred) R.drawable.oc_star else R.drawable.oc_code_square,
                emptyAction = if (!showStarred && own && Session.token != null) "新建 Gist" else "刷新",
                onEmptyAction = if (!showStarred && own && Session.token != null) ({ nav.push(Screen.GistEditor()) }) else null,
            ) { gist ->
                GistRow(gist) { nav.push(Screen.GistDetail(gist.id)) }
            }
        }
    }
}

@Composable
private fun GistRow(g0: Gist, onClick: () -> Unit) {
    val g = Gh.c
    val first = g0.files.keys.firstOrNull() ?: g0.id
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(g0.owner?.avatarUrl, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text("${g0.owner?.login ?: ""} / ", color = g.fgMuted, fontSize = 14.sp)
            Text(first, fontWeight = FontWeight.SemiBold, color = g.fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
            Spacer(Modifier.width(6.dp))
            if (!g0.public) Pill("私密")
        }
        if (!g0.description.isNullOrBlank()) {
            MdText(g0.description, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = g.fgMuted, maxLines = 2)
        }
        Text("${g0.files.size} 个文件 · ${g0.comments} 条评论 · 更新于 ${relTime(g0.updatedAt)}", fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 4.dp))
    }
}

// ======================= Gist 详情 =======================

/** 写入后让列表页（返回时）重新加载 */
private fun com.mobilegh.nav.Navigator.refreshGistLists() = refreshRetained { it is Screen.Gists }

@Composable
fun GistDetailScreen(id: String) {
    val g = Gh.c
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val sc = syntaxColors(g)
    val gist = rememberLoader("gist:${g.dark}") { f ->
        val d = GitHub.gist(id, f)
        d to withContext(Dispatchers.Default) {
            d.files.values.map { file -> file.filename to highlightLines(file.content.orEmpty(), file.filename, sc) }
        }
    }
    val signedIn = Session.token != null
    val starState = rememberLoader("gist:star") { if (Session.token == null) false else GitHub.isGistStarred(id) }
    val comments = rememberLoader("gist:comments") { f ->
        // 正常情况下 full+json 已带 body_html；缺失时用 /markdown 补渲染，失败则按纯文本显示
        GitHub.gistComments(id, f).map { c ->
            if (!c.bodyHtml.isNullOrBlank() || c.body.isNullOrBlank()) c
            else c.copy(bodyHtml = runCatching { GitHub.renderMarkdown(c.body, null) }.getOrElse { "<p>${escapeHtml(c.body).replace("\n", "<br>")}</p>" })
        }
    }
    var starred by remember { mutableStateOf<Boolean?>(null) }
    var starBusy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Comment?>(null) }
    var deleting by remember { mutableStateOf<Comment?>(null) }

    val draft = rememberDraft("gist-comment:$id")
    var comment by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.initial.body)) }
    var previewComment by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    val d = gist.data?.first
    val isStarred = starred ?: starState.data
    val me = Session.login
    val mine = d != null && me != null && d.owner?.login.equals(me, ignoreCase = true)
    val url = d?.htmlUrl?.takeIf { it.isNotBlank() } ?: "https://gist.github.com/$id"

    Page(
        d?.files?.keys?.firstOrNull() ?: "Gist", subtitle = d?.owner?.login,
        actions = {
            if (signedIn) {
                OcButton(
                    if (isStarred == true) R.drawable.oc_star_fill else R.drawable.oc_star,
                    {
                        val on = isStarred != true
                        starred = on
                        starBusy = true
                        entry.write({ starred = !on; starBusy = false; ctx.toast(it) }, {
                            starBusy = false
                            ctx.toast(if (on) "已加星标" else "已取消星标")
                            nav.refreshGistLists()
                        }) { GitHub.starGist(id, on) }
                    },
                    if (isStarred == true) g.attention else g.fg,
                    enabled = isStarred != null && !starBusy,
                )
            }
            MoreMenu(buildList {
                if (d != null && signedIn) {
                    if (mine) {
                        add(MenuAction("编辑") { nav.push(Screen.GistEditor(id)) })
                    } else {
                        add(MenuAction("Fork 到我的 Gist") {
                            if (busy) return@MenuAction
                            busy = true
                            entry.write<Gist>({ busy = false; ctx.toast(it) }, { fork ->
                                busy = false
                                ctx.toast("已 Fork")
                                nav.refreshGistLists()
                                nav.push(Screen.GistDetail(fork.id))
                            }) { GitHub.forkGist(id) }
                        })
                    }
                }
                add(MenuAction("刷新") { gist.refresh(); starState.load(true); comments.load(true) })
                add(MenuAction("复制链接") { ctx.copy(url) })
                add(MenuAction("分享") { ctx.share(url) })
                add(MenuAction("在浏览器打开") { ctx.openBrowser(url) })
                if (mine) add(MenuAction("删除 Gist", danger = true) { confirmDelete = true })
            })
        },
        bottomBar = {
            if (d != null && signedIn) {
                CommentComposerBar(comment, { comment = it }, previewComment, { previewComment = it }, null, sending, draft) {
                    if (comment.text.isBlank() || sending) return@CommentComposerBar
                    sending = true
                    val submitted = DraftValue(body = comment.text)
                    draft.update(submitted)
                    entry.write({ sending = false; ctx.toast(it) }, {
                        draft.submitted(submitted)
                        if (comment.text == submitted.body) comment = TextFieldValue()
                        previewComment = false
                        sending = false
                        ctx.toast("评论已发布")
                        comments.load(true)
                        nav.refreshGistLists()
                    }) { GitHub.addGistComment(id, submitted.body.trim()) }
                }
            }
        },
    ) { pad ->
        LoadBox(gist, Modifier.padding(pad).fillMaxSize()) { (d, files) ->
            SelectionContainer {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        if (!d.description.isNullOrBlank()) Text(d.description, fontSize = 16.sp, color = g.fg)
                        Text(
                            "创建于 ${fmtDateTime(d.createdAt)} · ${if (d.public) "公开" else "私密"} · ${d.files.size} 个文件",
                            fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                files.forEach { (fname, lines) ->
                    item(key = "h:$fname") {
                        HDivider()
                        Row(Modifier.fillMaxWidth().background(g.canvasSubtle).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Oc(R.drawable.oc_file_code, g.fgMuted)
                            Spacer(Modifier.width(8.dp))
                            Text(fname, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = g.fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            OcButton(R.drawable.oc_copy, { ctx.copy(d.files[fname]?.content.orEmpty()) }, g.fgMuted)
                        }
                        HDivider()
                    }
                    itemsIndexed(lines, key = { i, _ -> "$fname:$i" }) { i, line -> GistLine(i, line) }
                }
                item(key = "comments:title") {
                    HDivider()
                    SectionTitle("评论 ${comments.data?.size ?: d.comments}")
                }
                val list = comments.data
                when {
                    list == null && comments.error != null -> item(key = "comments:error") {
                        InlineError(comments.error!!, Modifier.padding(horizontal = 16.dp)) { comments.load(true) }
                    }
                    list == null -> item(key = "comments:loading") { Loading(Modifier.fillMaxWidth().height(64.dp)) }
                    list.isEmpty() -> item(key = "comments:empty") {
                        Text(
                            if (signedIn) "还没有评论，在下方发表第一条评论" else "还没有评论",
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 13.sp, color = g.fgMuted,
                        )
                    }
                    else -> items(list, key = { "c${it.id}" }) { c ->
                        val own = me != null && c.user?.login.equals(me, ignoreCase = true)
                        TimelineCard(
                            c.user, c.createdAt, if (c.user?.login != null && c.user.login == d.owner?.login) "作者" else null,
                            c.bodyHtml, "https://gist.github.com/",
                            menu = buildList {
                                if (own && signedIn) {
                                    add(MenuAction("编辑") { editing = c })
                                    add(MenuAction("删除", danger = true) { deleting = c })
                                }
                                add(MenuAction("复制内容") { ctx.copy(c.body.orEmpty()) })
                            },
                        )
                    }
                }
                if (list != null && list.size >= 100) item(key = "comments:more") {
                    GhButton("评论较多，在浏览器查看全部", Modifier.padding(16.dp).fillMaxWidth()) { ctx.openBrowser(url) }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
            }
        }
    }

    editing?.let { c ->
        EditDialog("编辑评论", null, c.body.orEmpty(), null, onDismiss = { editing = null }) { _, body ->
            editing = null
            entry.write({ ctx.toast(it) }, { ctx.toast("已保存"); comments.load(true) }) { GitHub.editGistComment(id, c.id, body) }
        }
    }
    deleting?.let { c ->
        GhDialog("删除评论", { deleting = null }, confirm = "删除", danger = true, onConfirm = {
            deleting = null
            entry.write({ ctx.toast(it) }, { ctx.toast("已删除"); comments.load(true); nav.refreshGistLists() }) { GitHub.deleteGistComment(id, c.id) }
        }) { Text("删除后无法恢复。", color = g.fg) }
    }
    if (confirmDelete) {
        GhDialog(
            "删除 Gist", { if (!busy) confirmDelete = false }, confirm = if (busy) "删除中…" else "删除", danger = true, confirmEnabled = !busy,
            onConfirm = {
                busy = true
                entry.write({ busy = false; confirmDelete = false; ctx.toast(it) }, {
                    busy = false
                    confirmDelete = false
                    ctx.toast("已删除")
                    nav.refreshGistLists()
                    nav.pop()
                }) { GitHub.deleteGist(id) }
            },
        ) { Text("将永久删除这个 Gist 及其全部文件、评论和修订历史，无法恢复。", color = g.fg) }
    }
}

@Composable
private fun GistLine(i: Int, line: AnnotatedString) {
    val g = Gh.c
    Row(Modifier.fillMaxWidth()) {
        Text("${i + 1}", Modifier.width(40.dp).padding(end = 8.dp), style = CodeStyle, color = g.fgMuted.copy(alpha = 0.7f), textAlign = TextAlign.End)
        Text(line, Modifier.weight(1f).padding(end = 8.dp), style = CodeStyle, color = g.fg)
    }
}

// ======================= 新建 / 编辑 Gist =======================

/** 编辑中的状态保存在页面条目上，切到其他页面再返回不会丢失 */
private class GistEditorState {
    var ready by mutableStateOf(false)
    var description by mutableStateOf("")
    var public by mutableStateOf(false)
    var originalDescription: String? = null
    val files = mutableStateListOf<GistDraftFile>()
    /** 已移除的原文件名 */
    val removed = mutableStateListOf<String>()

    fun init(gist: Gist?) {
        if (ready) return
        if (gist == null) {
            files.add(GistDraftFile())
        } else {
            description = gist.description.orEmpty()
            originalDescription = gist.description
            public = gist.public
            files.addAll(gistDraftFiles(gist.files))
        }
        ready = true
    }

    fun remove(index: Int) {
        val f = files.removeAt(index)
        if (f.original != null) removed.add(f.original)
    }
}

@Composable
fun GistEditorScreen(id: String?) {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val g = Gh.c
    val state = retain("gist-editor:state") { GistEditorState() }
    // 编辑时总是取最新内容，避免用缓存覆盖别处的修改
    val source = rememberLoader("gist-editor:source") { listOfNotNull(id?.let { GitHub.gist(it, true) }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val dirty = state.ready && !busy && (
        if (id == null) state.description.isNotBlank() || state.files.any { it.filename.isNotBlank() || it.content.isNotBlank() }
        else gistPatchPayload(state.originalDescription, state.description, state.files, state.removed) != null
    )
    androidx.activity.compose.BackHandler(enabled = dirty) { confirmLeave = true }
    if (confirmLeave) {
        GhDialog("放弃修改？", { confirmLeave = false }, confirm = "放弃", danger = true, onConfirm = {
            confirmLeave = false
            nav.pop()
        }) { Text(if (id == null) "尚未创建的 Gist 内容不会保存。" else "未保存的修改将会丢失。", color = g.fg) }
    }

    Page(
        if (id == null) "新建 Gist" else "编辑 Gist", subtitle = Session.login,
        onBack = { if (dirty) confirmLeave = true else nav.pop() },
    ) { pad ->
        LoadBox(source, Modifier.padding(pad).fillMaxSize()) { loaded ->
            LaunchedEffect(loaded) { state.init(loaded.firstOrNull()) }
            if (!state.ready) return@LoadBox
            val validation = gistFilesError(state.files, state.removed)
            Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                GhField(
                    state.description, { if (!busy) state.description = it }, "描述（可选）",
                    Modifier.padding(horizontal = 16.dp), placeholder = "这个 Gist 是做什么的",
                )
                if (id == null) {
                    SectionTitle("可见性")
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        RadioRow(!state.public, "私密（Secret）", "不会出现在搜索和你的主页中，但拿到链接的人都能查看", enabled = !busy) { state.public = false }
                        RadioRow(state.public, "公开", "所有人都能看到，会出现在搜索和你的主页中", enabled = !busy) { state.public = true }
                    }
                } else {
                    Text(
                        "${if (state.public) "公开" else "私密"} Gist · 可见性需在 GitHub 网页上修改",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = g.fgMuted,
                    )
                }
                SectionTitle("文件 ${state.files.size}")
                state.files.forEachIndexed { i, f ->
                    GistFileEditor(
                        f, enabled = !busy, removable = state.files.size > 1,
                        onChange = { state.files[i] = it },
                        onRemove = { state.remove(i) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (state.removed.isNotEmpty()) {
                    Text(
                        "将删除：" + state.removed.joinToString("、"),
                        Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = g.danger,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                GhButton("添加文件", Modifier.padding(horizontal = 16.dp), icon = R.drawable.oc_plus, enabled = !busy) {
                    state.files.add(GistDraftFile())
                }
                Spacer(Modifier.height(16.dp))
                val shownError = error ?: validation
                if (shownError != null) {
                    Text(shownError, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = if (error != null) g.danger else g.fgMuted)
                    Spacer(Modifier.height(8.dp))
                }
                GhButton(
                    when {
                        busy -> if (id == null) "创建中…" else "保存中…"
                        id != null -> "保存修改"
                        state.public -> "创建公开 Gist"
                        else -> "创建私密 Gist"
                    },
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(), primary = true,
                    enabled = !busy && validation == null && Session.token != null,
                ) {
                    error = null
                    if (id == null) {
                        val payload = runCatching { gistCreatePayload(state.description, state.public, state.files) }
                            .getOrElse { error = it.message; return@GhButton }
                        busy = true
                        entry.write<Gist>({ busy = false; error = it }, { created ->
                            busy = false
                            ctx.toast("Gist 已创建")
                            nav.refreshGistLists()
                            nav.replace(Screen.GistDetail(created.id))
                        }) { GitHub.createGist(payload) }
                    } else {
                        val payload = gistPatchPayload(state.originalDescription, state.description, state.files, state.removed)
                        if (payload == null) {
                            ctx.toast("没有修改")
                            nav.pop()
                            return@GhButton
                        }
                        busy = true
                        entry.write<Gist>({ busy = false; error = it }, {
                            busy = false
                            ctx.toast("已保存")
                            nav.refreshRetained { s -> s == Screen.GistDetail(id) || s is Screen.Gists }
                            nav.pop()
                        }) { GitHub.editGist(id, payload) }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun GistFileEditor(
    file: GistDraftFile,
    enabled: Boolean,
    removable: Boolean,
    onChange: (GistDraftFile) -> Unit,
    onRemove: () -> Unit,
) {
    val g = Gh.c
    Card(Modifier.padding(horizontal = 16.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(g.canvasSubtle, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    GhField(
                        file.filename, { if (enabled) onChange(file.copy(filename = it)) }, "文件名（含扩展名）",
                        placeholder = "例如 hello.py",
                    )
                }
                OcButton(R.drawable.oc_trash, onRemove, g.danger, enabled = enabled && removable)
            }
            HDivider()
            if (file.truncated) {
                Text(
                    "文件过大，内容未完整返回，只能改名或删除；修改内容请在 GitHub 网页上进行。",
                    Modifier.padding(12.dp), fontSize = 13.sp, color = g.fgMuted,
                )
            } else {
                OutlinedTextField(
                    file.content, { if (enabled) onChange(file.copy(content = it)) },
                    Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(8.dp),
                    textStyle = CodeStyle.copy(color = g.fg),
                    placeholder = { Text("文件内容", color = g.fgMuted, fontSize = 13.sp) },
                    minLines = 8,
                    shape = RoundedCornerShape(6.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default, autoCorrectEnabled = false),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = g.accent, unfocusedBorderColor = g.border,
                        focusedContainerColor = g.canvas, unfocusedContainerColor = g.canvas, cursorColor = g.accent,
                    ),
                )
            }
            if (file.original != null && file.filename.trim() != file.original) {
                Text("原文件名：${file.original}", Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), fontSize = 12.sp, color = g.fgMuted)
            }
        }
    }
}
