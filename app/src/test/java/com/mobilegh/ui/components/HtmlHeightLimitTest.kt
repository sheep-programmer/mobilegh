package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebView 的高度上限必须随组件宽度变化：Compose 把宽高打包进同一个 long，
 * 宽度越大，能容纳的高度就越小。同时上限不能低到把正常的长 README 截断。
 */
class HtmlHeightLimitTest {
    @Test fun phoneWidthAllowsTallReadmes() {
        // 长 README 实测可以到 13.8 万像素（coding-interview-university），
        // 以前 12000dp 的上限会把结尾整段截掉
        assertTrue(maxViewHeightPx(320) >= 140_000)
        assertTrue(maxViewHeightPx(1440) >= 140_000)
    }

    @Test fun readmeHeightMatchesReportedValue() {
        // 模拟器实测：320dp 宽（密度 1）的长 README 回报 1383282px 只是加载中的瞬时值，
        // 稳定后为 9 万像素左右；两者都必须落在一个「不截断」的上限内
        assertTrue(91_134 <= htmlMaxHeightDp(1f, 320))
        assertTrue(138_328 <= htmlMaxHeightDp(1f, 320))
    }

    @Test fun heightBudgetFollowsComposeConstraintLimit() {
        // createConstraints 要求 widthBits + heightBits <= 31，超限会抛异常
        val widths = listOf(320, 1080, 1440, 3000, 8190, 8192, 20000, 32766, 32767, 60000, 70000)
        for (w in widths) {
            val h = maxViewHeightPx(w)
            val widthBits = bits(w)
            val heightBits = bits(h)
            assertTrue("宽 $w / 高 $h 超出 Constraints 限制", widthBits + heightBits <= 31)
            assertTrue("宽 $w 的高度上限应大于 0", h > 0)
        }
    }

    @Test fun dpLimitShrinksWithDensity() {
        // JS 回报的是 CSS 像素，与 dp 相同；px = dp * density
        assertEquals(240_000, htmlMaxHeightDp(1f, 320))
        assertEquals(120_000, htmlMaxHeightDp(2f, 320))
        assertEquals(80_000, htmlMaxHeightDp(3f, 320))
    }

    private fun bits(value: Int): Int = when {
        value < 8191 -> 13
        value < 32767 -> 15
        value < 65535 -> 16
        value < 262143 -> 18
        else -> 31
    }
}
