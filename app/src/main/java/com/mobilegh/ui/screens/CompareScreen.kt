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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Compare
import com.mobilegh.data.Comparison
import com.mobilegh.data.GitHub
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.DiffModeToggle
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.Segmented
import com.mobilegh.ui.components.StatCell
import com.mobilegh.ui.components.emptyItem
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.parsePatch
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trc
import com.mobilegh.i18n.trf

/**
 * 比较两个版本（分支、标签或提交）。
 *
 * 顶部是两个可点按的版本卡片，点开直接在分支 / 标签里选，选完立即比较；中间的按钮可交换方向。
 * 结果先给一张摘要卡（领先 / 落后、提交数、文件数、增删行），再分「文件」「提交」两页，
 * 文件页的代码对比支持左旧右新的分栏视图。
 */
@Composable
fun CompareScreen(owner: String, name: String, initialBase: String = "HEAD~1", initialHead: String = "HEAD") {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    var base by rememberSaveable { mutableStateOf(initialBase) }
    var head by rememberSaveable { mutableStateOf(initialHead) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 0 不弹出；1 选基准；2 选目标；3 手动输入
    var dialog by rememberSaveable { mutableIntStateOf(0) }
    val compare = rememberLoader("compare:$base:$head") { force ->
        val c = Compare.load(owner, name, base, head, force = force)
        c to c.files.distinctBy { it.filename }.map { f -> ParsedFile(f, f.patch?.let(::parsePatch).orEmpty()) }
    }
    val collapsed = remember(base, head) { mutableStateMapOf<String, Boolean>() }

    Page(tr("比较版本"), subtitle = "$owner/$name", actions = {
        OcButton(R.drawable.oc_link_external, {
            ctx.openBrowser("https://github.com/$owner/$name/compare/${com.mobilegh.data.Api.q(base)}...${com.mobilegh.data.Api.q(head)}")
        }, description = tr("在浏览器打开"))
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            RefPickerCard(
                base = base, head = head,
                onBase = { dialog = 1 }, onHead = { dialog = 2 },
                onSwap = { val before = base; base = head; head = before },
                onManual = { dialog = 3 },
            )
            LoadBox(compare, Modifier.weight(1f)) { (c, files) ->
                if (tab == 0) {
                    val listState = rememberLazyListState()
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        item { CompareSummary(c, files.size, tab) { tab = it } }
                        if (files.size >= 300) item { Text(tr("GitHub 比较接口最多返回 300 个文件，此列表可能不完整。"), Modifier.padding(16.dp), color = g.attention, fontSize = 12.sp) }
                        if (files.isNotEmpty()) item { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) { DiffModeToggle() } }
                        if (files.isEmpty()) emptyItem(listState, tr("两个版本没有可显示的文件差异"), R.drawable.oc_diff)
                        fileDiffs(files, collapsed, g) { f -> nav.push(Screen.FileView(owner, name, f.filename, head)) }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                } else {
                    val commits = rememberPager("compare-commits:$base:$head", pageSize = 100) { page, force ->
                        if (page == 1 && !force) c.commits else Compare.load(owner, name, base, head, page, force).commits
                    }
                    PagedList(
                        commits, itemKey = { it.sha }, empty = tr("两个版本之间没有提交"),
                        header = { item { CompareSummary(c, files.size, tab) { tab = it } } },
                    ) { CommitRow(owner, name, it) }
                }
            }
        }
    }

    when (dialog) {
        1 -> BranchPicker(owner, name, base, { dialog = 0 }) { base = it; dialog = 0 }
        2 -> BranchPicker(owner, name, head, { dialog = 0 }) { head = it; dialog = 0 }
        3 -> ManualRefDialog(base, head, { dialog = 0 }) { b, h -> base = b; head = h; dialog = 0 }
    }
}

/** 顶部：基准 ⇄ 目标 两个版本卡片 + 手动输入入口 */
@Composable
private fun RefPickerCard(base: String, head: String, onBase: () -> Unit, onHead: () -> Unit, onSwap: () -> Unit, onManual: () -> Unit) {
    val g = Gh.c
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RefChip(tr("基准（旧）"), base, Modifier.weight(1f), onBase)
            Box(
                Modifier.padding(horizontal = 8.dp).size(36.dp).clip(CircleShape)
                    .border(1.dp, g.border, CircleShape).background(g.btnBg)
                    .clickable(onClickLabel = tr("交换基准与目标"), onClick = onSwap),
                contentAlignment = Alignment.Center,
            ) { Oc(R.drawable.oc_arrow_switch, g.fgMuted, 16.dp) }
            RefChip(tr("目标（新）"), head, Modifier.weight(1f), onHead)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("左边是旧版本，右边是新版本"), Modifier.weight(1f), fontSize = 12.sp, color = g.fgMuted)
            Text(
                tr("输入提交 SHA"), Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onManual).padding(horizontal = 6.dp, vertical = 8.dp),
                fontSize = 13.sp, color = g.accent,
            )
        }
    }
}

@Composable
private fun RefChip(label: String, ref: String, modifier: Modifier, onClick: () -> Unit) {
    val g = Gh.c
    val shape = RoundedCornerShape(8.dp)
    val isSha = ref.length >= 7 && ref.all { it in "0123456789abcdefABCDEF" }
    Column(
        modifier.clip(shape).background(g.canvasSubtle, shape).border(1.dp, g.border, shape)
            .clickable(onClickLabel = trf("选择{0}", label), onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 11.sp, color = g.fgMuted)
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Oc(if (isSha) R.drawable.oc_git_commit else R.drawable.oc_git_branch, g.fgMuted, 14.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                if (isSha && ref.length > 12) ref.take(7) else ref,
                Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.fg,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Oc(R.drawable.oc_chevron_down, g.fgMuted, 12.dp)
        }
    }
}

/** 结果摘要：领先/落后状态 + 三项统计 + 文件/提交页签 */
@Composable
private fun CompareSummary(c: Comparison, fileCount: Int, tab: Int, onTab: (Int) -> Unit) {
    val g = Gh.c
    val additions = c.files.sumOf { it.additions }
    val deletions = c.files.sumOf { it.deletions }
    val (statusText, statusColor) = when (c.status) {
        "identical" -> tr("两个版本内容相同") to g.fgMuted
        "ahead" -> trf("目标领先 {0} 个提交，可以直接合入基准", c.aheadBy) to g.success
        "behind" -> trf("目标落后基准 {0} 个提交", c.behindBy) to g.attention
        "diverged" -> trf("已分叉：目标领先 {0} 个、落后 {1} 个提交", c.aheadBy, c.behindBy) to g.attention
        else -> trf("领先 {0} · 落后 {1}", c.aheadBy, c.behindBy) to g.fgMuted
    }
    Column(Modifier.fillMaxWidth()) {
        Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column {
                Row(
                    Modifier.fillMaxWidth().background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Oc(if (c.status == "identical") R.drawable.oc_check_circle else R.drawable.oc_git_compare, statusColor, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(statusText, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = statusColor)
                }
                Row(Modifier.fillMaxWidth()) {
                    StatCell("${c.totalCommits}", trc("提交", "count"), Modifier.weight(1f))
                    StatCell("$fileCount", tr("文件"), Modifier.weight(1f))
                    Column(Modifier.weight(1f).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("+$additions", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = g.success, maxLines = 1)
                            Spacer(Modifier.width(6.dp))
                            Text("−$deletions", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = g.danger, maxLines = 1)
                        }
                        Text(tr("行变化"), fontSize = 11.sp, color = g.fgMuted, maxLines = 1)
                    }
                }
            }
        }
        Segmented(
            listOf(trf("文件 {0}", fileCount), trf("提交 {0}", c.totalCommits)), tab,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { onTab(it) }
    }
}

@Composable
private fun ManualRefDialog(base: String, head: String, onDismiss: () -> Unit, onApply: (String, String) -> Unit) {
    var b by remember { mutableStateOf(base) }
    var h by remember { mutableStateOf(head) }
    GhDialog(
        tr("输入版本"), onDismiss, confirm = tr("比较"),
        onConfirm = { if (b.isNotBlank() && h.isNotBlank()) onApply(b.trim(), h.trim()) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GhField(b, { b = it }, tr("基准版本（旧）"), placeholder = tr("分支、标签或提交 SHA"))
            GhField(h, { h = it }, tr("目标版本（新）"), placeholder = tr("分支、标签或提交 SHA"))
        }
    }
}
