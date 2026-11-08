package com.mobilegh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobilegh.R
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh
import kotlin.math.hypot

/**
 * 监听两指缩放手势进入缩放视图。
 * 单指操作完全不拦截，保证列表垂直滚动不受任何影响；只有检测到两指捏合张开时才触发。
 */
fun Modifier.onPinchToZoom(onPinch: (scale: Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        var startDist = 0f
        var triggered = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.size >= 2) {
                val p0 = event.changes[0].position
                val p1 = event.changes[1].position
                val dist = hypot(p0.x - p1.x, p0.y - p1.y)
                if (startDist <= 0f) {
                    startDist = dist
                } else if (!triggered && startDist > 0f) {
                    val ratio = dist / startDist
                    if (ratio > 1.15f) {
                        triggered = true
                        onPinch(ratio)
                    }
                }
            } else {
                startDist = 0f
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * README 全屏缩放查看弹窗：
 * 支持两指无级缩放（1.0x ~ 4.0x）、双击快速缩放/重置、全向拖动平移以及底部快捷缩放控制条。
 */
@Composable
fun ReadmeZoomDialog(
    html: String,
    baseUrl: String,
    initialScale: Float = 1.5f,
    onDismiss: () -> Unit,
) {
    val g = Gh.c
    var scale by remember { mutableFloatStateOf(initialScale.coerceIn(1.0f, 4.0f)) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(newOffset: Offset, currentScale: Float): Offset {
        val viewW = viewportSize.width.toFloat()
        val viewH = viewportSize.height.toFloat()
        val contentW = contentSize.width.toFloat() * currentScale
        val contentH = contentSize.height.toFloat() * currentScale

        val minX = if (contentW > viewW) viewW - contentW else 0f
        val maxX = 0f
        val clampedX = newOffset.x.coerceIn(minX, maxX)

        val minY = if (contentH > viewH) viewH - contentH else 0f
        val maxY = 0f
        val clampedY = newOffset.y.coerceIn(minY, maxY)

        return Offset(clampedX, clampedY)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(g.canvas)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶部栏
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(g.canvasSubtle)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OcButton(R.drawable.oc_x, onDismiss, g.fg)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tr("README 缩放查看"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = g.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(scale * 100).toInt()}%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = g.fgMuted,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                HDivider()

                // 主缩放视口
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { viewportSize = it }
                        .pointerInput(viewportSize, contentSize) {
                            detectTapGestures(
                                onDoubleTap = { p ->
                                    if (scale > 1.15f) {
                                        scale = 1.0f
                                        offset = clampOffset(Offset.Zero, 1.0f)
                                    } else {
                                        val newScale = 2.0f
                                        val anchored = p - (p - offset) * (newScale / scale)
                                        scale = newScale
                                        offset = clampOffset(anchored, newScale)
                                    }
                                },
                            )
                        }
                        .pointerInput(viewportSize, contentSize) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val oldScale = scale
                                val newScale = (oldScale * zoom).coerceIn(1.0f, 4.0f)
                                val anchored = centroid - (centroid - offset) * (newScale / oldScale)
                                scale = newScale
                                offset = clampOffset(anchored + pan, newScale)
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = offset.x
                                translationY = offset.y
                            }
                            .onSizeChanged { contentSize = it }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        HtmlView(
                            html = html,
                            baseUrl = baseUrl,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // 底部悬浮控制条
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(g.canvasSubtle.copy(alpha = 0.95f))
                    .border(1.dp, g.border, RoundedCornerShape(24.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "−",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (scale > 1.0f) g.fg else g.fgMuted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(enabled = scale > 1.0f) {
                            val next = (scale - 0.25f).coerceAtLeast(1.0f)
                            scale = next
                            offset = clampOffset(offset, next)
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Text(
                    "${(scale * 100).toInt()}%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = g.fg,
                )
                Text(
                    "+",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (scale < 4.0f) g.fg else g.fgMuted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(enabled = scale < 4.0f) {
                            val next = (scale + 0.25f).coerceAtMost(4.0f)
                            scale = next
                            offset = clampOffset(offset, next)
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                if (scale != 1.0f) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(16.dp)
                            .background(g.border),
                    )
                    Text(
                        tr("重置"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = g.accent,
                        modifier = Modifier
                            .clickable {
                                scale = 1.0f
                                offset = clampOffset(Offset.Zero, 1.0f)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
