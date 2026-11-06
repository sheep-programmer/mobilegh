package com.mobilegh.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.delay

/** 滑块占轨道的比例（size）和滑块在可滚动范围内的位置（offset，0 为顶部，1 为底部）。 */
internal data class ScrollProgress(val size: Float, val offset: Float)

/**
 * 估算滚动进度。[knownSizes] 是已经见过的项的真实高度（下标 → px）：见过的项用真实高度，没见过的项用已知项的中位数。
 * 不用平均值：一段很长的 README 会把平均值拉高，整个列表被算得过长，滑块就一下子跳到底部。
 * 滑块到顶或到底时直接贴边（最后一项完整可见即视为到底），不依赖估算。内容不足一屏时返回 null。
 */
internal fun estimateScrollProgress(
    total: Int,
    viewportPx: Float,
    firstIndex: Int,
    firstOffsetPx: Int,
    knownSizes: Map<Int, Int>,
    atEnd: Boolean,
): ScrollProgress? {
    if (total <= 0 || viewportPx <= 0f || knownSizes.isEmpty()) return null
    val sorted = knownSizes.values.sorted()
    val estimate = sorted[sorted.size / 2].coerceAtLeast(1)
    var before = 0f
    var content = 0f
    // 按项逐个累加：列表项通常不多，这里的开销可以忽略
    for (i in 0 until total) {
        val s = (knownSizes[i] ?: estimate).toFloat()
        if (i < firstIndex) before += s
        content += s
    }
    val scrollable = content - viewportPx
    if (scrollable <= 0f) return null
    val offset = when {
        atEnd -> 1f
        firstIndex == 0 && firstOffsetPx <= 0 -> 0f
        else -> ((before + firstOffsetPx) / scrollable).coerceIn(0f, 1f)
    }
    return ScrollProgress(size = (viewportPx / content).coerceIn(0.05f, 1f), offset = offset)
}

/**
 * 列表右侧的滚动进度条：滚动时显示，停下约 1 秒后淡出，不挡住内容。
 * 在列表所在的 Box 里调用，贴着右边缘显示。
 */
@Composable
fun BoxScope.ListScrollIndicator(state: LazyListState, modifier: Modifier = Modifier) {
    val g = Gh.c
    val scrolling = state.isScrollInProgress
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling) {
        if (scrolling) visible = true
        else {
            delay(1000)
            visible = false
        }
    }
    // 见过的项高度按下标记下来（滚出屏幕的项也保留），供估算使用
    val known = remember(state) { HashMap<Int, Int>() }
    val info = state.layoutInfo
    info.visibleItemsInfo.forEach { known[it.index] = it.size }
    val last = info.visibleItemsInfo.lastOrNull()
    val atEnd = last != null && last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset
    val progress = estimateScrollProgress(
        total = info.totalItemsCount,
        viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat(),
        firstIndex = state.firstVisibleItemIndex,
        firstOffsetPx = state.firstVisibleItemScrollOffset,
        knownSizes = known,
        atEnd = atEnd,
    ) ?: return
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(),
        modifier = modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
        BoxWithConstraints(Modifier.fillMaxHeight().padding(vertical = 8.dp).width(8.dp)) {
            val thumb = maxHeight * progress.size
            Box(
                Modifier.align(Alignment.TopEnd)
                    .offset(y = (maxHeight - thumb) * progress.offset)
                    .width(4.dp).height(thumb)
                    .clip(RoundedCornerShape(2.dp))
                    .background(g.fgMuted.copy(alpha = 0.5f)),
            )
        }
    }
}
