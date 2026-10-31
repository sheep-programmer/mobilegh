package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.components.DownloadPill
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.RegisterScrollTop
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 回到顶部按钮与下载悬浮胶囊的交互回归：只用本地数据，不联网。 */
@RunWith(AndroidJUnit4::class)
class ScrollTopAndPillTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun LongPage() {
        val nav = Navigator()
        CompositionLocalProvider(LocalNav provides nav) {
            MobileGhTheme {
                Page("测试长页", back = false) { pad ->
                    val state = rememberLazyListState()
                    RegisterScrollTop(state)
                    LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("list"), state = state) {
                        items((0 until 120).toList()) { Text("row $it") }
                    }
                }
            }
        }
    }

    @Test fun scrollTopButtonAppearsAfterScrollingAndReturnsToTop() {
        compose.setContent { LongPage() }
        compose.onNodeWithContentDescription("回到顶部").assertDoesNotExist()
        compose.onNodeWithTag("list").performScrollToIndex(60)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("回到顶部").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("row 0").assertIsDisplayed()
        compose.onNodeWithContentDescription("回到顶部").assertDoesNotExist()
    }

    @Test fun downloadPillCanBeDraggedButStaysOnScreen() {
        compose.setContent {
            MobileGhTheme {
                Box(Modifier.fillMaxSize()) { DownloadPill(3) {} }
            }
        }
        val pill = compose.onNodeWithText("下载中 · 3")
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val before = pill.fetchSemanticsNode().boundsInRoot

        // 拖向左上：胶囊应该跟着移动
        pill.performTouchInput { swipe(center, center + Offset(-200f, -300f), 300) }
        compose.waitForIdle()
        val moved = pill.fetchSemanticsNode().boundsInRoot
        assertTrue("pill should move left", moved.left < before.left - 100f)
        assertTrue("pill should move up", moved.top < before.top - 100f)

        // 再用力拖到角落外：胶囊不能被拖出屏幕
        pill.performTouchInput { swipe(center, center + Offset(-5000f, -5000f), 300) }
        compose.waitForIdle()
        val clamped = pill.fetchSemanticsNode().boundsInRoot
        assertTrue("left edge kept on screen", clamped.left >= root.left - 1f)
        assertTrue("top edge kept on screen", clamped.top >= root.top - 1f)
        assertEquals(before.width, clamped.width, 1f)
    }
}

