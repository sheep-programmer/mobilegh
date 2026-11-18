package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.Api
import com.mobilegh.data.ForkSort
import com.mobilegh.data.Net
import com.mobilegh.data.SearchType
import com.mobilegh.data.Session
import com.mobilegh.data.obj
import com.mobilegh.data.str
import com.mobilegh.i18n.tr
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.Screen
import com.mobilegh.ui.screens.ForkListScreen
import com.mobilegh.ui.screens.JobLogScreen
import com.mobilegh.ui.screens.PullFilesScreen
import com.mobilegh.ui.screens.SearchScreen
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkflowImprovementsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var server: LoopbackApi? = null
    private var previousProxy: String? = null
    private var nav: Navigator? = null

    @After fun tearDown() {
        compose.runOnUiThread {
            nav?.dispose()
            previousProxy?.let { Net.apiProxy = it }
        }
        server?.close()
    }

    @Test fun applyingAQualifierDoesNotSubmitSearch() {
        val api = serve { _, path, _ ->
            if (path.startsWith("/search/issues")) 200 to """{"items":[],"total_count":0}""" else 404 to "{}"
        }
        show(Screen.Search(SearchType.ISSUE)) { SearchScreen(initialType = SearchType.ISSUE) }
        val field = compose.onNode(hasSetTextAction())
        field.performClick().performTextInput("is:")
        compose.onNodeWithText("is: open").assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction() and hasText("is:open ")).assertIsDisplayed()
        assertTrue(api.requests.none { "/search/issues" in it })
        field.performImeAction()
        compose.waitUntil(5_000) { api.requests.any { "/search/issues" in it && ("is%3Aopen" in it || "is:open" in it) } }
    }

    @Test fun forkSortSwitchesDataSourcesAndKeepsNamePaginationIndependent() {
        val sorts = listOf(
            tr("最新创建") to "newest",
            tr("最早创建") to "oldest",
            tr("Star 数") to "stargazers",
            tr("订阅人数") to "watchers",
        )
        val api = serve { method, path, body ->
            when {
                method == "GET" && path.startsWith("/repos/fixture/forks/forks?") -> {
                    val order = path.substringAfter("sort=").substringBefore('&')
                    check(path == "/repos/fixture/forks/forks?sort=$order&per_page=30&page=1")
                    check(sorts.any { it.second == order })
                    200 to """[{"name":"$order-fork","full_name":"fixture/$order-fork","owner":{"login":"fixture"}}]"""
                }
                method == "POST" && path == "/graphql" -> {
                    val request = Api.plain.parseToJsonElement(body)
                    check(request.str("query")?.contains("field: NAME, direction: ASC") == true)
                    val variables = request.obj("variables")
                    check(variables.str("owner") == "fixture" && variables.str("name") == "forks")
                    val (name, info) = when (variables.str("cursor")) {
                        null -> "alpha-fork" to """{"hasNextPage":true,"endCursor":"name-next"}"""
                        "name-next" -> "beta-fork" to """{"hasNextPage":false,"endCursor":null}"""
                        else -> error("Unexpected fork cursor")
                    }
                    200 to """{"data":{"repository":{"forks":{"nodes":[{"name":"$name","fullName":"fixture/$name","owner":{"login":"fixture"}}],"pageInfo":$info}}}}"""
                }
                else -> 404 to "{}"
            }
        }
        val previousSort = Session.forkSort
        compose.runOnUiThread { Session.chooseForkSort(ForkSort.Newest) }
        try {
            show(Screen.Repos(RepoKind.Forks, "fixture", "forks")) { ForkListScreen("fixture", "forks") }
            fun awaitFork(name: String) {
                val text = "fixture / $name"
                compose.waitUntil(8_000) { compose.onNodeWithText(text).isDisplayed() }
            }
            var selected = sorts.first().first
            awaitFork("newest-fork")
            for ((label, query) in sorts.drop(1)) {
                compose.onNodeWithText(selected).performClick()
                compose.onNodeWithText(label).performClick()
                awaitFork("$query-fork")
                selected = label
            }
            compose.onNodeWithText(selected).performClick()
            compose.onNodeWithText(tr("名称")).performClick()
            awaitFork("alpha-fork")
            awaitFork("beta-fork")
            compose.onNodeWithText("fixture / watchers-fork").assertDoesNotExist()
            assertEquals(2, api.requests.count { it == "POST /graphql" })
            assertEquals(4, api.requests.count { it.startsWith("GET /repos/fixture/forks/forks?") })
        } finally {
            compose.runOnUiThread { Session.chooseForkSort(previousSort) }
        }
    }

    @Test fun jumpsToFailureInCollapsedGroupAndNavigatesToNextError() {
        serve { _, path, _ ->
            when (path) {
                "/repos/fixture/workflow/actions/jobs/71" -> 200 to """{"id":71,"status":"completed","steps":[{"name":"Build","number":2,"started_at":"2024-01-01T00:00:02Z","completed_at":"2024-01-01T00:00:04Z","conclusion":"failure"}]}"""
                "/repos/fixture/workflow/actions/jobs/71/logs" -> 200 to buildString {
                    appendLine("2024-01-01T00:00:02.000Z ##[group]Build")
                    appendLine("2024-01-01T00:00:03.000Z ##[error]First compiler failure")
                    appendLine("2024-01-01T00:00:04.000Z ##[endgroup]")
                    repeat(55) { appendLine("ordinary line $it") }
                    appendLine("2024-01-01T00:00:05.000Z ##[error]Second compiler failure")
                }
                else -> 404 to "{}"
            }
        }
        show(Screen.JobLog("fixture", "workflow", 71, "Build", stepNumber = 2, focusFailure = true)) {
            JobLogScreen("fixture", "workflow", 71, "Build", stepNumber = 2, focusFailure = true)
        }
        compose.waitUntil(5_000) { compose.onNodeWithText("1/2").isDisplayed() }
        compose.onNodeWithText("First compiler failure").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一处错误").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText("Second compiler failure").isDisplayed() }
        compose.onNodeWithText("2/2").assertIsDisplayed()
    }

    @Test fun fileViewedStateLoadsFromGitHubAndUnmarkWritesThrough() {
        val api = serve { _, path, body ->
            when (path.substringBefore('?')) {
                "/repos/fixture/review" -> 200 to "{}"
                "/repos/fixture/review/pulls/42" -> 200 to """{"id":42,"number":42,"head":{"sha":"head"},"base":{"sha":"base"}}"""
                "/repos/fixture/review/pulls/42/files" -> 200 to """[{"filename":"file.kt","sha":"blob","status":"modified"}]"""
                "/repos/fixture/review/pulls/42/comments", "/repos/fixture/review/pulls/42/reviews" -> 200 to "[]"
                "/graphql" -> when {
                    body.contains("query ReviewProgress") -> 200 to """{"data":{"repository":{"pullRequest":{"id":"fixture-id","headRefOid":"head","baseRefOid":"base","files":{"nodes":[{"path":"file.kt","viewerViewedState":"VIEWED"}],"pageInfo":{"hasNextPage":false}}}}}}"""
                    body.contains("unmarkFileAsViewed") -> 200 to """{"data":{"unmarkFileAsViewed":{"pullRequest":{"id":"fixture-id","headRefOid":"head","baseRefOid":"base"}}}}"""
                    else -> 200 to """{"data":{}}"""
                }
                else -> 404 to "{}"
            }
        }
        show(Screen.PullFiles("fixture", "review", 42)) { PullFilesScreen("fixture", "review", 42) }
        compose.waitUntil(5_000) { compose.onNodeWithText("1/1 个文件已查看").isDisplayed() }
        val checkbox = compose.onNode(isToggleable() and hasText("已查看"))
        checkbox.assertIsOn().performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText("0/1 个文件已查看").isDisplayed() }
        checkbox.assertIsOff()
        assertEquals(1, api.requestBodies.count { "unmarkFileAsViewed" in it && "file.kt" in it })
    }

    private fun serve(handler: (String, String, String) -> Pair<Int, String>) = LoopbackApi(handler).also {
        server = it
        previousProxy = Net.apiProxy
        Net.apiProxy = it.base
    }

    private fun show(screen: Screen, content: @Composable () -> Unit) {
        val navigator = Navigator()
        nav = navigator
        compose.runOnUiThread { navigator.push(screen) }
        compose.setContent {
            MobileGhTheme { CompositionLocalProvider(LocalNav provides navigator, LocalEntry provides navigator.top) { content() } }
        }
    }
}
