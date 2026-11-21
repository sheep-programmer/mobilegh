package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
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

    @Test fun narrowAssetRowKeepsSuffixAndPlacesDownloadCountBelowFilename() {
        val filename = "MobileGH-特别长的发布构建名称-with-an-extremely-long-build-identifier-linux-arm64.tar.gz"
        serveRelease(releaseBody("", listOf(filename)))
        show(Screen.Releases("fixture", "notes")) {
            Box(Modifier.width(280.dp)) { ReleasesScreen("fixture", "notes") }
        }
        val fullName = compose.onNodeWithText(filename, useUnmergedTree = true)
        compose.waitUntil(8_000) { fullName.isDisplayed() }
        val suffix = compose.onNodeWithText(".tar.gz", useUnmergedTree = true)
        val basename = compose.onNodeWithText(filename.removeSuffix(".tar.gz"), useUnmergedTree = true)
        val count = compose.onNodeWithText("123 次下载", substring = true, useUnmergedTree = true)
        val suffixLayouts = mutableListOf<TextLayoutResult>()
        suffix.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(suffixLayouts) }
        val suffixLayout = suffixLayouts.single()
        assertEquals("The entire suffix must be visible", ".tar.gz".length, suffixLayout.getLineEnd(0, visibleEnd = true))
        val nameLayouts = mutableListOf<TextLayoutResult>()
        basename.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(nameLayouts) }
        val nameLayout = nameLayouts.single()
        assertTrue("Basename must be shortened: ${nameLayout.size}, ${nameLayout.layoutInput.constraints}", nameLayout.isLineEllipsized(0))
        assertTrue(count.fetchSemanticsNode().boundsInRoot.top >= suffix.fetchSemanticsNode().boundsInRoot.bottom)
        compose.onNodeWithText(filename).performClick()
        compose.onNodeWithText("文件信息").assertIsDisplayed()
        compose.onNode(hasText(filename) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun platformPickerFiltersArchivesAndInstallersWithoutShowingChecksums() {
        val android = "client-linux-android.APKS"
        val windows = "client-windows11-x64.zip"
        val mac = "client-darwin-arm64.tar.gz"
        val linux = "client-ubuntu22.04-x64.tar.zst"
        val checksum = "$windows.sha256"
        val assets = listOf(android, windows, mac, linux, checksum)
        serveRelease(releaseBody("", assets))
        show(Screen.Releases("fixture", "notes")) { ReleasesScreen("fixture", "notes") }
        compose.waitUntil(8_000) { compose.onNodeWithText(android).isDisplayed() }
        var selected = "全部平台"
        for ((platform, file) in listOf("Android" to android, "Windows" to windows, "macOS" to mac, "Linux" to linux)) {
            compose.onNodeWithText(selected).performClick()
            compose.onNodeWithText(platform).performClick()
            compose.onNodeWithText(file).assertIsDisplayed()
            assets.filterNot { it == file }.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
            selected = platform
        }
        compose.onNodeWithText(selected).performClick()
        compose.onNodeWithText("全部平台").performClick()
        compose.onNodeWithText(checksum).assertIsDisplayed()
    }

    private fun serveRelease(release: String) = serve { path ->
        when (path.substringBefore('?')) {
            "/repos/fixture/notes" -> 200 to "{}"
            "/repos/fixture/notes/releases" -> 200 to "[$release]"
            "/repos/fixture/notes/releases/latest" -> 200 to release
            else -> 404 to "{}"
        }
    }

    private fun releaseBody(body: String, assets: List<String> = listOf("mobilegh-universal.apk")): String = buildJsonObject {
        put("id", 42)
        put("tag_name", "v987.0.0")
        put("name", "Test release")
        put("body", body)
        put("assets", buildJsonArray {
            for (file in assets) add(buildJsonObject {
                put("name", file)
                put("browser_download_url", "https://example.test/asset")
                put("size", 1000)
                put("download_count", 123)
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
