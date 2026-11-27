package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.Api
import com.mobilegh.data.Net
import com.mobilegh.data.ProcessRefreshClaims
import com.mobilegh.data.Session
import com.mobilegh.data.StarListCache
import com.mobilegh.data.User
import com.mobilegh.i18n.AppLanguage
import com.mobilegh.i18n.I18n
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.screens.StarTab
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StarredRepoCacheDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var nav = Navigator()
    private val fixtureLogin = "mobilegh-star-cache-fixture"
    private var previousProxy = ""
    private var previousAccount: String? = null
    private var previousLanguage = AppLanguage.Chinese

    @Before fun saveSettings() = compose.runOnUiThread {
        previousProxy = Net.apiProxy
        previousAccount = Session.login
        previousLanguage = I18n.choice
        I18n.select(AppLanguage.Chinese)
        File(Api.cacheDir, "repo-feeds").deleteRecursively()
        StarListCache.clearForTest()
        ProcessRefreshClaims.clearForTest()
    }

    @After fun restoreSettings() = compose.runOnUiThread {
        nav.dispose()
        Net.apiProxy = previousProxy
        File(Api.cacheDir, "repo-feeds").deleteRecursively()
        StarListCache.clearForTest()
        ProcessRefreshClaims.clearForTest()
        if (Session.login == fixtureLogin) {
            Session.signOut()
            previousAccount?.let(Session::switchTo)
        }
        I18n.select(previousLanguage)
    }

    private fun starFeedGraphqlResponse(repoName: String, id: Long): String = """
        {
          "data": {
            "viewer": { "login": "$fixtureLogin" },
            "repositoryOwner": {
              "__typename": "User",
              "login": "$fixtureLogin",
              "starredRepositories": {
                "isOverLimit": false,
                "pageInfo": { "hasNextPage": false, "endCursor": null },
                "edges": [
                  {
                    "starredAt": "2026-11-26T00:00:00Z",
                    "node": {
                      "databaseId": $id,
                      "name": "$repoName",
                      "nameWithOwner": "octo/$repoName",
                      "description": "Cached star repository fixture",
                      "isPrivate": false,
                      "stargazerCount": 42,
                      "forkCount": 3,
                      "updatedAt": "2026-11-26T00:00:00Z",
                      "primaryLanguage": { "name": "Kotlin", "color": "#A97BFF" },
                      "owner": { "login": "octo", "avatarUrl": "", "__typename": "User" }
                    }
                  }
                ]
              }
            }
          }
        }
    """.trimIndent()

    @Test fun coldStartShowsDiskCachedStarListImmediatelyWithSilentRefreshStatus() {
        val calls = AtomicInteger(0)
        val releaseSecondLaunch = CountDownLatch(1)
        val secondLaunchStarted = CountDownLatch(1)

        LoopbackApi { _, path, _ ->
            if (path == "/graphql") {
                val n = calls.incrementAndGet()
                if (n == 1) {
                    200 to starFeedGraphqlResponse("first-star", 101L)
                } else {
                    secondLaunchStarted.countDown()
                    releaseSecondLaunch.await(15, TimeUnit.SECONDS)
                    200 to starFeedGraphqlResponse("updated-star", 202L)
                }
            } else {
                200 to "{}"
            }
        }.use { server ->
            var generation by mutableIntStateOf(0)
            compose.runOnUiThread {
                Net.apiProxy = server.base
                Session.signIn("token-star-cache", User(login = fixtureLogin))
            }
            compose.setContent {
                MobileGhTheme {
                    androidx.compose.runtime.key(generation) {
                        CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
                            StarTab()
                        }
                    }
                }
            }

            // 1. 首次冷加载：从网络取回并写入磁盘缓存
            compose.waitUntil(8_000) {
                compose.onAllNodesWithText("first-star", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("first-star", substring = true).assertIsDisplayed()

            // 等待磁盘写入落盘
            val feedDir = File(Api.cacheDir, "repo-feeds")
            compose.waitUntil(5_000) {
                feedDir.listFiles()?.any { it.isFile && it.name.startsWith("repo-feed-") } == true
            }

            // 2. 模拟冷重启：清空内存快照和进程刷新标记、重建 Navigator，并阻塞第 2 次网络请求
            compose.runOnUiThread {
                nav.dispose()
                nav = Navigator()
                StarListCache.clearForTest()
                ProcessRefreshClaims.clearForTest()
                generation++
            }

            // 无需等待网络返回，磁盘缓存的 first-star 必须立即可见，且同时显示静默刷新状态
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("first-star", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("first-star", substring = true).assertIsDisplayed()
            assertTrue("Expected silent refresh to hit /graphql (calls=${calls.get()})", secondLaunchStarted.await(8, TimeUnit.SECONDS))
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("paged-list-silent-refresh").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("正在后台刷新…").assertIsDisplayed()

            // 3. 放行静默刷新：列表更新为最新数据，静默刷新状态条消失
            releaseSecondLaunch.countDown()
            compose.waitUntil(8_000) {
                compose.onAllNodesWithText("updated-star", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("updated-star", substring = true).assertIsDisplayed()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("paged-list-silent-refresh").fetchSemanticsNodes().isEmpty()
            }
        }
    }
}
