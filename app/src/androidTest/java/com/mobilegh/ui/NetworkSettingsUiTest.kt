package com.mobilegh.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.Net
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Tab
import com.mobilegh.ui.screens.SettingsScreen
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nav = Navigator()
    private lateinit var previous: NetSnapshot
    private var previousLastTest = 0L

    @Before fun saveNetworkSettings() {
        previous = NetSnapshot(
            Net.rawMode,
            Net.downloadMode,
            Net.manualRawId,
            Net.manualDownloadId,
        )
        previousLastTest = Net.lastTest
        Net.lastTest = System.currentTimeMillis()
        Net.rawMode = 0
        Net.downloadMode = 0
        Net.manualRawId = "jsd-mirror"
        Net.manualDownloadId = "gh-proxy"
    }

    @After fun restoreNetworkSettings() {
        Net.setRawManual(previous.manualRawId)
        Net.changeRawMode(previous.rawMode)
        Net.setDownloadManual(previous.manualDownloadId)
        Net.changeDownloadMode(previous.downloadMode)
        Net.lastTest = previousLastTest
        compose.runOnUiThread { nav.dispose() }
    }

    @Test fun fileAndDownloadModesAndNodesCanBeChosenIndependently() {
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(
                    LocalNav provides nav,
                    LocalEntry provides nav.tabEntries.getValue(Tab.Home),
                ) {
                    SettingsScreen()
                }
            }
        }
        compose.onNodeWithText("网络与加速").performScrollTo().performClick()
        compose.onNodeWithText("文件与图片").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("下载与克隆").performScrollTo().assertIsDisplayed()

        compose.onAllNodesWithText("自动（推荐）").onFirst().performClick()
        compose.onAllNodesWithText("手动选择").onLast().performClick()
        compose.onAllNodesWithText("jsDelivr · jsdmirror.com").onFirst().performClick()
        compose.onAllNodesWithText("jsDelivr · Fastly").onLast().performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, Net.rawMode)
            assertEquals("jsd-fastly", Net.manualRawId)
            assertEquals(0, Net.downloadMode)
            assertEquals("gh-proxy", Net.manualDownloadId)
        }

        compose.onNodeWithText("自动（推荐）").performClick()
        compose.onAllNodesWithText("手动选择").onLast().performClick()
        compose.onAllNodesWithText("gh-proxy.com").onFirst().performClick()
        compose.onAllNodesWithText("ghfast.top").onLast().performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, Net.rawMode)
            assertEquals("jsd-fastly", Net.manualRawId)
            assertEquals(1, Net.downloadMode)
            assertEquals("ghfast", Net.manualDownloadId)
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "network-settings-independent.png",
        )
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private data class NetSnapshot(
        val rawMode: Int,
        val downloadMode: Int,
        val manualRawId: String,
        val manualDownloadId: String,
    )
}
