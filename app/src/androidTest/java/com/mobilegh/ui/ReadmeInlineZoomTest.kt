package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.ui.components.ReadmeInlineZoomBox
import com.mobilegh.ui.components.ReadmeZoomResetPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadmeInlineZoomTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun testPinchToZoomAndReset() {
        var scale by mutableFloatStateOf(1.0f)
        var offset by mutableStateOf(Offset.Zero)

        composeTestRule.setContent {
            Box(Modifier.fillMaxSize()) {
                ReadmeInlineZoomBox(
                    scale = scale,
                    offset = offset,
                    onTransform = { s, o ->
                        scale = s
                        offset = o
                    },
                    modifier = Modifier.size(300.dp).testTag("zoom_box"),
                ) {
                    Text("Sample Readme Content")
                }
                ReadmeZoomResetPill(
                    scale = scale,
                    onReset = {
                        scale = 1.0f
                        offset = Offset.Zero
                    },
                    modifier = Modifier.testTag("reset_pill"),
                )
            }
        }

        // Initially at scale 1.0, reset pill should NOT be displayed
        composeTestRule.onNodeWithTag("reset_pill").assertDoesNotExist()

        // Perform pinch zoom
        composeTestRule.onNodeWithTag("zoom_box").performTouchInput {
            pinch(
                start0 = Offset(centerX - 20f, centerY),
                end0 = Offset(centerX - 100f, centerY),
                start1 = Offset(centerX + 20f, centerY),
                end1 = Offset(centerX + 100f, centerY),
            )
        }

        composeTestRule.waitForIdle()

        // Scale should now be > 1.05f
        assertTrue("Expected scale > 1.05f, got $scale", scale > 1.05f)

        // Reset pill should now be visible
        composeTestRule.onNodeWithTag("reset_pill").assertIsDisplayed()

        // Clicking reset pill should reset scale back to 1.0f
        composeTestRule.onNodeWithTag("reset_pill").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1.0f, scale, 0.001f)
        assertEquals(Offset.Zero, offset)

        // Reset pill should disappear after reset
        composeTestRule.onNodeWithTag("reset_pill").assertDoesNotExist()
    }
}
