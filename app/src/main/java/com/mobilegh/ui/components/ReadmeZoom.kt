package com.mobilegh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh
import kotlin.math.hypot

/**
 * 约束缩放后的偏移量，防止内容被拖拽移出可视区域。
 */
fun clampReadmeOffset(newOffset: Offset, currentScale: Float, size: IntSize): Offset {
    if (currentScale <= 1.02f || size.width <= 0 || size.height <= 0) {
        return Offset.Zero
    }
    val contentW = size.width.toFloat() * currentScale
    val contentH = size.height.toFloat() * currentScale
    val viewW = size.width.toFloat()
    val viewH = size.height.toFloat()

    val minX = if (contentW > viewW) viewW - contentW else 0f
    val maxX = 0f
    val clampedX = newOffset.x.coerceIn(minX, maxX)

    val minY = if (contentH > viewH) viewH - contentH else 0f
    val maxY = 0f
    val clampedY = newOffset.y.coerceIn(minY, maxY)

    return Offset(clampedX, clampedY)
}

/**
 * README 页面内直接双指捏合缩放与拖动平移手势检测器：
 * - 当 scale == 1.0f 时：单指滑动完全不拦截、不消费，父级列表垂直滚动与文字/链接点击 100% 顺畅工作；
 * - 只有检测到双指同时按下时才触发缩放，并拦截父级列表滚动；
 * - 当 scale > 1.05f 处于缩放状态时：支持单指全向拖动平移、双指继续缩放；
 * - 双指捏合缩小至 <= 1.02f 时自动归位重置。
 */
@Composable
fun Modifier.readmeInlineZoom(
    scale: Float,
    offset: Offset,
    contentSize: IntSize,
    onTransform: (newScale: Float, newOffset: Offset) -> Unit,
): Modifier {
    val currentScale by rememberUpdatedState(scale)
    val currentOffset by rememberUpdatedState(offset)
    val currentContentSize by rememberUpdatedState(contentSize)
    val currentOnTransform by rememberUpdatedState(onTransform)

    return pointerInput(Unit) {
        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)

            var isZooming = false
            var prevDist = 0f
            var prevCentroid = firstDown.position
            var localScale = currentScale
            var localOffset = currentOffset

            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }

                if (pressed.size >= 2) {
                    val p0 = pressed[0].position
                    val p1 = pressed[1].position
                    val dist = hypot(p0.x - p1.x, p0.y - p1.y)
                    val centroid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)

                    if (!isZooming) {
                        isZooming = true
                        prevDist = dist
                        prevCentroid = centroid
                        localScale = currentScale
                        localOffset = currentOffset
                    } else {
                        if (prevDist > 0f && dist > 0f) {
                            val zoomRatio = dist / prevDist
                            val pan = centroid - prevCentroid
                            val oldScale = localScale
                            val targetScale = (oldScale * zoomRatio).coerceIn(1.0f, 4.0f)
                            val anchored = centroid - (centroid - localOffset) * (targetScale / oldScale)
                            val targetOffset = clampReadmeOffset(anchored + pan, targetScale, currentContentSize)

                            if (targetScale <= 1.02f) {
                                localScale = 1.0f
                                localOffset = Offset.Zero
                                currentOnTransform(1.0f, Offset.Zero)
                            } else {
                                localScale = targetScale
                                localOffset = targetOffset
                                currentOnTransform(targetScale, targetOffset)
                            }
                        }
                        prevDist = dist
                        prevCentroid = centroid
                    }
                    event.changes.forEach { it.consume() }
                } else if (pressed.size == 1 && currentScale > 1.05f) {
                    val p = pressed[0]
                    if (isZooming) {
                        isZooming = false
                        prevCentroid = p.position
                        localOffset = currentOffset
                    } else {
                        val pan = p.position - prevCentroid
                        val targetOffset = clampReadmeOffset(localOffset + pan, currentScale, currentContentSize)
                        localOffset = targetOffset
                        currentOnTransform(currentScale, targetOffset)
                        p.consume()
                    }
                    prevCentroid = p.position
                } else {
                    isZooming = false
                    prevDist = 0f
                }
            } while (event.changes.any { it.pressed })

            if (localScale <= 1.02f && (currentScale != 1.0f || currentOffset != Offset.Zero)) {
                currentOnTransform(1.0f, Offset.Zero)
            }
        }
    }
}

/**
 * 页面内 README 原位缩放容器：
 * 将 README 内容包裹在 GPU 图层中，支持就地两指无级缩放与拖动平移。
 */
@Composable
fun ReadmeInlineZoomBox(
    scale: Float,
    offset: Offset,
    onTransform: (newScale: Float, newOffset: Offset) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var contentSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .clipToBounds()
            .readmeInlineZoom(
                scale = scale,
                offset = offset,
                contentSize = contentSize,
                onTransform = onTransform,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = offset.x
                    translationY = offset.y
                }
                .onSizeChanged { contentSize = it },
        ) {
            content()
        }
    }
}

/**
 * 缩放状态下显示的“瞬间还原”悬浮胶囊按钮。
 * 当 scale <= 1.05f 时完全不显示，不占空间、无多余按钮。
 */
@Composable
fun ReadmeZoomResetPill(
    scale: Float,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (scale <= 1.05f) return
    val g = Gh.c
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onReset),
        shape = RoundedCornerShape(24.dp),
        color = g.canvasSubtle.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, g.border),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Oc(R.drawable.oc_sync, g.accent, 16.dp)
            Text(
                text = "${(scale * 100).toInt()}% · " + tr("还原"),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = g.fg,
            )
        }
    }
}
