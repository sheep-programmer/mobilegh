package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.mobilegh.ui.components.lazyScrollbar
import com.mobilegh.ui.components.ScrollbarLazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.AppLog
import com.mobilegh.data.Job
import com.mobilegh.data.Step
import com.mobilegh.data.Run
import com.mobilegh.data.Session
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.act
import com.mobilegh.nav.OnRepoChanged
import com.mobilegh.data.RepoWrite
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.addLinkActions
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.ButtonRowAdaptive
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.RegisterScrollTop
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.downloadFile
import com.mobilegh.ui.components.fmtDuration
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.CodeStyle
import com.mobilegh.ui.components.MdText
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle

@Composable
fun runVisual(status: String?, conclusion: String?): Pair<Int, Color> {
    val g = Gh.c
    return when {
        status == "in_progress" -> R.drawable.oc_dot_fill to g.attention
        status in setOf("queued", "waiting", "pending", "requested") -> R.drawable.oc_clock to g.attention
        conclusion == "success" -> R.drawable.oc_check_circle_fill to g.success
        conclusion in setOf("failure", "timed_out", "startup_failure") -> R.drawable.oc_x_circle_fill to g.danger
        conclusion == "cancelled" -> R.drawable.oc_stop to g.fgMuted
        conclusion == "skipped" -> R.drawable.oc_skip to g.fgMuted
        else -> R.drawable.oc_alert to g.attention
    }
}

internal fun eventZh(e: String) = when (e) {
    "push" -> tr("推送")
    "pull_request" -> "PR"
    "workflow_dispatch" -> tr("手动")
    "schedule" -> tr("定时")
    "release" -> tr("发布")
    else -> e
}

@Composable
fun ActionsScreen(owner: String, name: String) {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val g = Gh.c
    val wfs = rememberLoader("wfs") { GitHub.workflows(owner, name, it) }
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    // 手动触发、重跑、取消都需要写权限；没有时不给入口，免得点了才报 403
    val canRun = repo.data?.let { it.permissions?.push == true && !it.archived } == true
    var sel by rememberSaveable { mutableIntStateOf(0) }
    val list = wfs.data.orEmpty()
    val wfId = if (sel == 0) null else list.getOrNull(sel - 1)?.id
    val runs = rememberPager("runs:$wfId") { p, f -> GitHub.runs(owner, name, wfId, p, f) }
    var choose by remember { mutableStateOf(false) }
    val selected = if (sel == 0) null else list.getOrNull(sel - 1)
    fun run(w: com.mobilegh.data.Workflow) = nav.push(Screen.RunWorkflow(owner, name, w.id, w.path, w.name))
    // 触发工作流后，GitHub 需要一点时间才会生成新的运行记录
    OnRepoChanged(owner, name, RepoWrite.ACTIONS) {
        entry.scope.launch { delay(2500); runs.refresh() }
    }

    Page(
        "Actions", subtitle = "$owner/$name",
        actions = {
            if (canRun) OcButton(R.drawable.oc_play, { if (selected != null) run(selected) else choose = true }, description = tr("运行工作流"))
            MoreMenu(buildList {
                selected?.let { w ->
                    if (canRun) add(MenuAction(tr("运行此工作流")) { run(w) })
                    if (w.path.startsWith(".github/workflows/")) add(MenuAction(tr("查看工作流文件")) { nav.push(Screen.FileView(owner, name, w.path, null)) })
                }
                addLinkActions(ctx, "https://github.com/$owner/$name/actions")
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (list.isNotEmpty()) {
                val options = listOf(tr("全部工作流")) + list.map { it.name + if (it.state != "active") tr("（已停用）") else "" }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("工作流"), color = g.fgMuted, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Dropdown(
                        label = options.getOrElse(sel) { tr("全部工作流") },
                        options = options,
                        onSelect = { sel = it },
                        active = sel != 0,
                    )
                }
                HDivider()
            }
            PagedList(runs, empty = tr("没有运行记录")) { RunRow(owner, name, it) }
        }
    }

    if (choose) {
        // 动态工作流（如 Dependabot、Pages）不在 .github/workflows 中，不能手动触发
        val runnable = list.filter { it.path.startsWith(".github/workflows/") && it.state == "active" }
        GhDialog(tr("选择要运行的工作流"), onDismiss = { choose = false }, confirm = tr("取消"), onConfirm = { choose = false }) {
            Column {
                when {
                    wfs.data == null && wfs.error == null -> Text(tr("加载中…"), color = g.fgMuted)
                    wfs.error != null -> Text(wfs.error!!, color = g.fgMuted, fontSize = 13.sp)
                    runnable.isEmpty() -> Text(tr("该仓库没有可手动运行的工作流"), color = g.fgMuted)
                    else -> runnable.forEach { w ->
                        Row(
                            Modifier.fillMaxWidth().clickable { choose = false; run(w) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Oc(R.drawable.oc_workflow, g.fgMuted)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(w.name, fontSize = 15.sp, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(w.path.removePrefix(".github/workflows/"), fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(tr("只有声明了 workflow_dispatch 触发器的工作流可以手动运行，下一步会读取工作流文件中的输入参数。"), fontSize = 12.sp, color = g.fgMuted)
            }
        }
    }
}

@Composable
private fun RunRow(owner: String, name: String, r: Run) {
    val nav = LocalNav.current
    val g = Gh.c
    val (icon, color) = runVisual(r.status, r.conclusion)
    Row(Modifier.fillMaxWidth().clickable { nav.push(Screen.RunDetail(owner, name, r.id)) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Oc(icon, color, 16.dp, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            MdText(r.displayTitle ?: r.name ?: "", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2)
            Spacer(Modifier.height(3.dp))
            Text("${r.name ?: ""} #${r.runNumber} · ${eventZh(r.event)} · ${r.headBranch ?: ""}", fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(r.actor?.avatarUrl, 14.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    "${r.actor?.login ?: ""} · ${relTime(r.createdAt)} · ${fmtDuration(r.runStartedAt ?: r.createdAt, if (r.status == "completed") r.updatedAt else null)}",
                    fontSize = 12.sp, color = g.fgMuted,
                )
            }
        }
    }
}

@Composable
fun RunDetailScreen(owner: String, name: String, runId: Long) {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val g = Gh.c
    val run = rememberLoader("run") { GitHub.run(owner, name, runId, it) }
    val jobs = rememberLoader("jobs") { GitHub.jobs(owner, name, runId, it) }
    val arts = rememberLoader("arts") { runCatching { GitHub.artifacts(owner, name, runId, it) }.getOrDefault(emptyList()) }
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    val canRun = repo.data?.let { it.permissions?.push == true && !it.archived } == true
    val r = run.data
    val running = r != null && r.status != "completed"
    LaunchedEffect(running) {
        while (running) {
            delay(6000)
            run.load(true)
            jobs.load(true)
        }
    }
    fun reload() { run.load(true); jobs.load(true) }
    var askCancel by remember { mutableStateOf(false) }
    if (askCancel) GhDialog(tr("取消运行？"), { askCancel = false }, confirm = tr("取消运行"), danger = true, onConfirm = {
        askCancel = false
        entry.act({ ctx.toast(it) }) { AppLog.info("actions", tr("取消 Run #") + runId); GitHub.cancelRun(owner, name, runId); ctx.toast(tr("已请求取消")); reload() }
    }) { Text(tr("取消后本次运行会停止，已完成的步骤不会回滚，之后只能重新运行。"), color = g.fg) }
    Page(
        r?.let { "${it.name} #${it.runNumber}" } ?: tr("运行详情"), subtitle = "$owner/$name",
        actions = {
            MoreMenu(buildList {
                r?.let { addLinkActions(ctx, it.htmlUrl) }
                r?.let { add(MenuAction(tr("复制提交 SHA")) { ctx.copy(it.headSha) }) }
            })
        },
    ) { pad ->
        LoadBox(run, Modifier.padding(pad).fillMaxSize()) { rr ->
            val expandedJobs = remember { mutableStateMapOf<Long, Boolean>() }
            ScrollbarLazyColumn(Modifier.fillMaxSize()) {
                item {
                    val (icon, color) = runVisual(rr.status, rr.conclusion)
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Oc(icon, color, 20.dp)
                            Spacer(Modifier.width(8.dp))
                            MdText(rr.displayTitle ?: rr.name ?: "", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            trf("{0} · {1} 触发 · {2} · 耗时 {3}", statusZh(rr.status, rr.conclusion), eventZh(rr.event), relTime(rr.createdAt), fmtDuration(rr.runStartedAt, if (rr.status == "completed") rr.updatedAt else null)),
                            fontSize = 13.sp, color = g.fgMuted,
                        )
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Oc(R.drawable.oc_git_branch, g.fgMuted, 14.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(rr.headBranch ?: "", fontSize = 13.sp, color = g.accent)
                            Spacer(Modifier.width(10.dp))
                            Oc(R.drawable.oc_git_commit, g.fgMuted, 14.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(rr.headSha.take(7), fontSize = 13.sp, color = g.accent, modifier = Modifier.clickable { nav.push(Screen.CommitDetail(owner, name, rr.headSha)) })
                            if (rr.runAttempt > 1) { Spacer(Modifier.width(10.dp)); Pill(trf("第 {0} 次尝试", rr.runAttempt)) }
                        }
                        if (canRun) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (rr.status == "completed") {
                                GhButton(tr("重新运行"), icon = R.drawable.oc_sync) {
                                    entry.act({ ctx.toast(it) }) { AppLog.info("actions", tr("重新运行 Run #") + runId); GitHub.rerun(owner, name, runId, false); ctx.toast(tr("已重新运行")); delay(2000); reload() }
                                }
                                if (rr.conclusion == "failure") GhButton(tr("重跑失败任务")) {
                                    entry.act({ ctx.toast(it) }) { AppLog.info("actions", tr("重跑失败任务 Run #") + runId); GitHub.rerun(owner, name, runId, true); ctx.toast(tr("已重跑失败任务")); delay(2000); reload() }
                                }
                            } else {
                                GhButton(tr("取消运行"), danger = true, icon = R.drawable.oc_stop) { askCancel = true }
                            }
                        }
                    }
                    HDivider()
                }
                val js = jobs.data
                if (js == null) item { com.mobilegh.ui.components.Loading(Modifier.fillMaxWidth().height(120.dp)) }
                else itemsIndexed(js, key = { _, j -> j.id }) { _, j ->
                    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column {
                            val (ji, jc) = runVisual(j.status, j.conclusion)
                            val expanded = expandedJobs[j.id] == true
                            val isJobRunning = j.status == "in_progress" || j.status == "queued"
                            Row(
                                Modifier.fillMaxWidth().clickable { expandedJobs[j.id] = !expanded }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Oc(if (expanded) R.drawable.oc_chevron_down else R.drawable.oc_chevron_right, g.fgMuted, 14.dp)
                                Spacer(Modifier.width(4.dp))
                                Oc(ji, jc, 16.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(j.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg, modifier = Modifier.weight(1f))
                                Text(fmtDuration(j.startedAt, j.completedAt), fontSize = 12.sp, color = g.fgMuted)
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .border(1.dp, if (isJobRunning) g.accent.copy(alpha = 0.5f) else g.borderMuted, RoundedCornerShape(6.dp))
                                        .background(if (isJobRunning) g.accent.copy(alpha = 0.12f) else g.canvas)
                                        .clickable { nav.push(Screen.JobLog(owner, name, j.id, runId = runId, title = j.name)) }
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isJobRunning) {
                                            Oc(if (j.status == "in_progress") R.drawable.oc_sync else R.drawable.oc_clock, g.accent, 11.dp)
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Text(
                                            when {
                                                j.status == "in_progress" -> tr("运行中")
                                                j.status == "queued" -> tr("排队中")
                                                else -> tr("日志")
                                            },
                                            fontSize = 12.sp,
                                            color = if (isJobRunning) g.accent else g.fg,
                                            fontWeight = if (isJobRunning) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                            if (expanded) {
                                HDivider()
                                if (j.steps.isEmpty()) {
                                    Text(tr("GitHub 尚未返回步骤明细，打开日志查看实时进度。"), Modifier.padding(12.dp), fontSize = 12.sp, color = g.fgMuted)
                                } else {
                                    j.steps.forEachIndexed { index, s ->
                                        val (si, scol) = runVisual(s.status, s.conclusion)
                                        Row(
                                            Modifier.fillMaxWidth().clickable { nav.push(Screen.JobLog(owner, name, j.id, runId = runId, title = j.name, stepNumber = s.number)) }
                                                .padding(start = 34.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text((index + 1).toString(), fontSize = 11.sp, color = g.fgMuted, modifier = Modifier.width(22.dp))
                                            Oc(si, scol, 13.dp)
                                            Spacer(Modifier.width(8.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(s.name, fontSize = 13.sp, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text(statusZh(s.status, s.conclusion), fontSize = 11.sp, color = scol)
                                            }
                                            Text(fmtDuration(s.startedAt, s.completedAt).takeIf { s.startedAt != null } ?: "", fontSize = 11.sp, color = g.fgMuted)
                                        }
                                    }
                                }
                                GhButton(
                                    if (isJobRunning) tr("查看实时进度与日志") else tr("打开终端日志"),
                                    Modifier.padding(start = 34.dp, end = 12.dp, top = 4.dp, bottom = 10.dp).fillMaxWidth(),
                                    icon = if (isJobRunning) R.drawable.oc_sync else R.drawable.oc_log,
                                ) {
                                    nav.push(Screen.JobLog(owner, name, j.id, runId = runId, title = j.name))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                val a = arts.data.orEmpty()
                if (a.isNotEmpty()) {
                    item { com.mobilegh.ui.components.SectionTitle(tr("产物 Artifacts")) }
                    itemsIndexed(a, key = { _, art -> art.id }) { _, art ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Oc(R.drawable.oc_package, g.fgMuted)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(art.name, fontSize = 14.sp, color = g.fg)
                                Text(fmtSize(art.sizeInBytes) + if (art.expired) tr(" · 已过期") else "", fontSize = 12.sp, color = g.fgMuted)
                            }
                            if (!art.expired) OcButton(R.drawable.oc_download, {
                                ctx.downloadFile(
                                    "https://api.github.com/repos/$owner/$name/actions/artifacts/${art.id}/zip",
                                    "${art.name}.zip",
                                    isPrivate = true,
                                    mimeType = "application/zip",
                                )
                            })
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

internal fun statusZh(s: String?, c: String?) = when {
    s == "in_progress" -> tr("运行中")
    s == "queued" -> tr("排队中")
    s == "waiting" -> tr("等待中")
    c == "success" -> tr("成功")
    c == "failure" -> tr("失败")
    c == "cancelled" -> tr("已取消")
    c == "skipped" -> tr("已跳过")
    c == "timed_out" -> tr("超时")
    else -> c ?: s ?: ""
}

private val TS = Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+Z ?""")
private val ANSI = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

/** kind：0 普通 1 错误 2 警告 3 分组标题 4 命令 5 通知；group 为所属分组编号（-1 不在分组内），header 表示分组标题行 */
private data class LogLine(val ts: String, val text: String, val kind: Int, val group: Int = -1, val header: Boolean = false)

/** 终端配色跟随应用主题（即系统的明暗设置）：暗色是深色终端，亮色是浅色终端 */
private class TerminalPalette(
    val bg: Color, val bar: Color, val fg: Color, val muted: Color, val prompt: Color,
    val group: Color, val error: Color, val warning: Color, val notice: Color,
)

private fun terminalPalette(dark: Boolean) = if (dark) TerminalPalette(
    bg = Color(0xFF0D1117), bar = Color(0xFF161B22), fg = Color(0xFFE6EDF3), muted = Color(0xFF8B949E),
    prompt = Color(0xFF7EE787), group = Color(0xFF79C0FF), error = Color(0xFFFF7B72), warning = Color(0xFFE3B341), notice = Color(0xFF58A6FF),
) else TerminalPalette(
    bg = Color(0xFFF6F8FA), bar = Color(0xFFEAEEF2), fg = Color(0xFF1F2328), muted = Color(0xFF656D76),
    prompt = Color(0xFF1A7F37), group = Color(0xFF0969DA), error = Color(0xFFCF222E), warning = Color(0xFF9A6700), notice = Color(0xFF0969DA),
)

@Composable
fun JobLogScreen(owner: String, name: String, jobId: Long, title: String, runId: Long = 0, stepNumber: Int = 0) {
    val g = Gh.c
    val t = terminalPalette(g.dark)
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()

    val job = rememberLoader("job:$jobId") { GitHub.job(owner, name, jobId, it) }

    val log = rememberLoader("log:$jobId") { force ->
        val currentJob = job.data ?: runCatching { GitHub.job(owner, name, jobId, force) }.getOrNull()
        if (currentJob != null && currentJob.status != "completed") {
            // 正在运行或排队，GitHub REST 日志接口此时不可用
            return@rememberLoader emptyList<LogLine>()
        }
        val raw = GitHub.jobLog(owner, name, jobId)
        val text = if (raw.length > 3_000_000) raw.takeLast(3_000_000) else raw
        // 分组（##[group] … ##[endgroup]）的行记下所属分组，界面据此默认折叠
        val out = ArrayList<LogLine>()
        var group = -1
        var groups = 0
        for (l0 in text.split('\n')) {
            val l = ANSI.replace(l0.trimEnd('\r'), "")
            val ts = TS.find(l)?.value ?: ""
            val body = l.substring(ts.length)
            when {
                body.startsWith("##[endgroup]") -> group = -1
                body.startsWith("##[group]") -> {
                    group = groups++
                    out += LogLine(ts, body.removePrefix("##[group]"), 3, group, header = true)
                }
                body.startsWith("##[error]") -> out += LogLine(ts, body.removePrefix("##[error]"), 1, group)
                body.startsWith("##[warning]") -> out += LogLine(ts, body.removePrefix("##[warning]"), 2, group)
                body.startsWith("##[notice]") -> out += LogLine(ts, body.removePrefix("##[notice]"), 5, group)
                body.startsWith("[command]") -> out += LogLine(ts, "$ " + body.removePrefix("[command]"), 4, group)
                else -> out += LogLine(ts, body, 0, group)
            }
        }
        out
    }

    val j = job.data
    val isRunning = j?.let { it.status == "in_progress" || it.status == "queued" || it.status == "waiting" } ?: false
    val effectiveRunId = if (runId > 0) runId else (j?.runId ?: 0)
    val targetUrl = j?.htmlUrl?.takeIf { it.isNotBlank() }
        ?: (if (effectiveRunId > 0) "https://github.com/$owner/$name/actions/runs/$effectiveRunId/job/$jobId" else "https://github.com/$owner/$name/actions")

    // 运行中状态下每 3 秒自动轮询进度
    LaunchedEffect(isRunning) {
        while (isRunning) {
            delay(3000)
            job.load(true)
        }
    }

    // 任务完成后自动拉取终端日志
    LaunchedEffect(j?.status) {
        if (j?.status == "completed" && (log.data == null || log.data?.isEmpty() == true)) {
            log.load(true)
        }
    }

    var showTs by rememberSaveable { mutableStateOf(false) }
    var onlyErrors by rememberSaveable { mutableStateOf(false) }
    val state = rememberLazyListState()
    // 分组默认折叠：点击分组标题展开或收起；只看错误时平铺显示所有错误和警告
    var expanded by remember(jobId) { mutableStateOf(emptySet<Int>()) }
    val lines = log.data.orEmpty()
    val groupIds = lines.filter { it.header }.map { it.group }
    val shown = if (onlyErrors) lines.filter { it.kind == 1 || it.kind == 2 }
        else lines.filter { it.group < 0 || it.header || it.group in expanded }
    // 已经滚到最下面时不显示「跳到最下面」，只有向上翻过之后才需要它
    val atBottom by remember(state) {
        androidx.compose.runtime.derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset)
        }
    }

    Page(
        title,
        subtitle = when {
            isRunning && j.status == "in_progress" -> tr("作业实时运行中")
            isRunning -> tr("作业排队中")
            else -> tr("作业日志")
        },
        actions = {
            if (!isRunning && log.data != null && log.data!!.isNotEmpty()) {
                OcButton(
                    R.drawable.oc_wrap_text,
                    { Session.toggleWrap() },
                    tint = if (Session.codeWrap) g.accent else g.fgMuted,
                    description = if (Session.codeWrap) tr("自动换行（已开启）") else tr("自动换行（已关闭）"),
                )
                OcButton(if (onlyErrors) R.drawable.oc_filter_remove else R.drawable.oc_filter, { onlyErrors = !onlyErrors }, if (onlyErrors) g.danger else g.fg)
            }
            MoreMenu(buildList {
                if (!isRunning && log.data != null && log.data!!.isNotEmpty()) {
                    add(MenuAction(if (showTs) tr("隐藏时间戳") else tr("显示时间戳")) { showTs = !showTs })
                    add(MenuAction(
                        if (Session.codeWrap) tr("自动换行（已开启）") else tr("自动换行（已关闭）"),
                        icon = R.drawable.oc_wrap_text,
                    ) { Session.toggleWrap() })
                    add(MenuAction(tr("复制全部日志"), icon = R.drawable.oc_copy) { log.data?.let { l -> ctx.copy(l.joinToString("\n") { it.text }) } })
                    if (groupIds.isNotEmpty()) {
                        val allOpen = expanded.size >= groupIds.size
                        add(MenuAction(if (allOpen) tr("折叠全部分组") else tr("展开全部分组"), icon = R.drawable.oc_chevron_down) {
                            expanded = if (allOpen) emptySet() else groupIds.toSet()
                        })
                    }
                }
                add(MenuAction(tr("在浏览器打开"), icon = R.drawable.oc_globe) { ctx.openBrowser(targetUrl) })
                add(MenuAction(tr("复制作业链接"), icon = R.drawable.oc_copy) { ctx.copy(targetUrl) })
            })
        },
        floating = {
            if (!isRunning && lines.isNotEmpty() && !atBottom) {
                FloatingActionButton({ scope.launch { state.scrollToItem((shown.size - 1).coerceAtLeast(0)) } }, containerColor = g.btnBg, contentColor = g.fg) {
                    Oc(R.drawable.oc_chevron_down, g.fg, 20.dp)
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(t.bg)) {
            // 顶部终端风格标题栏
            Row(Modifier.fillMaxWidth().background(t.bar).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0xFFFF5F56, 0xFFFFBD2E, 0xFF27C93F).forEach { c ->
                    Box(Modifier.size(9.dp).background(Color(c), CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(title, color = t.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (isRunning) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(g.accent.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Oc(if (j.status == "in_progress") R.drawable.oc_sync else R.drawable.oc_clock, g.accent, 11.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(if (j.status == "in_progress") tr("运行中") else tr("排队中"), color = g.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (j != null) {
                    val (si, scol) = runVisual(j.status, j.conclusion)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Oc(si, scol, 12.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(statusZh(j.status, j.conclusion), color = scol, fontSize = 11.sp)
                    }
                }
            }

            // 内容展示
            when {
                // 1. 还在初次加载 job 信息
                job.loading && j == null -> {
                    Loading(Modifier.fillMaxSize())
                }
                // 2. job 加载失败
                job.error != null && j == null -> {
                    ErrorState(job.error!!, Modifier.fillMaxSize()) { job.load(true) }
                }
                // 3. 任务正在运行中 (in_progress / queued / waiting)
                isRunning -> {
                    JobLiveRunningView(
                        job = j,
                        targetUrl = targetUrl,
                        highlightStepNumber = stepNumber,
                        onRefresh = { job.load(true) },
                    )
                }
                // 4. 任务已完成，但拉取日志发生错误 (403 权限、404 过期、或其他原因)
                log.error != null && j != null -> {
                    JobLogTroubleshootingView(
                        job = j,
                        error = log.error!!,
                        targetUrl = targetUrl,
                        onRetry = { log.load(true) },
                    )
                }
                // 5. 任务已完成，日志加载中
                log.loading && (log.data == null || log.data?.isEmpty() == true) -> {
                    Loading(Modifier.fillMaxSize())
                }
                // 6. 任务已完成，正常显示日志
                else -> {
                    val hs = rememberScrollState()
                    Box(Modifier.fillMaxSize().lazyScrollbar(state).then(if (Session.codeWrap) Modifier else Modifier.horizontalScroll(hs))) {
                        SelectionContainer {
                            RegisterScrollTop(state)
                            LazyColumn(if (Session.codeWrap) Modifier.fillMaxSize() else Modifier.width(2400.dp).fillMaxSize(), state = state) {
                                if (shown.isEmpty()) item { Text(if (onlyErrors) tr("没有错误或警告") else tr("日志为空"), Modifier.padding(16.dp), color = g.fgMuted) }
                                itemsIndexed(shown) { _, l ->
                                    val color = when (l.kind) {
                                        1 -> t.error
                                        2 -> t.warning
                                        3 -> t.group
                                        4 -> t.prompt
                                        5 -> t.notice
                                        else -> t.fg
                                    }
                                    val open = l.group in expanded
                                    // 分组标题可点按展开 / 收起；只看错误时标题不可点
                                    val foldable = l.header && !onlyErrors
                                    Text(
                                        buildAnnotatedString {
                                            if (showTs && l.ts.isNotEmpty()) withStyle(SpanStyle(color = t.muted)) { append(l.ts.substring(11, 19) + "  ") }
                                            val label = if (l.header) (if (open) "▾ " else "▸ ") + l.text else l.text
                                            withStyle(SpanStyle(color = color, fontWeight = if (l.kind == 3) FontWeight.SemiBold else null)) { append(label) }
                                        },
                                        Modifier.fillMaxWidth()
                                            .background(if (l.kind == 1) t.error.copy(alpha = 0.12f) else if (l.kind == 2) t.warning.copy(alpha = 0.12f) else Color.Transparent)
                                            .then(if (foldable) Modifier.clickable { expanded = if (open) expanded - l.group else expanded + l.group } else Modifier)
                                            .padding(horizontal = 10.dp, vertical = 1.dp),
                                        style = CodeStyle, softWrap = Session.codeWrap,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobLiveRunningView(
    job: Job,
    targetUrl: String,
    highlightStepNumber: Int,
    onRefresh: () -> Unit,
) {
    val g = Gh.c
    val ctx = rememberCtx()
    val scroll = rememberLazyListState()

    ScrollbarLazyColumn(Modifier.fillMaxSize(), state = scroll) {
        item {
            Card(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Oc(
                            if (job.status == "in_progress") R.drawable.oc_sync else R.drawable.oc_clock,
                            g.accent,
                            18.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (job.status == "in_progress") tr("任务正在实时运行中") else tr("任务正在排队等待中"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = g.fg,
                        )
                        job.startedAt?.let {
                            Text(
                                " · " + tr("已耗时 ") + fmtDuration(it, null),
                                fontSize = 12.sp,
                                color = g.fgMuted,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("GitHub 官方在作业执行期间暂不生成离线日志文件，任务完成后将自动在此呈现完整终端日志。如需实时观看终端输出流，请点击下方按钮直接在网页中查看。"),
                        fontSize = 12.sp,
                        color = g.fgMuted,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    ButtonRowAdaptive(Modifier.fillMaxWidth()) {
                        GhButton(
                            tr("在浏览器查看实时终端输出"),
                            icon = R.drawable.oc_link_external,
                        ) {
                            ctx.openBrowser(targetUrl)
                        }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, g.borderMuted, RoundedCornerShape(6.dp))
                                .background(g.canvas)
                                .clickable(onClick = onRefresh)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Oc(R.drawable.oc_sync, g.fg, 14.dp)
                                Spacer(Modifier.width(4.dp))
                                Text(tr("刷新"), fontSize = 13.sp, color = g.fg)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(g.accent, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("正在自动跟踪执行状态（每 3 秒刷新）"), fontSize = 11.sp, color = g.fgMuted)
                    }
                }
            }
        }

        item {
            SectionTitle(tr("各步骤执行进度"))
        }

        if (job.steps.isEmpty()) {
            item {
                Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Oc(R.drawable.oc_gear, g.fgMuted, 16.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tr("GitHub 正在准备 Runner 虚拟机环境，尚未开始第一步…"),
                            fontSize = 13.sp,
                            color = g.fgMuted,
                        )
                    }
                }
            }
        } else {
            itemsIndexed(job.steps, key = { _, s -> s.number }) { _, s ->
                val (si, scol) = runVisual(s.status, s.conclusion)
                val isCurrent = s.status == "in_progress"
                val isFocus = highlightStepNumber > 0 && s.number == highlightStepNumber
                Card(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isCurrent || isFocus) g.accent.copy(alpha = 0.08f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            s.number.toString(),
                            fontSize = 11.sp,
                            color = g.fgMuted,
                            modifier = Modifier.width(24.dp),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                        Oc(si, scol, 14.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.name,
                                fontSize = 13.sp,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = g.fg,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(statusZh(s.status, s.conclusion), fontSize = 11.sp, color = scol)
                                s.startedAt?.let { st ->
                                    val dur = fmtDuration(st, s.completedAt)
                                    if (dur.isNotBlank()) {
                                        Text(" · $dur", fontSize = 11.sp, color = g.fgMuted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun JobLogTroubleshootingView(
    job: Job,
    error: String,
    targetUrl: String,
    onRetry: () -> Unit,
) {
    val g = Gh.c
    val ctx = rememberCtx()
    val scroll = rememberLazyListState()

    val isAdminError = error.contains("admin", ignoreCase = true) || error.contains("403")
    val isExpiredError = error.contains("404") || error.contains("未找到")

    ScrollbarLazyColumn(Modifier.fillMaxSize(), state = scroll) {
        item {
            Card(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Oc(
                            if (isAdminError) R.drawable.oc_shield_lock else if (isExpiredError) R.drawable.oc_clock else R.drawable.oc_alert,
                            if (isAdminError || isExpiredError) g.attention else g.danger,
                            18.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                isAdminError -> tr("需要仓库管理权限下载离线日志")
                                isExpiredError -> tr("日志已过期或已被清理")
                                else -> tr("暂时无法获取离线日志文件")
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = g.fg,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            isAdminError -> tr("GitHub REST API 规定公开仓库非管理员账号无法通过 API 批量下载日志文件。您可以直接点击下方按钮在 GitHub 网页中免登录查看完整终端日志。")
                            isExpiredError -> tr("GitHub Actions 默认在 90 天后自动清理日志归档文件。若任务已过期，您仍可在网页端查看运行历史记录。")
                            else -> trf("GitHub 接口返回异常：{0}。您可以稍后重试，或直接在网页中查看完整终端日志。", error)
                        },
                        fontSize = 12.sp,
                        color = g.fgMuted,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    ButtonRowAdaptive(Modifier.fillMaxWidth()) {
                        GhButton(
                            tr("在网页中查看完整终端日志"),
                            icon = R.drawable.oc_link_external,
                        ) {
                            ctx.openBrowser(targetUrl)
                        }
                        if (!isAdminError && !isExpiredError) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(1.dp, g.borderMuted, RoundedCornerShape(6.dp))
                                    .background(g.canvas)
                                    .clickable(onClick = onRetry)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Oc(R.drawable.oc_sync, g.fg, 14.dp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(tr("重试"), fontSize = 13.sp, color = g.fg)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (job.steps.isNotEmpty()) {
            item {
                SectionTitle(tr("步骤执行明细"))
            }
            itemsIndexed(job.steps, key = { _, s -> s.number }) { _, s ->
                val (si, scol) = runVisual(s.status, s.conclusion)
                Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            s.number.toString(),
                            fontSize = 11.sp,
                            color = g.fgMuted,
                            modifier = Modifier.width(24.dp),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                        Oc(si, scol, 14.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontSize = 13.sp, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(statusZh(s.status, s.conclusion), fontSize = 11.sp, color = scol)
                                s.startedAt?.let { st ->
                                    val dur = fmtDuration(st, s.completedAt)
                                    if (dur.isNotBlank()) Text(" · $dur", fontSize = 11.sp, color = g.fgMuted)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
