package com.mobilegh.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Commit
import com.mobilegh.data.CommitFile
import com.mobilegh.data.GitHub
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.DiffLine
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.diffLines
import com.mobilegh.ui.components.fmtDateTime
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.parsePatch
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.share
import com.mobilegh.ui.components.MdText
import com.mobilegh.ui.theme.Gh
import com.mobilegh.ui.theme.GhColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CommitsScreen(owner: String, name: String, ref: String?, path: String?, author: String? = null, since: String? = null, until: String? = null) {
    val pager = rememberPager("commits:$author:$since:$until") { p, f -> GitHub.commits(owner, name, ref, path, p, f, author, since, until) }
    Page("提交历史", subtitle = listOfNotNull("$owner/$name", ref, path, author).joinToString(" · ")) { pad ->
        PagedList(pager, Modifier.padding(pad), itemKey = { it.sha }, header = {
            if (since != null || until != null) item {
                Text("${since?.take(10) ?: "不限"} 至 ${until?.take(10) ?: "不限"} · 按提交时间精确筛选（UTC）",
                    Modifier.padding(16.dp), fontSize = 12.sp, color = Gh.c.fgMuted)
            }
        }) { CommitRow(owner, name, it) }
    }
}

@Composable
fun CommitRow(owner: String, name: String, c: Commit) {
    val nav = LocalNav.current
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().clickable { nav.push(Screen.CommitDetail(owner, name, c.sha)) }.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            MdText(c.commit.message.lineSequence().first(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(c.author?.avatarUrl, 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${c.author?.login ?: c.commit.author?.name ?: ""} 提交于 ${relTime(c.commit.author?.date)}",
                    fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            c.sha.take(7),
            Modifier.background(g.neutralMuted, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = g.fg,
        )
    }
}

class ParsedFile(val file: CommitFile, val lines: List<DiffLine>)

@Composable
fun CommitDetailScreen(owner: String, name: String, sha: String) {
    val ctx = rememberCtx()
    val nav = LocalNav.current
    val g = Gh.c
    val data = rememberLoader("commit") {
        val c = GitHub.commit(owner, name, sha)
        c to withContext(Dispatchers.Default) { c.files.orEmpty().map { f -> ParsedFile(f, f.patch?.let(::parsePatch).orEmpty()) } }
    }
    val collapsed = retain("collapsed") { mutableStateMapOf<String, Boolean>() }
    Page(
        "提交 ${sha.take(7)}", subtitle = "$owner/$name",
        actions = {
            MoreMenu(listOf(
                MenuAction("复制 SHA") { ctx.copy(sha) },
                MenuAction("浏览此版本的文件") { nav.push(Screen.Files(owner, name, "", sha)) },
                MenuAction("分享") { ctx.share("https://github.com/$owner/$name/commit/$sha") },
                MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/$owner/$name/commit/$sha") },
            ))
        },
    ) { pad ->
        LoadBox(data, Modifier.padding(pad).fillMaxSize()) { (c, files) ->
            val state = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        val msg = c.commit.message
                        SelectionContainer {
                            Column {
                                MdText(msg.lineSequence().first(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                val rest = msg.substringAfter('\n', "").trim()
                                if (rest.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(rest, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = g.fgMuted)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(c.author?.avatarUrl, 22.dp, Modifier.clickable { c.author?.login?.let { nav.push(Screen.Profile(it)) } })
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${c.author?.login ?: c.commit.author?.name} 于 ${fmtDateTime(c.commit.author?.date)} 提交",
                                fontSize = 13.sp, color = g.fgMuted,
                            )
                        }
                        if (c.committer?.login != null && c.committer.login != c.author?.login) {
                            Text("由 ${c.committer.login} 合入 · ${fmtDateTime(c.commit.committer?.date)}", fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 4.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("SHA  $sha", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.clickable { ctx.copy(sha) })
                        c.parents.forEach { p ->
                            Text(
                                "父提交  ${p.sha.take(7)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = g.accent,
                                modifier = Modifier.padding(top = 2.dp).clickable { nav.push(Screen.CommitDetail(owner, name, p.sha)) },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        c.stats?.let { s ->
                            Row {
                                Text("${files.size} 个文件变更 ", fontSize = 13.sp, color = g.fg)
                                Text("+${s.additions} ", fontSize = 13.sp, color = g.success, fontWeight = FontWeight.SemiBold)
                                Text("−${s.deletions}", fontSize = 13.sp, color = g.danger, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    HDivider()
                }
                fileDiffs(files, collapsed, g) { f -> nav.push(Screen.FileView(owner, name, f.filename, sha)) }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

/** 文件级 diff 列表（可折叠），提交详情和 PR 文件共用 */
fun LazyListScope.fileDiffs(
    files: List<ParsedFile>,
    collapsed: MutableMap<String, Boolean>,
    g: GhColors,
    onOpen: (CommitFile) -> Unit,
) {
    files.forEach { pf ->
        val f = pf.file
        // 手机端默认只展示文件清单，避免打开提交时被大量裸 diff 淹没；点文件标题再展开代码块。
        val isCollapsed = collapsed[f.filename] ?: true
        item(key = "h:${f.filename}") {
            FileDiffHeader(f, isCollapsed, { collapsed[f.filename] = !isCollapsed }, { onOpen(f) })
        }
        if (!isCollapsed) {
            if (pf.lines.isEmpty()) {
                item(key = "e:${f.filename}") {
                    Text(
                        if (f.status == "renamed") "文件已重命名（无内容变化）" else "二进制文件或变更过大，无法显示 diff",
                        Modifier.padding(16.dp), fontSize = 13.sp, color = g.fgMuted,
                    )
                }
            } else {
                diffLines(f.filename, pf.lines, g)
            }
        }
    }
}

/**
 * 文件标题行。PR 文件页额外传入：viewed（「已查看」复选框）、comments（行内评论数）与 menu（文件操作），
 * 此时右侧的「查看文件」按钮并入菜单。
 */
@Composable
fun FileDiffHeader(
    f: CommitFile,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    viewed: Boolean? = null,
    onViewed: (Boolean) -> Unit = {},
    comments: Int = 0,
    menu: List<MenuAction>? = null,
) {
    val g = Gh.c
    Column {
        HDivider()
        Row(
            Modifier.fillMaxWidth().background(g.canvasSubtle).clickable(onClick = onToggle)
                .padding(start = 12.dp, end = if (menu != null) 0.dp else 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oc(if (collapsed) R.drawable.oc_chevron_right else R.drawable.oc_chevron_down, g.fgMuted, 14.dp)
            Spacer(Modifier.width(6.dp))
            val (label, color) = when (f.status) {
                "added" -> "新增" to g.success
                "removed" -> "删除" to g.danger
                "renamed" -> "重命名" to g.attention
                else -> "修改" to g.fgMuted
            }
            Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(f.filename, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = if (viewed == true) g.fgMuted else g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                f.previousFilename?.let { Text("重命名自 $it", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = g.fgMuted, maxLines = 1) }
                if (viewed != null) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("+${f.additions}", fontSize = 12.sp, color = g.success, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(4.dp))
                    Text("−${f.deletions}", fontSize = 12.sp, color = g.danger, fontWeight = FontWeight.SemiBold)
                    if (comments > 0) {
                        Spacer(Modifier.width(10.dp))
                        Oc(R.drawable.oc_comment, g.fgMuted, 12.dp)
                        Spacer(Modifier.width(3.dp))
                        Text("$comments", fontSize = 12.sp, color = g.fgMuted)
                    }
                }
            }
            if (viewed == null) {
                Text("+${f.additions}", fontSize = 12.sp, color = g.success, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Text("−${f.deletions}", fontSize = 12.sp, color = g.danger, fontWeight = FontWeight.SemiBold)
            } else {
                Spacer(Modifier.width(6.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .border(1.dp, if (viewed) g.accent.copy(alpha = 0.6f) else g.border, RoundedCornerShape(6.dp))
                        .background(if (viewed) g.accentSubtle else g.canvas, RoundedCornerShape(6.dp))
                        .clickable { onViewed(!viewed) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(14.dp)
                            .background(if (viewed) g.accent else g.canvas, RoundedCornerShape(3.dp))
                            .border(1.dp, if (viewed) g.accent else g.fgMuted, RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center,
                    ) { if (viewed) Oc(R.drawable.oc_check, g.canvas, 12.dp) }
                    Spacer(Modifier.width(6.dp))
                    Text("已查看", fontSize = 12.sp, color = if (viewed) g.accent else g.fg, fontWeight = FontWeight.Medium)
                }
            }
            if (menu != null) MoreMenu(menu, g.fgMuted)
            else if (f.status != "removed") {
                Spacer(Modifier.width(4.dp))
                com.mobilegh.ui.components.OcButton(R.drawable.oc_file_code, onOpen, g.fgMuted)
            }
        }
        HDivider()
    }
}
