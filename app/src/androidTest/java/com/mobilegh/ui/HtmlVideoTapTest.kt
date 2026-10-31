package com.mobilegh.ui

import android.view.View
import android.webkit.WebView
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
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** README 里的 <video>：在真实 WebView 里点它，必须打开原生播放器（VideoViewer 收到地址）。 */
@RunWith(AndroidJUnit4::class)
class HtmlVideoTapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun findWebView(root: View): WebView? {
        if (root is WebView) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) findWebView(root.getChildAt(i))?.let { return it }
        return null
    }

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
        compose.waitForIdle()
        compose.runOnUiThread { VideoViewer.close() }
        // 等网页加载完，再在页面里点击视频
        Thread.sleep(2500)
        val webView = findWebView(compose.activity.window.decorView)
        assertNotNull("webview should exist", webView)
        compose.runOnUiThread {
            webView!!.evaluateJavascript("document.querySelector('video').click();", null)
        }
        compose.waitUntil(5_000) { VideoViewer.url != null }
        assertEquals(src, VideoViewer.url)
        compose.runOnUiThread { VideoViewer.close() }
    }
}
