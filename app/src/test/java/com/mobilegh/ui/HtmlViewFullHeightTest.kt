package com.mobilegh.ui

import com.mobilegh.ui.components.htmlContentHeightDp
import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlViewFullHeightTest {
    @Test fun unloadedOrNegativeContentKeepsMinimumHeight() {
        // 首次组合时还没有 JS 回报，高度从 24dp 起步，避免 WebView 变成 0 高度
        assertEquals(24, htmlContentHeightDp(0, 90_000))
        assertEquals(24, htmlContentHeightDp(-5, 90_000))
    }

    @Test fun normalContentHeightIsUsedAsIs() {
        // 长 README 展开或折叠时，布局高度都是完整内容高度（1440dp 远高于折叠上限 480dp）
        assertEquals(1440, htmlContentHeightDp(1440, 90_000))
        assertEquals(480, htmlContentHeightDp(480, 90_000))
    }

    @Test fun abnormalHeightIsCappedAtMaxProtection() {
        // 循环图片等异常页面会回报几十万像素，必须夹在上限内，否则 Compose 约束会溢出
        assertEquals(91_134, htmlContentHeightDp(1_383_282, 91_134))
    }
}
