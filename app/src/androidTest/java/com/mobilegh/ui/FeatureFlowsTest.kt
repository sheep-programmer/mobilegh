package com.mobilegh.ui

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.NavVm
import com.mobilegh.data.*
import com.mobilegh.nav.*
import com.mobilegh.ui.screens.*
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import org.junit.Assert.*
import org.junit.After
import androidx.compose.runtime.CompositionLocalProvider
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureFlowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun signOutTestAccount() {
        compose.runOnUiThread { if (Session.login == "mobilegh-ui-test") Session.signOut() }
    }
    @Test fun androidShareTargetAcceptsSharedTextAndImages() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "仓库：https://github.com/octocat/Hello-World/issues/42")
        assertEquals(Screen.IssueDetail("octocat", "Hello-World", 42, false), IncomingLinks.fromIntent(intent)?.target)
        val targets = compose.activity.packageManager.queryIntentActivities(intent.setPackage(compose.activity.packageName), 0)
        assertTrue(targets.any { it.activityInfo.name == "com.mobilegh.MainActivity" })
        assertNotNull(ShareIntents.parse("text/plain", "hello", emptyList()))
        assertNotNull(ShareIntents.parse("image/png", null, listOf(SharedAttachment("/cache/a.png", "image/png", "a.png"))))
    }
    @Test fun sharedLinkWaitsForLoginAndIsConsumedOnlyOnce() {
        compose.setContent { Text("fixture") }
        compose.runOnIdle {
            // 本用例验证未登录时暂存链接；清除设备套件中其他用例留下的会话。
            Session.signOut()
            val saved = SavedStateHandle()
            val vm = NavVm(saved)
            vm.receive("https://github.com/octocat/Hello-World")
            val oldNav = vm.navFor(Session.generation)
            vm.openPending(oldNav)
            assertEquals("https://github.com/octocat/Hello-World", vm.pendingUrl)
            assertTrue(oldNav.stack.isEmpty())
            Session.signIn("local-fixture-token", User(login = "mobilegh-ui-test"))
            val nav = vm.navFor(Session.generation)
            vm.openPending(nav)
            vm.openPending(nav)
            assertEquals(Screen.Repo("octocat", "Hello-World"), nav.top.screen)
            assertEquals(1, nav.stack.size)
            assertNull(vm.pendingUrl)
            assertNull(saved.get<String>("pendingUrl"))
            nav.dispose()
        }
    }
    @Test fun searchHistoryCanOpenAndRemoveASavedQuery() {
        var opened: SearchHistoryEntry? = null
        var removed: SearchHistoryEntry? = null
        val entry = SearchHistoryEntry("language:kotlin", 4)
        val nav = Navigator()
        try {
            compose.setContent {
                MobileGhTheme {
                    CompositionLocalProvider(LocalNav provides nav) {
                        SearchHistorySection(listOf(entry), { opened = it }, { removed = it }, {})
                    }
                }
            }
            compose.onNodeWithText(entry.query).performClick()
            assertEquals(entry, opened)
            compose.onNodeWithContentDescription("移除搜索历史：language:kotlin").performClick()
            assertEquals(entry, removed)
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }
    @Test fun profileEditorKeepsChangedValuesWhenTheSaveButtonIsPressed() {
        var saved: ProfileFields? = null
        compose.setContent {
            var fields by remember { mutableStateOf(ProfileFields(name = "Old")) }
            MobileGhTheme { ProfileEditorForm(fields, { fields = it }, false, null) { saved = fields } }
        }
        compose.onNodeWithText("显示名称").performTextReplacement("New")
        compose.onNodeWithText("保存资料").performScrollTo().performClick()
        assertEquals("New", saved?.name)
    }
    @Test fun sharedAttachmentUploadBlocksIssueSubmission() {
        val nav = Navigator()
        val (id, attachment) = pendingShareWithAttachment("submission-upload")
        try {
            showSharedIssue(nav, id, "submission-upload")
            compose.waitUntil(15_000) { compose.onAllNodesWithText("标题").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("标题").performTextInput("must include attachment")

            compose.onNodeWithText("创建 Issue").assertIsNotEnabled()
            assertNotNull(PendingIssueShare.peek(compose.activity, id))
            assertTrue(attachment.exists())
        } finally {
            compose.runOnUiThread { nav.dispose() }
            PendingIssueShare.finish(compose.activity, id)
        }
    }

    @Test fun cancellingSharedIssueBeforeUploadRemovesPendingFiles() {
        val nav = Navigator()
        val (id, attachment) = pendingShareWithAttachment("cancel-before-upload")
        try {
            showSharedIssue(nav, id, "cancel-share")
            compose.onNodeWithText("取消").performClick()

            assertNull(PendingIssueShare.peek(compose.activity, id))
            assertFalse(attachment.exists())
            assertTrue(nav.stack.none { it.screen is Screen.NewIssue })
        } finally {
            compose.runOnUiThread { nav.dispose() }
            PendingIssueShare.finish(compose.activity, id)
        }
    }

    @Test fun systemBackFromSharedIssueBeforeUploadRemovesPendingFiles() {
        val nav = Navigator()
        val (id, attachment) = pendingShareWithAttachment("back-before-upload")
        try {
            showSharedIssue(nav, id, "back-share")
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

            assertNull(PendingIssueShare.peek(compose.activity, id))
            assertFalse(attachment.exists())
            assertTrue(nav.stack.none { it.screen is Screen.NewIssue })
        } finally {
            compose.runOnUiThread { nav.dispose() }
            PendingIssueShare.finish(compose.activity, id)
        }
    }

    private fun pendingShareWithAttachment(name: String): Pair<String, File> {
        val root = File(compose.activity.cacheDir, "issue-share").apply { mkdirs() }
        val attachment = File(root, "$name.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val id = PendingIssueShare.save(
            compose.activity,
            IssueShare("shared draft", listOf(SharedAttachment(attachment.absolutePath, "image/png", "$name.png"))),
        )
        return id to attachment
    }

    private fun showSharedIssue(nav: Navigator, id: String, repo: String) {
        compose.runOnUiThread {
            Session.signIn("local-fixture-token", User(login = "mobilegh-ui-test"))
            Drafts.store(compose.activity).clear("mobilegh-ui-test", "new-issue:octocat/$repo")
            nav.push(Screen.Repo("octocat", repo))
            nav.push(Screen.NewIssue("octocat", repo, shareId = id))
        }
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.top) {
                    // 与 Main 相同的全局返回处理放在页面之前；页面自己的 BackHandler 应优先拦截。
                    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }
                    if (nav.top.screen is Screen.NewIssue) {
                        NewIssueScreen("octocat", repo, shareId = id)
                    } else {
                        Text("closed")
                    }
                }
            }
        }
    }

    @Test fun newIssueDraftIsEncryptedAndRestoredAfterClosingTheEditor() {
        val nav = Navigator()
        val visible = mutableStateOf(true)
        compose.runOnUiThread {
            Session.signIn("local-fixture-token", User(login = "mobilegh-ui-test"))
            Drafts.store(compose.activity).clear("mobilegh-ui-test", "new-issue:octocat/hello-world")
        }
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                    if (visible.value) NewIssueScreen("octocat", "Hello-World") else Text("closed")
                }
            }
        }
        // 先读取仓库的 Issue 模板；读不到（测试 Token 无效）时回落到空白表单
        compose.waitUntil(15_000) { compose.onAllNodesWithText("标题").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("标题").performTextInput("draft-title")
        compose.onNodeWithText("描述（支持 Markdown）").performTextInput("draft-body")
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(5000) { Drafts.store(compose.activity).load("mobilegh-ui-test", "new-issue:octocat/hello-world").body == "draft-body" }
        val encrypted = compose.activity.getSharedPreferences("editor_drafts_v1", Context.MODE_PRIVATE).all.values.map { it.toString() }
        assertTrue(encrypted.none { it.contains("draft-body") || it.contains("draft-title") })
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("draft-title").assertExists()
        compose.onNodeWithText("draft-body").assertExists()
        compose.runOnIdle { visible.value = false; nav.dispose() }
    }
}
