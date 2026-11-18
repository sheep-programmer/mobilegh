package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.Net
import com.mobilegh.data.Update
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.screens.ReleasesScreen
import com.mobilegh.ui.screens.UpdateDialog
import com.mobilegh.ui.theme.MobileGhTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseNotesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var server: LoopbackApi? = null
    private var previousProxy: String? = null
    private var nav: Navigator? = null

    @After fun tearDown() {
        compose.runOnUiThread {
            nav?.dispose()
            previousProxy?.let { Net.apiProxy = it }
        }
        server?.close()
    }

    @Test fun longUpdateNotesScrollThroughTheLastLineWithActionsVisible() {
        val end = "End of the update announcement"
        val notes = (1..65).joinToString("\n") { "Change $it: regression fixes and layout improvements." } + "\n$end"
        val available = AtomicBoolean(true)
        serve { path ->
            if (!available.get() || path != "/repos/sheep-programmer/mobilegh/releases/latest") 404 to "{}"
            else 200 to releaseBody(notes)
        }
        val fullText = compose.onNodeWithText(end, substring = true, useUnmergedTree = true)
        try {
            compose.waitUntil(15_000) { !Update.checking }
            assertEquals(notes, runBlocking { Update.check() }?.notes)
            show(Screen.Root) { UpdateDialog(onDismiss = {}, onSkip = {}) }
            compose.waitUntil(8_000) { fullText.isDisplayed() }
            val layouts = mutableListOf<TextLayoutResult>()
            fullText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().getLineForOffset(notes.length - 1) >= 65)
            assertTrue(!layouts.single().hasVisualOverflow)

            val scroller = compose.onNode(hasScrollAction())
            val axis = scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(axis.maxValue() > 0)
            repeat(5) {
                if (axis.value() < axis.maxValue() - 1) scroller.performTouchInput { swipeUp() }
            }
            compose.waitUntil(5_000) { axis.value() >= axis.maxValue() - 1 }
            compose.onNodeWithText("下载更新").assertIsDisplayed()
            compose.onNodeWithText("稍后").assertIsDisplayed()
            compose.onNodeWithText("跳过此版本").assertIsDisplayed()
        } finally {
            available.set(false)
            runBlocking { Update.check() }
        }
    }

    @Test fun releaseWithoutRenderedHtmlStillShowsItsFullMarkdown() {
        val end = "End of the release description"
        val notes = "# Changelog\n\nFirst section.\n\n$end"
        val release = releaseBody(notes)
        serve { path ->
            when (path.substringBefore('?')) {
                "/repos/fixture/notes" -> 200 to "{}"
                "/repos/fixture/notes/releases" -> 200 to "[$release]"
                "/repos/fixture/notes/releases/latest" -> 200 to release
                else -> 404 to "{}"
            }
        }
        show(Screen.Releases("fixture", "notes")) { ReleasesScreen("fixture", "notes") }
        val expand = compose.onNodeWithText("展开说明")
        compose.waitUntil(8_000) { expand.isDisplayed() }
        expand.performClick()
        val fullText = compose.onNodeWithText(notes, useUnmergedTree = true)
        compose.waitUntil(5_000) { fullText.isDisplayed() }
        compose.onNodeWithText("收起").performScrollTo().assertIsDisplayed()
    }

    private fun releaseBody(body: String): String = buildJsonObject {
        put("id", 42)
        put("tag_name", "v987.0.0")
        put("name", "Test release")
        put("body", body)
        put("assets", buildJsonArray {
            add(buildJsonObject {
                put("name", "mobilegh-universal.apk")
                put("browser_download_url", "https://example.test/mobilegh-universal.apk")
                put("size", 1000)
            })
        })
    }.toString()

    private fun serve(handler: (String) -> Pair<Int, String>) {
        previousProxy = Net.apiProxy
        server = LoopbackApi { _, path, _ -> handler(path) }.also { Net.apiProxy = it.base }
    }

    private fun show(screen: Screen, content: @Composable () -> Unit) {
        val navigator = Navigator()
        nav = navigator
        compose.runOnUiThread { navigator.push(screen) }
        compose.setContent {
            MobileGhTheme { CompositionLocalProvider(LocalNav provides navigator, LocalEntry provides navigator.top) { content() } }
        }
    }
}
