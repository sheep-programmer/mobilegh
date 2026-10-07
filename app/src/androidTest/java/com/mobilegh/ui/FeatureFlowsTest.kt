package com.mobilegh.ui

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
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
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureFlowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun signOutTestAccount() {
        compose.runOnUiThread { if (Session.login == "mobilegh-ui-test") Session.signOut() }
    }
    @Test fun androidShareTargetAcceptsSharedTextAndRejectsUnsupportedContent() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "仓库：https://github.com/octocat/Hello-World/issues/42")
        assertEquals(Screen.IssueDetail("octocat", "Hello-World", 42, false), IncomingLinks.fromIntent(intent)?.target)
        val targets = compose.activity.packageManager.queryIntentActivities(intent.setPackage(compose.activity.packageName), 0)
        assertTrue(targets.any { it.activityInfo.name == "com.mobilegh.MainActivity" })
        assertNull(IncomingLinks.fromIntent(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_TEXT, "https://github.com/octocat/Hello-World")))
        assertNull(IncomingLinks.fromIntent(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")))
    }
    @Test fun sharedLinkWaitsForLoginAndIsConsumedOnlyOnce() {
        compose.setContent { Text("fixture") }
        compose.runOnIdle {
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
        compose.setContent {
            MobileGhTheme { SearchHistorySection(listOf(entry), { opened = it }, { removed = it }, {}) }
        }
        compose.onNodeWithText(entry.query).performClick()
        assertEquals(entry, opened)
        compose.onNodeWithContentDescription("移除搜索历史：language:kotlin").performClick()
        assertEquals(entry, removed)
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
