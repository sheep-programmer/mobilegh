package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.Session
import com.mobilegh.i18n.AppLanguage
import com.mobilegh.i18n.I18n
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.theme.MobileGhTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentTranslationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun modelTranslatesPublicFixtureAndCanRestoreOriginal() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationSmoke") == "true")
        val original = "This application makes it easy to browse source code and review changes with your team."
        val wasEnabled = Session.translateDocuments
        val previousLanguage = I18n.choice
        try {
            compose.runOnUiThread {
                I18n.select(AppLanguage.Chinese)
                Session.chooseDocumentTranslation(true)
            }
            compose.setContent {
                CompositionLocalProvider(LocalNav provides Navigator()) {
                    MobileGhTheme { HtmlView("<p>$original</p>") }
                }
            }
            compose.waitUntil(120_000) {
                compose.onAllNodesWithText("查看原文").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("翻译失败，正在显示原文").fetchSemanticsNodes().isNotEmpty()
            }
            val translated = compose.onAllNodesWithText("查看原文").fetchSemanticsNodes().isNotEmpty()
            if (!translated) {
                compose.onNodeWithText(original).assertExists()
                compose.onNodeWithText("重试").assertExists()
            }
            assertTrue("ML Kit model unavailable; verified that the original and Retry are shown", translated)
            compose.onNodeWithText(original).assertDoesNotExist()
            compose.onNodeWithText("查看原文").performClick()
            compose.onNodeWithText(original).assertExists()
        } finally {
            compose.runOnUiThread {
                Session.chooseDocumentTranslation(wasEnabled)
                I18n.select(previousLanguage)
            }
        }
    }

    @Test fun offscreenProseWaitsForScroll() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationSmoke") == "true")
        val previousLanguage = I18n.choice
        val wasEnabled = Session.translateDocuments
        val first = "The source directory contains examples to help developers learn the library."
        val last = "After installing the application, review the handbook and contact the maintainers for assistance."
        val html = "<p>$first</p>" + (1..70).joinToString("") { "<pre>fixture item $it</pre>" } + "<p>$last</p>"
        lateinit var scrollToEnd: () -> Unit
        try {
            compose.runOnUiThread {
                I18n.select(AppLanguage.Chinese)
                Session.chooseDocumentTranslation(true)
            }
            compose.setContent {
                CompositionLocalProvider(LocalNav provides Navigator()) {
                    val state = rememberScrollState()
                    val scope = rememberCoroutineScope()
                    scrollToEnd = { scope.launch { state.scrollTo(state.maxValue) } }
                    MobileGhTheme { Box(Modifier.fillMaxSize().verticalScroll(state)) { HtmlView(html) } }
                }
            }
            compose.waitUntil(90_000) { compose.onAllNodesWithText("查看原文").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(first).assertDoesNotExist()
            compose.onNodeWithText(last).assertExists()
            compose.runOnIdle { scrollToEnd() }
            compose.waitUntil(20_000) { compose.onAllNodesWithText(last).fetchSemanticsNodes().isEmpty() }
        } finally {
            compose.runOnUiThread {
                Session.chooseDocumentTranslation(wasEnabled)
                I18n.select(previousLanguage)
            }
        }
    }
}
