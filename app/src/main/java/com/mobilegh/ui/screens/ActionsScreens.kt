package com.mobilegh.ui.screens

import androidx.compose.foundation.background
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
import com.mobilegh.data.Run
import com.mobilegh.data.Session
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.act
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.Pill
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

private fun eventZh(e: String) = when (e) {
    "push" -> "推送"
    "pull_request" -> "PR"
    "workflow_dispatch" -> "手动"
    "schedule" -> "定时"
    "release" -> "发布"
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
    var sel by rememberSaveable { mutableIntStateOf(0) }
    val list = wfs.data.orEmpty()
    val wfId = if (sel == 0) null else list.getOrNull(sel - 1)?.id
    val runs = rememberPager("runs:$wfId") { p, f -> GitHub.runs(owner, name, wfId, p, f) }
    var dispatch by remember { mutableStateOf(false) }

    Page(
        "Actions", subtitle = "$owner/$name",
        actions = {
            OcButton(R.drawable.oc_play, { dispatch = true })
            MoreMenu(listOf(MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/$owner/$name/actions") }))
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (list.isNotEmpty()) Chips(listOf("全部工作流") + list.map { it.name + if (it.state != "active") "（已停用）" else "" }, sel) { sel = it }
            HDivider()
            PagedList(runs, empty = "没有运行记录") { RunRow(owner, name, it) }
        }
    }

    if (dispatch) {
        var wi by remember { mutableIntStateOf(if (sel > 0) sel - 1 else 0) }
        var ref by remember { mutableStateOf(repo.data?.defaultBranch ?: "main") }
        GhDialog(
            "手动运行工作流", onDismiss = { dispatch = false }, confirm = "运行", confirmEnabled = list.isNotEmpty() && ref.isNotBlank(),
            onConfirm = {
                val w = list[wi]
                dispatch = false
                entry.act({ ctx.toast(it) }) {
                    AppLog.info("actions", "手动触发工作流：" + w.name + " @ " + ref.trim())
                    GitHub.dispatch(owner, name, w.id, ref.trim())
                    ctx.toast("已触发 ${w.name}")
                    delay(2500)
                    runs.refresh()
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (list.isEmpty()) Text("该仓库没有工作流", color = g.fgMuted)
                else Dropdown(list[wi].name, list.map { it.name }, { wi = it })
                GhField(ref, { ref = it }, "分支或标签")
                Text("工作流需要声明 workflow_dispatch 触发器才能手动运行。", fontSize = 12.sp, color = g.fgMuted)
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
    Page(
        r?.let { "${it.name} #${it.runNumber}" } ?: "运行详情", subtitle = "$owner/$name",
        actions = {
            MoreMenu(buildList {
                r?.let { add(MenuAction("在浏览器打开") { ctx.openBrowser(it.htmlUrl) }) }
                r?.let { add(MenuAction("复制提交 SHA") { ctx.copy(it.headSha) }) }
            })
        },
    ) { pad ->
        LoadBox(run, Modifier.padding(pad).fillMaxSize()) { rr ->
            val expandedJobs = remember { mutableStateMapOf<Long, Boolean>() }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    val (icon, color) = runVisual(rr.status, rr.conclusion)
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Oc(icon, color, 20.dp)
                            Spacer(Modifier.width(8.dp))
                            MdText(rr.displayTitle ?: rr.name ?: "", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${statusZh(rr.status, rr.conclusion)} · ${eventZh(rr.event)} 触发 · ${relTime(rr.createdAt)} · 耗时 ${fmtDuration(rr.runStartedAt, if (rr.status == "completed") rr.updatedAt else null)}",
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
                            if (rr.runAttempt > 1) { Spacer(Modifier.width(10.dp)); Pill("第 ${rr.runAttempt} 次尝试") }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (rr.status == "completed") {
                                GhButton("重新运行", icon = R.drawable.oc_sync) {
                                    entry.act({ ctx.toast(it) }) { AppLog.info("actions", "重新运行 Run #" + runId); GitHub.rerun(owner, name, runId, false); ctx.toast("已重新运行"); delay(2000); reload() }
                                }
                                if (rr.conclusion == "failure") GhButton("重跑失败任务") {
                                    entry.act({ ctx.toast(it) }) { AppLog.info("actions", "重跑失败任务 Run #" + runId); GitHub.rerun(owner, name, runId, true); ctx.toast("已重跑失败任务"); delay(2000); reload() }
                                }
                            } else {
                                GhButton("取消运行", danger = true, icon = R.drawable.oc_stop) {
                                    entry.act({ ctx.toast(it) }) { AppLog.info("actions", "取消 Run #" + runId); GitHub.cancelRun(owner, name, runId); ctx.toast("已请求取消"); reload() }
                                }
                            }
                        }
                    }
                    HDivider()
                }
                val js = jobs.data
                if (js == null) item { com.mobilegh.ui.components.Loading(Modifier.fillMaxWidth().height(120.dp)) }
                else itemsIndexed(js, key = { _, j -> j.id }) { _, j ->
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Column {
                            val (ji, jc) = runVisual(j.status, j.conclusion)
                            val expanded = expandedJobs[j.id] == true
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
                                Spacer(Modifier.width(6.dp))
                                Text("日志", fontSize = 12.sp, color = g.accent, modifier = Modifier.clickable { nav.push(Screen.JobLog(owner, name, j.id, j.name)) })
                            }
                            if (expanded) {
                                HDivider()
                                if (j.steps.isEmpty()) {
                                    Text("GitHub 尚未返回步骤明细，打开日志查看终端输出。", Modifier.padding(12.dp), fontSize = 12.sp, color = g.fgMuted)
                                } else {
                                    j.steps.forEachIndexed { index, s ->
                                        val (si, scol) = runVisual(s.status, s.conclusion)
                                        Row(
                                            Modifier.fillMaxWidth().clickable { nav.push(Screen.JobLog(owner, name, j.id, j.name + " · " + s.name)) }
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
                                GhButton("打开终端日志", Modifier.padding(start = 34.dp, end = 12.dp, top = 4.dp, bottom = 10.dp).fillMaxWidth(), icon = R.drawable.oc_log) {
                                    nav.push(Screen.JobLog(owner, name, j.id, j.name))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                val a = arts.data.orEmpty()
                if (a.isNotEmpty()) {
                    item { com.mobilegh.ui.components.SectionTitle("产物 Artifacts") }
                    itemsIndexed(a) { _, art ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Oc(R.drawable.oc_package, g.fgMuted)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(art.name, fontSize = 14.sp, color = g.fg)
                                Text(fmtSize(art.sizeInBytes) + if (art.expired) " · 已过期" else "", fontSize = 12.sp, color = g.fgMuted)
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

private fun statusZh(s: String?, c: String?) = when {
    s == "in_progress" -> "运行中"
    s == "queued" -> "排队中"
    s == "waiting" -> "等待中"
    c == "success" -> "成功"
    c == "failure" -> "失败"
    c == "cancelled" -> "已取消"
    c == "skipped" -> "已跳过"
    c == "timed_out" -> "超时"
    else -> c ?: s ?: ""
}

private val TS = Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+Z ?""")
private val ANSI = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

private data class LogLine(val ts: String, val text: String, val kind: Int) // 0 普通 1 错误 2 警告 3 分组 4 命令 5 通知

@Composable
fun JobLogScreen(owner: String, name: String, jobId: Long, title: String) {
    val g = Gh.c
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()
    val log = rememberLoader("log") {
        val raw = GitHub.jobLog(owner, name, jobId)
        val text = if (raw.length > 3_000_000) raw.takeLast(3_000_000) else raw
        text.split('\n').mapNotNull { l0 ->
            val l = ANSI.replace(l0.trimEnd('\r'), "")
            val ts = TS.find(l)?.value ?: ""
            val body = l.substring(ts.length)
            when {
                body.startsWith("##[endgroup]") -> null
                body.startsWith("##[group]") -> LogLine(ts, "▸ " + body.removePrefix("##[group]"), 3)
                body.startsWith("##[error]") -> LogLine(ts, body.removePrefix("##[error]"), 1)
                body.startsWith("##[warning]") -> LogLine(ts, body.removePrefix("##[warning]"), 2)
                body.startsWith("##[notice]") -> LogLine(ts, body.removePrefix("##[notice]"), 5)
                body.startsWith("[command]") -> LogLine(ts, "$ " + body.removePrefix("[command]"), 4)
                else -> LogLine(ts, body, 0)
            }
        }
    }
    var showTs by rememberSaveable { mutableStateOf(false) }
    var onlyErrors by rememberSaveable { mutableStateOf(false) }
    val state = rememberLazyListState()
    Page(
        title, subtitle = "作业日志",
        actions = {
            OcButton(R.drawable.oc_alert, { onlyErrors = !onlyErrors }, if (onlyErrors) g.danger else g.fg)
            MoreMenu(listOf(
                MenuAction(if (showTs) "隐藏时间戳" else "显示时间戳") { showTs = !showTs },
                MenuAction(if (Session.codeWrap) "关闭自动换行" else "自动换行") { Session.toggleWrap() },
                MenuAction("复制全部日志") { log.data?.let { l -> ctx.copy(l.joinToString("\n") { it.text }) } },
                MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/$owner/$name/actions/runs/0/job/$jobId") },
            ))
        },
        floating = {
            FloatingActionButton({ scope.launch { log.data?.let { state.scrollToItem((it.size - 1).coerceAtLeast(0)) } } }, containerColor = g.btnBg, contentColor = g.fg) {
                Oc(R.drawable.oc_chevron_down, g.fg, 20.dp)
            }
        },
    ) { pad ->
        LoadBox(log, Modifier.padding(pad).fillMaxSize()) { lines ->
            val shown = if (onlyErrors) lines.filter { it.kind == 1 || it.kind == 2 } else lines
            val hs = rememberScrollState()
            Box(Modifier.fillMaxSize().background(g.canvasInset).then(if (Session.codeWrap) Modifier else Modifier.horizontalScroll(hs))) {
                SelectionContainer {
                    LazyColumn(if (Session.codeWrap) Modifier.fillMaxSize() else Modifier.width(2400.dp).fillMaxSize(), state = state) {
                        if (shown.isEmpty()) item { Text(if (onlyErrors) "没有错误或警告" else "日志为空", Modifier.padding(16.dp), color = g.fgMuted) }
                        itemsIndexed(shown) { _, l ->
                            val color = when (l.kind) {
                                1 -> g.danger
                                2 -> g.attention
                                3 -> g.fg
                                4 -> g.accent
                                5 -> g.accent
                                else -> g.fg
                            }
                            Text(
                                (if (showTs && l.ts.isNotEmpty()) l.ts.substring(11, 19) + "  " else "") + l.text,
                                Modifier.fillMaxWidth()
                                    .background(if (l.kind == 1) g.dangerSubtle else if (l.kind == 2) g.attentionSubtle else Color.Transparent)
                                    .padding(horizontal = 10.dp, vertical = 1.dp),
                                style = CodeStyle, color = color, softWrap = Session.codeWrap,
                                fontWeight = if (l.kind == 3) FontWeight.SemiBold else null,
                            )
                        }
                    }
                }
            }
        }
    }
}
