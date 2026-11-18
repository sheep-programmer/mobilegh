package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Tab
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootNavigationBarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun attachedNavigationShowsAllTabs() = verifyAllTabs(floating = false)

    @Test fun floatingNavigationShowsAllTabsOnNarrowScreen() = verifyAllTabs(floating = true)

    private fun verifyAllTabs(floating: Boolean) {
        val nav = Navigator()
        compose.setContent {
            MobileGhTheme { Box(Modifier.width(300.dp)) { RootNavigationBar(nav, floating) } }
        }
        for (tab in listOf(Tab.Repos, Tab.Star, Tab.Explore, Tab.Home)) {
            compose.onNodeWithText(tab.title).performClick()
            compose.runOnIdle { assertEquals(tab, nav.tab) }
        }
    }
}
