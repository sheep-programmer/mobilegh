package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Compare
import com.mobilegh.data.GitHub
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.Gh

@Composable
fun CompareScreen(owner: String, name: String, initialBase: String = "HEAD~1", initialHead: String = "HEAD") {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    var base by rememberSaveable { mutableStateOf(initialBase) }
    var head by rememberSaveable { mutableStateOf(initialHead) }
    var appliedBase by rememberSaveable { mutableStateOf(initialBase) }
    var appliedHead by rememberSaveable { mutableStateOf(initialHead) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val branches = rememberLoader("compare-branches") { GitHub.branches(owner, name, 1, it).map { branch -> branch.name } }
    val compare = rememberLoader("compare:$appliedBase:$appliedHead") { force ->
        val c = Compare.load(owner, name, appliedBase, appliedHead, force = force)
        c to c.files.distinctBy { it.filename }.map { f -> ParsedFile(f, f.patch?.let(::parsePatch).orEmpty()) }
    }
    val collapsed = remember(appliedBase, appliedHead) { mutableStateMapOf<String, Boolean>() }
    Page("比较版本", subtitle = "$owner/$name", actions = {
        OcButton(R.drawable.oc_link_external, {
            ctx.openBrowser("https://github.com/$owner/$name/compare/${com.mobilegh.data.Api.q(appliedBase)}...${com.mobilegh.data.Api.q(appliedHead)}")
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    GhField(base, { base = it }, "基准版本", placeholder = "分支、标签或 SHA")
                    if (branches.data.orEmpty().isNotEmpty()) Dropdown("选择分支", branches.data.orEmpty(), { base = branches.data!![it] }, Modifier.padding(top = 6.dp))
                }
                Column(Modifier.weight(1f)) {
                    GhField(head, { head = it }, "目标版本", placeholder = "分支、标签或 SHA")
                    if (branches.data.orEmpty().isNotEmpty()) Dropdown("选择分支", branches.data.orEmpty(), { head = branches.data!![it] }, Modifier.padding(top = 6.dp))
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhButton("交换", icon = R.drawable.oc_git_branch) { val before = base; base = head; head = before }
                GhButton("比较", Modifier.weight(1f), primary = true, enabled = base.isNotBlank() && head.isNotBlank()) {
                    if (appliedBase == base.trim() && appliedHead == head.trim()) compare.refresh()
                    else { appliedBase = base.trim(); appliedHead = head.trim() }
                }
            }
            Spacer(Modifier.height(12.dp))
            LoadBox(compare, Modifier.weight(1f)) { (c, files) ->
                Column {
                    Text("${c.totalCommits} 个提交 · 领先 ${c.aheadBy} · 落后 ${c.behindBy}", Modifier.padding(horizontal = 16.dp), color = g.fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Chips(listOf("文件 ${files.size}", "提交 ${c.totalCommits}"), tab) { tab = it }
                    if (tab == 0) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (files.size >= 300) item { Text("GitHub 比较接口最多返回 300 个文件，此列表可能不完整。", Modifier.padding(16.dp), color = g.attention, fontSize = 12.sp) }
                            if (files.isEmpty()) item { EmptyState("两个版本没有可显示的文件差异") }
                            fileDiffs(files, collapsed, g) { f -> nav.push(Screen.FileView(owner, name, f.filename, appliedHead)) }
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                    } else {
                        val commits = rememberPager("compare-commits:$appliedBase:$appliedHead", pageSize = 100) { page, force ->
                            if (page == 1 && !force) c.commits else Compare.load(owner, name, appliedBase, appliedHead, page, force).commits
                        }
                        PagedList(commits, itemKey = { it.sha }) { CommitRow(owner, name, it) }
                    }
                }
            }
        }
    }
}
