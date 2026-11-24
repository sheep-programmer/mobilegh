package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.Invitation
import com.mobilegh.data.Notification
import com.mobilegh.data.OrgMembership
import com.mobilegh.data.Repo
import com.mobilegh.data.Subject
import com.mobilegh.data.User
import com.mobilegh.i18n.AppLanguage
import com.mobilegh.i18n.I18n
import com.mobilegh.nav.Loader
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Pager
import com.mobilegh.nav.PagerPage
import com.mobilegh.ui.screens.NotificationsScreen
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationFiltersScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nav = Navigator()
    private var previousLanguage = AppLanguage.Chinese

    @Before fun forceChinese() = compose.runOnUiThread {
        previousLanguage = I18n.choice
        I18n.select(AppLanguage.Chinese)
    }

    @After fun restoreLanguage() = compose.runOnUiThread {
        nav.dispose()
        I18n.select(previousLanguage)
    }

    @Test fun combinedFiltersCanFindLaterPagesAndClearFromAnEmptyTab() {
        val orgManual = thread(1, "Organization manual thread", "Acme", "Organization", "manual")
        val orgSubscribed = thread(2, "Organization subscribed thread", "Acme", "Organization", "subscribed")
        val personal = thread(3, "Personal thread", "alice", "User", "manual")
        val later = thread(4, "Later matching thread", "ACME", "Organization", "manual")
        val entry = nav.top
        val pager = Pager<Notification>(entry.scope, fetchPage = { page, _ ->
            if (page == 1) PagerPage(listOf(orgSubscribed, personal), hasMore = true)
            else PagerPage(listOf(later), hasMore = false)
        })
        pager.restoreFrom(listOf(orgManual, orgSubscribed, personal), pages = 1, ended = false)
        entry.bag["notif:0"] = pager
        entry.bag["notif:1"] = Pager<Notification>(entry.scope).apply { restoreFrom(emptyList(), pages = 1, ended = true) }
        entry.bag["rinv"] = Loader<List<Invitation>>(entry.scope) { emptyList() }.apply { accept(emptyList()) }
        entry.bag["oinv"] = Loader<List<OrgMembership>>(entry.scope) { emptyList() }.apply { accept(emptyList()) }

        compose.setContent {
            CompositionLocalProvider(LocalNav provides nav, LocalEntry provides entry) {
                MobileGhTheme { NotificationsScreen() }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("组织").performClick()
        compose.onNodeWithText("Acme").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("Organization manual thread").assertIsDisplayed()
        compose.onNodeWithText("Personal thread").assertDoesNotExist()

        compose.onNodeWithText("原因").performClick()
        compose.onNodeWithText("手动订阅").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("Organization subscribed thread").assertDoesNotExist()

        compose.runOnIdle { pager.refresh() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("已加载的通知中没有匹配项").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("加载更多").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Later matching thread").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("全部").performClick()
        compose.onNodeWithText("没有匹配的通知").assertIsDisplayed()
        compose.onNodeWithText("清除筛选").performClick()
        compose.onNodeWithText("暂无通知").assertIsDisplayed()
    }

    private fun thread(id: Long, title: String, login: String, ownerType: String, reason: String) = Notification(
        id = id.toString(),
        unread = true,
        reason = reason,
        subject = Subject(title = title, type = "Issue"),
        repository = Repo(id = id, name = "repo-$id", fullName = "$login/repo-$id", owner = User(login = login, type = ownerType)),
    )
}
