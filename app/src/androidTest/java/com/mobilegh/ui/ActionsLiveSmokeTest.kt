package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.Api
import com.mobilegh.data.ApiException
import com.mobilegh.data.GitHub
import com.mobilegh.data.Issue
import com.mobilegh.data.IssueHierarchy
import com.mobilegh.data.IssueTypes
import com.mobilegh.data.Net
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.data.obj
import com.mobilegh.data.str
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.screens.ActionsSettingsScreen
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionsLiveSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun privateRepositorySettingsAndTemporaryWrites() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureName = args.getString("actionsLiveFixture").orEmpty()
        assumeTrue(fixtureName.matches(Regex("MOBILEGH_SMOKE_[0-9]+")))
        val credential = File(compose.activity.cacheDir, "actions-live-credential")
        val token = credential.takeIf(File::isFile)?.readText()?.trim().orEmpty()
        credential.delete()
        check(token.isNotBlank()) { "The opted-in Actions test requires an authenticated fixture" }
        val owner = "sheep-programmer"
        val name = "mgh-create-test"
        val previousProxy = Net.apiProxy
        val previousAccount = Session.login
        val nav = Navigator()
        compose.runOnUiThread {
            Net.apiProxy = ""
            Session.signIn(token, User(login = "mobilegh-actions-live"))
            nav.push(Screen.ActionsSettings(owner, name))
        }
        try {
            val repository = runBlocking { GitHub.repo(owner, name, force = true) }
            assertTrue(repository.isPrivate && repository.owner.type == "User" && repository.permissions?.admin == true)
            compose.setContent {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
                    MobileGhTheme { ActionsSettingsScreen(owner, name) }
                }
            }
            listOf("编辑 Actions", "编辑保留", "编辑访问", "编辑私有 Fork", "编辑 Workflow").forEach { label ->
                await(label)
                compose.onNodeWithText(label).performScrollTo().performClick()
                compose.onNodeWithText("保存").performClick()
                awaitDialogClosed()
            }
            compose.onNodeWithText("Fork PR 审批").assertDoesNotExist()
            val originalPermissions = runBlocking { GitHub.actionPermissions(owner, name, force = true) }
            assertTrue(originalPermissions.enabled && originalPermissions.allowedActions == "all")
            try {
                compose.onNodeWithText("编辑 Actions").performScrollTo().performClick()
                compose.onNode(hasText("all") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
                compose.onNodeWithText("selected").performClick()
                compose.onNodeWithText("保存").performClick()
                awaitDialogClosed()
                assertEquals("selected", runBlocking { GitHub.actionPermissions(owner, name, force = true) }.allowedActions)
                await("编辑允许范围")
                compose.onNodeWithText("编辑允许范围").performScrollTo().performClick()
                compose.onNodeWithText("Actions 模式（每行一个）").assertDoesNotExist()
                compose.onNodeWithText("保存").performClick()
                awaitDialogClosed()
            } finally {
                runBlocking {
                    GitHub.updateActionPermissions(owner, name, originalPermissions.enabled, originalPermissions.allowedActions, originalPermissions.shaPinningRequired)
                    assertEquals(originalPermissions, GitHub.actionPermissions(owner, name, force = true))
                }
            }

            switch("通用权限", "Secrets")
            await("仓库 Secrets")
            compose.onNodeWithText("组织 Secrets").assertDoesNotExist()
            compose.onNodeWithText("新建 Secret").performClick()
            compose.onNodeWithText("Secret 名称").performTextReplacement(fixtureName)
            compose.onNodeWithText("Secret 值").performTextReplacement("mobilegh-smoke-placeholder")
            compose.onNodeWithText("保存").performClick()
            awaitDialogClosed()
            await(fixtureName)
            compose.onNodeWithContentDescription("覆盖 Secret $fixtureName").performScrollTo().performClick()
            compose.onNodeWithText("Secret 值").performTextReplacement("mobilegh-smoke-placeholder-updated")
            compose.onNodeWithText("保存").performClick()
            awaitDialogClosed()
            compose.onNodeWithContentDescription("删除 Secret $fixtureName").performScrollTo().performClick()
            compose.onNodeWithText("删除").performClick()
            awaitGone(fixtureName)

            switch("Secrets", "Variables")
            compose.onNodeWithText("新建 Variable").performClick()
            compose.onNodeWithText("Variable 名称").performTextReplacement(fixtureName)
            compose.onNodeWithText("Variable 值").performTextReplacement("mobilegh-create")
            compose.onNodeWithText("保存").performClick()
            awaitDialogClosed()
            await(fixtureName)
            compose.onNodeWithContentDescription("编辑 Variable $fixtureName").performScrollTo().performClick()
            compose.onNodeWithText("Variable 值").performTextReplacement("mobilegh-update")
            compose.onNodeWithText("保存").performClick()
            awaitDialogClosed()
            await("mobilegh-update")
            assertEquals("mobilegh-update", runBlocking { GitHub.actionVariables(owner, name, force = true) }.first { it.name == fixtureName }.value)
            compose.onNodeWithContentDescription("删除 Variable $fixtureName").performScrollTo().performClick()
            compose.onNodeWithText("删除").performClick()
            awaitGone(fixtureName)

            switch("Variables", "Runners")
            await("暂无 Runners")
            compose.onNodeWithText("下载 Runner").performScrollTo().performClick()
            compose.waitUntil(25_000) {
                compose.onAllNodes(hasText("SHA-256:", substring = true) and hasAnyAncestor(isDialog()))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithText("关闭").performClick()
            awaitDialogClosed()
            compose.onNodeWithText("注册 Runner").performScrollTo().performClick()
            await("过期时间：", substring = true)
            compose.onNodeWithContentDescription("复制注册令牌").assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
            awaitDialogClosed()

            switch("Runners", "Caches")
            await("暂无 Caches")
            await("活跃缓存：", substring = true)
            val cacheLimitStatus = runBlocking {
                runCatching { GitHub.actionCacheRetentionLimit(owner, name, force = true) }.exceptionOrNull()
            }
            if (cacheLimitStatus is ApiException && cacheLimitStatus.code == 402) {
                await("配置有效的付款方式", substring = true)
                compose.onNodeWithText("配置有效的付款方式", substring = true).performScrollTo().assertIsDisplayed()
            } else {
                check(cacheLimitStatus == null) { "Unexpected cache retention API status" }
                await("缓存保留")
            }
            switch("Caches", "通用权限")
            compose.onNodeWithText("Actions 设置").assertIsDisplayed()
            assertEquals(1, nav.stack.size)
        } finally {
            runBlocking {
                runCatching { GitHub.deleteActionSecret(owner, name, fixtureName) }
                runCatching { GitHub.deleteActionVariable(owner, name, fixtureName) }
            }
            compose.runOnUiThread {
                nav.dispose()
                if (Session.login == "mobilegh-actions-live") Session.signOut()
                previousAccount?.let(Session::switchTo)
                Net.apiProxy = previousProxy
            }
        }
    }

    @Test fun testRepositoryIssueHierarchyPersistsOnGitHub() {
        val fixtureName = InstrumentationRegistry.getArguments().getString("issuesLiveFixture").orEmpty()
        assumeTrue(fixtureName.matches(Regex("MOBILEGH_SMOKE_[0-9]+")))
        val credential = File(compose.activity.cacheDir, "issues-live-credential")
        val token = credential.takeIf(File::isFile)?.readText()?.trim().orEmpty()
        credential.delete()
        check(token.isNotBlank()) { "The opted-in Issue test requires an authenticated fixture" }
        val owner = "sheep-programmer"
        val name = "mgh-create-test"
        val previousProxy = Net.apiProxy
        val previousAccount = Session.login
        val temporaryIssues = mutableListOf<Issue>()
        compose.runOnUiThread {
            Net.apiProxy = ""
            Session.signIn(token, User(login = "mobilegh-issues-live"))
        }
        try {
            runBlocking {
                val repo = GitHub.repo(owner, name, force = true)
                assertTrue(repo.isPrivate && repo.hasIssues && repo.permissions?.admin == true)
                val parent = checkNotNull(GitHub.createIssue(owner, name, "$fixtureName parent", "Temporary regression fixture"))
                    .also(temporaryIssues::add)
                val child = checkNotNull(GitHub.createIssue(owner, name, "$fixtureName child", "Temporary regression fixture"))
                    .also(temporaryIssues::add)
                val parentId = checkNotNull(parent.nodeId)
                val childId = checkNotNull(child.nodeId)
                IssueHierarchy.addSubIssue(parentId, childId)
                assertEquals(parent.number, IssueHierarchy.load(owner, name, child.number, force = true).parent?.number)
                assertEquals(listOf(child.number), IssueHierarchy.load(owner, name, parent.number, force = true).subIssues.map { it.number })
                GitHub.setIssueState(owner, name, child.number, open = false, reason = "completed")
                assertEquals("closed", GitHub.issue(owner, name, child.number, force = true).state)
                withTimeout(35_000) {
                    while (IssueHierarchy.load(owner, name, parent.number, force = true).summary?.completed != 1) {
                        delay(1_000)
                    }
                }
                IssueHierarchy.removeParent(parentId, childId)
                assertNull(IssueHierarchy.load(owner, name, child.number, force = true).parent)
                val type = IssueTypes.options(owner, name).firstOrNull { it.enabled }
                if (type != null) {
                    assertEquals(type.id, IssueTypes.set(childId, type.id)?.id)
                    assertEquals(type.id, IssueTypes.current(owner, name, child.number)?.id)
                    IssueTypes.set(childId, null)
                }
            }
        } finally {
            try {
                runBlocking {
                    val failures = temporaryIssues.asReversed().mapNotNull { issue ->
                        runCatching {
                            val result = Api.graphql(
                                "mutation(\$id: ID!) { deleteIssue(input: {issueId: \$id}) { repository { nameWithOwner } } }",
                                mapOf("id" to checkNotNull(issue.nodeId)),
                            )
                            assertEquals("$owner/$name", result.obj("deleteIssue").obj("repository").str("nameWithOwner"))
                        }.exceptionOrNull()
                    }
                    if (failures.isNotEmpty()) throw IllegalStateException("Temporary Issue cleanup failed", failures.first())
                }
            } finally {
                compose.runOnUiThread {
                    if (Session.login == "mobilegh-issues-live") Session.signOut()
                    previousAccount?.let(Session::switchTo)
                    Net.apiProxy = previousProxy
                }
            }
        }
    }

    private fun switch(from: String, to: String) {
        compose.onAllNodesWithText(from)[0].performClick()
        compose.onNodeWithText(to).performClick()
    }

    private fun await(text: String, substring: Boolean = false) {
        compose.waitUntil(25_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private fun awaitGone(text: String) {
        compose.waitUntil(25_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
    }

    private fun awaitDialogClosed() {
        compose.waitUntil(25_000) {
            compose.onAllNodesWithText("Actions 设置").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() &&
                compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
        }
    }
}
