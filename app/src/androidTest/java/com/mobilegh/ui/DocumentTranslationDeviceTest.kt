package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.OfflineTranslation
import com.mobilegh.data.Session
import com.mobilegh.i18n.AppLanguage
import com.mobilegh.i18n.I18n
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.components.DocumentTranslation
import com.mobilegh.ui.components.TranslationParagraphCache
import com.mobilegh.ui.components.ParsedHtml
import com.mobilegh.ui.components.HtmlBlock
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.Inline
import com.mobilegh.ui.components.InlineStyle
import com.mobilegh.ui.components.translateDocumentBlocks
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.TranslationMoreMenu
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.screens.SettingsScreen
import com.mobilegh.ui.theme.MobileGhTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentTranslationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val prefs get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("session", android.content.Context.MODE_PRIVATE)
    private var oldConsent = false

    @org.junit.Before fun rememberConsent() { oldConsent = prefs.getBoolean("translate_documents", false) }

    @org.junit.After fun restoreConsent() {
        compose.runOnUiThread { Session.disableDocumentTranslation() }
        prefs.edit().putBoolean("translate_documents", oldConsent).apply()
        if (oldConsent) runBlocking { Session.restoreDocumentTranslation() }
    }

    @Test fun enablingTranslationFirstAsksForModelsAndCancelKeepsTheSwitchOff() {
        val previousLanguage = I18n.choice
        val nav = Navigator()
        try {
            compose.runOnUiThread {
                I18n.select(AppLanguage.Chinese)
                Session.disableDocumentTranslation()
            }
            compose.setContent {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                    MobileGhTheme { SettingsScreen() }
                }
            }
            compose.onNodeWithText("外观与语言").performScrollTo().performClick()
            compose.onNodeWithText("离线翻译").performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue("The switch must stay off before model download and verification", !Session.translateDocuments)
            }
            compose.onNodeWithText("下载离线翻译模型").assertExists()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("下载离线翻译模型").assertDoesNotExist()
            compose.runOnIdle { assertTrue(!Session.translateDocuments) }
        } finally {
            compose.runOnUiThread {
                nav.dispose()
                Session.disableDocumentTranslation()
                I18n.select(previousLanguage)
            }
        }
    }

    @Test fun confirmingModelDownloadEnablesTranslationAfterVerification() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationSetup") == "true")
        val previousLanguage = I18n.choice
        val nav = Navigator()
        try {
            compose.runOnUiThread {
                I18n.select(AppLanguage.Chinese)
                Session.disableDocumentTranslation()
            }
            compose.setContent {
                CompositionLocalProvider(LocalNav provides nav, LocalEntry provides nav.root) {
                    MobileGhTheme { SettingsScreen() }
                }
            }
            compose.onNodeWithText("外观与语言").performScrollTo().performClick()
            compose.onNodeWithText("离线翻译").performScrollTo().performClick()
            compose.onNodeWithText("下载", substring = false).performClick()
            compose.waitUntil(260_000) {
                Session.translateDocuments || compose.onAllNodesWithText("下载或校验失败，请检查网络和存储空间后重试")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle {
                assertTrue("Verified model should enable translation", Session.translateDocuments)
                assertTrue("The verified switch should be saved", prefs.getBoolean("translate_documents", false))
            }
        } finally {
            compose.runOnUiThread {
                nav.dispose()
                I18n.select(previousLanguage)
            }
        }
    }

    @Test fun unavailableModelCannotRestoreAPreviouslyEnabledSwitch() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationUnavailable") == "true")
        compose.runOnUiThread { Session.disableDocumentTranslation() }
        prefs.edit().putBoolean("translate_documents", true).commit()
        Session.restoreDocumentTranslation()
        assertTrue("An old preference must not bypass verification", !Session.translateDocuments)
        assertTrue("An unusable model must clear the saved switch", !prefs.getBoolean("translate_documents", false))
    }

    @Test fun translationMenuIsOnlyAvailableWhenOfflineTranslationIsEnabled() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationSmoke") == "true")
        val previousLanguage = I18n.choice
        val nav = Navigator()
        try {
            compose.runOnUiThread {
                I18n.select(AppLanguage.Chinese)
                Session.disableDocumentTranslation()
            }
            compose.setContent {
                CompositionLocalProvider(LocalNav provides nav) {
                    MobileGhTheme {
                        Page("README", translationInMenu = true, actions = {
                            TranslationMoreMenu(listOf(MenuAction("复制链接") {}))
                        }) { pad -> Text("Original content", Modifier.padding(pad)) }
                    }
                }
            }
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("翻译").assertDoesNotExist()
            compose.onNodeWithText("显示原文").assertDoesNotExist()
            compose.onNodeWithText("复制链接").performClick()
            runBlocking { Session.enableDocumentTranslation() }
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("显示原文").performClick()
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("翻译").performClick()
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("显示原文").assertExists()
            compose.runOnUiThread { Session.disableDocumentTranslation() }
            compose.onNodeWithText("显示原文").assertDoesNotExist()
            compose.onNodeWithText("翻译").assertDoesNotExist()
            compose.onNodeWithText("复制链接").performClick()
        } finally {
            compose.runOnUiThread {
                nav.dispose()
                Session.disableDocumentTranslation()
                I18n.select(previousLanguage)
            }
        }
    }

    @Test fun modelTranslatesPublicFixtureAndCanRestoreOriginal() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationSmoke") == "true")
        val original = "This application makes it easy to browse source code and review changes with your team."
        val previousLanguage = I18n.choice
        try {
            compose.runOnUiThread {
                I18n.select(AppLanguage.Chinese)
            }
            runBlocking { Session.enableDocumentTranslation() }
            compose.setContent {
                CompositionLocalProvider(LocalNav provides Navigator()) {
                    MobileGhTheme {
                        Page("README", translationInMenu = true, actions = { TranslationMoreMenu(emptyList()) }) { pad ->
                            HtmlView("<p>$original</p>", Modifier.padding(pad))
                        }
                    }
                }
            }
            compose.waitUntil(120_000) {
                compose.onAllNodesWithContentDescription("由 Google 自动翻译").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("翻译失败，正在显示原文").fetchSemanticsNodes().isNotEmpty()
            }
            val translated = compose.onAllNodesWithContentDescription("由 Google 自动翻译").fetchSemanticsNodes().isNotEmpty()
            if (!translated) {
                compose.onNodeWithText(original).assertExists()
                compose.onNodeWithText("重试").assertExists()
            }
            assertTrue("ML Kit model unavailable; verified that the original and Retry are shown", translated)
            compose.onNodeWithText(original).assertDoesNotExist()
            compose.onNodeWithText("显示原文").assertDoesNotExist()
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("显示原文").performClick()
            compose.onNodeWithText(original).assertExists()
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("翻译").performClick()
            compose.onNodeWithText(original).assertDoesNotExist()
        } finally {
            compose.runOnUiThread {
                Session.disableDocumentTranslation()
                I18n.select(previousLanguage)
            }
        }
    }

    @Test fun localModelPreservesFormattedSentence() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationSmoke") == "true")
        val style = InlineStyle(bold = true, link = "https://github.com/owner/repo")
        val code = Inline.Text("git pull", InlineStyle(code = true))
        val original = HtmlBlock.Paragraph(listOf(
            Inline.Text("For owner/repo v1.2.3, read the "),
            Inline.Text("upgrade guide", style),
            Inline.Text(" before running "), code, Inline.Text("."),
        ))
        OfflineTranslation.prepare(download = true)
        var calls = 0
        val result = translateDocumentBlocks(listOf(original)) {
            calls++
            OfflineTranslation.translate("en", "zh", it)
        }.single() as HtmlBlock.Paragraph
        assertEquals("The on-device engine should keep the formatting markers", 1, calls)
        val text = result.inlines.filterIsInstance<Inline.Text>()
        assertTrue(text.any { it.style == style && it.text.any { ch -> ch in '\u4e00'..'\u9fff' } })
        assertTrue(text.contains(code))
        assertTrue(text.joinToString("") { it.text }.contains("owner/repo"))
        assertTrue(text.joinToString("") { it.text }.contains("v1.2.3"))
    }

    @Test fun chineseIntroductionDoesNotPreventTranslationOfEnglishParagraph() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationSmoke") == "true")
        val cn = HtmlBlock.Paragraph(listOf(Inline.Text("这里介绍如何安装应用、配置项目并阅读发布说明。".repeat(65))))
        val en = HtmlBlock.Paragraph(listOf(Inline.Text("Read the installation guide before configuring the application.")))
        OfflineTranslation.prepare(download = true)
        val engine = DocumentTranslation(ParsedHtml(listOf(cn, en)), "zh", TranslationParagraphCache(), "test")
        assertEquals(cn, engine.translate(cn))
        val translated = engine.translate(en) as HtmlBlock.Paragraph
        assertTrue(translated.inlines.filterIsInstance<Inline.Text>().any { part ->
            part.text.any { it in '\u4e00'..'\u9fff' }
        })
    }

    @Test fun offscreenProseWaitsForScroll() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationSmoke") == "true")
        val previousLanguage = I18n.choice
        val first = "The source directory contains examples to help developers learn the library."
        val last = "After installing the application, review the handbook and contact the maintainers for assistance."
        val html = "<p>$first</p>" + (1..70).joinToString("") { "<pre>fixture item $it</pre>" } + "<p>$last</p>"
        lateinit var scrollToEnd: () -> Unit
        try {
            compose.runOnUiThread {
                I18n.select(AppLanguage.Chinese)
            }
            runBlocking { Session.enableDocumentTranslation() }
            compose.setContent {
                CompositionLocalProvider(LocalNav provides Navigator()) {
                    val state = rememberScrollState()
                    val scope = rememberCoroutineScope()
                    scrollToEnd = { scope.launch { state.scrollTo(state.maxValue) } }
                    MobileGhTheme { Box(Modifier.fillMaxSize().verticalScroll(state)) { HtmlView(html) } }
                }
            }
            compose.waitUntil(90_000) { compose.onAllNodesWithContentDescription("由 Google 自动翻译").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(first).assertDoesNotExist()
            compose.onNodeWithText(last).assertExists()
            compose.runOnIdle { scrollToEnd() }
            compose.waitUntil(20_000) { compose.onAllNodesWithText(last).fetchSemanticsNodes().isEmpty() }
        } finally {
            compose.runOnUiThread {
                Session.disableDocumentTranslation()
                I18n.select(previousLanguage)
            }
        }
    }
}
