package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.Api
import com.mobilegh.data.Net
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.screens.IssueHierarchyPicker
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IssueHierarchyPickerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var previousProxy = ""
    private var previousCache: File? = null
    private var server: LoopbackApi? = null
    private var nav: Navigator? = null

    @After fun restore() {
        compose.runOnUiThread {
            if (Session.login == "mobilegh-ui-test") Session.signOut()
            Net.apiProxy = previousProxy
            previousCache?.let { Api.cacheDir = it }
            nav?.dispose()
        }
        server?.close()
    }

    @Test fun loadMoreAppendsTheSecondSearchPageWithoutDuplicates() {
        val fixture = LoopbackApi { _, path, _ ->
            when {
                path.contains("/search/issues?") && path.contains("page=1") -> 200 to searchPage(1, 30, total = 31)
                path.contains("/search/issues?") && path.contains("page=2") -> 200 to searchPage(30, 31, total = 31)
                else -> 404 to """{"message":"unexpected $path"}"""
            }
        }
        server = fixture
        previousProxy = Net.apiProxy
        previousCache = Api.cacheDir
        Api.cacheDir = File(compose.activity.cacheDir, "issue-hierarchy-picker-ui").apply { mkdirs() }
        Net.apiProxy = fixture.base
        val navigator = Navigator().also { nav = it }
        compose.runOnUiThread {
            Session.signIn("local-fixture-token", User(login = "mobilegh-ui-test"))
            navigator.push(Screen.IssueDetail("octo", "hello", 1, false))
        }
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides navigator, LocalEntry provides navigator.top) {
                    IssueHierarchyPicker(
                        owner = "octo", name = "hello", currentNodeId = "I_current",
                        existing = emptyList(), title = "设置父 Issue", busy = false,
                        onDismiss = {}, onPick = {},
                    )
                }
            }
        }

        compose.onNodeWithText("搜索").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Issue 1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("加载更多"))
        compose.onNodeWithText("加载更多").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("加载更多", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Issue 31"))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Issue 31", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("加载更多").assertDoesNotExist()

        assertEquals(1, fixture.requests.count { it.contains("page=1") })
        assertEquals(1, fixture.requests.count { it.contains("page=2") })
    }

    private fun searchPage(start: Int, end: Int, total: Int): String {
        val items = (start..end).joinToString(",") { number ->
            """{"id":$number,"node_id":"I_$number","number":$number,"title":"Issue $number","state":"open"}"""
        }
        return """{"total_count":$total,"incomplete_results":false,"items":[$items]}"""
    }
}
