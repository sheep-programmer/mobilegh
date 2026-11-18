package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.Api
import com.mobilegh.data.FileReviewState
import com.mobilegh.data.GitHub
import com.mobilegh.data.Net
import com.mobilegh.data.ReviewProgressApi
import com.mobilegh.data.SavedReplies
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.i18n.AppLanguage
import com.mobilegh.i18n.I18n
import com.mobilegh.nav.ActionsSettingsSection
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.screens.ActionsSettingsScreen
import com.mobilegh.ui.screens.PullFilesScreen
import com.mobilegh.ui.screens.RunDetailScreen
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseLiveSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val owner = "sheep-programmer"
    private val repo = "mgh-create-test"
    private val args = InstrumentationRegistry.getArguments()
    private val branch get() = args.getString("releaseLiveBranch").orEmpty()
    private val ref get() = "refs/heads/$branch"
    private val workflowPath = ".github/workflows/mobilegh-smoke.yml"

    @Test fun workflowCanRerunFailedJobsAndThenRerunAllJobs() {
        withFixture("run") { nav ->
            val id = positiveId("releaseLiveRun")
            val initial = runBlocking { GitHub.run(owner, repo, id, force = true) }
            check(initial.headBranch == branch && initial.status == "completed" && initial.conclusion == "failure")
            mount(nav, Screen.RunDetail(owner, repo, id)) { RunDetailScreen(owner, repo, id) }

            val failed = "重跑失败任务"
            await(failed)
            compose.onNodeWithText(failed).performClick()
            compose.onNodeWithText("启用调试日志").performClick()
            confirm(failed)
            awaitAttempt(id, initial.runAttempt + 1)

            val all = "重新运行"
            await(all)
            await("第 ${initial.runAttempt + 1} 次尝试")
            compose.onNodeWithText(all).performClick()
            confirm(all)
            awaitAttempt(id, initial.runAttempt + 2)
            await("第 ${initial.runAttempt + 2} 次尝试")
        }
    }

    @Test fun pullFileViewedStatePersistsOnGitHub() {
        withFixture("pull") { nav ->
            val number = positiveId("releaseLivePull").toInt()
            val pull = runBlocking { GitHub.pull(owner, repo, number, force = true) }
            check(pull.head.ref == branch && pull.base.ref == "main" && pull.state == "open")
            val generation = Session.generation
            fun readState() = runBlocking {
                ReviewProgressApi.read(owner, repo, number, generation).states.getValue(workflowPath)
            }
            check(readState() != FileReviewState.VIEWED)
            mount(nav, Screen.PullFiles(owner, repo, number)) { PullFilesScreen(owner, repo, number) }
            await("0/1 个文件已查看")
            val checkbox = isToggleable() and hasText("已查看")
            val viewed = compose.onNode(checkbox)
            awaitEnabled(checkbox)
            viewed.assertIsOff().performClick()
            await("1/1 个文件已查看")
            assertEquals(FileReviewState.VIEWED, readState())
            awaitEnabled(checkbox)
            viewed.assertIsOn().performClick()
            await("0/1 个文件已查看")
            assertTrue(readState() != FileReviewState.VIEWED)
        }
    }

    @Test fun savedRepliesCanBeReadWithExistingAccount() {
        assumeTrue(args.getString("savedRepliesLive") == "true")
        assumeTrue("Testing requires the designated GitHub account in the app", Session.login.equals(owner, ignoreCase = true))
        runBlocking {
            SavedReplies.savedReplies(graphql = { query, variables -> Api.graphqlRoot(query, variables, force = true) })
        }
    }

    @Test fun cacheCanBeDeletedAndListRefreshes() {
        withFixture("cache") { nav ->
            val cache = runBlocking { GitHub.actionCaches(owner, repo, branch, ref, force = true) }
                .single { it.key == branch && it.ref == ref }
            mount(nav, Screen.ActionsSettings(owner, repo, ActionsSettingsSection.Caches)) {
                ActionsSettingsScreen(owner, repo, ActionsSettingsSection.Caches)
            }
            val description = "删除 Cache $branch"
            awaitDescription(description)
            compose.onNodeWithContentDescription(description).performScrollTo().performClick()
            confirm("删除")
            awaitGone(description)
            assertTrue(runBlocking { GitHub.actionCaches(owner, repo, branch, ref, force = true) }.none { it.id == cache.id })
        }
    }

    @Test fun registeredRunnerCanBeDeletedAndListRefreshes() {
        withFixture("runner") { nav ->
            val id = positiveId("releaseLiveRunner")
            val runner = runBlocking { GitHub.actionRunners(owner, repo, branch, force = true) }.single { it.id == id }
            check(runner.name == branch && runner.status == "offline" && !runner.busy)
            mount(nav, Screen.ActionsSettings(owner, repo, ActionsSettingsSection.Runners)) {
                ActionsSettingsScreen(owner, repo, ActionsSettingsSection.Runners)
            }
            val description = "删除 Runner $branch"
            awaitDescription(description)
            compose.onNodeWithContentDescription(description).performScrollTo().performClick()
            confirm("删除")
            awaitGone(description)
            assertTrue(runBlocking { GitHub.actionRunners(owner, repo, branch, force = true) }.none { it.id == id })
        }
    }

    private fun withFixture(test: String, content: (Navigator) -> Unit) {
        assumeTrue(branch.matches(Regex("mobilegh-smoke-[0-9]+")))
        val credential = File(compose.activity.cacheDir, "release-live-$test")
        val token = try { credential.readText().trim() } finally { credential.delete() }
        check(token.isNotBlank())
        val priorAccount = Session.login
        val priorProxy = Net.apiProxy
        val priorLanguage = I18n.choice
        val login = "mobilegh-release-live-$test"
        val nav = Navigator()
        compose.runOnUiThread {
            Net.apiProxy = ""
            I18n.select(AppLanguage.Chinese)
            Session.signIn(token, User(login = login))
        }
        try {
            val repository = runBlocking { GitHub.repo(owner, repo, force = true) }
            check(repository.isPrivate && repository.permissions?.admin == true)
            content(nav)
        } finally {
            compose.runOnUiThread {
                nav.dispose()
                if (Session.login == login) Session.signOut()
                priorAccount?.let(Session::switchTo)
                Net.apiProxy = priorProxy
                I18n.select(priorLanguage)
            }
        }
    }

    private fun positiveId(name: String) = checkNotNull(args.getString(name)?.toLongOrNull()?.takeIf { it > 0 }) { "Missing fixture: $name" }

    private fun mount(nav: Navigator, screen: Screen, content: @Composable () -> Unit) {
        compose.runOnUiThread { nav.push(screen) }
        compose.setContent {
            CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
                MobileGhTheme { content() }
            }
        }
    }

    private fun confirm(label: String) = compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()

    private fun await(text: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }

    private fun awaitDescription(description: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
    }

    private fun awaitEnabled(matcher: SemanticsMatcher) = compose.waitUntil(30_000) {
        compose.onAllNodes(matcher and isEnabled()).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
    }

    private fun awaitGone(description: String) = compose.waitUntil(30_000) {
        compose.onAllNodes(isDialog()).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
    }

    private fun awaitAttempt(id: Long, attempt: Int) = runBlocking {
        withTimeout(120_000) {
            while (true) {
                val run = GitHub.run(owner, repo, id, force = true)
                check(run.headBranch == branch)
                if (run.runAttempt == attempt && run.status == "completed") {
                    assertEquals("success", run.conclusion)
                    return@withTimeout
                }
                delay(3_000)
            }
        }
    }
}
