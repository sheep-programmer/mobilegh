package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.Api
import com.mobilegh.data.Net
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.screens.ShareIssueScreen
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIssueUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var previousProxy = ""
    private var previousCache: File? = null
    private var server: LoopbackApi? = null

    @Before fun setUp() {
        compose.runOnUiThread { Session.signIn("local-fixture-token", User(login = "octo")) }
    }

    @After fun tearDown() {
        compose.runOnUiThread {
            if (Session.login == "octo") Session.signOut()
            Net.apiProxy = previousProxy
            previousCache?.let { Api.cacheDir = it }
        }
        server?.close()
    }

    @Test fun recreationAfterLoadingAnotherPageRebuildsTheWholeRepositoryList() {
        previousProxy = Net.apiProxy
        previousCache = Api.cacheDir
        Api.cacheDir = File(compose.activity.cacheDir, "share-issue-${System.nanoTime()}").apply { mkdirs() }
        val api = LoopbackApi { _, path, _ ->
            when {
                path.endsWith("page=1") -> 200 to """[{"id":1,"name":"first","full_name":"octo/first","owner":{"login":"octo"},"has_issues":true}]"""
                path.endsWith("page=2") -> 200 to """[{"id":2,"name":"second","full_name":"octo/second","owner":{"login":"octo"},"has_issues":true}]"""
                else -> 500 to """{"message":"unexpected request"}"""
            }
        }
        server = api
        Net.apiProxy = api.base
        val nav = Navigator()
        nav.push(Screen.ShareIssue("restoration-test"))
        val content: @Composable () -> Unit = {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
                    ShareIssueScreen("restoration-test")
                }
            }
        }
        try {
            compose.setContent(content)
            waitForText("octo/first")
            compose.onNodeWithText("加载更多").performClick()
            waitForText("octo/second")

            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { it.setContent(content = content) }

            waitForText("octo/first")
            compose.onNodeWithText("octo/first").assertIsDisplayed()
            compose.onNodeWithText("octo/second").assertDoesNotExist()
            compose.onNodeWithText("加载更多").performClick()
            waitForText("octo/second")
            compose.onNodeWithText("octo/first").assertIsDisplayed()
            compose.onNodeWithText("octo/second").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
