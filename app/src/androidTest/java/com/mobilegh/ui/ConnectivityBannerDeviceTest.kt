package com.mobilegh.ui

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.i18n.AppLanguage
import com.mobilegh.i18n.I18n
import com.mobilegh.ui.components.ConnectivityBanner
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectivityBannerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realConnectivityChangeKeepsContentVisibleAndRecovers() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("offlineSmoke") == "true")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
        assumeTrue(shell("getprop ro.kernel.qemu") == "1")
        val wifiEnabled = shell("settings get global wifi_on") == "1"
        val dataEnabled = shell("settings get global mobile_data") == "1"
        assumeTrue(wifiEnabled || dataEnabled)
        val previousLanguage = I18n.choice
        val offline = "网络已断开，已加载内容仍可查看"
        val recovered = "网络已恢复"
        fun visible(text: String): Boolean {
            compose.mainClock.advanceTimeBy(100)
            return compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        fun restoreNetwork() {
            if (wifiEnabled) shell("svc wifi enable")
            if (dataEnabled) shell("svc data enable")
        }
        try {
            compose.runOnUiThread { I18n.select(AppLanguage.Chinese) }
            compose.setContent {
                MobileGhTheme { Column { Text("Loaded content"); ConnectivityBanner() } }
            }
            compose.waitForIdle()
            shell("svc wifi disable")
            shell("svc data disable")
            compose.waitUntil(15_000) { visible(offline) }
            compose.onNodeWithText("Loaded content").assertExists()
            restoreNetwork()
            compose.waitUntil(35_000) { visible(recovered) }
            compose.waitUntil(8_000) { !visible(recovered) }
            compose.onNodeWithText(offline).assertDoesNotExist()
        } finally {
            restoreNetwork()
            compose.runOnUiThread { I18n.select(previousLanguage) }
        }
    }
}
