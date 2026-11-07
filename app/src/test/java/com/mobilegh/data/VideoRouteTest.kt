package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRouteTest {
    @Test fun ordinaryHostsPlayTheOriginalUrl() = runBlocking {
        val src = "https://example.com/clip.mp4"
        assertEquals(src, VideoRoute.pick(src))
    }

    @Test fun signedAttachmentLinksAreNeverSentToMirrors() = runBlocking {
        // 带 JWT 的签名地址是凭据，只能直连（与图片策略一致）
        val src = "https://private-user-images.githubusercontent.com/1/2.mp4?jwt=abc.def.ghi"
        assertEquals(src, VideoRoute.pick(src))
    }
}
