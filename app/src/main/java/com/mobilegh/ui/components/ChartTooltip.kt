package com.mobilegh.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 图表上方为数值气泡预留的高度 */
val ChartBubbleSpace = 48.dp

/**
 * 让图表响应手指：点按、长按都会选中手指下的那一列，左右拖动时选中项跟着走。
 * [count] 是列数；每一列等宽，按横坐标换算出下标，并通过 [onSelect] 回调。
 */
fun Modifier.chartSelect(count: Int, onSelect: (Int) -> Unit): Modifier = this
    .pointerInput(count) {
        detectTapGestures(
            onTap = { o -> if (count > 0) onSelect(columnAt(o.x, size.width, count)) },
            onLongPress = { o -> if (count > 0) onSelect(columnAt(o.x, size.width, count)) },
        )
    }
    .pointerInput(count) {
        detectHorizontalDragGestures { change, _ ->
            if (count > 0) onSelect(columnAt(change.position.x, size.width, count))
        }
    }

/** 折线图版本：数据点从 [leftInset] 起等距排布到右边缘，手指落在哪个点附近就选哪个点 */
fun Modifier.chartSelectPoints(count: Int, leftInset: androidx.compose.ui.unit.Dp, onSelect: (Int) -> Unit): Modifier = this
    .pointerInput(count) {
        val inset = leftInset.toPx()
        detectTapGestures(
            onTap = { o -> if (count > 0) onSelect(nearestPoint(o.x, inset, size.width.toFloat(), count)) },
            onLongPress = { o -> if (count > 0) onSelect(nearestPoint(o.x, inset, size.width.toFloat(), count)) },
        )
    }
    .pointerInput(count) {
        val inset = leftInset.toPx()
        detectHorizontalDragGestures { change, _ ->
            if (count > 0) onSelect(nearestPoint(change.position.x, inset, size.width.toFloat(), count))
        }
    }

internal fun nearestPoint(x: Float, inset: Float, width: Float, count: Int): Int {
    if (count <= 1) return 0
    val step = (width - inset) / (count - 1)
    return Math.round((x - inset) / step).coerceIn(0, count - 1)
}

internal fun columnAt(x: Float, width: Int, count: Int): Int =
    (x / (width.toFloat() / count)).toInt().coerceIn(0, count - 1)

/**
 * 在 [anchorX]/[anchorY]（被选中柱子的顶部）上方画一个带小三角的数值气泡。
 * [lines] 逐行居中显示（例如「12 次提交」「03-05」）；气泡超出画布左右边界时会被收回来，小三角仍然指向柱子。
 */
fun DrawScope.drawValueBubble(
    measurer: TextMeasurer,
    lines: List<String>,
    anchorX: Float,
    anchorY: Float,
    background: Color,
    foreground: Color,
) {
    if (lines.isEmpty()) return
    val first = TextStyle(fontSize = 12.sp, color = foreground, fontWeight = FontWeight.SemiBold)
    val rest = TextStyle(fontSize = 10.sp, color = foreground.copy(alpha = 0.8f))
    val layouts = lines.mapIndexed { i, text -> measurer.measure(text, if (i == 0) first else rest) }
    val padX = 8.dp.toPx()
    val padY = 4.dp.toPx()
    val pointer = 5.dp.toPx()
    val gap = 3.dp.toPx()
    val w = layouts.maxOf { it.size.width } + padX * 2
    val h = layouts.sumOf { it.size.height } + padY * 2
    val x = (anchorX - w / 2).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    val y = (anchorY - h - pointer - gap).coerceAtLeast(0f)
    drawRoundRect(background, Offset(x, y), Size(w, h), CornerRadius(6.dp.toPx()))
    val tipX = anchorX.coerceIn(x + pointer * 2, (x + w - pointer * 2).coerceAtLeast(x + pointer * 2))
    drawPath(
        Path().apply {
            moveTo(tipX - pointer, y + h)
            lineTo(tipX + pointer, y + h)
            lineTo(tipX, y + h + pointer)
            close()
        },
        background,
    )
    var top = y + padY
    layouts.forEach { layout ->
        drawText(layout, topLeft = Offset(x + (w - layout.size.width) / 2, top))
        top += layout.size.height
    }
}
