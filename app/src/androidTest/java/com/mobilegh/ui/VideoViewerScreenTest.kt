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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoViewerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun viewerPlaysTheVideoOverHttp() = withVideoFixture { src ->
        var player: ExoPlayer? = null
        compose.setContent {
            MobileGhTheme {
                VideoViewerContent(
                    src,
                    onPlayer = { player = it },
                )
            }
        }
        // ExoPlayer 只能在主线程访问，所有读取都放到主线程上
        compose.waitUntil(20_000) { onMain { player?.playbackState } == Player.STATE_READY }
        val p = player!!
        assertNull("player error: ${onMain { p.playerError }}", onMain { p.playerError })
        val start = onMain { p.currentPosition }
        compose.waitUntil(5_000) { onMain { p.currentPosition } > start }
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

internal fun withVideoFixture(block: (String) -> Unit) {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    val bytes = assets.open("inline-video.mp4.base64").bufferedReader().use { reader ->
        android.util.Base64.decode(reader.readText(), android.util.Base64.DEFAULT)
    }
    LoopbackApi.media { LoopbackApi.Response(200, "video/mp4", bytes) }.use { server -> block("${server.base}/clip.mp4") }
}
