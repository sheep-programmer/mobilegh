package com.mobilegh.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

/**
 * 右侧滚动进度条（所有可滚动的列表和页面共用）。
 *
 * - 滚动时显示，停下约 1.2 秒后淡出，不挡住内容；
 * - 显示期间按住滑块上下拖动可以快速定位（长讨论、长文件不用一直甩）；
 * - 直接画在列表上（绘制阶段读取滚动位置），滚动时不会触发额外的重组。
 */
private class ScrollbarGeometry {
    var trackTop = 0f
    var trackHeight = 0f
    var thumbTop = 0f
    var thumbHeight = 0f
    var width = 0f
}

/** 拖动位置换算成滑块进度（0 顶部，1 底部）：手指对准滑块中心 */
internal fun scrollbarFraction(touchY: Float, trackTop: Float, trackHeight: Float, thumbHeight: Float): Float {
    val range = trackHeight - thumbHeight
    if (range <= 0f) return 0f
    return ((touchY - trackTop - thumbHeight / 2f) / range).coerceIn(0f, 1f)
}

/**
 * 进度换算成列表位置（第几项、项内偏移）。见过的项用真实高度，没见过的用中位数估算，
 * 与 [estimateScrollProgress] 用同一套估算，拖到哪里滑块就停在哪里。
 */
internal fun estimateScrollTarget(total: Int, knownSizes: Map<Int, Int>, viewportPx: Float, fraction: Float): Pair<Int, Int> {
    if (total <= 0 || knownSizes.isEmpty()) return 0 to 0
    val sorted = knownSizes.values.sorted()
    val estimate = sorted[sorted.size / 2].coerceAtLeast(1)
    var content = 0f
    for (i in 0 until total) content += (knownSizes[i] ?: estimate).toFloat()
    val target = (content - viewportPx).coerceAtLeast(0f) * fraction.coerceIn(0f, 1f)
    var acc = 0f
    for (i in 0 until total) {
        val s = (knownSizes[i] ?: estimate).toFloat()
        if (acc + s > target) return i to (target - acc).toInt()
        acc += s
    }
    return (total - 1) to 0
}

/**
 * 公共部分：显示 / 淡出、绘制滑块、拖动。[progress] 在绘制阶段调用，返回 null 表示内容不足一屏（不显示）；
 * [onDrag] 收到 0..1 的目标进度。
 */
@Composable
private fun Modifier.scrollbar(
    scrolling: Boolean,
    progress: () -> ScrollProgress?,
    onDrag: (Float) -> Unit,
): Modifier {
    val g = Gh.c
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val active = scrolling || dragging
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(1200)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "scrollbar")
    val geo = remember { ScrollbarGeometry() }
    val color = g.fgMuted
    val margin = with(density) { 2.dp.toPx() }
    val inset = with(density) { 4.dp.toPx() }
    val thin = with(density) { 4.dp.toPx() }
    val thick = with(density) { 8.dp.toPx() }
    val minThumb = with(density) { 36.dp.toPx() }
    val touchWidth = with(density) { 32.dp.toPx() }
    val touchSlack = with(density) { 24.dp.toPx() }
    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                // 在 Initial 阶段先于列表拿到按下事件：只有落在可见的滑块上才接管，其余触摸照常交给列表
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val onThumb = alpha > 0.5f && geo.thumbHeight > 0f &&
                    down.position.x >= size.width - touchWidth &&
                    down.position.y in (geo.thumbTop - touchSlack)..(geo.thumbTop + geo.thumbHeight + touchSlack)
                if (!onThumb) return@awaitEachGesture
                down.consume()
                dragging = true
                try {
                    // 按下的位置相对滑块中心的偏差，拖动时保持不变，滑块不会一按就跳
                    val grab = down.position.y - (geo.thumbTop + geo.thumbHeight / 2f)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.consume()
                        onDrag(scrollbarFraction(change.position.y - grab, geo.trackTop, geo.trackHeight, geo.thumbHeight))
                    }
                } finally {
                    dragging = false
                }
            }
        }
        .drawWithContent {
            drawContent()
            if (alpha <= 0f) return@drawWithContent
            val p = progress() ?: run { geo.thumbHeight = 0f; return@drawWithContent }
            val track = size.height - inset * 2
            val thumb = (track * p.size).coerceIn(minThumb.coerceAtMost(track), track)
            val top = inset + (track - thumb) * p.offset
            val w = if (dragging) thick else thin
            geo.trackTop = inset
            geo.trackHeight = track
            geo.thumbTop = top
            geo.thumbHeight = thumb
            geo.width = w
            drawRoundRect(
                color = color.copy(alpha = (if (dragging) 0.85f else 0.5f) * alpha),
                topLeft = Offset(size.width - w - margin, top),
                size = Size(w, thumb),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
}

/** LazyColumn 的滚动条：加在列表自身（或列表所在的可视区域）的 Modifier 上 */
@Composable
fun Modifier.lazyScrollbar(state: LazyListState): Modifier {
    // 见过的项高度按下标记下来（滚出屏幕的项也保留），供估算使用
    val known = remember(state) { HashMap<Int, Int>() }
    var target by remember(state) { mutableStateOf<Pair<Int, Int>?>(null) }
    // 拖动目标只保留最新一个：快速拖动时不会排队滚动
    LaunchedEffect(state) {
        snapshotFlow { target }.filterNotNull().collectLatest { (index, offset) -> state.scrollToItem(index, offset) }
    }
    return scrollbar(
        scrolling = state.isScrollInProgress,
        progress = {
            val info = state.layoutInfo
            info.visibleItemsInfo.forEach { known[it.index] = it.size }
            val last = info.visibleItemsInfo.lastOrNull()
            val atEnd = last != null && last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset
            estimateScrollProgress(
                total = info.totalItemsCount,
                viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat(),
                firstIndex = state.firstVisibleItemIndex,
                firstOffsetPx = state.firstVisibleItemScrollOffset,
                knownSizes = known,
                atEnd = atEnd,
            )
        },
        onDrag = { fraction ->
            val info = state.layoutInfo
            target = estimateScrollTarget(
                info.totalItemsCount, known,
                (info.viewportEndOffset - info.viewportStartOffset).toFloat(), fraction,
            )
        },
    )
}

/** verticalScroll 页面的滚动条：放在 verticalScroll 之前，画在可视区域上 */
@Composable
fun Modifier.columnScrollbar(state: ScrollState): Modifier = scrollbar(
    scrolling = state.isScrollInProgress,
    progress = {
        val max = state.maxValue
        val viewport = state.viewportSize
        if (max <= 0 || max == Int.MAX_VALUE || viewport <= 0) null
        else ScrollProgress(
            size = (viewport.toFloat() / (viewport + max)).coerceIn(0.05f, 1f),
            offset = (state.value.toFloat() / max).coerceIn(0f, 1f),
        )
    },
    onDrag = { fraction ->
        val max = state.maxValue
        if (max in 1 until Int.MAX_VALUE) state.dispatchRawDelta(fraction * max - state.value)
    },
)

/** 带滚动条的 verticalScroll */
@Composable
fun Modifier.verticalScrollWithBar(state: ScrollState = rememberScrollState()): Modifier =
    columnScrollbar(state).verticalScroll(state)

/** 带右侧滚动条的 LazyColumn，参数与 LazyColumn 一致 */
@Composable
fun ScrollbarLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.lazyScrollbar(state),
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}
