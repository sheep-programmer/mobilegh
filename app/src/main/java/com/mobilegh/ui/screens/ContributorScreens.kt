package com.mobilegh.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.ContributionDates
import com.mobilegh.data.ContributionPoint
import com.mobilegh.data.ContributionRange
import com.mobilegh.data.ContributionSnapshot
import com.mobilegh.data.ContributionSource
import com.mobilegh.data.ContributionStats
import com.mobilegh.data.ContributionStatus
import com.mobilegh.data.ContributionSummary
import com.mobilegh.data.ContributorContribution
import com.mobilegh.data.aggregateContributions
import com.mobilegh.data.validateContributionDates
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.theme.Gh
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

@Composable
fun ContributorsScreen(owner: String, name: String) {
    val g = Gh.c
    val loader = rememberLoader("contributionDetails:$owner/$name") { ContributionStats.load(owner, name, it) }
    var selected by rememberSaveable(owner, name) { mutableIntStateOf(0) }
    val range = ContributionRange.entries.getOrElse(selected) { ContributionRange.All }
    val today = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate()
    var startDate by rememberSaveable(owner, name) { mutableStateOf(today.minusDays(29).toString()) }
    var endDate by rememberSaveable(owner, name) { mutableStateOf(today.toString()) }
    val dates = remember(startDate, endDate, today) { validateContributionDates(startDate, endDate, today) }
    // Keep usable statistics visible if a later refresh finds the server rebuilding its cache.
    var lastStatistics by remember(owner, name) { mutableStateOf<ContributionSnapshot?>(null) }
    val current = loader.data
    LaunchedEffect(current) {
        if (current?.status == ContributionStatus.Ready && current.source == ContributionSource.Statistics) {
            lastStatistics = current
        }
    }
    val snapshot = if (current?.source == ContributionSource.ContributorList && lastStatistics != null) lastStatistics else current
    val stale = snapshot !== current || loader.error != null
    val summary = remember(snapshot, range, dates.dates, today) {
        snapshot?.let { aggregateContributions(it, range, dates.dates, today.atStartOfDay(ZoneOffset.UTC).toInstant()) }
    }

    Page("贡献者", subtitle = "$owner/$name", actions = {
        IconButton(
            onClick = { loader.refresh() }, enabled = !loader.loading,
            modifier = Modifier.semantics { contentDescription = "刷新贡献统计" },
        ) { Oc(R.drawable.oc_sync, g.fgMuted, 20.dp) }
    }) { pad ->
        when {
            snapshot == null && loader.error != null -> ErrorState(loader.error!!, Modifier.padding(pad).fillMaxSize()) { loader.load(true) }
            snapshot == null || summary == null -> Column(
                Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Loading(Modifier.size(48.dp))
                Text("正在加载贡献统计…", color = g.fgMuted, fontSize = 13.sp)
                Text("GitHub 首次计算可能需要一些时间", Modifier.padding(16.dp), color = g.fgMuted, fontSize = 12.sp)
            }
            else -> PullToRefreshBox(
                isRefreshing = loader.refreshing, onRefresh = { loader.refresh() },
                modifier = Modifier.padding(pad).fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Chips(ContributionRange.entries.map { it.label }, range.ordinal) { selected = it }
                        if (range == ContributionRange.Custom) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    startDate, { startDate = it }, label = { Text("开始日期（UTC）") },
                                    placeholder = { Text("YYYY-MM-DD") }, singleLine = true,
                                    isError = dates.error != null, modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    endDate, { endDate = it }, label = { Text("结束日期（UTC，含当天）") },
                                    placeholder = { Text("YYYY-MM-DD") }, singleLine = true,
                                    isError = dates.error != null, modifier = Modifier.fillMaxWidth(),
                                )
                                dates.error?.let { Text(it, color = g.danger, fontSize = 12.sp) }
                            }
                        }
                    }
                    if (stale || current?.message != null || snapshot.truncated) item {
                        ContributionNotice(
                            buildList {
                                if (stale) add("显示上次加载的统计。${loader.error ?: current?.message.orEmpty()}")
                                else current?.message?.let { add(it) }
                                if (snapshot.truncated) add("仅汇总已加载的贡献者；占比以此列表为分母")
                            }.joinToString("\n"), loader.loading,
                        ) { loader.refresh() }
                    }
                    item { ContributionOverview(summary, range, snapshot) }
                    when {
                        snapshot.records.isEmpty() -> item {
                            ContributionEmpty(
                                when (snapshot.status) {
                                    ContributionStatus.Pending -> "周统计仍在生成"
                                    ContributionStatus.Empty -> "GitHub 未返回贡献统计"
                                    else -> "暂无可用的贡献者数据"
                                },
                                if (snapshot.status == ContributionStatus.Empty) "仓库可能为空，或统计尚无数据。可稍后刷新。"
                                else "稍后刷新可重新请求统计。", loader.loading,
                            ) { loader.refresh() }
                        }
                        range == ContributionRange.Custom && dates.error != null -> item {
                            ContributionEmpty("请修正日期范围", "日期需要有效，且结束日期不能晚于今天（UTC）。")
                        }
                        summary.rangeUnavailable -> item {
                            ContributionEmpty(
                                "该日期范围没有可用的周数据",
                                if (snapshot.source == ContributionSource.ContributorList)
                                    "当前仅有累计提交数；请选择「全部提交」，或稍后重试周统计。"
                                else "请选择已返回的日期范围。累计提交数可在「全部提交」查看。",
                            )
                        }
                        summary.contributors.isEmpty() -> item {
                            ContributionEmpty("该范围内没有贡献", "所选整周没有记录到提交或行数变化。")
                        }
                        else -> {
                            item {
                                Text(
                                    "${if (range == ContributionRange.All) "累计提交排名" else "所选整周提交排名"} · ${summary.contributors.size} 位贡献者",
                                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    color = g.fgMuted, fontSize = 12.sp,
                                )
                            }
                            itemsIndexed(summary.contributors, key = { _, contributor -> contributor.key }) { index, contributor ->
                                ContributorDetailCard(index + 1, contributor, range, snapshot.truncated)
                                val author = contributor.author?.login?.takeIf { it.isNotBlank() }
                                if (author != null && (range != ContributionRange.Custom || dates.error == null)) {
                                    val nav = LocalNav.current
                                    com.mobilegh.ui.components.TextLink("查看逐笔提交") {
                                        val requested = summary.requestedDates
                                        nav.push(Screen.Commits(owner, name, null, author = author,
                                            since = requested?.start?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toString(),
                                            until = requested?.end?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.minusSeconds(1)?.toString()))
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            if (snapshot.source == ContributionSource.Statistics)
                                "统计基于默认分支，排除合并提交与空提交。占比按返回的贡献者提交数计算；未关联账号的贡献可能不在统计中。GitHub 缓存可能延迟更新。"
                            else "备用贡献者列表来自 GitHub 的缓存，可能延迟数小时。它仅提供累计提交数，不提供日期、周趋势或增删行数。",
                            Modifier.padding(16.dp), color = g.fgMuted, fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributionNotice(message: String, loading: Boolean, retry: () -> Unit) {
    val g = Gh.c
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(8.dp))
        .background(g.attentionSubtle).padding(12.dp)) {
        Text(message, color = g.fg, fontSize = 12.sp)
        TextButton(onClick = retry, enabled = !loading) { Text(if (loading) "正在重试…" else "重新获取统计") }
    }
}

@Composable
private fun ContributionOverview(summary: ContributionSummary, range: ContributionRange, snapshot: ContributionSnapshot) {
    val g = Gh.c
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(range.label, color = g.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            summary.requestedDates?.let { MutedText("请求日期：${dateLabel(it)}（UTC）") }
            summary.availableDates?.let { MutedText("返回周数据：${dateLabel(it)}（UTC）") }
            if (range == ContributionRange.All) {
                MutedText("提交数使用 GitHub 的累计总数。周趋势与行数仅覆盖返回的周数据。")
            } else if (!summary.rangeUnavailable) {
                summary.includedDates?.let { MutedText("计入整周：${dateLabel(it)}（UTC）") }
                if (summary.boundaryWeeksIncluded) MutedText("包含与日期范围重叠的完整周，边界提交无法精确到天。", g.attention)
                if (summary.partialCoverage) MutedText("所选日期未被返回数据完整覆盖，仅汇总可用整周。", g.attention)
            }
            HDivider()
            val linesLabel = if (range == ContributionRange.All && !summary.linesCoverAllCommits) "返回周数据" else "所选统计"
            ContributionMetricGrid(listOf(
                ContributionMetric("贡献者", if (summary.rangeUnavailable ||
                    (snapshot.records.isEmpty() && snapshot.status != ContributionStatus.Empty)) null else summary.contributors.size.toLong(), g.fg),
                ContributionMetric(if (range == ContributionRange.All) "累计提交" else "整周提交", summary.commits, g.accent),
                ContributionMetric("新增行 · $linesLabel", summary.additions, g.success, "+"),
                ContributionMetric("删除行 · $linesLabel", summary.deletions, g.danger, "−"),
            ))
            if (summary.additions == null || summary.deletions == null) {
                MutedText("— 表示数据不可用。大仓库可能不提供增删行数；未返回的指标不会显示为零。")
            }
            if (snapshot.source == ContributionSource.Statistics && summary.weekly.isNotEmpty() && !summary.rangeUnavailable) {
                HDivider()
                WeeklyContributionGraph(summary.weekly, height = 90.dp)
            } else {
                MutedText("周趋势暂无可用数据")
            }
        }
    }
}

private data class ContributionMetric(val label: String, val count: Long?, val color: Color, val prefix: String = "")

@Composable
private fun ContributionMetricGrid(metrics: List<ContributionMetric>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 260.dp || fontScale > 1.4f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            metrics.chunked(columns).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    group.forEach { metric ->
                        Column(Modifier.weight(1f)) {
                            Text(metric.label, color = Gh.c.fgMuted, fontSize = 11.sp)
                            Text(
                                if (metric.count == null) "—" else metric.prefix + contributionCount(metric.count),
                                color = metric.color, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributorDetailCard(rank: Int, contributor: ContributorContribution, range: ContributionRange, truncated: Boolean) {
    val g = Gh.c
    val nav = LocalNav.current
    val login = contributor.author?.login.orEmpty()
    val displayName = login.ifBlank { contributor.author?.name?.takeIf { it.isNotBlank() } ?: "未关联账号的贡献者" }
    Card(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = login.isNotBlank()) { nav.push(Screen.Profile(login)) }
                    .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!contributor.author?.avatarUrl.isNullOrBlank()) {
                    Avatar(contributor.author!!.avatarUrl, 36.dp, square = contributor.author.type == "Organization")
                } else {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(g.canvasSubtle), contentAlignment = Alignment.Center) {
                        Oc(R.drawable.oc_people, g.fgMuted, 20.dp)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(displayName, color = g.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("#$rank${if (login.isNotBlank()) " · 查看个人主页" else " · 未关联 GitHub 账号"}", color = g.fgMuted, fontSize = 11.sp)
                }
            }
            ContributionMetricGrid(listOf(
                ContributionMetric(if (range == ContributionRange.All) "累计提交" else "整周提交", contributor.commits, g.fg),
                ContributionMetric("活跃周 · 返回数据", contributor.activeWeeks?.toLong(), g.accent),
                ContributionMetric("新增行 · 返回周数据", contributor.additions, g.success, "+"),
                ContributionMetric("删除行 · 返回周数据", contributor.deletions, g.danger, "−"),
            ))
            val share = contributor.share
            MutedText("${if (truncated) "已加载列表提交占比" else "返回贡献者提交占比"}：${share?.let { String.format(Locale.getDefault(), "%.1f%%", it * 100) } ?: "—"}")
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(g.borderMuted)) {
                if (share != null && share > 0) Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(0f, 1f))
                    .height(5.dp).background(g.success))
            }
            if (contributor.weekly.isNotEmpty()) {
                WeeklyContributionGraph(contributor.weekly, height = 42.dp)
                if (contributor.firstActiveWeek != null) {
                    MutedText("已知首次活跃周：${contributor.firstActiveWeek}")
                    MutedText("已知最近活跃周：${contributor.lastActiveWeek}")
                } else MutedText(if (contributor.activeWeeks == null) "活跃周数据不完整" else "返回周数据中暂无活跃提交")
                if (range == ContributionRange.All && !contributor.linesCoverAllCommits) {
                    MutedText("周趋势及增删行数不代表全部提交历史。")
                }
            } else MutedText("GitHub 未提供该贡献者的周趋势或增删行数。")
        }
    }
}

@Composable
private fun WeeklyContributionGraph(points: List<ContributionPoint>, height: Dp) {
    val g = Gh.c
    val visible = points.takeLast(52)
    val peak = visible.mapNotNull { it.commits }.maxOrNull()
    val peakLabel = peak?.let { contributionCount(it) } ?: "—"
    val missing = visible.count { it.commits == null }
    val description = "${visible.first().start} 至 ${visible.last().start} 的每周提交，已知最高 $peakLabel 次，$missing 周数据缺失"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MutedText("每周提交${if (points.size > 52) " · 最近 52 个返回周" else " · ${visible.size} 周"} · 已知最高 $peakLabel")
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
            val slot = size.width / visible.size
            val barWidth = slot * 0.7f
            drawLine(g.borderMuted, Offset(0f, size.height), Offset(size.width, size.height))
            visible.forEachIndexed { index, point ->
                val count = point.commits
                when {
                    count == null -> drawLine(g.fgMuted.copy(alpha = 0.35f), Offset((index + 0.5f) * slot, 0f),
                        Offset((index + 0.5f) * slot, size.height), strokeWidth = 1.dp.toPx())
                    count > 0 -> {
                        val barHeight = (size.height * (count.toDouble() / (peak ?: 1L).coerceAtLeast(1L))).toFloat()
                        drawRoundRect(g.accent, Offset(index * slot + (slot - barWidth) / 2, size.height - barHeight),
                            Size(barWidth, barHeight), CornerRadius(1.dp.toPx()))
                    }
                }
            }
        }
        MutedText("${visible.first().start} — ${visible.last().start}（周起始，UTC）")
        if (missing > 0) MutedText("灰色竖线表示缺失周数据；零提交周留空。")
    }
}

@Composable
private fun ContributionEmpty(title: String, detail: String, loading: Boolean = false, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Oc(R.drawable.oc_people, Gh.c.fgMuted, 28.dp)
        Text(title, color = Gh.c.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = Gh.c.fgMuted, fontSize = 13.sp)
        if (retry != null) TextButton(onClick = retry, enabled = !loading) { Text(if (loading) "正在加载…" else "重试") }
    }
}

@Composable
private fun MutedText(text: String, color: Color = Gh.c.fgMuted) {
    Text(text, color = color, fontSize = 11.sp)
}

private fun dateLabel(dates: ContributionDates) = "${dates.start} — ${dates.end}"
private fun contributionCount(value: Long) = NumberFormat.getIntegerInstance().format(value)

/** Small repository-homepage section; /contributors provides cumulative counts only. */
@Composable
fun ContributorPreview(owner: String, name: String) {
    val g = Gh.c
    val nav = LocalNav.current
    val loader = rememberLoader("contributionPreview:$owner/$name") { ContributionStats.preview(owner, name, it) }
    val snapshot = loader.data
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Keep title and action on separate lines so large fonts never squeeze the link out.
        Text("贡献者", color = g.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { nav.push(Screen.Contributors(owner, name)) }) {
            Text("详细统计", color = g.accent)
            Spacer(Modifier.size(6.dp))
            Oc(R.drawable.oc_chevron_right, g.accent, 12.dp)
        }
        when {
            snapshot == null && loader.error == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Loading(Modifier.size(32.dp))
                Text("正在加载贡献者…", color = g.fgMuted, fontSize = 12.sp)
            }
            snapshot == null -> {
                Text(loader.error ?: "贡献者暂不可用", color = g.fgMuted, fontSize = 12.sp)
                TextButton(onClick = { loader.load(true) }, enabled = !loader.loading) { Text("重试") }
            }
            snapshot.records.isEmpty() -> {
                MutedText(snapshot.message ?: "GitHub 未返回贡献者数据")
                if (snapshot.status == ContributionStatus.Pending) {
                    TextButton(onClick = { loader.load(true) }, enabled = !loader.loading) { Text("重试") }
                }
            }
            else -> {
                snapshot.records.take(3).forEachIndexed { index, record ->
                    val user = record.author
                    val login = user?.login.orEmpty()
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = login.isNotBlank()) { nav.push(Screen.Profile(login)) },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!user?.avatarUrl.isNullOrBlank()) Avatar(user!!.avatarUrl, 28.dp, square = user.type == "Organization")
                                else Oc(R.drawable.oc_people, g.fgMuted, 28.dp)
                                Text(login.ifBlank { user?.name?.takeIf { it.isNotBlank() } ?: "未关联账号的贡献者" },
                                    Modifier.weight(1f), color = g.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("#${index + 1}", color = g.fgMuted, fontSize = 11.sp)
                            }
                            Text("${record.total?.let { contributionCount(it) } ?: "—"} 次累计提交", color = g.accent,
                                fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                MutedText("提交最多的 ${snapshot.records.size.coerceAtMost(3)} 位贡献者 · GitHub 返回的累计提交数，缓存可能延迟。")
            }
        }
    }
}
