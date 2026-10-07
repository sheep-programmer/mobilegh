package com.mobilegh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.components.ClipboardLinkHost
import com.mobilegh.ui.screens.ExploreScreen
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinkNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nav = Navigator()

    @Before fun clearClipboard() = copy("")

    @After fun disposeNavigation() {
        compose.runOnUiThread { nav.dispose() }
    }

    private fun showSearch() {
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) { ExploreScreen() }
            }
        }
    }

    private fun showClipboard() {
        compose.setContent {
            MobileGhTheme {
                Scaffold(snackbarHost = { ClipboardLinkHost(nav) }) { pad -> Box(Modifier.fillMaxSize().padding(pad)) }
            }
        }
        compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
    }

    private fun copy(text: String) {
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("test", text))
        }
    }

    @Test fun keyboardSearchOpensPastedRepositoryAddress() {
        showSearch()
        val field = compose.onNodeWithText("搜索 GitHub 或打开链接")
        field.performTextInput("https://github.com/octocat/Hello-World.git")
        field.performImeAction()
        compose.runOnIdle { assertEquals(Screen.Repo("octocat", "Hello-World"), nav.top.screen) }
    }

    @Test fun searchButtonOpensIssueLink() {
        showSearch()
        compose.onNodeWithText("搜索 GitHub 或打开链接")
            .performTextInput("github.com/octocat/Hello-World/issues/42")
        compose.onNodeWithContentDescription("搜索或打开链接").performClick()
        compose.runOnIdle { assertEquals(Screen.IssueDetail("octocat", "Hello-World", 42, false), nav.top.screen) }
    }

    @Test fun clipboardPromptOpensRepositoryFromSharedText() {
        showClipboard()
        copy("推荐仓库：https://github.com/octocat/Hello-World，试试看")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("打开").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("打开").performClick()
        compose.runOnIdle { assertEquals(Screen.Repo("octocat", "Hello-World"), nav.top.screen) }
    }

    @Test fun existingClipboardLinkIsDetectedWhenTheHostFirstAppears() {
        copy("https://github.com/octocat/Hello-World")
        showClipboard()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("剪贴板仓库：octocat/Hello-World").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun clipboardIssueLinkCanBeOpenedFromItsRepositoryPage() {
        compose.runOnUiThread { nav.push(Screen.Repo("octocat", "Hello-World")) }
        showClipboard()
        copy("https://github.com/octocat/Hello-World/issues/42")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("打开").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("打开").performClick()
        compose.runOnIdle { assertEquals(Screen.IssueDetail("octocat", "Hello-World", 42, false), nav.top.screen) }
    }

    @Test fun clipboardCopiedWhileAwayIsDetectedWhenTheAppResumes() {
        showClipboard()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        copy("https://github.com/octocat/Hello-World")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("剪贴板仓库：octocat/Hello-World").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("打开").performClick()
        compose.runOnIdle { assertEquals(Screen.Repo("octocat", "Hello-World"), nav.top.screen) }
    }

    @Test fun replacingClipboardWithOrdinaryTextHidesThePrompt() {
        showClipboard()
        copy("https://github.com/octocat/Hello-World")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("打开").fetchSemanticsNodes().isNotEmpty() }
        copy("hello world")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("打开").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("打开").assertDoesNotExist()
    }
}
