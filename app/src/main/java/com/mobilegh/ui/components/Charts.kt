package com.mobilegh.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.mobilegh.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.mobilegh.i18n.trf

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
    val summary = lineChartSummary(labels, a, b, nameA, nameB)
    Canvas(modifier.fillMaxWidth().height(160.dp + ChartBubbleSpace).testTag("chart").semantics { contentDescription = summary }.chartSelectPoints(a.size, leftInset) { selected = it }) {
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
            leftPad + selected * stepX, minOf(yOf(a[selected]), yOf(b.getOrElse(selected) { 0 })),
            g.canvas, g.fg, borderColor = g.border,
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
    onDoubleTap: ((Int) -> Unit)? = null,
) {
    val g = Gh.c
    val measurer = rememberTextMeasurer()
    var selected by remember(values) { mutableIntStateOf(-1) }
    val summary = barChartSummary(values, labels, unit)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height + ChartBubbleSpace)
            .testTag("chart")
            .semantics { contentDescription = summary }
            .chartSelect(values.size, selected = selected, onDoubleTap = onDoubleTap) { selected = it }
    ) {
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
                (selected + 0.5f) * slot, size.height - barH(v),
                background = g.canvas, foreground = g.fg, borderColor = g.border,
            )
        } else drawText(measurer, tr("点按柱子查看数值，再次点按取消"), Offset(0f, 4.dp.toPx()), TextStyle(fontSize = 10.sp, color = g.fgMuted))
    }
}

/**
 * 柱状图的读屏摘要：合计、最高值（有标签时附上最高值所在的标签）和范围。纯函数，只依赖传入的数据。
 * [unit] 与 [BarChart] 的后缀相同，拼在数字后面。
 */
fun barChartSummary(values: List<Int>, labels: List<String>, unit: String = ""): String {
    if (values.isEmpty()) return tr("暂无数据")
    val total = values.sum()
    val peak = values.maxOrNull() ?: 0
    val peakLabel = labels.getOrNull(values.indexOf(peak))?.takeIf { it.isNotBlank() }
    val first = labels.firstOrNull()?.takeIf { it.isNotBlank() }
    val last = labels.lastOrNull()?.takeIf { it.isNotBlank() }
    return if (peakLabel != null && first != null && last != null) {
        trf("柱状图，合计 {0}{2}，最高 {1}{2}（{3}），范围 {4} 至 {5}", total, peak, unit, peakLabel, first, last)
    } else {
        trf("柱状图，合计 {0}{2}，最高 {1}{2}", total, peak, unit)
    }
}

/**
 * 双序列折线图的读屏摘要：A 序列合计与峰值、B 序列峰值，以及标签范围。
 * B 序列（如独立访客）按天相加没有意义，所以只报峰值。纯函数。
 */
fun lineChartSummary(labels: List<String>, a: List<Int>, b: List<Int>, nameA: String, nameB: String): String {
    if (a.isEmpty()) return tr("暂无数据")
    val peakA = a.maxOrNull() ?: 0
    val peakB = b.maxOrNull() ?: 0
    val first = labels.firstOrNull()?.takeIf { it.isNotBlank() }
    val last = labels.lastOrNull()?.takeIf { it.isNotBlank() }
    return if (first != null && last != null) {
        trf("折线图，{0}合计 {1}，{0}峰值 {2}，{3}峰值 {4}，范围 {5} 至 {6}", nameA, a.sum(), peakA, nameB, peakB, first, last)
    } else {
        trf("折线图，{0}合计 {1}，{0}峰值 {2}，{3}峰值 {4}", nameA, a.sum(), peakA, nameB, peakB)
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

data class PieSlice(
    val label: String,
    val value: Int,
    val color: Color,
    val description: String = "",
)

/**
 * 环形饼状图：展示各成分比例，中间可放置总数或选中项信息，支持扇区轻触选中。
 */
@Composable
fun PieChart(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier,
    selectedSlice: Int = -1,
    onSelectSlice: ((Int) -> Unit)? = null,
    centerContent: (@Composable () -> Unit)? = null,
) {
    val total = slices.sumOf { it.value }
    val g = Gh.c
    val currentSlices by rememberUpdatedState(slices)
    val currentOnSelect by rememberUpdatedState(onSelectSlice)

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { offset ->
                val onSelect = currentOnSelect ?: return@detectTapGestures
                val list = currentSlices
                val tot = list.sumOf { it.value }
                if (tot <= 0) return@detectTapGestures
                val cx = size.width / 2f
                val cy = size.height / 2f
                val dx = offset.x - cx
                val dy = offset.y - cy
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
                val outerR = minOf(size.width, size.height) / 2f
                val strokeW = 32.dp.toPx()
                val innerR = outerR - strokeW
                if (dist in (innerR * 0.7f)..(outerR * 1.2f)) {
                    val angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    var norm = (angle + 90f) % 360f
                    if (norm < 0f) norm += 360f
                    var acc = 0f
                    for (i in list.indices) {
                        val sweep = 360f * list[i].value / tot
                        if (norm >= acc && norm <= acc + sweep) {
                            onSelect(i)
                            break
                        }
                        acc += sweep
                    }
                }
            }
        }) {
            val strokeWidth = 28.dp.toPx()
            val radius = (size.minDimension - strokeWidth - 8.dp.toPx()) / 2
            val center = Offset(size.width / 2, size.height / 2)
            if (total == 0) {
                drawCircle(
                    color = g.neutralMuted,
                    radius = radius,
                    style = Stroke(width = strokeWidth),
                )
                return@Canvas
            }
            var startAngle = -90f
            slices.forEachIndexed { idx, s ->
                val sweep = 360f * s.value / total
                if (s.value > 0) {
                    val isSelected = idx == selectedSlice
                    val sw = if (isSelected) strokeWidth + 6.dp.toPx() else strokeWidth
                    val col = if (selectedSlice < 0 || isSelected) s.color else s.color.copy(alpha = 0.45f)
                    drawArc(
                        color = col,
                        startAngle = startAngle + if (sweep > 3f) 1.5f else 0f,
                        sweepAngle = if (sweep > 3f) sweep - 3f else sweep,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = sw, cap = StrokeCap.Round),
                    )
                }
                startAngle += sweep
            }
        }
        if (centerContent != null) {
            centerContent()
        }
    }
}

/** 横向比例条（如贡献类型占比），支持点击查看详情 */
@Composable
fun RatioRow(
    label: String,
    value: Int,
    total: Int,
    color: Color,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = Gh.c.fg, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) {
                badge()
                Spacer(Modifier.width(8.dp))
            }
            Text("$value", fontSize = 13.sp, color = Gh.c.fgMuted)
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Oc(R.drawable.oc_chevron_right, Gh.c.fgMuted, 12.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Gh.c.neutralMuted)) {
            val f = if (total == 0) 0f else value.toFloat() / total
            if (f > 0f) Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}
