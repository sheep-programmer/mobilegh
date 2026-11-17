package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.Api
import com.mobilegh.data.Net
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.screens.RunDetailScreen
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeploymentReviewFlowUiTest {
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

    @Test fun approvedEnvironmentCannotBeSubmittedAgainWhileRefreshIsDelayed() {
        previousProxy = Net.apiProxy
        previousCache = Api.cacheDir
        Api.cacheDir = File(compose.activity.cacheDir, "deployment-review-${System.nanoTime()}").apply { mkdirs() }
        val pendingCalls = AtomicInteger()
        val delayedRefreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val api = LoopbackApi { _, path, body ->
            when {
                path == "/repos/octo/hello/actions/runs/1" -> 200 to runJson
                path.startsWith("/repos/octo/hello/actions/runs/1/jobs") -> 200 to """{"total_count":0,"jobs":[]}"""
                path.startsWith("/repos/octo/hello/actions/runs/1/artifacts") -> 200 to """{"total_count":0,"artifacts":[]}"""
                path == "/repos/octo/hello" -> 200 to """{"id":1,"name":"hello","full_name":"octo/hello","owner":{"login":"octo"},"permissions":{"push":true},"archived":false}"""
                path == "/graphql" && body.contains("approveDeployments") ->
                    200 to """{"data":{"approveDeployments":{"clientMutationId":"local"}}}"""
                path == "/graphql" && body.contains("pendingDeploymentRequests") -> {
                    if (pendingCalls.incrementAndGet() == 1) {
                        200 to pendingProduction
                    } else {
                        delayedRefreshStarted.countDown()
                        releaseRefresh.await(5, TimeUnit.SECONDS)
                        200 to """{"data":{"node":{"pendingDeploymentRequests":{"nodes":[]}}}}"""
                    }
                }
                else -> 404 to """{"message":"unexpected $path"}"""
            }
        }
        server = api
        Net.apiProxy = api.base
        val nav = Navigator()
        nav.push(Screen.RunDetail("octo", "hello", 1L))
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
                    RunDetailScreen("octo", "hello", 1L)
                }
            }
        }
        try {
            waitForText("production")
            compose.onNodeWithText("production").assertIsDisplayed().performClick()
            compose.onNodeWithText("批准部署").performScrollTo().performClick()
            compose.onAllNodesWithText("批准部署").onLast().performClick()

            assertTrue("mutation must trigger a pending-review refresh", delayedRefreshStarted.await(5, TimeUnit.SECONDS))
            val approvalButtons = compose.onAllNodesWithText("批准部署").fetchSemanticsNodes()
            assertTrue(
                "the approved environment must be removed or remain disabled until refresh completes",
                approvalButtons.isEmpty() || approvalButtons.all { SemanticsProperties.Disabled in it.config },
            )

            releaseRefresh.countDown()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText("production").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(1, api.requestBodies.count { it.contains("approveDeployments") })
        } finally {
            releaseRefresh.countDown()
            compose.runOnUiThread { nav.dispose() }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        val runJson = """
            {"id":1,"node_id":"WR_node","name":"Deploy","display_title":"Deploy production",
             "head_branch":"main","head_sha":"abcdef1234567","event":"push","status":"waiting",
             "run_number":1,"run_attempt":1,"created_at":"2026-11-15T00:00:00Z",
             "html_url":"https://github.com/octo/hello/actions/runs/1","workflow_id":1,"path":".github/workflows/deploy.yml"}
        """.trimIndent()
        val pendingProduction = """
            {"data":{"node":{"pendingDeploymentRequests":{"nodes":[
              {"environment":{"id":"E_production","name":"production"},"currentUserCanApprove":true,
               "reviewers":{"nodes":[{"__typename":"User","login":"mona"}]}}
            ]}}}}
        """.trimIndent()
    }
}
