package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
    Page("仓库代码搜索", subtitle = "$owner/$name") { pad ->
        Column(Modifier.padding(pad)) {
            GhField(input, { input = it }, "搜索代码内容", Modifier.padding(12.dp),
                placeholder = "函数名、类名或文字，支持 language: 与 path:",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                trailing = { OcButton(R.drawable.oc_search, { submit() }) })
            if (query.isBlank()) EmptyState("输入关键词搜索该仓库的默认分支")
            else CodeSearchResults(CodeReading.searchQuery(query, owner, name))
        }
    }
}

@Composable
fun CodeSearchResults(query: String) {
    val nav = LocalNav.current
    var total by remember(query) { mutableIntStateOf(0) }
    var incomplete by remember(query) { mutableStateOf(false) }
    val pager = rememberPager("code-search:$query") { page, force ->
        CodeReading.search(query, page, force).also { total = it.totalCount; incomplete = it.incompleteResults }.items
    }
    PagedList(pager, itemKey = { "${it.repository.fullName}:${it.path}" }, empty = "没有找到匹配的代码", header = {
        item {
            Text("搜索默认分支中的代码 · $total 个结果", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
            if (incomplete || total > 1000) Text("GitHub 返回了部分结果，请添加仓库、路径或语言条件缩小范围", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.attention, fontSize = 12.sp)
        }
    }) { hit ->
        Column(Modifier.fillMaxWidth().clickable {
            val target = Links.route(hit.htmlUrl) ?: Screen.FileView(hit.repository.owner.login, hit.repository.name, hit.path, hit.repository.defaultBranch)
            nav.push(target)
        }.padding(16.dp)) {
            Text(hit.repository.fullName.ifBlank { "${hit.repository.owner.login}/${hit.repository.name}" }, color = Gh.c.fgMuted, fontSize = 12.sp)
            Text(hit.path, color = Gh.c.accent, fontSize = 14.sp)
            hit.textMatches.firstOrNull()?.fragment?.let { Text(it.take(1200), Modifier.padding(top = 8.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Gh.c.fg, maxLines = 7, overflow = TextOverflow.Ellipsis) }
        }
        HDivider()
    }
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
    Page("查找文件", subtitle = "$owner/$name · ${ref ?: "默认分支"}") { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            GhField(input, { input = it }, "输入文件名或路径", Modifier.padding(12.dp), placeholder = "如 README 或 src Main")
            LoadBox(index, Modifier.weight(1f)) { data ->
                Column {
                    Text("${matches.count} 个匹配路径 · ${data.commit.take(7)}", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
                    if (data.truncated) Text("仓库很大，GitHub 只返回了部分路径；未找到时可继续按目录浏览", Modifier.padding(horizontal = 16.dp), color = Gh.c.attention, fontSize = 12.sp)
                    if (matches.count > matches.entries.size) Text("显示前 ${matches.entries.size} 项，请继续输入以缩小范围", Modifier.padding(horizontal = 16.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
                    if (matches.entries.isEmpty()) EmptyState("没有匹配的文件或目录")
                    else LazyColumn {
                        items(matches.entries, key = { it.path }) { file ->
                            Row(Modifier.fillMaxWidth().clickable {
                                nav.push(if (file.type == "tree") Screen.Files(owner, name, file.path, data.commit) else Screen.FileView(owner, name, file.path, data.commit))
                            }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Oc(if (file.type == "tree") R.drawable.oc_file_directory else R.drawable.oc_file_code)
                                Text(file.path, Modifier.weight(1f).padding(start = 12.dp), color = Gh.c.fg, fontSize = 14.sp)
                            }
                            HDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BlameScreen(owner: String, name: String, path: String, ref: String?) {
    val nav = LocalNav.current
    val blame = rememberLoader("blame:$ref") { CodeReading.blame(owner, name, path, ref) }
    var lineInput by rememberSaveable { mutableStateOf("") }
    Page("逐行归因", subtitle = path, actions = {
        OcButton(R.drawable.oc_file_code, { nav.push(Screen.FileView(owner, name, path, blame.data?.commit ?: ref)) })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            GhField(lineInput, { lineInput = it.filter(Char::isDigit) }, "定位行号（留空查看全部）", Modifier.padding(12.dp))
            LoadBox(blame, Modifier.weight(1f)) { data ->
                val line = lineInput.toIntOrNull()
                val ranges = if (lineInput.isBlank()) data.ranges else listOfNotNull(line?.let(data::rangeFor))
                Column {
                    Text("$owner/$name · ${data.commit.take(7)}", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Gh.c.fgMuted, fontSize = 12.sp)
                    data.sourceNote?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Gh.c.attention, fontSize = 12.sp) }
                    if (ranges.isEmpty()) EmptyState(if (lineInput.isBlank()) "该文件没有可用的行归因" else "行号不在该文件中")
                    else LazyColumn {
                        ranges.forEach { range ->
                            item(key = "commit:${range.start}") {
                                Row(Modifier.fillMaxWidth().clickable { nav.push(Screen.CommitDetail(owner, name, range.commit)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(range.avatar, 28.dp)
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text("${range.author} · ${range.start}–${range.end} 行", color = Gh.c.fg, fontSize = 13.sp)
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
                                    Text("$number", Modifier.width(48.dp), color = Gh.c.fgMuted, style = CodeStyle)
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
