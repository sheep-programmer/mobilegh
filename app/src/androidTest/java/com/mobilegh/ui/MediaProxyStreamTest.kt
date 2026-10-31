package com.mobilegh.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.MediaProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实网络：从公开测试视频源站流式读取，验证 Range 原样转发、不把整个文件读进内存。 */
@RunWith(AndroidJUnit4::class)
class MediaProxyStreamTest {
    private val video = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"

    @Test fun rangeRequestReturnsPartialContentWithOnlyTheRequestedBytes() {
        val s = MediaProxy.open(video, "bytes=0-99")
        assertNotNull(s)
        s!!.body.use { body ->
            assertEquals(206, s.code)
            assertTrue(s.headers["Content-Range"]!!.startsWith("bytes 0-99/"))
            assertEquals(100, body.readBytes().size)
        }
    }

    @Test fun fullRequestReturnsOkWithVideoMime() {
        val s = MediaProxy.open(video, null)
        assertNotNull(s)
        s!!.body.use {
            assertEquals(200, s.code)
            assertEquals("video/mp4", s.mime)
        }
    }
}
