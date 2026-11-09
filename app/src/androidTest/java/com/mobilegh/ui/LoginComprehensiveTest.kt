package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.MobileApprovalOAuth
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.components.GitHubWebLogin
import com.mobilegh.ui.components.GitHubWebLoginHost
import com.mobilegh.ui.screens.LoginScreen
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginComprehensiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nav = Navigator()

    @After
    fun tearDown() {
        compose.runOnUiThread {
            GitHubWebLogin.close()
            nav.dispose()
        }
    }

    @Test
    fun loginScreenInitialStateHasCleanButtonsAndNoBareText() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                MobileGhTheme {
                    LoginScreen()
                }
            }
        }

        // Chips are displayed
        compose.onNodeWithText("Token 登录").assertExists()
        compose.onNodeWithText("GitHub 授权").assertExists()

        // Card content
        compose.onNodeWithText("使用 GitHub 账号").assertExists()
        compose.onNodeWithText("登录后自动启用数字审批，验证请求会弹窗并通知你。").assertExists()

        // Primary "继续" button is present and enabled
        val continueBtn = compose.onNodeWithText("继续")
        continueBtn.assertExists()
        continueBtn.assertIsEnabled()

        // Secondary "网页授权" button is present and enabled
        val webAuthBtn = compose.onNodeWithText("网页授权")
        webAuthBtn.assertExists()
        webAuthBtn.assertIsEnabled()

        // No unstyled bare text
        compose.onNodeWithText("正在等待 GitHub 授权").assertDoesNotExist()
        compose.onNodeWithText("取消授权").assertDoesNotExist()
        compose.onNodeWithText("生成 Token").assertDoesNotExist()
    }

    @Test
    fun tabSwitchingBetweenTokenAndOAuth() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                MobileGhTheme {
                    LoginScreen()
                }
            }
        }

        // Switch to Token 登录
        compose.onNodeWithText("Token 登录").performClick()
        compose.waitForIdle()

        // Token input field exists
        compose.onNodeWithText("Personal Access Token").assertExists()

        // Login button is disabled when token is blank
        val loginBtn = compose.onNodeWithText("登录")
        loginBtn.assertExists()
        loginBtn.assertIsNotEnabled()

        // Secondary button "在网页生成 Token" exists
        compose.onNodeWithText("在网页生成 Token").assertExists()

        // Enter a token
        compose.onNode(hasSetTextAction()).performTextInput("ghp_testToken1234567890")
        compose.waitForIdle()

        // Login button becomes enabled
        loginBtn.assertIsEnabled()

        // Switch back to GitHub 授权
        compose.onNodeWithText("GitHub 授权").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("使用 GitHub 账号").assertExists()
        compose.onNodeWithText("继续").assertExists().assertIsEnabled()
        compose.onNodeWithText("网页授权").assertExists().assertIsEnabled()
    }

    @Test
    fun topRightMenuAndLoginHelp() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                MobileGhTheme {
                    LoginScreen()
                }
            }
        }

        // Click top-right kebab menu
        compose.onNodeWithContentDescription("更多：检查更新、网络、日志").performClick()
        compose.waitForIdle()

        // Menu items
        compose.onNodeWithText("检查更新").assertExists()
        compose.onNodeWithText("详细日志").assertExists()
        compose.onNodeWithText("应用网络代理").assertExists()
        compose.onNodeWithText("两步验证与数字批准").assertExists()
        compose.onNodeWithText("登录帮助").assertExists()

        // Click 登录帮助
        compose.onNodeWithText("登录帮助").performClick()
        compose.waitForIdle()

        // Dialog shows
        compose.onNodeWithText("知道了").assertExists()
        compose.onNodeWithText("知道了").performClick()
        compose.waitForIdle()

        // Press back from Menu
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()

        // Back to LoginScreen
        compose.onNodeWithText("使用 GitHub 账号").assertExists()
    }

    @Test
    fun oauthLaunchAndAutoCancelOnReturn() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                MobileGhTheme {
                    LoginScreen()
                    GitHubWebLoginHost()
                }
            }
        }

        // Click 继续
        compose.onNodeWithText("继续").performClick()
        compose.waitForIdle()

        // GitHubWebLogin dialog is now visible
        assertTrue("GitHubWebLogin should have opened an auth URL", GitHubWebLogin.url != null)
        assertTrue("State should be busy waiting for browser", MobileApprovalOAuth.state.value.busy)

        // Close webview (simulating user tapping 'X' or pressing back)
        compose.runOnUiThread {
            GitHubWebLogin.close()
        }
        compose.waitForIdle()

        // After close, authorization must be automatically cancelled
        assertFalse("MobileApprovalOAuth state must be reset after dialog close", MobileApprovalOAuth.state.value.busy)
        assertFalse("Waiting for browser must be reset", MobileApprovalOAuth.state.value.waitingForBrowser)

        // "继续" button must be immediately enabled again!
        val continueBtn = compose.onNodeWithText("继续")
        continueBtn.assertExists()
        continueBtn.assertIsEnabled()

        // No naked text on screen
        compose.onNodeWithText("正在等待 GitHub 授权").assertDoesNotExist()

        // Click 继续 again to ensure seamless re-entry
        compose.onNodeWithText("继续").performClick()
        compose.waitForIdle()

        assertTrue("Second tap on 继续 should open auth URL smoothly", GitHubWebLogin.url != null)

        // Clean up
        compose.runOnUiThread {
            GitHubWebLogin.close()
        }
        compose.waitForIdle()
        assertFalse(MobileApprovalOAuth.state.value.busy)
    }

    @Test
    fun mobileApprovalOAuthDirectLifecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inst = InstrumentationRegistry.getInstrumentation()

        var url = ""
        inst.runOnMainSync {
            url = MobileApprovalOAuth.begin(context)
        }
        assertTrue(url.startsWith("https://github.com/login/oauth/authorize?"))
        assertTrue(MobileApprovalOAuth.state.value.busy)
        assertTrue(MobileApprovalOAuth.state.value.waitingForBrowser)

        // Calling begin again without manual cancel should cleanly reset without throwing check exception
        var secondUrl = ""
        inst.runOnMainSync {
            secondUrl = MobileApprovalOAuth.begin(context)
        }
        assertTrue(secondUrl.startsWith("https://github.com/login/oauth/authorize?"))
        assertTrue(MobileApprovalOAuth.state.value.busy)

        // Cancel resets everything
        inst.runOnMainSync {
            MobileApprovalOAuth.cancel(context)
        }
        assertFalse(MobileApprovalOAuth.state.value.busy)
        assertFalse(MobileApprovalOAuth.state.value.waitingForBrowser)
        assertNull(MobileApprovalOAuth.state.value.error)
    }

    @Test
    fun tokenVisibilityToggle() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                MobileGhTheme {
                    LoginScreen()
                }
            }
        }

        compose.onNodeWithText("Token 登录").performClick()
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).performTextInput("ghp_secretTokenVal")
        compose.waitForIdle()

        // Toggle eye icon to show token
        val toggleBtn = compose.onNodeWithContentDescription("显示 Token")
        toggleBtn.assertExists()
        toggleBtn.performClick()
        compose.waitForIdle()

        // Now icon says "隐藏 Token"
        compose.onNodeWithContentDescription("隐藏 Token").assertExists()
        compose.onNodeWithContentDescription("隐藏 Token").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("显示 Token").assertExists()
    }

    @Test
    fun addingAccountVariantDisplaysCorrectTitleAndBackButton() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                MobileGhTheme {
                    LoginScreen(adding = true)
                }
            }
        }
        compose.onNodeWithText("添加 GitHub 账号").assertExists()
        compose.onNodeWithContentDescription("返回").assertExists()
    }

    @Test
    fun reauthorizingAccountVariantDisplaysCorrectTitleAndBackButton() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                MobileGhTheme {
                    LoginScreen(reauthorizing = true)
                }
            }
        }
        compose.onNodeWithText("重新授权 GitHub 账号").assertExists()
        compose.onNodeWithContentDescription("返回").assertExists()
    }

    @Test
    fun webLoginHostDialogBehavior() {
        compose.setContent {
            MobileGhTheme {
                GitHubWebLoginHost()
            }
        }

        var onDismissCalled = false
        compose.runOnUiThread {
            GitHubWebLogin.request(
                customTitle = "自定义授权标题",
                urlToOpen = "https://github.com/login/oauth/authorize?test=1",
                onDismiss = { onDismissCalled = true },
            )
        }
        compose.waitForIdle()

        // Title is visible
        compose.onNodeWithText("自定义授权标题").assertExists()

        // Close button works
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.waitForIdle()

        assertTrue("onDismiss callback should be called on close", onDismissCalled)
        assertNull("URL should be cleared after close", GitHubWebLogin.url)
    }
}
