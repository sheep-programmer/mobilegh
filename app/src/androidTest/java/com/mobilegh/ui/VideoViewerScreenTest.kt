package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.ui.components.VideoViewerContent
import com.mobilegh.ui.theme.MobileGhTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实网络 + 真实 ExoPlayer：不依赖截图（系统 UI 偶发 ANR 会遮挡画面），
 * 直接读播放器状态：已就绪、没有错误、播放位置在前进。
 */
@RunWith(AndroidJUnit4::class)
class VideoViewerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun viewerPlaysTheVideoFromTheNetwork() {
        var player: ExoPlayer? = null
        compose.setContent {
            MobileGhTheme {
                VideoViewerContent(
                    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4",
                    onPlayer = { player = it },
                )
            }
        }
        // ExoPlayer 只能在主线程访问，所有读取都放到主线程上
        compose.waitUntil(20_000) { onMain { player?.playbackState } == Player.STATE_READY }
        val p = player!!
        assertNull("player error: ${onMain { p.playerError }}", onMain { p.playerError })
        val start = onMain { p.currentPosition }
        // 等两秒，位置必须前进：说明真的在播，而不是卡在缓冲或一直停在 0
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(2000)
        val end = onMain { p.currentPosition }
        assertTrue("playback position should advance ($start -> $end)", end > start)
        // 倍速：切到 2x 后播放参数必须真的生效
        onMain { p.playbackParameters = androidx.media3.common.PlaybackParameters(2f) }
        assertEquals(2f, onMain { p.playbackParameters.speed }, 0.001f)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        compose.runOnUiThread { result = block() }
        return result as T
    }
}
