package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.SearchFilters
import com.mobilegh.data.SearchHistoryEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.screens.SearchFilterDialog
import com.mobilegh.ui.screens.SearchHistorySection
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regressions for the narrow-screen controls users interact with every day. No network writes. */
@RunWith(AndroidJUnit4::class)
class ExperienceRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun filtersRemainScrollableAndApplyWithoutLosingTheSelectedValue() {
        var applied: SearchFilters? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MobileGhTheme {
                    SearchFilterDialog(0, SearchFilters(), { applied = it }, {})
                }
            }
        }
        compose.onNodeWithText("10000 以上").performScrollTo().performClick()
        compose.onNodeWithText("应用 · 1").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(10000, applied?.minStars) }
    }

    @Test fun keyboardLeavesFilterActionsReachable() {
        var dismissed = 0
        compose.setContent {
            MobileGhTheme { SearchFilterDialog(0, SearchFilters(), {}, { dismissed++ }) }
        }
        compose.onNodeWithText("归属用户或组织").performClick().performTextInput("JetBrains")
        compose.waitForIdle()
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun filterCancelKeepsTheOriginalSelection() {
        var applied = 0
        var dismissed = 0
        compose.setContent {
            MobileGhTheme {
                SearchFilterDialog(0, SearchFilters(minStars = 100), { applied++ }, { dismissed++ })
            }
        }
        compose.onNodeWithText("10000 以上").performScrollTo().performClick()
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, applied); assertEquals(1, dismissed) }
    }

    @Test fun collapsedSearchHistoryCanBeClearedInOneAction() {
        val entries = mutableStateOf((1..5).map { SearchHistoryEntry("query-$it", 0) })
        val nav = Navigator()
        try {
            compose.setContent {
                MobileGhTheme {
                    CompositionLocalProvider(LocalNav provides nav) {
                        Box(Modifier.fillMaxSize()) {
                            SearchHistorySection(entries.value, {}, {}, { entries.value = emptyList() }, collapsed = true)
                        }
                    }
                }
            }
            compose.onNodeWithText("query-3").assertIsDisplayed()
            compose.onNodeWithText("query-4").assertDoesNotExist()
            compose.onNodeWithText("清空").performClick()
            compose.onNodeWithText("搜索历史").assertDoesNotExist()
            compose.runOnIdle { assertEquals(emptyList<SearchHistoryEntry>(), entries.value) }
        } finally {
            compose.runOnUiThread { nav.dispose() }
        }
    }

    @Test fun starLongPressDoesNotAlsoClickAndDisabledButtonIgnoresBoth() {
        var clicked = 0
        var held = 0
        val enabled = mutableStateOf(true)
        compose.setContent {
            MobileGhTheme {
                GhButton("Star", Modifier.requiredSize(140.dp, 48.dp), enabled = enabled.value,
                    onLongClick = { held++ }) { clicked++ }
            }
        }
        compose.onNodeWithText("Star").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, held); assertEquals(0, clicked) }
        compose.onNodeWithText("Star").performClick()
        compose.runOnIdle { assertEquals(1, clicked); enabled.value = false }
        compose.onNodeWithText("Star").assertIsNotEnabled().performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, held); assertEquals(1, clicked) }
    }
}
