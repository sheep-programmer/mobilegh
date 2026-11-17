package com.mobilegh.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.LocalContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.VideoViewer
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** README 里的 <video>：原生视频卡片必须打开原生播放器（VideoViewer 收到地址）。 */
@RunWith(AndroidJUnit4::class)
class HtmlVideoTapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tappingAVideoInTheReadmeOpensTheNativePlayer() {
        val src = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"
        val nav = Navigator()
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalNav provides nav) {
                    HtmlView("<p>demo</p><video controls src=\"$src\"></video>")
                }
            }
        }
        compose.runOnUiThread { VideoViewer.close() }
        compose.onNodeWithText("放大播放").performClick()
        compose.waitUntil(5_000) { VideoViewer.url != null }
        assertEquals(src, VideoViewer.url)
        compose.runOnUiThread { VideoViewer.close() }
    }
}
