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
 * 按可见项的平均高度估算滚动进度：列表项高度差异很大时位置是近似值，但滑块能反映大致进度。
 * 内容不足一屏时返回 null，不显示滚动条。
 */
internal fun scrollProgress(state: LazyListState): ScrollProgress? {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (info.totalItemsCount == 0 || visible.isEmpty() || viewport <= 0f) return null
    val averageItem = visible.map { it.size }.average().toFloat().coerceAtLeast(1f)
    val content = info.totalItemsCount * averageItem
    val scrollable = content - viewport
    if (scrollable <= 0f) return null
    val position = state.firstVisibleItemIndex * averageItem + state.firstVisibleItemScrollOffset
    return ScrollProgress(
        size = (viewport / content).coerceIn(0.05f, 1f),
        offset = (position / scrollable).coerceIn(0f, 1f),
    )
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
    val progress = scrollProgress(state) ?: return
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
