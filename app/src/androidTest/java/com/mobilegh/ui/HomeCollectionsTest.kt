package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.GitHubStarList
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.Screen
import com.mobilegh.ui.screens.HomeCollections
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeCollectionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nav = Navigator()
    private val list = GitHubStarList("list-1", "Learning", "", false, "learning", "mona", 8)
    @After fun dispose() { compose.runOnUiThread { nav.dispose() } }
    private fun show(error: Boolean = false) {
        compose.setContent {
            MobileGhTheme {
                Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
                    Text("你好，mona", Modifier.padding(16.dp))
                    HomeCollections(
                        if (error) null else listOf(list), emptyList(),
                        if (error) "unavailable" else null, if (error) "unavailable" else null,
                        { nav.push(Screen.StarLists("mona")) },
                        { nav.push(Screen.StarLists("mona", it.id)) },
                        { nav.push(Screen.Repos(RepoKind.Starred, "mona")) },
                        { nav.push(Screen.Achievements("mona")) },
                        {}, {},
                    )
                }
            }
        }
    }
    @Test fun highlightedListOpensTheSelectedListDirectly() {
        show()
        compose.onNodeWithText("Learning").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Screen.StarLists("mona", "list-1"), nav.top.screen) }
    }
    @Test fun achievementsAreVisibleAndOpenTheirNativePage() {
        show()
        compose.onNodeWithContentDescription("打开我的成就").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Screen.Achievements("mona"), nav.top.screen) }
    }
    @Test fun entriesRemainUsableIfTheirPreviewsCannotBeLoaded() {
        show(error = true)
        compose.onNodeWithContentDescription("打开收藏列表").performClick()
        compose.runOnIdle { assertEquals(Screen.StarLists("mona"), nav.top.screen) }
        compose.onNodeWithContentDescription("打开已 Star").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Screen.Repos(RepoKind.Starred, "mona"), nav.top.screen) }
    }
}
