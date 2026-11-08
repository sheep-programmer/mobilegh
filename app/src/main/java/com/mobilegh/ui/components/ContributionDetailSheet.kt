package com.mobilegh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.ContribCalendar
import com.mobilegh.data.ContribDay
import com.mobilegh.data.ContribWeek
import com.mobilegh.data.Contributions
import com.mobilegh.data.Event
import com.mobilegh.data.GitHub
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.ui.theme.Gh
import com.mobilegh.ui.theme.GhColors
import com.mobilegh.ui.theme.LightGh
import java.util.Locale
import kotlin.math.roundToInt

fun weekdayName(weekday: Int): String = when (weekday) {
    0 -> tr("周日")
    1 -> tr("周一")
    2 -> tr("周二")
    3 -> tr("周三")
    4 -> tr("周四")
    5 -> tr("周五")
    6 -> tr("周六")
    else -> ""
}

/** 贡献详情弹层展示模式：单日或所在周 */
enum class ContribDetailMode { Day, Week }

/**
 * 单日与所在周贡献详情弹层：
 * 双击热力图某一天或点击某天摘要打开，展示具体的分类构成（提交、PR、Review、Issue、新建仓库）以及参与仓库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayContributionSheet(
    login: String,
    day: ContribDay,
    week: ContribWeek,
    initialMode: ContribDetailMode = ContribDetailMode.Day,
    onDismiss: () -> Unit,
    onPickDay: (ContribDay) -> Unit = {},
) {
    val g = Gh.c
    val nav = LocalNav.current
    var mode by remember { mutableStateOf(initialMode) }
    var currentDay by remember(day) { mutableStateOf(day) }

    val weekDays = week.contributionDays.sortedBy { it.date }
    val weekStart = weekDays.firstOrNull()?.date ?: currentDay.date
    val weekEnd = weekDays.lastOrNull()?.date ?: currentDay.date
    val weekTotal = weekDays.sumOf { it.contributionCount }

    // 读取当前所选模式对应日期范围的数据
    val fromDate = if (mode == ContribDetailMode.Day) currentDay.date else weekStart
    val toDate = if (mode == ContribDetailMode.Day) currentDay.date else weekEnd

    var details by remember(fromDate, toDate, login) { mutableStateOf<Contributions?>(null) }
    var loading by remember(fromDate, toDate, login) { mutableStateOf(true) }
    var error by remember(fromDate, toDate, login) { mutableStateOf<String?>(null) }
    var dayEvents by remember(fromDate, toDate, login) { mutableStateOf<List<Event>>(emptyList()) }
    var showPie by remember { mutableStateOf(false) }
    var selectedPieSlice by remember { mutableIntStateOf(-1) }

    LaunchedEffect(fromDate, toDate, login) {
        loading = true
        error = null
        try {
            details = GitHub.contributionsRange(login, fromDate, toDate)
            if (mode == ContribDetailMode.Day && currentDay.contributionCount > 0) {
                // 如果是单日，尝试匹配该日内的公开事件（近 90 天有效）
                runCatching {
                    val events = GitHub.userEvents(login, 1, false)
                    dayEvents = events.filter { it.createdAt?.startsWith(currentDay.date) == true }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: tr("读取贡献详情失败")
        } finally {
            loading = false
        }
    }

    if (!showPie) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = g.canvas,
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
                    .verticalScrollWithBar(),
            ) {
            // 模式切换条：[ 当天贡献 ] [ 所在周贡献 ]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(g.canvasSubtle)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (mode == ContribDetailMode.Day) g.canvas else Color.Transparent)
                        .clickable { mode = ContribDetailMode.Day }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tr("当天贡献"),
                        fontSize = 13.sp,
                        fontWeight = if (mode == ContribDetailMode.Day) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (mode == ContribDetailMode.Day) g.accent else g.fgMuted,
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (mode == ContribDetailMode.Week) g.canvas else Color.Transparent)
                        .clickable { mode = ContribDetailMode.Week }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        trf("所在周（{0} 次）", weekTotal),
                        fontSize = 13.sp,
                        fontWeight = if (mode == ContribDetailMode.Week) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (mode == ContribDetailMode.Week) g.accent else g.fgMuted,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (mode == ContribDetailMode.Day) {
                // 单日头部
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${currentDay.date} · ${weekdayName(currentDay.weekday)}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = g.fg,
                        )
                        Text(
                            if (currentDay.contributionCount == 0) tr("无贡献记录")
                            else trf("共 {0} 次贡献", currentDay.contributionCount),
                            fontSize = 13.sp,
                            color = if (currentDay.contributionCount > 0) g.success else g.fgMuted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Box(
                        Modifier
                            .size(24.dp)
                            .background(g.heat[levelOf(currentDay)], RoundedCornerShape(4.dp))
                            .border(1.dp, g.border, RoundedCornerShape(4.dp)),
                    )
                }
            } else {
                // 所在周头部
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            trf("{0} 至 {1}", weekStart, weekEnd),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = g.fg,
                        )
                        Text(
                            trf("本周 7 天共 {0} 次贡献", weekTotal),
                            fontSize = 13.sp,
                            color = if (weekTotal > 0) g.success else g.fgMuted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 一周 7 天迷你柱状分布
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(g.canvasSubtle)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    weekDays.forEach { wd ->
                        val isCurrent = wd.date == currentDay.date
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    currentDay = wd
                                    mode = ContribDetailMode.Day
                                    onPickDay(wd)
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Text(
                                weekdayName(wd.weekday).removePrefix("周"),
                                fontSize = 11.sp,
                                color = if (isCurrent) g.accent else g.fgMuted,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .background(g.heat[levelOf(wd)], RoundedCornerShape(3.dp))
                                    .border(
                                        if (isCurrent) 1.5.dp else 1.dp,
                                        if (isCurrent) g.accent else g.border,
                                        RoundedCornerShape(3.dp),
                                    ),
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${wd.contributionCount}",
                                fontSize = 10.sp,
                                color = if (isCurrent) g.fg else g.fgMuted,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HDivider()
            Spacer(Modifier.height(12.dp))

            when {
                loading -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = g.fgMuted)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("正在读取贡献明细…"), fontSize = 13.sp, color = g.fgMuted)
                    }
                }
                error != null -> {
                    InlineError(trf("读取失败：{0}", error!!))
                }
                details != null -> {
                    val d = details!!
                    val total = d.totalCommitContributions + d.totalPullRequestContributions +
                        d.totalPullRequestReviewContributions + d.totalIssueContributions +
                        d.totalRepositoryContributions

                    if (total == 0 && d.restrictedContributionsCount == 0 && (if (mode == ContribDetailMode.Day) currentDay.contributionCount else weekTotal) == 0) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Oc(R.drawable.oc_calendar, g.fgMuted, 36.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(tr("暂无贡献记录"), fontSize = 14.sp, color = g.fgMuted)
                            Text(tr("未产生公开提交、拉取请求、评审或议题。"), fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 4.dp))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("贡献构成"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg, modifier = Modifier.weight(1f))
                            SectionActionChip(
                                text = tr("饼状图"),
                                tooltip = tr("查看饼状图构成分析"),
                                onClick = {
                                    selectedPieSlice = -1
                                    showPie = true
                                },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        val safeTotal = total.coerceAtLeast(1)
                        if (d.totalCommitContributions > 0) RatioRow(tr("提交 Commits"), d.totalCommitContributions, safeTotal, g.success, onClick = { selectedPieSlice = 0; showPie = true })
                        if (d.totalPullRequestContributions > 0) RatioRow(tr("拉取请求 Pull Requests"), d.totalPullRequestContributions, safeTotal, g.accent, onClick = { selectedPieSlice = 1; showPie = true })
                        if (d.totalPullRequestReviewContributions > 0) RatioRow(tr("代码审查 Review"), d.totalPullRequestReviewContributions, safeTotal, g.done, onClick = { selectedPieSlice = 2; showPie = true })
                        if (d.totalIssueContributions > 0) RatioRow(tr("议题 Issues"), d.totalIssueContributions, safeTotal, g.attention, onClick = { selectedPieSlice = 3; showPie = true })
                        if (d.totalRepositoryContributions > 0) RatioRow(tr("新建仓库"), d.totalRepositoryContributions, safeTotal, g.fgMuted, onClick = { selectedPieSlice = 4; showPie = true })
                        if (d.restrictedContributionsCount > 0) {
                            Text(
                                trf("另有 {0} 次私有贡献（受隐私设置保护）", d.restrictedContributionsCount),
                                fontSize = 12.sp,
                                color = g.fgMuted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        if (d.commitContributionsByRepository.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text(tr("参与贡献的仓库"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg)
                            Spacer(Modifier.height(4.dp))
                            val max = d.commitContributionsByRepository.maxOfOrNull { it.contributions.totalCount } ?: 1
                            d.commitContributionsByRepository.forEach { rc ->
                                val name = rc.repository.nameWithOwner
                                Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                                    val parts = name.split('/')
                                    if (parts.size >= 2) {
                                        nav.push(Screen.Repo(parts[0], parts[1]))
                                    }
                                }) {
                                    RatioRow(name, rc.contributions.totalCount, max, g.success, badge = { VisibilityBadge(rc.repository.isPrivate) })
                                }
                            }
                        }

                        if (dayEvents.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text(tr("该日动态明细"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg)
                            Spacer(Modifier.height(6.dp))
                            dayEvents.forEach { ev ->
                                EventItem(ev)
                            }
                        }
                    }
                }
            }
        }
    }
} else if (showPie && details != null) {
        val d = details!!
        val slices = remember(d, g) {
            buildContributionSlices(
                commits = d.totalCommitContributions,
                prs = d.totalPullRequestContributions,
                reviews = d.totalPullRequestReviewContributions,
                issues = d.totalIssueContributions,
                repos = d.totalRepositoryContributions,
                g = g,
            )
        }
        val pieTotal = d.totalCommitContributions + d.totalPullRequestContributions +
            d.totalPullRequestReviewContributions + d.totalIssueContributions +
            d.totalRepositoryContributions
        ContributionPieSheet(
            title = if (mode == ContribDetailMode.Day) trf("{0} 贡献构成", currentDay.date) else trf("第 {0} 周贡献构成", weekStart),
            slices = slices,
            total = pieTotal,
            restrictedCount = d.restrictedContributionsCount,
            initialSelectedIndex = selectedPieSlice,
            onDismiss = { showPie = false },
        )
    }
}


/**
 * 星期活跃分布详情弹层：
 * 双击「一周中的活跃分布」某一天（如周二）柱状图时打开，展示全年中该星期的总体贡献、各周记录及最高单日。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekdayContributionSheet(
    login: String,
    weekday: Int,
    cal: ContribCalendar,
    onDismiss: () -> Unit,
    onPickDay: (ContribDay, ContribWeek) -> Unit,
) {
    val g = Gh.c
    val title = weekdayName(weekday)

    val weeksWithDay = cal.weeks.mapNotNull { w ->
        val d = w.contributionDays.firstOrNull { it.weekday == weekday }
        if (d != null) d to w else null
    }
    val allDays = weeksWithDay.map { it.first }
    val totalCount = allDays.sumOf { it.contributionCount }
    val activeDays = allDays.count { it.contributionCount > 0 }
    val totalWeeks = allDays.size.coerceAtLeast(1)
    val avg = totalCount.toDouble() / totalWeeks
    val best = allDays.maxByOrNull { it.contributionCount }?.takeIf { it.contributionCount > 0 }
    val pct = if (cal.totalContributions > 0) (totalCount * 100f / cal.totalContributions) else 0f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = g.canvas,
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
                    .verticalScrollWithBar(),
            ) {
            Text(
                trf("{0}活跃分布与构成", title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = g.fg,
            )
            Text(
                trf("全年共 {0} 次贡献 · 占全年总量 {1}%", totalCount, String.format(Locale.getDefault(), "%.1f", pct)),
                fontSize = 13.sp,
                color = g.fgMuted,
                modifier = Modifier.padding(top = 2.dp),
            )

            Spacer(Modifier.height(12.dp))
            HDivider()

            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                StatCell(trf("{0} / {1} 周", activeDays, totalWeeks), tr("活跃周数"), Modifier.weight(1f))
                StatCell(String.format(Locale.getDefault(), "%.1f", avg), tr("平均贡献/周"), Modifier.weight(1f))
                StatCell(trf("{0}%", String.format(Locale.getDefault(), "%.1f", pct)), tr("贡献占比"), Modifier.weight(1f))
                StatCell("${best?.contributionCount ?: 0}", tr("最高单日"), Modifier.weight(1f))
            }

            HDivider()
            Spacer(Modifier.height(12.dp))

            Text(trf("全年中所有 {0} 的记录", title), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg)
            Text(tr("点按任意一天可直接查看该日的具体构成"), fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))

            // 优先展示有贡献的记录，按日期倒序
            val sortedList = weeksWithDay.sortedWith(
                compareByDescending<Pair<ContribDay, ContribWeek>> { it.first.contributionCount > 0 }
                    .thenByDescending { it.first.date }
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(g.canvasSubtle)
                    .border(1.dp, g.border, RoundedCornerShape(8.dp)),
            ) {
                sortedList.forEachIndexed { idx, (d, w) ->
                    if (idx > 0) HDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onPickDay(d, w)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .background(g.heat[levelOf(d)], RoundedCornerShape(3.dp))
                                .border(1.dp, g.border, RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(d.date, fontSize = 14.sp, color = g.fg, modifier = Modifier.weight(1f))
                        Text(
                            if (d.contributionCount == 0) tr("无贡献") else trf("{0} 次贡献", d.contributionCount),
                            fontSize = 13.sp,
                            color = if (d.contributionCount > 0) g.success else g.fgMuted,
                            fontWeight = if (d.contributionCount > 0) FontWeight.Medium else FontWeight.Normal,
                        )
                        Spacer(Modifier.width(6.dp))
                        Oc(R.drawable.oc_chevron_right, g.fgMuted, 14.dp)
                    }
                }
            }
        }
    }
}

/**
 * 构造统一的贡献分类饼图扇区数据
 */
fun buildContributionSlices(
    commits: Int,
    prs: Int,
    reviews: Int,
    issues: Int,
    repos: Int,
    g: GhColors = LightGh,
): List<PieSlice> {
    return listOf(
        PieSlice(
            label = tr("提交 Commits"),
            value = commits,
            color = g.success,
            description = tr("在默认或其它分支上提交的代码版本记录"),
        ),
        PieSlice(
            label = tr("拉取请求 Pull Requests"),
            value = prs,
            color = g.accent,
            description = tr("向代码仓库发起的拉取与合并请求"),
        ),
        PieSlice(
            label = tr("代码审查 Review"),
            value = reviews,
            color = g.done,
            description = tr("对其他协作者提交 PR 的评审与反馈"),
        ),
        PieSlice(
            label = tr("议题 Issues"),
            value = issues,
            color = g.attention,
            description = tr("在仓库中创建的问题报告、功能建议或讨论"),
        ),
        PieSlice(
            label = tr("新建仓库"),
            value = repos,
            color = g.fgMuted,
            description = tr("主持或创建的全新开源与私有代码仓库"),
        ),
    )
}

/**
 * 贡献构成饼状图分析弹层：
 * 点击贡献构成或单项分类时打开，以环形饼图呈现各贡献类型（提交、PR、Review、Issue、新建仓库）的比例与数值，
 * 支持轻触扇区交互与分类列表展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributionPieSheet(
    title: String = tr("贡献构成分析"),
    subtitle: String? = null,
    slices: List<PieSlice>,
    total: Int,
    restrictedCount: Int = 0,
    initialSelectedIndex: Int = -1,
    onDismiss: () -> Unit,
) {
    val g = Gh.c
    var selectedIndex by remember { mutableIntStateOf(initialSelectedIndex) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = g.canvas,
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
                    .verticalScrollWithBar(),
            ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = g.fg,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                subtitle ?: trf("共 {0} 次公开贡献 · {1} 种贡献类型", total, slices.count { it.value > 0 }),
                fontSize = 13.sp,
                color = g.fgMuted,
                modifier = Modifier.padding(top = 2.dp),
            )

            Spacer(Modifier.height(14.dp))
            HDivider()

            // 环形饼图区域
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                PieChart(
                    slices = slices,
                    modifier = Modifier.size(190.dp),
                    selectedSlice = selectedIndex,
                    onSelectSlice = { idx ->
                        selectedIndex = if (selectedIndex == idx) -1 else idx
                    },
                    centerContent = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            val sel = slices.getOrNull(selectedIndex)
                            if (sel != null && sel.value > 0) {
                                Text(
                                    "${sel.value}",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = sel.color,
                                )
                                val pct = if (total > 0) (sel.value * 100f / total) else 0f
                                Text(
                                    String.format(Locale.getDefault(), "%.1f%%", pct),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = g.fg,
                                )
                                Text(
                                    sel.label.split(" ").first(),
                                    fontSize = 11.sp,
                                    color = g.fgMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Text(
                                    "$total",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = g.fg,
                                )
                                Text(
                                    tr("次总贡献"),
                                    fontSize = 12.sp,
                                    color = g.fgMuted,
                                )
                                Text(
                                    tr("点按扇区查看"),
                                    fontSize = 10.sp,
                                    color = g.fgMuted.copy(alpha = 0.65f),
                                )
                            }
                        }
                    },
                )
            }

            HDivider()
            Spacer(Modifier.height(14.dp))

            Text(
                tr("各成分占比与说明"),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = g.fg,
            )
            Spacer(Modifier.height(8.dp))

            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                slices.forEachIndexed { idx, s ->
                    val isSelected = idx == selectedIndex
                    val pct = if (total > 0) (s.value * 100f / total) else 0f
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) g.canvasSubtle else g.canvasSubtle.copy(alpha = 0.5f))
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) s.color else g.border,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable {
                                selectedIndex = if (selectedIndex == idx) -1 else idx
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(s.color, CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                s.label,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = g.fg,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                trf("{0} 次", s.value),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = g.fg,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                String.format(Locale.getDefault(), "%.1f%%", pct),
                                fontSize = 12.sp,
                                color = g.fgMuted,
                            )
                        }

                        // 迷你进度条
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(g.neutralMuted),
                        ) {
                            val f = if (total == 0) 0f else s.value.toFloat() / total
                            if (f > 0f) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(f)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(s.color),
                                )
                            }
                        }

                        if (s.description.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                s.description,
                                fontSize = 12.sp,
                                color = g.fgMuted,
                            )
                        }
                    }
                }
            }

            if (restrictedCount > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    trf("另有 {0} 次私有仓库贡献，因隐私设置未纳入上方公开成分。", restrictedCount),
                    fontSize = 12.sp,
                    color = g.fgMuted,
                )
            }
        }
    }
}


