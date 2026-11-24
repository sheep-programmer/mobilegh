package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.Net
import com.mobilegh.data.Repo
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.i18n.AppLanguage
import com.mobilegh.i18n.I18n
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.components.RepoItem
import com.mobilegh.ui.screens.ExploreScreen
import com.mobilegh.ui.screens.RepoScreen
import com.mobilegh.ui.theme.MobileGhTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowseLoadingOrderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nav = Navigator()
    private val fixtureLogin = "mobilegh-browse-fixture"
    private var previousProxy = ""
    private var previousAccount: String? = null
    private var previousLanguage = AppLanguage.Chinese

    @Before fun saveSettings() = compose.runOnUiThread {
        previousProxy = Net.apiProxy
        previousAccount = Session.login
        previousLanguage = I18n.choice
        I18n.select(AppLanguage.Chinese)
    }

    @After fun restoreSettings() = compose.runOnUiThread {
        nav.dispose()
        Net.apiProxy = previousProxy
        if (Session.login == fixtureLogin) {
            Session.signOut()
            previousAccount?.let(Session::switchTo)
        }
        I18n.select(previousLanguage)
    }

    @Test fun repositoryStartsWithMetadataAndReadmeAndOnlyReadsPullCountOnce() {
        val releaseMetadata = CountDownLatch(1)
        val repository = "/repos/loading-fixture/repository"
        val preview = Repo(name = "repository", owner = User(login = "loading-fixture"), description = "Description from the list")
        LoopbackApi { _, path, _ ->
            when (path) {
                repository -> {
                    check(releaseMetadata.await(30, TimeUnit.SECONDS))
                    200 to """{"id":7032,"name":"repository","full_name":"loading-fixture/repository","description":"First screen fixture","owner":{"login":"loading-fixture"}}"""
                }
                "$repository/readme" -> 200 to "<p>README ready before repository metadata</p>"
                "$repository/languages" -> 200 to "{}"
                else -> 200 to if (path.startsWith("$repository/pulls")) "[]" else "{}"
            }
        }.use { server ->
            compose.runOnUiThread { Net.apiProxy = server.base }
            mount {
                when (val page = nav.top.screen) {
                    is Screen.Repo -> RepoScreen(page.owner, page.name)
                    else -> RepoItem(preview)
                }
            }
            try {
                compose.onNodeWithText("repository", substring = true).performClick()
                compose.waitUntil(10_000) { "GET $repository" in server.requests && "GET $repository/readme" in server.requests }
                compose.onNodeWithText("Description from the list").assertIsDisplayed()
                compose.onNodeWithText("浏览代码").assertIsDisplayed()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("README ready before repository metadata").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(setOf("GET $repository", "GET $repository/readme"), server.requests.toSet())
            } finally {
                releaseMetadata.countDown()
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("First screen fixture").fetchSemanticsNodes().isNotEmpty() &&
                    server.requests.any { it.startsWith("GET $repository/pulls?") }
            }
            assertEquals(1, server.requests.count { it.startsWith("GET $repository/pulls?") })
        }
    }

    @Test fun exploreOnlyLoadsTheSelectedFeedAndRefreshesOnce() {
        LoopbackApi { _, path, _ ->
            when {
                path.startsWith("/search/repositories?") -> 200 to """{"total_count":1,"items":[{"id":8021,"name":"fixture","full_name":"loopback/fixture","owner":{"login":"loopback"},"description":"Visible recommendation"}]}"""
                path.contains("/received_events?") -> 200 to "[]"
                else -> 404 to "{}"
            }
        }.use { server ->
            compose.runOnUiThread {
                Net.apiProxy = server.base
                Session.signIn("browse-loopback-fixture", User(login = fixtureLogin))
            }
            mount { ExploreScreen() }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Visible recommendation").fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(server.requests.any { it.contains("/received_events?") })
            compose.onNodeWithText("换一批").performClick()
            compose.waitUntil(10_000) { server.requests.count { it.startsWith("GET /search/repositories?") } >= 2 }
            compose.onNodeWithText("动态").performClick()
            compose.waitUntil(10_000) { server.requests.any { it.contains("/received_events?") } }
            assertEquals(2, server.requests.count { it.startsWith("GET /search/repositories?") })
        }
    }

    private fun mount(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
            MobileGhTheme { content() }
        }
    }
}
