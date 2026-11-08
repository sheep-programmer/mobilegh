package com.mobilegh.ui.screens

import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.ScrollbarLazyColumn
import com.mobilegh.ui.components.PagedList
import com.mobilegh.nav.rememberPager
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.*
import com.mobilegh.nav.*
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.CodeStyle
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

@Composable
fun CodeSearchScreen(owner: String, name: String) {
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun submit() {
        query = input.trim()
        if (query.isNotBlank()) History.search(CodeReading.searchQuery(query, owner, name), 4)
        focus.clearFocus()
    }
    Page(tr("仓库代码搜索"), subtitle = "$owner/$name") { pad ->
        Column(Modifier.padding(pad)) {
            CompactSearchField(input, { input = it }, tr("搜索代码内容"), Modifier.padding(12.dp),
                hint = tr("函数名、类名或文字，支持 language: 与 path:"),
                onSearch = { submit() },
                trailing = { FieldAction(R.drawable.oc_search, tr("搜索代码内容")) { submit() } })
            if (query.isBlank()) EmptyState(tr("输入关键词搜索该仓库的默认分支"), R.drawable.oc_search)
            else CodeSearchResults(CodeReading.searchQuery(query, owner, name), highlight = query)
        }
    }
}

@Composable
fun CodeSearchResults(query: String, highlight: String = query) {
    val nav = LocalNav.current
    // 用户输入里有 language:/path: 等限定符，高亮只用自由文本部分
    val mark = Gh.c.attention.copy(alpha = 0.32f)
    var total by remember(query) { mutableIntStateOf(0) }
    var incomplete by remember(query) { mutableStateOf(false) }
    val pager = rememberPager("code-search:$query") { page, force ->
        CodeReading.search(query, page, force).also { total = it.totalCount; incomplete = it.incompleteResults }.items
    }
    PagedList(pager, itemKey = { "${it.repository.fullName}:${it.path}" }, empty = tr("没有找到匹配的代码"), header = {
        item {
            Text(trf("搜索默认分支中的代码 · {0} 个结果", total), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
            if (incomplete || total > 1000) Text(tr("GitHub 返回了部分结果，请添加仓库、路径或语言条件缩小范围"), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.attention, fontSize = 12.sp)
        }
    }) { hit ->
        Column(Modifier.fillMaxWidth().clickable {
            val target = Links.route(hit.htmlUrl) ?: Screen.FileView(hit.repository.owner.login, hit.repository.name, hit.path, hit.repository.defaultBranch)
            nav.pushLink(target)
        }.padding(16.dp)) {
            Text(hit.repository.fullName.ifBlank { "${hit.repository.owner.login}/${hit.repository.name}" }, color = Gh.c.fgMuted, fontSize = 12.sp)
            Text(SearchText.annotated(hit.path, highlight, mark), color = Gh.c.accent, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            hit.textMatches.firstOrNull()?.fragment?.let { f ->
                Text(
                    SearchText.annotated(f.take(1200), highlight, mark),
                    Modifier.padding(top = 8.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Gh.c.fg,
                    maxLines = 7, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * GitHub 官方搜索的文件查询：含 / 的词按路径（path:），以 . 开头的按扩展名（extension:），其余按文件名（filename:）。
 * 只搜默认分支，不需要先拉取整棵文件树，大仓库也能马上出结果。
 */
internal fun fileSearchQuery(owner: String, name: String, input: String): String? {
    val tokens = input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return null
    val parts = tokens.map { t ->
        when {
            t.contains('/') -> "path:${t.trim('/')}"
            t.startsWith('.') && t.length > 1 -> "extension:${t.drop(1)}"
            else -> "filename:$t"
        }
    }
    return (parts + "repo:$owner/$name").joinToString(" ")
}

@Composable
fun FileFinderScreen(owner: String, name: String, ref: String?) {
    val nav = LocalNav.current
    var input by rememberSaveable { mutableStateOf("") }
    val index = rememberLoader("file-index:$ref") { force -> CodeReading.files(owner, name, ref, force) }
    var matches by remember { mutableStateOf(FileMatches(emptyList(), 0)) }
    LaunchedEffect(index.data, input) {
        val data = index.data ?: return@LaunchedEffect
        delay(120)
        matches = withContext(Dispatchers.Default) { CodeReading.filterFiles(data.entries, input) }
    }
    // 文件树还没读完、读取失败或被截断（大仓库）时，改用 GitHub 官方搜索，不必等全量列表
    val data = index.data
    val useSearch = input.isNotBlank() && (data == null || data.truncated)
    var debounced by remember { mutableStateOf("") }
    LaunchedEffect(input) {
        delay(400)
        debounced = input
    }
    // 常用查找预设：点一下直接搜
    val presets = listOf(
        "README" to "README", "Markdown" to ".md", tr("工作流") to ".github/workflows", "LICENSE" to "LICENSE",
        "Gradle" to ".gradle", "package.json" to "package.json", "Dockerfile" to "Dockerfile", "YAML" to ".yml",
    )
    Page(
        tr("查找文件"), subtitle = "$owner/$name · ${ref ?: tr("默认分支")}",
        actions = {
            // 文件名找不到时，可以直接按代码内容搜
            OcButton(R.drawable.oc_code, { nav.push(Screen.CodeSearch(owner, name)) }, description = tr("搜索代码内容"))
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            GhField(input, { input = it }, tr("输入文件名或路径"), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), placeholder = tr("如 README、.md、src/main"))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (label, value) ->
                    val on = input.trim() == value
                    Text(
                        label,
                        Modifier.clip(RoundedCornerShape(50)).background(if (on) Gh.c.accentSubtle else Gh.c.canvasSubtle)
                            .border(1.dp, if (on) Gh.c.accent else Gh.c.border, RoundedCornerShape(50))
                            .clickable { input = if (on) "" else value }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        color = if (on) Gh.c.accent else Gh.c.fg, fontSize = 13.sp,
                    )
                }
            }
            when {
                useSearch -> {
                    val query = fileSearchQuery(owner, name, debounced)
                    Text(
                        if (data == null) tr("文件树还在读取，先用 GitHub 搜索（仅默认分支）")
                        else tr("仓库很大，文件树不完整，改用 GitHub 搜索（仅默认分支）"),
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.fgMuted, fontSize = 12.sp,
                    )
                    if (query != null) FileSearchResults(owner, name, query)
                }
                data == null -> Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    // 整棵文件树是一次递归请求，大仓库会慢：先给进度，也可以直接输入用 GitHub 搜索，或按目录浏览
                    if (index.error != null) InlineError(index.error!!) { index.refresh() }
                    else {
                        Text(tr("正在读取仓库文件树…输入关键词或点上面的预设，可以直接用 GitHub 搜索"), fontSize = 12.sp, color = Gh.c.fgMuted)
                        Loading(Modifier.fillMaxWidth().height(40.dp))
                    }
                    GhButton(tr("先浏览目录"), Modifier.fillMaxWidth().padding(top = 4.dp), icon = R.drawable.oc_file_directory) {
                        nav.push(Screen.Files(owner, name, "", ref))
                    }
                }
                else -> Column(Modifier.weight(1f)) {
                    Text(trf("{0} 个匹配路径 · {1}", matches.count, data.commit.take(7)), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
                    if (matches.count > matches.entries.size) Text(trf("显示前 {0} 项，请继续输入以缩小范围", matches.entries.size), Modifier.padding(horizontal = 16.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
                    if (matches.entries.isEmpty()) EmptyState(tr("没有匹配的文件或目录"), R.drawable.oc_search, action = if (input.isNotBlank()) tr("清除输入") else null) { input = "" }
                    else ScrollbarLazyColumn {
                        items(matches.entries, key = { it.path }) { file ->
                            FileRow(file.path, file.type == "tree") {
                                nav.push(if (file.type == "tree") Screen.Files(owner, name, file.path, data.commit) else Screen.FileView(owner, name, file.path, data.commit))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(path: String, directory: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (directory) Oc(R.drawable.oc_file_directory_fill, Gh.c.folder)
        else Oc(if (ext(path) in IMAGE_EXT) R.drawable.oc_file_media else R.drawable.oc_file, Gh.c.fgMuted)
        Text(path, Modifier.weight(1f).padding(start = 12.dp), color = Gh.c.fg, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HDivider()
}

/** GitHub 搜索的文件结果：同一路径只显示一次，点开查看文件 */
@Composable
private fun ColumnScope.FileSearchResults(owner: String, name: String, query: String) {
    val nav = LocalNav.current
    var total by remember(query) { mutableIntStateOf(0) }
    val pager = rememberPager("file-search:$query") { page, force ->
        CodeReading.search(query, page, force).also { total = it.totalCount }.items.distinctBy { it.path }
    }
    Text(trf("{0} 个结果", total), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
    PagedList(
        pager, Modifier.weight(1f),
        itemKey = { it.path },
        empty = tr("GitHub 搜索没有找到匹配的文件。新提交的文件需要等 GitHub 建好索引；也可以按目录浏览"),
        emptyIcon = R.drawable.oc_search,
    ) { hit ->
        FileRow(hit.path, false) { nav.push(Screen.FileView(owner, name, hit.path, null)) }
    }
}

@Composable
fun BlameScreen(owner: String, name: String, path: String, ref: String?) {
    val nav = LocalNav.current
    val blame = rememberLoader("blame:$ref") { CodeReading.blame(owner, name, path, ref) }
    var lineInput by rememberSaveable { mutableStateOf("") }
    Page(tr("逐行归因"), subtitle = path, actions = {
        OcButton(R.drawable.oc_file_code, { nav.push(Screen.FileView(owner, name, path, blame.data?.commit ?: ref)) })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            GhField(lineInput, { lineInput = it.filter(Char::isDigit) }, tr("定位行号（留空查看全部）"), Modifier.fillMaxWidth().padding(12.dp))
            LoadBox(blame, Modifier.fillMaxWidth().weight(1f)) { data ->
                val line = lineInput.toIntOrNull()
                val ranges = if (lineInput.isBlank()) data.ranges else listOfNotNull(line?.let(data::rangeFor))
                Column(Modifier.fillMaxSize()) {
                    Text("$owner/$name · ${data.commit.take(7)}", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
                    data.sourceNote?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.attention, fontSize = 12.sp) }
                    if (ranges.isEmpty()) {
                        EmptyState(
                            if (lineInput.isBlank()) tr("该文件没有可用的行归因") else tr("行号不在该文件中"),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    } else SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
                        ScrollbarLazyColumn(Modifier.fillMaxSize()) {
                            ranges.forEach { range ->
                                item(key = "commit:${range.start}") {
                                    Row(Modifier.fillMaxWidth().clickable { nav.push(Screen.CommitDetail(owner, name, range.commit)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Avatar(range.avatar, 28.dp)
                                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                            Text(trf("{0} · {1}–{2} 行", range.author, range.start, range.end), color = Gh.c.fg, fontSize = 13.sp)
                                            Text(range.message, color = Gh.c.accent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text("${range.commit.take(7)} · ${relTime(range.date)}", color = Gh.c.fgMuted, fontSize = 11.sp)
                                        }
                                    }
                                    HDivider()
                                }
                                val count = (minOf(range.end, data.lines.size) - range.start + 1).coerceAtLeast(0)
                                items(count, key = { "line:${range.start + it}" }) { offset ->
                                    val number = range.start + offset
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                                        DisableSelection {
                                            Text("$number", Modifier.width(48.dp), color = Gh.c.fgMuted, style = CodeStyle)
                                        }
                                        Text(data.lines[number - 1], Modifier.weight(1f), color = Gh.c.fg, style = CodeStyle)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
