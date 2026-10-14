package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.nav.Pager
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.platform.testTag

@RunWith(AndroidJUnit4::class)
class EmptyStateLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun centerY(node: SemanticsNodeInteraction) = node.getUnclippedBoundsInRoot().let { (it.top + it.bottom) / 2 }

    @Test fun emptyStateIsCenteredInRemainingSpaceAndOffersItsAction() {
        var clicked = 0
        compose.setContent {
            MobileGhTheme {
                Column(Modifier.fillMaxSize()) {
                    Text("顶部说明", Modifier.height(120.dp).testTag("header"))
                    EmptyState("暂无本机列表", modifier = Modifier.weight(1f).fillMaxWidth().testTag("empty"), action = "新建本机列表") { clicked++ }
                }
            }
        }
        val area = compose.onNodeWithTag("empty").getUnclippedBoundsInRoot()
        val message = centerY(compose.onNodeWithText("暂无本机列表"))
        // 图标、文字、按钮整体居中，文字中心应接近区域中心（容许图标与按钮带来的偏移）
        assertTrue("message $message not centered in ${area.top}..${area.bottom}", (message - (area.top + area.bottom) / 2).value.let { kotlin.math.abs(it) } < 48f)
        compose.onNodeWithText("新建本机列表").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicked) }
    }

    @Test fun pagedListEmptyStateFillsTheViewportBelowItsHeader() {
        var refreshed = 0
        compose.setContent {
            MobileGhTheme {
                val scope = rememberCoroutineScope()
                val pager = Pager<String>(scope) { _, _ -> refreshed++; emptyList() }
                PagedList(pager, Modifier.fillMaxSize().testTag("list"), empty = "没有仓库", header = {
                    item { Text("筛选", Modifier.height(160.dp)) }
                }) { Text(it) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("没有仓库").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val list = compose.onNodeWithTag("list").getUnclippedBoundsInRoot()
        val message = centerY(compose.onNodeWithText("没有仓库"))
        assertTrue("message $message should sit in the lower area of ${list.top}..${list.bottom}", message > list.top + 160.dp + (list.bottom - list.top - 160.dp) / 4)
        val before = refreshed
        compose.onNodeWithText("刷新").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { refreshed > before }
    }

    @Test fun approvalSettingsOfferSetupInsteadOfAnInertSwitchWithoutADevice() {
        compose.runOnUiThread { com.mobilegh.data.MobileApprovalMonitor.reload(compose.activity) }
        compose.setContent { MobileGhTheme { Column { com.mobilegh.ui.components.ApprovalSettingsRows() } } }
        compose.onNodeWithText("数字审批未启用").assertIsDisplayed()
        compose.onNodeWithText("授权启用").assertIsDisplayed()
        compose.onAllNodesWithText("自动检测 2FA 请求").assertCountEquals(0)
    }
}
