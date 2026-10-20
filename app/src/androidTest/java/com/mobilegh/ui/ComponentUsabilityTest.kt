package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.RadioRow
import com.mobilegh.ui.components.Segmented
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentUsabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longPressButtonHasButtonRoleAndDisabledInteraction() {
        val enabled = mutableStateOf(true)
        var clicks = 0
        var longClicks = 0
        compose.setContent {
            MobileGhTheme {
                GhButton("Star", primary = true, enabled = enabled.value,
                    onLongClick = { longClicks++ }, onClick = { clicks++ })
            }
        }
        val button = compose.onNodeWithText("Star")
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        button.performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, longClicks); assertEquals(0, clicks) }
        compose.runOnIdle { enabled.value = false }
        button.assertIsNotEnabled()
        button.performTouchInput { click(); longClick() }
        compose.runOnIdle { assertEquals(1, longClicks); assertEquals(0, clicks) }
    }

    @Test fun longPressAndMaterialButtonsUseTheSameDisabledContainer() {
        compose.setContent {
            MobileGhTheme {
                Column {
                    GhButton("Material", Modifier.width(160.dp), primary = true, enabled = false) {}
                    GhButton("Long press", Modifier.width(160.dp), primary = true, enabled = false, onLongClick = {}) {}
                }
            }
        }
        fun background(label: String): Color {
            val pixels = compose.onNodeWithText(label).captureToImage().toPixelMap()
            return pixels[8, pixels.height / 2]
        }
        assertEquals(background("Material"), background("Long press"))
    }

    @Test fun selectionControlsExposeStateAndOneSwitchTargetAtLargeFonts() {
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Column(Modifier.width(280.dp)) {
                        Segmented(listOf("GitHub", "本机"), selected = 0) {}
                        RadioRow(selected = true, title = "当前选项") {}
                        SwitchRow("自动刷新", checked = true) {}
                    }
                }
            }
        }
        compose.onNodeWithText("GitHub").assertIsSelected().assertIsEnabled()
        compose.onNodeWithText("当前选项").assertIsSelected()
        compose.onNodeWithText("自动刷新").assertIsOn()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        val bounds = compose.onNodeWithText("本机").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
    }
}
