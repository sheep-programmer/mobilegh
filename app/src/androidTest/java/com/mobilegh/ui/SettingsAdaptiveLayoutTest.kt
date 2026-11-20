package com.mobilegh.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.R
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.ui.components.MenuRow
import com.mobilegh.ui.screens.SettingsScreen
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsAdaptiveLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longSummaryWrapsBelowTitleAtLargeFontSize() {
        val summary = "文件 自动（推荐） · 下载 自动（推荐） · 自定义镜像 example.org"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MobileGhTheme {
                    Box(Modifier.width(300.dp).testTag("settings-row")) {
                        MenuRow(R.drawable.oc_globe, "网络与加速", value = summary) {}
                    }
                }
            }
        }
        val titleLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("网络与加速", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertEquals(1, titleLayouts.single().lineCount)
        val summaryLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(summary, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(summaryLayouts) }
        assertTrue(summaryLayouts.single().lineCount > 1)
        assertTrue(!summaryLayouts.single().hasVisualOverflow)
        val title = compose.onNodeWithText("网络与加速", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText(summary, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Summary must be entirely below the title", value.top >= title.bottom)
        screenshot("settings-row", "settings-large-font.png")
    }

    @Test fun settingsHomeKeepsLongSummariesReadableAndClickable() {
        val nav = Navigator()
        compose.setContent {
            CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                MobileGhTheme {
                    Box(Modifier.width(320.dp).testTag("settings-home")) { SettingsScreen() }
                }
            }
        }
        compose.onNodeWithText("网络与加速").performScrollTo().assertIsDisplayed()
        screenshot("settings-home", "settings-home-narrow.png")
        compose.onNodeWithText("网络与加速").performClick()
        compose.onNodeWithText("文件与图片").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("下载与克隆").performScrollTo().assertIsDisplayed()
    }

    @Test fun wideLayoutUsesFullWidthAndReturnsToBottomNavigationInNarrowWindow() {
        val nav = Navigator()
        val wide = mutableStateOf(true)
        var compositions = 0
        compose.setContent {
            MobileGhTheme {
                Box(Modifier.requiredSize(if (wide.value) 1100.dp else 360.dp, 650.dp).testTag("adaptive-frame")) {
                    RootAdaptiveLayout(nav) { rail ->
                        remember { compositions++ }
                        Box(Modifier.fillMaxSize().testTag("content")) {
                            CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                                SettingsScreen()
                            }
                        }
                        if (!rail) Box(Modifier.align(Alignment.BottomCenter)) { RootNavigationBar(nav) }
                    }
                }
            }
        }
        val density = compose.density
        val wideContent = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertTrue("The content must extend beyond the old 900dp limit", wideContent.width / density.density > 900f)
        compose.runOnIdle { nav.push(Screen.Repo("octocat", "layout-fixture")) }
        compose.onNodeWithText(Tab.Star.title).performClick()
        compose.runOnIdle {
            assertEquals(Tab.Star, nav.tab)
            assertTrue(nav.stack.isEmpty())
        }
        screenshot("adaptive-frame", "tablet-full-width.png")
        compose.runOnIdle { wide.value = false }
        val narrowContent = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertEquals(360f, narrowContent.width / density.density, 1f)
        compose.onNodeWithText(Tab.Repos.title).performClick()
        compose.runOnIdle {
            assertEquals(Tab.Repos, nav.tab)
            assertEquals("Changing window size must preserve content state", 1, compositions)
        }
    }

    private fun screenshot(tag: String, name: String) {
        val image = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
