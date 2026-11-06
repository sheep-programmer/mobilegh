package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListScrollProgressTest {
    // 10 项：前 4 项各 100px，第 4 项是 2000px 的 README，其余 5 项未见过（估算为中位数 100px）
    private val known = mapOf(0 to 100, 1 to 100, 2 to 100, 3 to 100, 4 to 2000)

    @Test fun noKnownItemsOrContentThatFitsShowsNoBar() {
        assertNull(estimateScrollProgress(10, 500f, 0, 0, emptyMap(), atEnd = false))
        // 两项共 200px，一屏 500px 放得下，不需要滚动条
        assertNull(estimateScrollProgress(2, 500f, 0, 0, mapOf(0 to 100, 1 to 100), atEnd = false))
    }

    @Test fun topOfListIsExactlyZero() {
        val p = estimateScrollProgress(10, 500f, 0, 0, known, atEnd = false)!!
        assertEquals(0.0, p.offset.toDouble(), 0.0)
    }

    @Test fun bigReadmeDoesNotPushTheThumbToTheBottom() {
        // 内容估算：400 + 2000 + 5 × 100 = 2900，可滚动距离 2400。滚到 README 顶部时应在 400 / 2400 ≈ 0.167
        val p = estimateScrollProgress(10, 500f, 4, 0, known, atEnd = false)!!
        assertEquals(400.0 / 2400.0, p.offset.toDouble(), 0.001)
        assertEquals(500.0 / 2900.0, p.size.toDouble(), 0.001)
    }

    @Test fun lastItemFullyVisibleIsTheBottom() {
        val p = estimateScrollProgress(10, 500f, 7, 0, known, atEnd = true)!!
        assertEquals(1.0, p.offset.toDouble(), 0.0)
    }
}
