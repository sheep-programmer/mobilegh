package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollbarMathTest {
    @Test fun dragFractionCentersThumbAndClamps() {
        // 轨道 0..1000，滑块高 200：手指在 100（滑块中心在顶）→ 0；在 900 → 1；在 500 → 0.5
        assertEquals(0f, scrollbarFraction(100f, 0f, 1000f, 200f), 1e-4f)
        assertEquals(1f, scrollbarFraction(900f, 0f, 1000f, 200f), 1e-4f)
        assertEquals(0.5f, scrollbarFraction(500f, 0f, 1000f, 200f), 1e-4f)
        assertEquals(0f, scrollbarFraction(-50f, 0f, 1000f, 200f), 1e-4f)
        assertEquals(0f, scrollbarFraction(300f, 0f, 200f, 200f), 1e-4f)
    }

    @Test fun scrollTargetUsesKnownAndEstimatedSizes() {
        // 10 项每项 100px，视口 300：可滚动 700。拖到一半 → 350px → 第 3 项偏移 50
        val known = (0 until 10).associateWith { 100 }
        assertEquals(3 to 50, estimateScrollTarget(10, known, 300f, 0.5f))
        assertEquals(0 to 0, estimateScrollTarget(10, known, 300f, 0f))
        // 只见过前两项：其余按中位数（100）估算
        assertEquals(3 to 50, estimateScrollTarget(10, mapOf(0 to 100, 1 to 100), 300f, 0.5f))
        assertEquals(0 to 0, estimateScrollTarget(0, emptyMap(), 300f, 0.5f))
    }
}
