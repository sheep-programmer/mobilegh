package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.CodeView
import com.mobilegh.ui.components.EmptyState
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

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "avif")
private val MD_EXT = setOf("md", "markdown", "mdown", "mkd", "rst", "adoc", "asciidoc", "org", "textile")
private val BINARY_EXT = setOf("zip", "jar", "apk", "aab", "so", "dll", "exe", "bin", "pdf", "gz", "tgz", "xz", "7z", "rar", "woff", "woff2", "ttf", "otf", "mp3", "mp4", "mov", "class", "dex", "keystore", "jks", "psd", "o", "a", "dylib")

private fun ext(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

@Composable
fun FilesScreen(owner: String, name: String, path: String, ref: String?) {
    val nav = LocalNav.current
    val g = Gh.c
    val ctx = rememberCtx()
    val items = rememberLoader("dir") { f -> GitHub.contents(owner, name, path, ref, f).sortedWith(compareBy({ it.type != "dir" }, { it.name.lowercase() })) }
    val last = rememberLoader("last") { GitHub.commits(owner, name, ref, path.ifEmpty { null }, 1, it).firstOrNull() }
    Page(
        if (path.isEmpty()) name else path.substringAfterLast('/'),
        subtitle = listOfNotNull(ref, path.takeIf { it.isNotEmpty() }?.let { "/$it" }).joinToString(" · ").ifEmpty { "$owner/$name" },
        actions = {
            OcButton(R.drawable.oc_history, { nav.push(Screen.Commits(owner, name, ref, path.ifEmpty { null })) })
            MoreMenu(listOf(
                MenuAction("复制路径") { ctx.copy(path.ifEmpty { "/" }) },
                MenuAction("查找仓库文件") { nav.push(Screen.FileFinder(owner, name, ref)) },
                MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/$owner/$name/tree/${ref ?: "HEAD"}/$path") },
            ))
        },
    ) { pad ->
        LoadBox(items, Modifier.padding(pad).fillMaxSize()) { list ->
            val hasLast = last.data != null
            val hasUp = path.isNotEmpty()
            val total = (if (hasLast) 1 else 0) + (if (hasUp) 1 else 0) + list.size
            val upIdx = if (hasLast) 1 else 0
            val base = upIdx + if (hasUp) 1 else 0
            fun box(i: Int) = Modifier.groupItem(i == 0, i == total - 1, g.border, if (i == 0 && hasLast) g.canvasSubtle else g.canvas)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
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
                        Oc(R.drawable.oc_file_directory_fill, Color(0xFF54AEFF))
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
                            "dir" -> Oc(R.drawable.oc_file_directory_fill, Color(0xFF54AEFF))
                            "submodule" -> Oc(R.drawable.oc_file_submodule, g.fgMuted)
                            "symlink" -> Oc(R.drawable.oc_link, g.fgMuted)
                            else -> Oc(if (ext(c.name) in IMAGE_EXT) R.drawable.oc_file_media else R.drawable.oc_file, g.fgMuted)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, color = g.fg, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (c.type == "file") Text(fmtSize(c.size), fontSize = 12.sp, color = g.fgMuted)
                    }
                }
                if (list.isEmpty()) item { EmptyState("空目录") }
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
    val body = rememberLoader("file:${g.dark}") {
        when {
            e in IMAGE_EXT -> FileBody.Image
            e in BINARY_EXT -> FileBody.Binary
            e in MD_EXT -> {
                val html = GitHub.fileHtml(owner, name, path, ref)
                FileBody.Html(html, "")
            }
            else -> {
                val text = GitHub.fileRaw(owner, name, path, ref)
                if (text.take(8000).contains('\u0000')) FileBody.Binary
                else FileBody.Code(text, withContext(Dispatchers.Default) { highlightLines(text, path, sc) })
            }
        }
    }
    var mdSource by rememberSaveable { mutableStateOf(false) }
    val source = rememberLoader("src:$mdSource:${g.dark}") {
        if (!mdSource) null else GitHub.fileRaw(owner, name, path, ref).let { t -> t to withContext(Dispatchers.Default) { highlightLines(t, "x.txt", sc) } }
    }
    val fileName = path.substringAfterLast('/')
    Page(
        fileName, subtitle = "${ref ?: "默认分支"} · $path",
        actions = {
            val b = body.data
            MoreMenu(buildList {
                if (b is FileBody.Code) add(MenuAction("复制全部内容") { ctx.copy(b.text) })
                if (b is FileBody.Html) add(MenuAction(if (mdSource) "查看渲染" else "查看源码") { mdSource = !mdSource })
                add(MenuAction(if (Session.codeWrap) "关闭自动换行" else "自动换行") { Session.toggleWrap() })
                add(MenuAction("文件历史") { nav.push(Screen.Commits(owner, name, ref, path)) })
                add(MenuAction("逐行归因（Blame）") { nav.push(Screen.Blame(owner, name, path, ref)) })
                add(MenuAction("复制路径") { ctx.copy(path) })
                add(MenuAction("分享链接") { ctx.share("https://github.com/$owner/$name/blob/${ref ?: "HEAD"}/$path") })
                add(MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/$owner/$name/blob/${ref ?: "HEAD"}/$path") })
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
                    if (s != null) CodeView(s.second, Session.codeWrap) else com.mobilegh.ui.components.Loading()
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
                        HtmlView(b.html, baseUrl = "https://github.com/$owner/$name/blob/${ref ?: "HEAD"}/${path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }}")
                    }
                }
                FileBody.Image -> ZoomImage(rawUrl, Modifier.fillMaxSize())
                FileBody.Binary -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyState("二进制文件，无法预览", R.drawable.oc_file)
                    GhButton("下载到本机", Modifier.padding(horizontal = 32.dp).fillMaxWidth(), icon = R.drawable.oc_download) {
                        ctx.downloadFile(
                            "https://github.com/$owner/$name/raw/${ref ?: "HEAD"}/$path",
                            path.substringAfterLast('/'),
                            com.mobilegh.data.Net.isPrivateRepo("$owner/$name"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BranchesScreen(owner: String, name: String) {
    val nav = LocalNav.current
    val g = Gh.c
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    val branches = com.mobilegh.nav.rememberPager("branches", 100) { p, f -> GitHub.branches(owner, name, p, f) }
    val tags = com.mobilegh.nav.rememberPager("tags", 100) { p, f -> GitHub.tags(owner, name, p, f) }
    Page("分支与标签", subtitle = "$owner/$name") { pad ->
        Column(Modifier.padding(pad)) {
            Chips(listOf("分支", "标签"), tab) { tab = it }
            HDivider()
            if (tab == 0) {
                com.mobilegh.ui.components.PagedList(branches) { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.push(Screen.Files(owner, name, "", b.name)) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Oc(R.drawable.oc_git_branch, g.fgMuted)
                        Spacer(Modifier.width(10.dp))
                        Text(b.name, color = g.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (b.name == repo.data?.defaultBranch) { Pill("默认", g.accent); Spacer(Modifier.width(6.dp)) }
                        if (b.protected) Oc(R.drawable.oc_shield_lock, g.attention)
                        Spacer(Modifier.width(6.dp))
                        Text(b.commit.sha.take(7), fontSize = 12.sp, color = g.fgMuted, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.clickable { nav.push(Screen.CommitDetail(owner, name, b.commit.sha)) })
                    }
                }
            } else {
                com.mobilegh.ui.components.PagedList(tags, empty = "没有标签") { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.push(Screen.Files(owner, name, "", t.name)) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Oc(R.drawable.oc_tag, g.fgMuted)
                        Spacer(Modifier.width(10.dp))
                        Text(t.name, color = g.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(t.commit.sha.take(7), fontSize = 12.sp, color = g.fgMuted, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
