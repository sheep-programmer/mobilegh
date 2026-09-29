package com.mobilegh.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.data.ContribCalendar
import com.mobilegh.data.ContribDay
import com.mobilegh.ui.theme.Gh
import java.time.LocalDate

fun levelOf(d: ContribDay): Int = when (d.contributionLevel) {
    "FIRST_QUARTILE" -> 1
    "SECOND_QUARTILE" -> 2
    "THIRD_QUARTILE" -> 3
    "FOURTH_QUARTILE" -> 4
    else -> if (d.contributionCount > 0) 1 else 0
}

/** GitHub 贡献热力图（官方手机 App 看不到）。可横向滑动、点按查看某天详情。 */
@Composable
fun Heatmap(cal: ContribCalendar, modifier: Modifier = Modifier) {
    val g = Gh.c
    val weeks = cal.weeks
    val cell = 12.dp
    val gap = 3.dp
    val left = 22.dp
    val top = 16.dp
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = g.fgMuted)
    var selected by remember(cal) { mutableStateOf<ContribDay?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(cal, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }

    Column(modifier) {
        Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            val width = left + (cell + gap) * weeks.size
            val height = top + (cell + gap) * 7
            Canvas(
                Modifier
                    .size(width, height)
                    .pointerInput(cal) {
                        detectTapGestures { p ->
                            val col = ((p.x - left.toPx()) / (cell + gap).toPx()).toInt()
                            val row = ((p.y - top.toPx()) / (cell + gap).toPx()).toInt()
                            selected = weeks.getOrNull(col)?.contributionDays?.firstOrNull { it.weekday == row }
                        }
                    },
            ) {
                val c = cell.toPx()
                val gp = gap.toPx()
                val l = left.toPx()
                val t = top.toPx()
                val r = CornerRadius(2.dp.toPx())
                // 星期标签（周日在第一行，与 GitHub 一致）
                listOf(1 to "一", 3 to "三", 5 to "五").forEach { (row, s) ->
                    drawText(measurer, s, Offset(0f, t + row * (c + gp) - 1.dp.toPx()), labelStyle)
                }
                var lastMonth = -1
                var lastLabelCol = -10
                weeks.forEachIndexed { wi, w ->
                    val first = w.contributionDays.firstOrNull() ?: return@forEachIndexed
                    val month = runCatching { LocalDate.parse(first.date).monthValue }.getOrDefault(-1)
                    if (month != lastMonth) {
                        if (wi - lastLabelCol >= 3 && wi < weeks.size - 1) {
                            drawText(measurer, "${month}月", Offset(l + wi * (c + gp), 0f), labelStyle)
                            lastLabelCol = wi
                        }
                        lastMonth = month
                    }
                    w.contributionDays.forEach { d ->
                        val x = l + wi * (c + gp)
                        val y = t + d.weekday * (c + gp)
                        drawRoundRect(g.heat[levelOf(d)], Offset(x, y), Size(c, c), r)
                        drawRoundRect(g.fg.copy(alpha = 0.06f), Offset(x, y), Size(c, c), r, style = Stroke(1f))
                        if (d == selected) drawRoundRect(g.fg, Offset(x - 1, y - 1), Size(c + 2, c + 2), r, style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val s = selected
            Text(
                if (s != null) "${s.date}：${if (s.contributionCount == 0) "无贡献" else "${s.contributionCount} 次贡献"}" else "点按方块查看当天贡献",
                fontSize = 12.sp, color = if (s != null) g.fg else g.fgMuted, fontWeight = if (s != null) FontWeight.Medium else null,
                modifier = Modifier.weight(1f),
            )
            Text("少", fontSize = 11.sp, color = g.fgMuted)
            Spacer(Modifier.width(4.dp))
            g.heat.forEach {
                Box(Modifier.padding(horizontal = 1.5.dp).size(10.dp).background(it, RoundedCornerShape(2.dp)))
            }
            Spacer(Modifier.width(4.dp))
            Text("多", fontSize = 11.sp, color = g.fgMuted)
        }
    }
}

data class ContribStats(
    val total: Int,
    val activeDays: Int,
    val longest: Int,
    val current: Int,
    val best: ContribDay?,
    val byWeekday: IntArray,
)

fun contribStats(cal: ContribCalendar): ContribStats {
    val today = LocalDate.now().toString()
    val days = cal.weeks.flatMap { it.contributionDays }.filter { it.date <= today }
    var longest = 0
    var run = 0
    days.forEach { d ->
        run = if (d.contributionCount > 0) run + 1 else 0
        if (run > longest) longest = run
    }
    // 当前连续：今天还没提交时，从昨天开始算
    var current = 0
    val rev = days.asReversed()
    val startIdx = if (rev.firstOrNull()?.contributionCount == 0) 1 else 0
    for (i in startIdx until rev.size) {
        if (rev[i].contributionCount > 0) current++ else break
    }
    val byWeekday = IntArray(7)
    days.forEach { byWeekday[it.weekday] += it.contributionCount }
    return ContribStats(
        total = cal.totalContributions,
        activeDays = days.count { it.contributionCount > 0 },
        longest = longest,
        current = current,
        best = days.maxByOrNull { it.contributionCount }?.takeIf { it.contributionCount > 0 },
        byWeekday = byWeekday,
    )
}

@Composable
fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(value, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = Gh.c.fg, maxLines = 1)
        Text(label, fontSize = 11.sp, color = Gh.c.fgMuted, maxLines = 1)
    }
}
