package com.mobilegh.ui.components

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
