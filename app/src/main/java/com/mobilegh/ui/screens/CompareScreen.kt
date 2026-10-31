package com.mobilegh.ui.screens

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.mobilegh.data.Branch
import com.mobilegh.data.Compare
import com.mobilegh.data.Comparison
import com.mobilegh.data.GitHub
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.DiffModeToggle
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.StatCell
import com.mobilegh.ui.components.emptyItem
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.parsePatch
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trc
import com.mobilegh.i18n.trf

/**
 * 比较两个版本（分支、标签或提交）。
 *
 * 顶部是一张「基准 / 目标」卡片，点一行直接在分支 / 标签里选，选完立即比较；右侧按钮可交换方向。
 * 结果是一张状态卡（领先 / 落后、提交数、文件数、增删行），下面分「文件」「提交」两页，
 * 文件页的代码对比支持左旧右新的分栏视图。输入 SHA、删除分支放在右上角菜单里。
 */
@Composable
fun CompareScreen(owner: String, name: String, initialBase: String = "HEAD~1", initialHead: String = "HEAD") {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    var base by rememberSaveable { mutableStateOf(initialBase) }
    var head by rememberSaveable { mutableStateOf(initialHead) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 0 不弹出；1 选基准；2 选目标；3 手动输入
    var dialog by rememberSaveable { mutableIntStateOf(0) }
    // 非 null 时弹出删除确认；只允许删除目标分支，且不能是默认分支
    var deleting by remember { mutableStateOf<Branch?>(null) }
    val compare = rememberLoader("compare:$base:$head") { force ->
        val c = Compare.load(owner, name, base, head, force = force)
        c to c.files.distinctBy { it.filename }.map { f -> ParsedFile(f, f.patch?.let(::parsePatch).orEmpty()) }
    }
    val collapsed = remember(base, head) { mutableStateMapOf<String, Boolean>() }
    val r = repo.data
    val canDeleteHead = r != null && r.permissions?.push == true && !r.archived &&
        head != r.defaultBranch && head != base && !isShaRef(head)

    Page(tr("比较版本"), subtitle = "$owner/$name", actions = {
        MoreMenu(buildList {
            add(MenuAction(tr("输入提交 SHA")) { dialog = 3 })
            add(MenuAction(tr("在浏览器打开")) { ctx.openBrowser("https://github.com/$owner/$name/compare/${com.mobilegh.data.Api.q(base)}...${com.mobilegh.data.Api.q(head)}") })
            if (canDeleteHead) add(MenuAction(tr("删除分支"), danger = true) { deleting = Branch(name = head) })
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            RefPickerCard(
                base = base, head = head,
                onBase = { dialog = 1 }, onHead = { dialog = 2 },
                onSwap = { val before = base; base = head; head = before },
            )
            LoadBox(compare, Modifier.weight(1f)) { (c, files) ->
                if (tab == 0) {
                    val listState = rememberLazyListState()
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        item { CompareHeader(c, files.size, tab) { tab = it } }
                        if (files.size >= 300) item { Text(tr("GitHub 比较接口最多返回 300 个文件，此列表可能不完整。"), Modifier.padding(16.dp), color = g.attention, fontSize = 12.sp) }
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
                        header = { item { CompareHeader(c, files.size, tab) { tab = it } } },
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
    deleting?.let { b ->
        DeleteBranchDialog(owner, name, b, onDismiss = { deleting = null }) {
            deleting = null
            // 比较的目标分支已经不存在，回到上一页（分支列表）
            nav.pop()
            ctx.toast(trf("已删除分支 {0}", b.name))
        }
    }
}

private fun isShaRef(ref: String) = ref.length >= 7 && ref.all { it in "0123456789abcdefABCDEF" }

/** 顶部：基准 / 目标 两行合在一张卡片里，右侧一个交换按钮 */
@Composable
private fun RefPickerCard(base: String, head: String, onBase: () -> Unit, onHead: () -> Unit, onSwap: () -> Unit) {
    val g = Gh.c
    Card(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RefLine(tr("基准（旧）"), base, onBase)
                HDivider()
                RefLine(tr("目标（新）"), head, onHead)
            }
            Box(
                Modifier.padding(end = 10.dp).size(36.dp).clip(CircleShape).clickable(onClickLabel = tr("交换基准与目标"), onClick = onSwap),
                contentAlignment = Alignment.Center,
            ) { Oc(R.drawable.oc_arrow_switch, g.fgMuted, 18.dp) }
        }
    }
}

@Composable
private fun RefLine(label: String, ref: String, onClick: () -> Unit) {
    val g = Gh.c
    val isSha = isShaRef(ref)
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = trf("选择{0}", label), onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = g.fgMuted)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Oc(if (isSha) R.drawable.oc_git_commit else R.drawable.oc_git_branch, g.fgMuted, 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isSha && ref.length > 12) ref.take(7) else ref,
                    Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.fg,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Oc(R.drawable.oc_chevron_down, g.fgMuted, 12.dp)
    }
}

/** 列表头部：结果摘要 + 文件 / 提交 切换，文件页和提交页共用 */
@Composable
private fun CompareHeader(c: Comparison, fileCount: Int, tab: Int, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        CompareSummary(c, fileCount)
        // 数量已在摘要卡里，标签只写名称；文件页的统一 / 分栏切换和标签同一行，省一行高度
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Chips(listOf(tr("文件"), tr("提交")), tab, Modifier.weight(1f)) { onTab(it) }
            if (tab == 0 && fileCount > 0) DiffModeToggle(Modifier.padding(start = 8.dp))
        }
        HDivider()
    }
}

/** 结果摘要：一张卡片，上半是领先 / 落后状态，下半是提交数、文件数、增删行 */
@Composable
private fun CompareSummary(c: Comparison, fileCount: Int) {
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
    Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Oc(if (c.status == "identical") R.drawable.oc_check_circle else R.drawable.oc_git_compare, statusColor, 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(statusText, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = statusColor)
            }
            HDivider()
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
