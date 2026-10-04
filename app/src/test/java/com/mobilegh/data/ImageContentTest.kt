package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageContentTest {
    @Test fun mirrorErrorPagesAreRejectedEvenWithAnImageExtension() {
        listOf(
            "<!doctype html><html>upstream failed</html>",
            "\uFEFF<!-- proxy error --><html>try again</html>",
            "upstream temporarily unavailable",
            "{\"error\":\"rate limited\"}",
        ).forEach { assertNull(ImageContent.mimeOf("https://mirror.test/photo.png", it.toByteArray())) }
    }

    @Test fun actualContentDeterminesTypeInsteadOfTheUrlExtension() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0)
        assertEquals("image/png", ImageContent.mimeOf("https://mirror.test/photo.jpg", png))
        assertEquals("image/svg+xml", ImageContent.mimeOf("https://mirror.test/image", "\uFEFF<?xml version=\"1.0\"?><!-- icon --><svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".toByteArray()))
        assertNull(ImageContent.mimeOf("https://mirror.test/image.svg", "<html><svg></svg></html>".toByteArray()))
    }

    @Test fun unknownLengthStreamsHaveAHardLimit() {
        assertNull(ImageContent.readLimited(ByteArray(65).inputStream(), 64))
        val exactly = ByteArray(64) { it.toByte() }
        assertArrayEquals(exactly, ImageContent.readLimited(exactly.inputStream(), 64))
    }

    @Test fun blobNormalizationKeepsSignedQueryAndEncodedPaths() {
        assertEquals(
            "https://raw.githubusercontent.com/owner/repo/main/docs/my%20photo.png?token=example&raw=true",
            ImageContent.normalize("https://github.com/owner/repo/blob/main/docs/my%20photo.png?token=example&raw=true"),
        )
        val attachment = "https://private-user-images.githubusercontent.com/1/image.png?jwt=example"
        assertEquals(attachment, ImageContent.normalize(attachment))
    }
}
