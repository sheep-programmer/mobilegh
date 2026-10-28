package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.mobilegh.ui.components.groupItem
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.Session
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.act
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.toast
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.OnRepoChanged
import com.mobilegh.data.RepoWrite
import com.mobilegh.data.repoFileDownloadUrl
import androidx.compose.runtime.remember
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.CodeView
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.emptyItem
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.ZoomImage
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.highlightLines
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.downloadFile
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.share
import com.mobilegh.ui.components.syntaxColors
import com.mobilegh.ui.components.MdText
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "avif")
private val MD_EXT = setOf("md", "markdown", "mdown", "mkd", "rst", "adoc", "asciidoc", "org", "textile")
private val BINARY_EXT = setOf("zip", "jar", "apk", "aab", "so", "dll", "exe", "bin", "pdf", "gz", "tgz", "xz", "7z", "rar", "woff", "woff2", "ttf", "otf", "mp3", "mp4", "mov", "class", "dex", "keystore", "jks", "psd", "o", "a", "dylib")

internal fun ext(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

@Composable
fun FilesScreen(owner: String, name: String, path: String, ref: String?) {
    val nav = LocalNav.current
    val g = Gh.c
    val ctx = rememberCtx()
    val items = rememberLoader("dir") { f -> GitHub.contents(owner, name, path, ref, f).sortedWith(compareBy({ it.type != "dir" }, { it.name.lowercase() })) }
    val last = rememberLoader("last") { GitHub.commits(owner, name, ref, path.ifEmpty { null }, 1, it).firstOrNull() }
    val canPush = rememberLoader("can-push") { GitHub.repo(owner, name, it).let { r -> r.permissions?.push == true && !r.archived } }
    OnRepoChanged(owner, name) { items.refresh(); last.refresh() }
    Page(
        if (path.isEmpty()) name else path.substringAfterLast('/'),
        subtitle = listOfNotNull(ref, path.takeIf { it.isNotEmpty() }?.let { "/$it" }).joinToString(" · ").ifEmpty { "$owner/$name" },
        actions = {
            OcButton(R.drawable.oc_history, { nav.push(Screen.Commits(owner, name, ref, path.ifEmpty { null })) })
            MoreMenu(buildList {
                if (canPush.data == true) {
                    add(MenuAction("新建文件") { nav.push(Screen.EditFile(owner, name, ref, null, path)) })
                    // 当前目录就是上传的目标目录，选文件夹时会在下面再建一层
                    add(MenuAction("上传文件（夹）") { nav.push(Screen.UploadFiles(owner, name, ref, path)) })
                }
                add(MenuAction("复制路径") { ctx.copy(path.ifEmpty { "/" }) })
                add(MenuAction("查找仓库文件") { nav.push(Screen.FileFinder(owner, name, ref)) })
                add(MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/$owner/$name/tree/${ref ?: "HEAD"}/$path") })
            })
        },
    ) { pad ->
        LoadBox(items, Modifier.padding(pad).fillMaxSize()) { list ->
            val hasLast = last.data != null
            val hasUp = path.isNotEmpty()
            val total = (if (hasLast) 1 else 0) + (if (hasUp) 1 else 0) + list.size
            val upIdx = if (hasLast) 1 else 0
            val base = upIdx + if (hasUp) 1 else 0
            fun box(i: Int) = Modifier.groupItem(i == 0, i == total - 1, g.border, if (i == 0 && hasLast) g.canvasSubtle else g.canvas)
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
                last.data?.let { c ->
                    item {
                        Row(
                            box(0).fillMaxWidth().clickable { nav.push(Screen.CommitDetail(owner, name, c.sha)) }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(c.author?.avatarUrl, 20.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(c.author?.login ?: c.commit.author?.name ?: "", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = g.fg)
                            Spacer(Modifier.width(6.dp))
                            MdText(c.commit.message.lineSequence().first(), Modifier.weight(1f), fontSize = 13.sp, color = g.fgMuted, maxLines = 1)
                            Spacer(Modifier.width(6.dp))
                            Text(relTime(c.commit.author?.date), fontSize = 12.sp, color = g.fgMuted)
                        }
                    }
                }
                if (hasUp) item {
                    Row(box(upIdx).fillMaxWidth().clickable { nav.parentFiles(owner, name, path, ref) }.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Oc(R.drawable.oc_file_directory_fill, g.folder)
                        Spacer(Modifier.width(12.dp))
                        Text("..", color = g.fg, fontSize = 14.sp)
                    }
                }
                itemsIndexed(list, key = { _, it -> it.path }) { idx, c ->
                    Row(
                        box(base + idx).fillMaxWidth().clickable {
                            when (c.type) {
                                "dir" -> nav.push(Screen.Files(owner, name, c.path, ref))
                                "submodule" -> c.htmlUrl?.let { u -> com.mobilegh.nav.Links.route(u)?.let(nav::push) ?: ctx.openBrowser(u) }
                                else -> nav.push(Screen.FileView(owner, name, c.path, ref))
                            }
                        }.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (c.type) {
                            "dir" -> Oc(R.drawable.oc_file_directory_fill, g.folder)
                            "submodule" -> Oc(R.drawable.oc_file_submodule, g.fgMuted)
                            "symlink" -> Oc(R.drawable.oc_link, g.fgMuted)
                            else -> Oc(if (ext(c.name) in IMAGE_EXT) R.drawable.oc_file_media else R.drawable.oc_file, g.fgMuted)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, color = g.fg, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (c.type == "file") Text(fmtSize(c.size), fontSize = 12.sp, color = g.fgMuted)
                    }
                }
                if (list.isEmpty()) emptyItem(listState, "空目录", R.drawable.oc_file_directory)
            }
        }
    }
}

private sealed interface FileBody {
    data class Code(val text: String, val lines: List<AnnotatedString>) : FileBody
    data class Html(val html: String, val raw: String) : FileBody
    data object Image : FileBody
    data object Binary : FileBody
}

@Composable
fun FileViewScreen(owner: String, name: String, path: String, ref: String?) {
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val g = Gh.c
    val sc = syntaxColors(g)
    val e = ext(path)
    val rawUrl = "https://raw.githubusercontent.com/$owner/$name/${ref ?: "HEAD"}/${com.mobilegh.data.Api.encodePath(path)}"
    val body = rememberLoader("file:${g.dark}") { force ->
        when {
            e in IMAGE_EXT -> FileBody.Image
            e in BINARY_EXT -> FileBody.Binary
            e in MD_EXT -> {
                val html = GitHub.fileHtml(owner, name, path, ref, force)
                FileBody.Html(html, "")
            }
            else -> {
                val text = GitHub.fileRaw(owner, name, path, ref, force)
                if (text.take(8000).contains('\u0000')) FileBody.Binary
                else FileBody.Code(text, withContext(Dispatchers.Default) { highlightLines(text, path, sc) })
            }
        }
    }
    var mdSource by rememberSaveable { mutableStateOf(false) }
    val source = rememberLoader("src:$mdSource:${g.dark}") { force ->
        if (!mdSource) null else GitHub.fileRaw(owner, name, path, ref, force).let { t -> t to withContext(Dispatchers.Default) { highlightLines(t, "x.txt", sc) } }
    }
    // 仓库信息除了决定能否写入，下载还要用两样：isPrivate 决定走哪个主机（私有的
    // github.com/…/raw/… 带 Token 实测 404，公开的正常 302，所以私有只能走 raw 主机带凭据），
    // defaultBranch 则在未选分支时给出确定的 ref，而不是赌 raw 主机能不能解析 HEAD
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    val canPush = repo.data?.let { it.permissions?.push == true && !it.archived } == true
    OnRepoChanged(owner, name) { body.refresh(); if (mdSource) source.refresh(); repo.refresh() }
    var deleting by remember { mutableStateOf(false) }
    if (deleting) DeleteFileDialog(owner, name, path, ref) { deleting = false }
    val fileName = path.substringAfterLast('/')
    val downloadRef = ref ?: repo.data?.defaultBranch ?: "HEAD"
    val downloadPrivate = repo.data?.isPrivate ?: com.mobilegh.data.Net.isPrivateRepo("$owner/$name")
    // 可见性未知时不能猜成公开，否则私有文件地址会被送给公共镜像且不带凭据。
    val downloadReady = repo.data != null
    Page(
        fileName, subtitle = "${ref ?: "默认分支"} · $path",
        actions = {
            val b = body.data
            // 没有推送权限时编辑页只能提示去网页 Fork，不如直接不给入口（与「删除文件」一致）
            val editable = canPush && ((b is FileBody.Code && b.text.length <= RepoWrite.MAX_EDIT_BYTES) || b is FileBody.Html)
            if (editable) OcButton(R.drawable.oc_pencil, { nav.push(Screen.EditFile(owner, name, ref, path)) })
            MoreMenu(buildList {
                if (editable) add(MenuAction("编辑文件") { nav.push(Screen.EditFile(owner, name, ref, path)) })
                if (b is FileBody.Code) add(MenuAction("复制全部内容") { ctx.copy(b.text) })
                if (b is FileBody.Html) add(MenuAction(if (mdSource) "查看渲染" else "查看源码") { mdSource = !mdSource })
                add(MenuAction(if (Session.codeWrap) "关闭自动换行" else "自动换行") { Session.toggleWrap() })
                // 代码、图片、Markdown 以前都没有下载入口（只有二进制页有按钮），现在统一放到「更多」
                if (downloadReady) add(MenuAction("下载") {
                    ctx.downloadFile(repoFileDownloadUrl(owner, name, downloadRef, path, downloadPrivate), fileName, downloadPrivate)
                })
                add(MenuAction("文件历史") { nav.push(Screen.Commits(owner, name, ref, path)) })
                add(MenuAction("逐行归因（Blame）") { nav.push(Screen.Blame(owner, name, path, ref)) })
                add(MenuAction("复制路径") { ctx.copy(path) })
                add(MenuAction("分享链接") { ctx.share("https://github.com/$owner/$name/blob/${ref ?: "HEAD"}/$path") })
                add(MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/$owner/$name/blob/${ref ?: "HEAD"}/$path") })
                if (canPush) add(MenuAction("删除文件", danger = true) { deleting = true })
            })
        },
    ) { pad ->
        LoadBox(body, Modifier.padding(pad).fillMaxSize()) { b ->
            when (b) {
                is FileBody.Code -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${b.lines.size} 行 · ${fmtSize(b.text.length.toLong())}", fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.weight(1f))
                        if (b.text.length > 400_000) Pill("过大，仅显示前 400KB", g.attention)
                    }
                    HDivider()
                    CodeView(b.lines, Session.codeWrap)
                }
                is FileBody.Html -> if (mdSource) {
                    val s = source.data
                    when {
                        s != null -> CodeView(s.second, Session.codeWrap)
                        source.error != null -> ErrorState(source.error!!) { source.refresh() }
                        else -> com.mobilegh.ui.components.Loading()
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
                        HtmlView(b.html, baseUrl = "https://github.com/$owner/$name/blob/${ref ?: "HEAD"}/${path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }}")
                    }
                }
                FileBody.Image -> ZoomImage(rawUrl, Modifier.fillMaxSize())
                FileBody.Binary -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyState("二进制文件，无法预览", R.drawable.oc_file)
                    GhButton(
                        "下载到本机", Modifier.padding(horizontal = 32.dp).fillMaxWidth(),
                        icon = R.drawable.oc_download, enabled = downloadReady,
                    ) {
                        ctx.downloadFile(repoFileDownloadUrl(owner, name, downloadRef, path, downloadPrivate), fileName, downloadPrivate)
                    }
                }
            }
        }
    }
}

@Composable
fun BranchesScreen(owner: String, name: String) {
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val g = Gh.c
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    val branches = com.mobilegh.nav.rememberPager("branches", 100) { p, f -> GitHub.branches(owner, name, p, f) }
    val tags = com.mobilegh.nav.rememberPager("tags", 100) { p, f -> GitHub.tags(owner, name, p, f) }
    OnRepoChanged(owner, name) { branches.refresh(); tags.refresh() }
    val r = repo.data
    val canPush = r?.permissions?.push == true && !r.archived
    // 新建分支的来源；null 表示弹窗关闭
    var creatingFrom by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<com.mobilegh.data.Branch?>(null) }
    Page(
        "分支与标签", subtitle = "$owner/$name",
        actions = {
            if (canPush) OcButton(R.drawable.oc_plus, { creatingFrom = r?.defaultBranch ?: "" })
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Chips(listOf("分支", "标签"), tab) { tab = it }
            HDivider()
            if (tab == 0) {
                com.mobilegh.ui.components.PagedList(branches) { b ->
                    val isDefault = b.name == r?.defaultBranch
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.push(Screen.Files(owner, name, "", b.name)) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Oc(R.drawable.oc_git_branch, g.fgMuted)
                        Spacer(Modifier.width(10.dp))
                        Text(b.name, color = g.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isDefault) { Spacer(Modifier.width(6.dp)); Pill("默认", g.accent) }
                        if (b.protected) {
                            Spacer(Modifier.width(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Oc(R.drawable.oc_shield_lock, g.attention, 12.dp)
                                Spacer(Modifier.width(2.dp))
                                Pill("受保护", g.attention)
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(b.commit.sha.take(7), fontSize = 12.sp, color = g.fgMuted, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.clickable { nav.push(Screen.CommitDetail(owner, name, b.commit.sha)) })
                        MoreMenu(buildList {
                            add(MenuAction("浏览代码") { nav.push(Screen.Files(owner, name, "", b.name)) })
                            add(MenuAction("提交历史") { nav.push(Screen.Commits(owner, name, b.name)) })
                            if (canPush) add(MenuAction("从此分支新建分支") { creatingFrom = b.name })
                            if (!isDefault && r != null) add(MenuAction("与 ${r.defaultBranch} 比较") { nav.push(Screen.Compare(owner, name, r.defaultBranch, b.name)) })
                            if (!isDefault && r != null && canPush) add(MenuAction("发起拉取请求") { nav.push(Screen.NewPull(owner, name, b.name, r.defaultBranch, b.name)) })
                            add(MenuAction("复制分支名") { ctx.copy(b.name) })
                            if (canPush && !isDefault) add(MenuAction("删除分支", danger = true) { deleting = b })
                        }, g.fgMuted)
                    }
                }
            } else {
                com.mobilegh.ui.components.PagedList(tags, empty = "没有标签") { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.push(Screen.Files(owner, name, "", t.name)) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Oc(R.drawable.oc_tag, g.fgMuted)
                        Spacer(Modifier.width(10.dp))
                        Text(t.name, color = g.fg, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.commit.sha.take(7), fontSize = 12.sp, color = g.fgMuted, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        MoreMenu(buildList {
                            add(MenuAction("浏览代码") { nav.push(Screen.Files(owner, name, "", t.name)) })
                            if (canPush) add(MenuAction("从此标签新建分支") { creatingFrom = t.name })
                            add(MenuAction("复制标签名") { ctx.copy(t.name) })
                        }, g.fgMuted)
                    }
                }
            }
        }
    }
    creatingFrom?.let { from ->
        NewBranchDialog(owner, name, from, onDismiss = { creatingFrom = null }) { created ->
            creatingFrom = null
            tab = 0
            ctx.toast("已创建分支 $created")
        }
    }
    deleting?.let { b ->
        DeleteBranchDialog(owner, name, b, onDismiss = { deleting = null }) {
            deleting = null
            branches.update { list -> list.removeAll { it.name == b.name } }
            ctx.toast("已删除分支 ${b.name}")
        }
    }
}

@Composable
private fun NewBranchDialog(owner: String, name: String, initialSource: String, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val g = Gh.c
    val entry = LocalEntry.current
    var branch by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(initialSource) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameOk = RepoWrite.validBranch(branch.trim())
    GhDialog(
        "新建分支", onDismiss = { if (!busy) onDismiss() },
        confirm = if (busy) "创建中…" else "创建", confirmEnabled = nameOk && source.isNotBlank() && !busy,
        onConfirm = {
            busy = true
            error = null
            entry.act({ busy = false; error = if (it.contains("already exists", true)) "分支 ${branch.trim()} 已存在" else it }) {
                val sha = GitHub.resolveSha(owner, name, source.trim())
                GitHub.createBranch(owner, name, branch.trim(), sha)
                RepoWrite.changed(owner, name)
                busy = false
                onCreated(branch.trim())
            }
        },
    ) {
        Column {
            GhField(branch, { branch = it }, "分支名称", placeholder = "例如 feature/login")
            if (branch.isNotBlank() && !nameOk) Text("分支名称无效：不能包含空格、..、~ ^ : ? * [ 等字符", fontSize = 12.sp, color = g.danger)
            Spacer(Modifier.height(8.dp))
            GhField(
                source, { source = it }, "来源（分支、标签或提交 SHA）",
                trailing = { OcButton(R.drawable.oc_git_branch, { picking = true }, g.fgMuted, enabled = !busy) },
            )
            Text("新分支将指向来源当前所在的提交", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = g.fgMuted)
            error?.let { com.mobilegh.ui.components.InlineError(it) }
        }
    }
    if (picking) BranchPicker(owner, name, source, onDismiss = { picking = false }) { source = it; picking = false }
}

@Composable
private fun DeleteBranchDialog(owner: String, name: String, b: com.mobilegh.data.Branch, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val g = Gh.c
    val entry = LocalEntry.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GhDialog(
        "删除分支", onDismiss = { if (!busy) onDismiss() },
        confirm = if (busy) "删除中…" else "删除", danger = true, confirmEnabled = !busy,
        onConfirm = {
            busy = true
            error = null
            entry.act({ busy = false; error = it }) {
                GitHub.deleteBranch(owner, name, b.name)
                RepoWrite.changed(owner, name)
                busy = false
                onDeleted()
            }
        },
    ) {
        Column {
            Text("确定删除分支 ${b.name} 吗？", fontSize = 14.sp, color = g.fg)
            Spacer(Modifier.height(6.dp))
            Text("最新提交 ${b.commit.sha.take(7)}。只存在于该分支上的提交在删除后只能通过 SHA 找回；以该分支发起的拉取请求会被关闭。", fontSize = 13.sp, color = g.fgMuted)
            if (b.protected) {
                Spacer(Modifier.height(6.dp))
                Text("该分支受保护，GitHub 可能拒绝删除。", fontSize = 13.sp, color = g.attention)
            }
            error?.let { com.mobilegh.ui.components.InlineError(it) }
        }
    }
}
