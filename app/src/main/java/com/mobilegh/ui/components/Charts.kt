package com.mobilegh.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.ui.theme.Gh
import com.mobilegh.ui.theme.langColor
import com.mobilegh.i18n.tr

@Composable
fun LangDot(name: String?, hex: String? = null, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(langColor(name, hex)))
}

/** 仓库语言占比条 */
@Composable
fun LanguageBar(langs: Map<String, Long>, modifier: Modifier = Modifier) {
    val total = langs.values.sum().toFloat().coerceAtLeast(1f)
    val sorted = langs.entries.sortedByDescending { it.value }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
            sorted.forEach { (k, v) ->
                val w = v / total
                if (w > 0.002f) Box(Modifier.weight(w).height(8.dp).background(langColor(k)))
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sorted.take(10).forEach { (k, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LangDot(k, size = 8.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(k, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Gh.c.fg)
                    Spacer(Modifier.width(4.dp))
                    Text("%.1f%%".format(v * 100 / total), fontSize = 12.sp, color = Gh.c.fgMuted)
                }
            }
        }
    }
}

/**
 * 双序列折线图（总数/独立），用于流量统计。
 * 点按、长按或左右拖动会选中最近的一天，在图上方弹出气泡，写明日期和两条线各自的数值。
 */
@Composable
fun LineChart(
    labels: List<String>,
    a: List<Int>,
    b: List<Int>,
    colorA: Color,
    colorB: Color,
    modifier: Modifier = Modifier,
    nameA: String = tr("总数"),
    nameB: String = tr("独立"),
) {
    val g = Gh.c
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 9.sp, color = g.fgMuted)
    var selected by remember(a, b) { mutableIntStateOf(-1) }
    val leftInset = 28.dp
    Canvas(modifier.fillMaxWidth().height(160.dp + ChartBubbleSpace).testTag("chart").chartSelectPoints(a.size, leftInset) { selected = it }) {
        if (a.isEmpty()) return@Canvas
        // 刻度取 3 的倍数，避免出现 1/0/0/0 这样的重复刻度
        val raw = (a + b).maxOrNull() ?: 0
        val max = ((raw + 2) / 3 * 3).coerceAtLeast(3)
        val leftPad = leftInset.toPx()
        val bottomPad = 16.dp.toPx()
        val top = ChartBubbleSpace.toPx()
        val w = size.width - leftPad
        val h = size.height - bottomPad - top - 4.dp.toPx()
        val stepX = if (a.size > 1) w / (a.size - 1) else w
        fun yOf(v: Int) = top + h - h * v / max
        for (k in 0..3) {
            val y = top + h * k / 3
            drawLine(g.borderMuted, Offset(leftPad, y), Offset(size.width, y), 1f)
            val v = max * (3 - k) / 3
            drawText(measurer, fmtCount(v), Offset(0f, y - 6.dp.toPx()), style)
        }
        if (selected in a.indices) drawLine(g.border, Offset(leftPad + selected * stepX, top), Offset(leftPad + selected * stepX, top + h), 1.dp.toPx())
        fun series(vals: List<Int>, c: Color) {
            val p = Path()
            vals.forEachIndexed { i, v ->
                val x = leftPad + i * stepX
                if (i == 0) p.moveTo(x, yOf(v)) else p.lineTo(x, yOf(v))
            }
            drawPath(p, c, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            vals.forEachIndexed { i, v ->
                drawCircle(c, if (i == selected) 4.5.dp.toPx() else 2.5.dp.toPx(), Offset(leftPad + i * stepX, yOf(v)))
            }
        }
        series(a, colorA)
        series(b, colorB)
        val every = (labels.size / 5).coerceAtLeast(1)
        labels.forEachIndexed { i, s ->
            if (i % every == 0) drawText(measurer, s, Offset(leftPad + i * stepX - 10.dp.toPx(), size.height - 12.dp.toPx()), style)
        }
        if (selected in a.indices) drawValueBubble(
            measurer,
            listOf(labels.getOrElse(selected) { "" }, "$nameA ${a[selected]}", "$nameB ${b.getOrElse(selected) { 0 }}"),
            leftPad + selected * stepX, minOf(yOf(a[selected]), yOf(b.getOrElse(selected) { 0 })), g.fg, g.canvas,
        ) else drawText(measurer, tr("点按或长按图表查看每天的数值"), Offset(leftPad, 4.dp.toPx()), style)
    }
}

/**
 * 柱状图（每周提交等）。点按、长按或左右拖动会选中那一根柱子，在它上方弹出气泡显示具体数值，
 * [labels] 非空时气泡里再加一行说明（日期、星期等），[unit] 是数值后缀（如「 次」）。
 */
@Composable
fun BarChart(
    values: List<Int>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 100.dp,
    labels: List<String> = emptyList(),
    unit: String = "",
) {
    val g = Gh.c
    val measurer = rememberTextMeasurer()
    var selected by remember(values) { mutableIntStateOf(-1) }
    Canvas(modifier.fillMaxWidth().height(height + ChartBubbleSpace).testTag("chart").chartSelect(values.size) { selected = it }) {
        if (values.isEmpty()) return@Canvas
        val top = ChartBubbleSpace.toPx()
        val chartH = size.height - top
        val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val slot = size.width / values.size
        val bw = (slot * 0.7f).coerceAtLeast(1f)
        fun barH(v: Int) = chartH * v / max
        if (selected in values.indices) drawRoundRect(g.accentSubtle, Offset(selected * slot, top), Size(slot, chartH), CornerRadius(3.dp.toPx()))
        drawLine(g.borderMuted, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        values.forEachIndexed { i, v ->
            val bh = barH(v)
            val c = if (selected < 0 || i == selected) color else color.copy(alpha = 0.55f)
            if (v > 0) drawRoundRect(c, Offset(i * slot + (slot - bw) / 2, size.height - bh), Size(bw, bh), CornerRadius(1.5.dp.toPx()))
        }
        if (selected in values.indices) {
            val v = values[selected]
            drawValueBubble(
                measurer, listOfNotNull("$v$unit", labels.getOrNull(selected)?.takeIf { it.isNotBlank() }),
                (selected + 0.5f) * slot, size.height - barH(v), g.fg, g.canvas,
            )
        } else drawText(measurer, tr("点按或长按柱子查看数值"), Offset(0f, 4.dp.toPx()), TextStyle(fontSize = 10.sp, color = g.fgMuted))
    }
}

@Composable
fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 12.sp, color = Gh.c.fgMuted)
    }
}

/** 横向比例条（如贡献类型占比） */
@Composable
fun RatioRow(label: String, value: Int, total: Int, color: Color, badge: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = Gh.c.fg, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) {
                badge()
                Spacer(Modifier.width(8.dp))
            }
            Text("$value", fontSize = 13.sp, color = Gh.c.fgMuted)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Gh.c.neutralMuted)) {
            val f = if (total == 0) 0f else value.toFloat() / total
            if (f > 0f) Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}
