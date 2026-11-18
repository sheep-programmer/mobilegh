package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.ui.components.ExternalLinkDialog
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.ImageViewer
import com.mobilegh.ui.components.LinkMenu
import com.mobilegh.ui.theme.MobileGhTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadmeLinkGestureTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setup() {
        LinkMenu.close()
    }

    @After
    fun closeOverlays() {
        composeTestRule.runOnIdle {
            ImageViewer.close()
            ExternalLinkDialog.dismiss()
        }
    }

    @Test
    fun readmeLinkedImageOpensItsTargetAndLongPressPreviewsTheImage() {
        val target = "https://example.org/project"
        withImageFixture { source ->
            showReadme("""<p><a href="$target"><img src="$source" width="80" height="60" alt="Project cover"></a></p>""")
            val image = composeTestRule.onNodeWithContentDescription("Project cover")
            composeTestRule.waitUntil(8_000) { image.isDisplayed() }
            image.performTouchInput { click() }
            composeTestRule.runOnIdle {
                assertEquals(target, ExternalLinkDialog.current?.url)
                assertNull(ImageViewer.url)
                ExternalLinkDialog.dismiss()
            }
            image.performTouchInput { longClick() }
            composeTestRule.runOnIdle {
                assertEquals(source, ImageViewer.url)
                assertNull(ExternalLinkDialog.current)
            }
        }
    }

    @Test
    fun unlinkedReadmeImageStillPreviewsOnTap() = withImageFixture { source ->
        showReadme("""<p><img src="$source" width="80" height="60" alt="Plain screenshot"></p>""")
        val image = composeTestRule.onNodeWithContentDescription("Plain screenshot")
        composeTestRule.waitUntil(8_000) { image.isDisplayed() }
        image.performTouchInput { click() }
        composeTestRule.runOnIdle {
            assertEquals(source, ImageViewer.url)
            assertNull(ExternalLinkDialog.current)
        }
    }

    @Test
    fun imageInTableRecoversFromTemporaryServerErrorWithoutATap() {
        val attempts = AtomicInteger()
        val png = samplePng()
        LoopbackApi.media {
            if (attempts.incrementAndGet() == 1) LoopbackApi.Response(503, "text/plain")
            else LoopbackApi.Response(200, "image/png", png)
        }.use { server ->
            showReadme("""<table><tr><td><img src="${server.base}/cover.png" height="80" alt="Icon preview"></td></tr></table>""")
            val image = composeTestRule.onNodeWithContentDescription("Icon preview")
            composeTestRule.waitUntil(10_000) { image.isDisplayed() }
            assertEquals(2, attempts.get())
        }
    }

    @Test
    fun removedImageInTableShowsUnavailableInsteadOfRetrying() {
        LoopbackApi.media { LoopbackApi.Response(404, "text/plain") }.use { server ->
            showReadme("""<table><tr><td><img src="${server.base}/removed.png" height="80" alt="Removed preview"></td></tr></table>""")
            val unavailable = composeTestRule.onNodeWithText("图片已失效")
            composeTestRule.waitUntil(10_000) { unavailable.isDisplayed() }
            unavailable.assertIsNotEnabled()
            assertEquals(listOf("GET /removed.png"), server.requests)
        }
    }

    private fun showReadme(html: String) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalNav provides Navigator()) {
                MobileGhTheme { HtmlView(html, linkedImagesOpenLink = true) }
            }
        }
    }

    private fun samplePng(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(80, 60, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        val png = java.io.ByteArrayOutputStream().apply {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        bitmap.recycle()
        return png
    }

    private fun withImageFixture(block: (String) -> Unit) {
        val png = samplePng()
        LoopbackApi.media { LoopbackApi.Response(200, "image/png", png) }.use { server ->
            block("${server.base}/cover.png")
        }
    }

    @Test
    fun testSwipeAcrossLinkDoesNotTriggerPopup() {
        val sampleHtml = """
            <p>Welcome to project. Check out the <a href="https://github.com/example/repo">Documentation Link</a> for details.</p>
        """.trimIndent()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalNav provides Navigator()) {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    HtmlView(
                        html = sampleHtml,
                        modifier = Modifier.testTag("html_view"),
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        assertNull("LinkMenu should initially be closed", LinkMenu.url)

        // Perform a swipe/drag gesture across the link
        composeTestRule.onNodeWithTag("html_view").performTouchInput {
            // Drag across the text horizontally
            swipe(
                start = Offset(centerX, centerY),
                end = Offset(centerX + 200f, centerY + 300f),
                durationMillis = 150,
            )
        }

        composeTestRule.waitForIdle()

        // LinkMenu MUST NOT be shown after swiping/sliding!
        assertNull("LinkMenu must NOT pop up during sliding/swiping", LinkMenu.url)
    }

    @Test
    fun testLongPressOnLinkTriggersPopup() {
        val sampleHtml = """
            <p><a href="https://github.com/example/repo">Clickable Link</a></p>
        """.trimIndent()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalNav provides Navigator()) {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    HtmlView(
                        html = sampleHtml,
                        modifier = Modifier.testTag("html_view"),
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        assertNull("LinkMenu should initially be closed", LinkMenu.url)

        // Perform genuine long press on the link (positioned at the start of the first paragraph)
        composeTestRule.onNodeWithTag("html_view").performTouchInput {
            longClick(Offset(24.dp.toPx(), 16.dp.toPx()))
        }

        composeTestRule.waitForIdle()

        // LinkMenu MUST be shown after long press!
        assertEquals("https://github.com/example/repo", LinkMenu.url)

        // Close menu
        LinkMenu.close()
        assertNull(LinkMenu.url)
    }
}
