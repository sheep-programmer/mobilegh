package com.mobilegh.ui

import android.app.NotificationManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.Net
import com.mobilegh.data.Notification
import com.mobilegh.data.NotificationMuteConfig
import com.mobilegh.data.NotificationMuteRules
import com.mobilegh.data.Repo
import com.mobilegh.data.Session
import com.mobilegh.data.Subject
import com.mobilegh.data.User
import com.mobilegh.data.WorkflowPresetStore
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.service.GitHubNotificationDelivery
import com.mobilegh.service.NotificationActionReceiver
import com.mobilegh.ui.components.ImageViewer
import com.mobilegh.ui.components.ImageViewerHost
import com.mobilegh.ui.components.Segmented
import com.mobilegh.ui.components.ShareCardRenderer
import com.mobilegh.ui.screens.ActionsQuotaCard
import com.mobilegh.ui.screens.FileViewScreen
import com.mobilegh.ui.screens.IssueDetailScreen
import com.mobilegh.ui.screens.PullChecksScreen
import com.mobilegh.ui.screens.PullFilesScreen
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewFeaturesLiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var previousProxy: String = ""
    private var previousLogin: String? = null
    private var server: LoopbackApi? = null
    private val nav = Navigator()
    private val sha = "a".repeat(40)

    @Before fun setUp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        inst.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        Session.init(ctx)
        previousProxy = Net.apiProxy
        previousLogin = Session.login
        Session.login = "mona"
    }

    @After fun tearDown() {
        compose.runOnUiThread { nav.dispose() }
        Net.apiProxy = previousProxy
        Session.login = previousLogin
        server?.close()
    }

    @Test fun checkRunAnnotationsExpandAndNavigateToBlob() {
        server = LoopbackApi { _, path, _ ->
            when {
                path.contains("/commits/$sha/check-runs") -> 200 to """
                    {"total_count":1,"check_runs":[{"id":101,"name":"android-lint","status":"completed","conclusion":"failure","output":{"title":"Lint failed","annotations_count":1},"app":{"name":"GitHub Actions"}}]}
                """.trimIndent()
                path.contains("/commits/$sha/status") -> 200 to """{"state":"failure","statuses":[]}"""
                path.contains("/pulls/7") -> 200 to """{"number":7,"title":"Fix SVG","state":"open","head":{"ref":"fix","sha":"$sha"},"base":{"ref":"main","sha":"$sha"}}"""
                path.contains("/check-runs/101/annotations") -> 200 to """
                    [{"path":"src/App.kt","start_line":42,"end_line":44,"annotation_level":"failure","title":"Unsafe call","message":"Null pointer dereference"}]
                """.trimIndent()
                else -> 404 to "{}"
            }
        }.also { Net.apiProxy = it.base }

        compose.runOnUiThread { nav.push(Screen.PullChecks("mona", "repo", 7, sha)) }
        compose.setContent {
            MobileGhTheme { CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) { PullChecksScreen("mona", "repo", 7, sha) } }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("标注 (1)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("标注 (1)").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("src/App.kt:42").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Null pointer dereference").assertIsDisplayed()
        compose.onNodeWithText("src/App.kt:42").performClick()
        compose.runOnIdle {
            assertEquals(Screen.Blob("mona", "repo", "$sha/src/App.kt", 42..44), nav.top.screen)
        }
    }

    @Test fun prBehindShowsUpdateBranchButtonAndCallsApi() {
        server = LoopbackApi { method, path, _ ->
            when {
                method == "PUT" && path.contains("/pulls/7/update-branch") -> 202 to """{"message":"Updating pull request branch."}"""
                path.endsWith("/repos/mona/repo") -> 200 to """{"name":"repo","full_name":"mona/repo","owner":{"login":"mona"},"default_branch":"main","permissions":{"push":true,"pull":true}}"""
                path.contains("/issues/7/comments") || path.contains("/issues/7/timeline") || path.contains("/pulls/7/reviews") || path.contains("/pulls/7/commits") -> 200 to "[]"
                path.contains("/issues/7") -> 200 to """{"id":7,"number":7,"title":"Behind PR","state":"open","user":{"login":"mona"},"body":"desc","created_at":"2026-11-27T00:00:00Z","pull_request":{"url":"https://api.github.com/repos/mona/repo/pulls/7"}}"""
                path.contains("/pulls/7") -> 200 to """{"id":7,"number":7,"node_id":"PR_7","title":"Behind PR","state":"open","mergeable_state":"behind","user":{"login":"mona"},"head":{"ref":"feat","label":"mona:feat","sha":"$sha","repo":{"name":"repo","full_name":"mona/repo","owner":{"login":"mona"},"default_branch":"main"}},"base":{"ref":"main","label":"mona:main","sha":"$sha","repo":{"name":"repo","full_name":"mona/repo","owner":{"login":"mona"},"default_branch":"main"}}}"""
                path.contains("/commits/$sha/check-runs") -> 200 to """{"total_count":0,"check_runs":[]}"""
                path.contains("/commits/$sha/status") -> 200 to """{"state":"success","statuses":[]}"""
                else -> 200 to "{}"
            }
        }.also { Net.apiProxy = it.base }

        compose.runOnUiThread { nav.push(Screen.IssueDetail("mona", "repo", 7, isPull = true)) }
        compose.setContent {
            MobileGhTheme { CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) { IssueDetailScreen("mona", "repo", 7, isPull = true) } }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("更新分支").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("更新分支").performClick()
        compose.waitUntil(5_000) { server!!.requests.any { it.startsWith("PUT ") && it.contains("/pulls/7/update-branch") } }
        assertTrue(server!!.requestBodies.any { it.contains("/pulls/7/update-branch") && it.contains(sha) })
    }

    @Test fun fileViewOutlineExtractsSymbolsAndJumpsToLine() {
        val filler = (1..60).joinToString("\n") { "    val filler$it = $it" }
        val code = "class Engine {\n$filler\n    fun tick() {\n        val targetLineMarker = 63\n    }\n}\n"
        server = LoopbackApi { _, path, _ ->
            when {
                path.contains("/contents/src/Engine.kt") -> 200 to code
                path.endsWith("/repos/mona/repo") -> 200 to """{"name":"repo","full_name":"mona/repo","owner":{"login":"mona"},"default_branch":"main","private":false}"""
                else -> 404 to "{}"
            }
        }.also { Net.apiProxy = it.base }

        compose.runOnUiThread { nav.push(Screen.FileView("mona", "repo", "src/Engine.kt", "main")) }
        compose.setContent {
            MobileGhTheme { CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) { FileViewScreen("mona", "repo", "src/Engine.kt", "main") } }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("class Engine {").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("符号大纲…").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("筛选符号…").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("筛选符号…").performTextInput("tick")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("L62").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("L62").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("val targetLineMarker = 63", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun notificationDeliveryAttachesMarkReadAndQuickReplyActions() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val notif = Notification(
            id = "9001",
            subject = Subject(
                title = "Fix SVG rendering",
                url = "https://api.github.com/repos/mona/repo/issues/7",
                type = "PullRequest",
            ),
            repository = Repo(name = "repo", fullName = "mona/repo", owner = User(login = "mona")),
        )
        assertTrue(GitHubNotificationDelivery.show(ctx, notif))
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val postDeadline = System.currentTimeMillis() + 3_000
        var posted = nm.activeNotifications.firstOrNull { it.id == "9001".hashCode() }
        while (posted == null && System.currentTimeMillis() < postDeadline) {
            Thread.sleep(50)
            posted = nm.activeNotifications.firstOrNull { it.id == "9001".hashCode() }
        }
        assertNotNull("Notification must be active on device", posted)
        val actions = posted!!.notification.actions.orEmpty()
        assertTrue(actions.any { it.title == "标为已读" })
        val replyAction = actions.firstOrNull { it.title == "快速回复" }
        assertNotNull("Quick reply action must be present", replyAction)
        assertTrue(replyAction!!.remoteInputs.orEmpty().any { it.resultKey == NotificationActionReceiver.KEY_REPLY_TEXT })

        server = LoopbackApi { method, path, _ ->
            if (method == "PATCH" && path.contains("/notifications/threads/9001")) 205 to "" else 404 to "{}"
        }.also { Net.apiProxy = it.base }

        ctx.sendBroadcast(
            Intent(ctx, NotificationActionReceiver::class.java)
                .setPackage(ctx.packageName)
                .setAction(NotificationActionReceiver.ACTION_MARK_READ)
                .putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, "9001")
                .putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, "9001".hashCode()),
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            server!!.requests.none { it.startsWith("PATCH ") && it.contains("/notifications/threads/9001") }
        ) {
            Thread.sleep(50)
        }
        assertTrue(server!!.requests.any { it.startsWith("PATCH ") && it.contains("/notifications/threads/9001") })
        nm.cancelAll()
    }

    @Test fun issueDetailRendersTitleAndBodyBeforeSlowCommentsFinish() {
        server = LoopbackApi { _, path, _ ->
            when {
                path.contains("/issues/7/comments") -> {
                    Thread.sleep(3_000)
                    200 to """[{"id":501,"user":{"login":"bob"},"body":"Slow comment","body_html":"<p>Slow comment</p>","created_at":"2026-11-27T00:01:00Z"}]"""
                }
                path.endsWith("/repos/mona/repo") -> 200 to """{"name":"repo","full_name":"mona/repo","owner":{"login":"mona"},"default_branch":"main"}"""
                path.contains("/issues/7") -> 200 to """{"id":7,"number":7,"title":"Instant Issue Title","state":"open","user":{"login":"mona"},"body":"Instant body","body_html":"<p>Instant body</p>","created_at":"2026-11-27T00:00:00Z"}"""
                else -> 200 to "{}"
            }
        }.also { Net.apiProxy = it.base }

        compose.runOnUiThread { nav.push(Screen.IssueDetail("mona", "repo", 7, isPull = false)) }
        compose.setContent {
            MobileGhTheme { CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) { IssueDetailScreen("mona", "repo", 7, isPull = false) } }
        }
        // Title and body must appear well before the 3000ms comments endpoint finishes.
        compose.waitUntil(1_500) {
            compose.onAllNodesWithText("Instant Issue Title").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Instant body").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Slow comment").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun shareCardRendererWorkflowPresetAndMuteRulesPersistOnDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val codeBmp = ShareCardRenderer.renderCodeCard("mona", "repo", "src/Main.kt", listOf("fun main() {", "    println(\"hi\")", "}"), 1, dark = true)
        assertTrue(codeBmp.width == 1080 && codeBmp.height >= 360)
        val repoBmp = ShareCardRenderer.renderRepoCard(
            Repo(name = "repo", fullName = "mona/repo", description = "Fast client", language = "Kotlin", stargazersCount = 1234, forksCount = 56, owner = User(login = "mona")),
            dark = false,
        )
        assertTrue(repoBmp.width == 1080 && repoBmp.height >= 360)

        WorkflowPresetStore.save(ctx, "mona", "repo", 77L, "release", mapOf("env" to "prod"))
        val loaded = WorkflowPresetStore.load(ctx, "mona", "repo", 77L)
        assertEquals("release", loaded?.ref)
        assertEquals("prod", loaded?.inputs?.get("env"))
        WorkflowPresetStore.clear(ctx, "mona", "repo", 77L)

        NotificationMuteRules.init(ctx)
        val prev = NotificationMuteRules.config
        try {
            NotificationMuteRules.update(NotificationMuteConfig(muteBots = true))
            val botNotif = Notification(id = "88", subject = Subject(title = "Bump gradle by dependabot[bot]"))
            assertTrue(NotificationMuteRules.isMuted(botNotif))
        } finally {
            NotificationMuteRules.update(prev)
        }
    }

    @Test fun pullFilesCanFilterBySingleCommit() {
        val commitB = "b".repeat(40)
        server = LoopbackApi { _, path, _ ->
            when {
                path.endsWith("/repos/mona/repo") -> 200 to """{"name":"repo","full_name":"mona/repo","owner":{"login":"mona"},"default_branch":"main"}"""
                path.contains("/pulls/7/files") -> 200 to """[{"filename":"all-a.kt","status":"modified","additions":1,"deletions":0,"patch":"@@ -1,1 +1,2 @@\n+a"},{"filename":"commit-b.kt","status":"modified","additions":2,"deletions":0,"patch":"@@ -1,1 +1,2 @@\n+b"}]"""
                path.contains("/pulls/7/commits") -> 200 to """[{"sha":"$sha","commit":{"message":"First commit"}},{"sha":"$commitB","commit":{"message":"Second commit"}}]"""
                path.contains("/commits/$commitB") -> 200 to """{"sha":"$commitB","commit":{"message":"Second commit"},"files":[{"filename":"commit-b.kt","status":"modified","additions":2,"deletions":0,"patch":"@@ -1,1 +1,2 @@\n+b"}]}"""
                path.contains("/pulls/7/comments") -> 200 to "[]"
                path.contains("/pulls/7") -> 200 to """{"id":7,"number":7,"title":"PR","state":"open","head":{"ref":"feat","sha":"$commitB"},"base":{"ref":"main","sha":"$sha"}}"""
                else -> 200 to "{}"
            }
        }.also { Net.apiProxy = it.base }

        compose.runOnUiThread { nav.push(Screen.PullFiles("mona", "repo", 7)) }
        compose.setContent {
            MobileGhTheme { CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) { PullFilesScreen("mona", "repo", 7) } }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("全部提交（2 个）").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("全部提交（2 个）").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("${commitB.take(7)} · Second commit").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("${commitB.take(7)} · Second commit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 个文件  ").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun imageViewerRendersSvgWithoutBlackCollapse() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val svgBytes = inst.context.assets.open("stellar-architecture.svg").use { it.readBytes() }
        server = LoopbackApi.media { LoopbackApi.Response(200, "image/svg+xml", svgBytes) }
        val svgUrl = "${server!!.base}/architecture.svg"

        compose.setContent {
            MobileGhTheme { ImageViewerHost() }
        }
        compose.runOnUiThread { ImageViewer.open(svgUrl, "分层架构") }
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("分层架构").fetchSemanticsNodes().isNotEmpty() &&
                    server!!.requests.isNotEmpty() &&
                    compose.onAllNodes(androidx.compose.ui.test.hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes().isEmpty()
            }
            compose.waitForIdle()
            Thread.sleep(400)
            assertTrue(compose.onAllNodesWithText("图片加载失败").fetchSemanticsNodes().isEmpty())
            val shot = inst.uiAutomation.takeScreenshot()
            var nonBlack = 0
            val yStart = shot.height / 3
            val yEnd = (shot.height * 2) / 3
            for (y in yStart until yEnd step 16) {
                for (x in 100 until shot.width - 100 step 16) {
                    val c = shot.getPixel(x, y)
                    if (android.graphics.Color.red(c) > 30 || android.graphics.Color.green(c) > 30 || android.graphics.Color.blue(c) > 30) {
                        nonBlack++
                    }
                }
            }
            assertTrue("Fullscreen SVG viewer must render visible non-black content (nonBlack=$nonBlack)", nonBlack > 50)
        } finally {
            compose.runOnUiThread { ImageViewer.close() }
        }
    }

    @Test fun actionsQuotaCardDisplaysMinutesStorageAndPackages() {
        server = LoopbackApi { _, path, _ ->
            when {
                path.contains("/settings/billing/actions") -> 200 to """
                    {"total_minutes_used":305,"total_paid_minutes_used":0,"included_minutes":2000,"minutes_used_breakdown":{"UBUNTU":205,"MACOS":10,"WINDOWS":90}}
                """.trimIndent()
                path.contains("/settings/billing/shared-storage") -> 200 to """
                    {"days_left_in_billing_cycle":12,"estimated_paid_storage_for_month":0.0,"estimated_storage_for_month":0.42}
                """.trimIndent()
                path.contains("/settings/billing/packages") -> 200 to """
                    {"total_gigabytes_bandwidth_used":0.5,"total_paid_gigabytes_bandwidth_used":0.0,"included_gigabytes_bandwidth":1.0}
                """.trimIndent()
                else -> 404 to "{}"
            }
        }.also { Net.apiProxy = it.base }

        compose.runOnUiThread { nav.push(Screen.Settings) }
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
                    ActionsQuotaCard()
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Actions 分钟  已用 305/2000").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("UBUNTU: 205m · MACOS: 10m · WINDOWS: 90m").assertIsDisplayed()
        compose.onNodeWithText("共享存储预估：0.42 GB · 本周期剩余 12 天").assertIsDisplayed()
        compose.onNodeWithText("Packages 流量：已用 0.5/1.0 GB").assertIsDisplayed()
    }

    @Test fun segmentedSwitchesCleanlyBetweenTabs() {
        compose.setContent {
            MobileGhTheme {
                var selected by remember { mutableIntStateOf(0) }
                Segmented(
                    options = listOf("活动", "动态"),
                    selected = selected,
                ) { selected = it }
            }
        }
        compose.onNodeWithText("活动").assertIsSelected()
        compose.onNodeWithText("动态").assertIsNotSelected()

        compose.onNodeWithText("动态").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("活动").assertIsNotSelected()
        compose.onNodeWithText("动态").assertIsSelected()

        compose.onNodeWithText("活动").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("活动").assertIsSelected()
        compose.onNodeWithText("动态").assertIsNotSelected()
    }
}
