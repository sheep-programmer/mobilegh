package com.mobilegh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProxyTest {
    @Test fun videoExtensionsAreStreamed() {
        assertTrue(MediaProxy.wants("https://github.com/o/r/raw/main/demo.mp4", null))
        assertTrue(MediaProxy.wants("https://raw.githubusercontent.com/o/r/main/a.WEBM?raw=true", null))
        assertTrue(MediaProxy.wants("https://github.com/user-attachments/assets/abc.mov#t=1", null))
    }

    @Test fun rangeRequestsAreStreamedEvenWithoutExtension() {
        assertTrue(MediaProxy.wants("https://github.com/user-attachments/assets/abc", "bytes=0-"))
    }

    @Test fun plainImagesStayOnTheImagePath() {
        assertFalse(MediaProxy.wants("https://raw.githubusercontent.com/o/r/main/logo.png", null))
        assertFalse(MediaProxy.wants("https://raw.githubusercontent.com/o/r/main/anim.gif", null))
    }

    @Test fun videoLinksOpenInThePlayer() {
        assertTrue(MediaProxy.isVideoLink("https://example.com/clip.mp4"))
        assertFalse(MediaProxy.isVideoLink("https://example.com/page.html"))
    }
}
