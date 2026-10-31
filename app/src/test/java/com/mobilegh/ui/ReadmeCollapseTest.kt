package com.mobilegh.ui

import com.mobilegh.ui.screens.README_COLLAPSED_DP
import com.mobilegh.ui.screens.readmeOverflows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadmeCollapseTest {
    @Test fun collapsedCapIsFourHundredEightyDp() {
        assertEquals(480, README_COLLAPSED_DP)
    }

    @Test fun readmeShorterThanTheCapDoesNotOverflow() {
        assertFalse(readmeOverflows(renderedPx = 0, collapsedPx = 1440))
        assertFalse(readmeOverflows(renderedPx = 1200, collapsedPx = 1440))
    }

    @Test fun readmeAtOrAboveTheCapOverflows() {
        // 折叠时 HtmlView 被限制在上限内，渲染高度恰好等于上限，也表示内容被截断
        assertTrue(readmeOverflows(renderedPx = 1440, collapsedPx = 1440))
        assertTrue(readmeOverflows(renderedPx = 5000, collapsedPx = 1440))
    }
}
