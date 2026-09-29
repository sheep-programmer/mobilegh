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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.ui.theme.Gh
import com.mobilegh.ui.theme.langColor

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

/** 双序列折线图（总数/独立），用于流量统计 */
@Composable
fun LineChart(
    labels: List<String>,
    a: List<Int>,
    b: List<Int>,
    colorA: Color,
    colorB: Color,
    modifier: Modifier = Modifier,
) {
    val g = Gh.c
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 9.sp, color = g.fgMuted)
    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        if (a.isEmpty()) return@Canvas
        // 刻度取 3 的倍数，避免出现 1/0/0/0 这样的重复刻度
        val raw = (a + b).maxOrNull() ?: 0
        val max = ((raw + 2) / 3 * 3).coerceAtLeast(3)
        val leftPad = 28.dp.toPx()
        val bottomPad = 16.dp.toPx()
        val w = size.width - leftPad
        val h = size.height - bottomPad - 4.dp.toPx()
        val stepX = if (a.size > 1) w / (a.size - 1) else w
        for (k in 0..3) {
            val y = 4.dp.toPx() + h * k / 3
            drawLine(g.borderMuted, Offset(leftPad, y), Offset(size.width, y), 1f)
            val v = max * (3 - k) / 3
            drawText(measurer, fmtCount(v), Offset(0f, y - 6.dp.toPx()), style)
        }
        fun series(vals: List<Int>, c: Color) {
            val p = Path()
            vals.forEachIndexed { i, v ->
                val x = leftPad + i * stepX
                val y = 4.dp.toPx() + h - h * v / max
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, c, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            vals.forEachIndexed { i, v ->
                drawCircle(c, 2.5.dp.toPx(), Offset(leftPad + i * stepX, 4.dp.toPx() + h - h * v / max))
            }
        }
        series(a, colorA)
        series(b, colorB)
        val every = (labels.size / 5).coerceAtLeast(1)
        labels.forEachIndexed { i, s ->
            if (i % every == 0) drawText(measurer, s, Offset(leftPad + i * stepX - 10.dp.toPx(), size.height - 12.dp.toPx()), style)
        }
    }
}

/** 柱状图（每周提交） */
@Composable
fun BarChart(values: List<Int>, color: Color, modifier: Modifier = Modifier, height: Dp = 100.dp) {
    val g = Gh.c
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val slot = size.width / values.size
        val bw = (slot * 0.7f).coerceAtLeast(1f)
        drawLine(g.borderMuted, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        values.forEachIndexed { i, v ->
            val bh = size.height * v / max
            if (v > 0) drawRoundRect(color, Offset(i * slot + (slot - bw) / 2, size.height - bh), Size(bw, bh), CornerRadius(1.5.dp.toPx()))
        }
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
fun RatioRow(label: String, value: Int, total: Int, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(label, fontSize = 13.sp, color = Gh.c.fg, modifier = Modifier.weight(1f))
            Text("$value", fontSize = 13.sp, color = Gh.c.fgMuted)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Gh.c.neutralMuted)) {
            val f = if (total == 0) 0f else value.toFloat() / total
            if (f > 0f) Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}
