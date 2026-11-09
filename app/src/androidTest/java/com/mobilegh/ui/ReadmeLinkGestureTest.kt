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
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.LinkMenu
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
