package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.Api
import com.mobilegh.data.GitHub
import com.mobilegh.data.Net
import com.mobilegh.data.Session
import com.mobilegh.data.SubscriptionSettings
import com.mobilegh.data.User
import com.mobilegh.data.obj
import com.mobilegh.data.str
import com.mobilegh.i18n.AppLanguage
import com.mobilegh.i18n.I18n
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.nav.UserKind
import com.mobilegh.ui.components.fmtCount
import com.mobilegh.ui.screens.RepoScreen
import com.mobilegh.ui.screens.UsersScreen
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryWatchersTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nav = Navigator()
    private var previousProxy = ""
    private var previousLanguage = AppLanguage.Chinese
    private var previousAccount: String? = null
    private val fixtureLogin = "mobilegh-watch-test-fixture"

    @Before fun saveSettings() = compose.runOnUiThread {
        previousProxy = Net.apiProxy
        previousLanguage = I18n.choice
        previousAccount = Session.login
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

    @Test fun watchersUseGraphQlAndFollowItsCursorEvenForAShortPage() {
        LoopbackApi { method, path, body ->
            check(method == "POST" && path == "/graphql")
            val request = Api.plain.parseToJsonElement(body)
            check(request.str("query")?.contains("watchers(first:") == true)
            val variables = request.obj("variables")
            check(variables.str("owner") == "octocat" && variables.str("name") == "hello")
            check(variables.str("first") == "30")
            when (variables.str("after")) {
                null -> 200 to watcherResponse("first-watcher", "page-two")
                "page-two" -> 200 to watcherResponse("second-watcher")
                else -> error("Unexpected watchers cursor")
            }
        }.use { server ->
            showWatchers(server)
            await("second-watcher")
            compose.onNodeWithText("first-watcher").assertIsDisplayed()
            compose.onNodeWithText("second-watcher").assertIsDisplayed()
            compose.onNodeWithText("重试").assertDoesNotExist()
            assertEquals(listOf("POST /graphql", "POST /graphql"), server.requests)
        }
    }

    @Test fun graphQlErrorWithPartialDataIsNotPresentedAsAnEmptyList() {
        LoopbackApi { method, path, _ ->
            check(method == "POST" && path == "/graphql")
            200 to """{"data":{"repository":null},"errors":[{"type":"NOT_FOUND","message":"Could not resolve repository"}]}"""
        }.use { server ->
            showWatchers(server)
            await("Could not resolve repository")
            compose.onNodeWithText("重试").assertIsDisplayed()
            compose.onNodeWithText("暂无仓库关注者").assertDoesNotExist()
        }
    }

    @Test fun rateLimitErrorRemainsRetryable() {
        LoopbackApi { method, path, _ ->
            check(method == "POST" && path == "/graphql")
            403 to """{"message":"API rate limit exceeded"}"""
        }.use { server ->
            showWatchers(server)
            await("API 调用次数已达上限", substring = true)
            compose.onNodeWithText("重试").assertIsDisplayed()
            assertEquals(listOf("POST /graphql"), server.requests)
        }
    }

    @Test fun emptyWatcherListIsNotAnError() {
        LoopbackApi { method, path, _ ->
            check(method == "POST" && path == "/graphql")
            200 to watcherResponse()
        }.use { server ->
            showWatchers(server)
            await("暂无仓库关注者")
            compose.onNodeWithText("暂无仓库关注者").assertIsDisplayed()
            compose.onNodeWithText("重试").assertDoesNotExist()
        }
    }

    @Test fun anEmptyStarListWithAConfirmedStarCountShowsThePolicyNotice() {
        LoopbackApi { method, path, _ ->
            check(method == "GET")
            when (path) {
                "/repos/octocat/hello/stargazers?per_page=30&page=1" -> 200 to "[]"
                "/repos/octocat/hello" -> 200 to """{"stargazers_count":128}"""
                else -> error("Unexpected request $path")
            }
        }.use { server ->
            showUsers(server, UserKind.Stargazers)
            await("128 Star", substring = true)
            compose.onNodeWithText("128 Star", substring = true).assertIsDisplayed()
            compose.onNodeWithText("暂无 Star 用户").assertDoesNotExist()
            compose.onNodeWithText("重试").assertDoesNotExist()
        }
    }

    @Test fun livePublicRepositoryWatchersAndPersonalSubscription() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("watchersLive") == "true")
        val credential = File(compose.activity.cacheDir, "watchers-live-credential")
        val token = credential.takeIf(File::isFile)?.let { file ->
            try { file.readText().trim() } finally { file.delete() }
        }
        compose.runOnUiThread {
            Net.apiProxy = ""
            if (!token.isNullOrBlank()) Session.signIn(token, User(login = fixtureLogin))
        }
        check(Session.token != null) { "Sign in to the emulator before running the live Watch test" }
        val repository = runBlocking { GitHub.repo("octocat", "Hello-World", force = true) }
        assertTrue(!repository.isPrivate && repository.permissions?.admin != true)
        val count = "${fmtCount(checkNotNull(repository.subscribersCount))} 人关注"
        val firstPage = runBlocking { GitHub.watchers("octocat", "Hello-World", after = null, force = true) }
        val first = firstPage.users.first()
        val secondPage = runBlocking { GitHub.watchers("octocat", "Hello-World", checkNotNull(firstPage.nextCursor), force = true) }
        assertTrue(secondPage.users.any { candidate -> firstPage.users.none { it.login == candidate.login } })
        val subscription = runBlocking { SubscriptionSettings.current("octocat", "Hello-World") }
        compose.runOnUiThread { nav.push(Screen.Repo("octocat", "Hello-World")) }
        mount()

        await(count)
        compose.onNodeWithText(count).performClick()
        await(first.login)
        compose.onNodeWithText(first.login).assertIsDisplayed()
        compose.onNodeWithText("重试").assertDoesNotExist()

        compose.runOnUiThread { nav.pop() }
        await(subscription.title)
        compose.onNodeWithText(subscription.title).performClick()
        await("仓库订阅")
        await("接收这个仓库的所有活动通知")
        compose.onNodeWithText("保存").assertIsDisplayed()

        val owned = runBlocking { GitHub.repo("sheep-programmer", "mobilegh", force = true) }
        assertTrue(owned.permissions?.admin == true)
        val ownCount = "${fmtCount(checkNotNull(owned.subscribersCount))} 人关注"
        compose.runOnUiThread { nav.replace(Screen.Repo("sheep-programmer", "mobilegh")) }
        await(ownCount)
        compose.onNodeWithText(ownCount).performClick()
        if (owned.subscribersCount == 0) {
            await("暂无仓库关注者")
        } else {
            val ownWatcher = runBlocking { GitHub.watchers("sheep-programmer", "mobilegh", null, force = true) }.users.first()
            await(ownWatcher.login)
        }
        compose.onNodeWithText("重试").assertDoesNotExist()
    }

    private fun watcherResponse(login: String? = null, next: String? = null): String {
        val nodes = login?.let { """{"login":"$it","name":null,"avatarUrl":""}""" }.orEmpty()
        val cursor = next?.let { "\"$it\"" } ?: "null"
        return """{"data":{"repository":{"watchers":{"nodes":[$nodes],"pageInfo":{"hasNextPage":${next != null},"endCursor":$cursor}}}}}"""
    }

    private fun showWatchers(server: LoopbackApi) = showUsers(server, UserKind.Watchers)

    private fun showUsers(server: LoopbackApi, kind: UserKind) {
        compose.runOnUiThread {
            Net.apiProxy = server.base
            if (Session.token != null) Session.signIn("watcher-loopback-fixture", User(login = fixtureLogin))
            nav.push(Screen.Users(kind, "octocat", "hello"))
        }
        mount()
    }

    private fun mount() = compose.setContent {
        CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
            MobileGhTheme {
                when (val screen = nav.top.screen) {
                    is Screen.Repo -> RepoScreen(screen.owner, screen.name)
                    is Screen.Users -> UsersScreen(screen.kind, screen.a, screen.b)
                    else -> error("Unexpected screen $screen")
                }
            }
        }
    }

    private fun await(text: String, substring: Boolean = false) = compose.waitUntil(25_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
}
