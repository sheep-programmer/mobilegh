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

    @Test fun parseDataUriHandlesBase64SvgLogoFromShieldsBadges() {
        // Shields.io Safari badge logo
        val safariDataUri = "data:image/svg+xml;base64,PHN2ZyBmaWxsPSJ3aGl0ZSIgcm9sZT0iaW1nIiB2aWV3Qm94PSIwIDAgMjQgMjQiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+PHRpdGxlPlNhZmFyaTwvdGl0bGU+PC9zdmc+"
        val parsed = ImageContent.parseDataUri(safariDataUri)
        org.junit.Assert.assertNotNull(parsed)
        assertEquals("image/svg+xml", parsed?.mime)
        assertEquals(true, parsed?.isSvg)
        assertEquals(true, parsed?.bytes?.toString(Charsets.UTF_8)?.contains("<title>Safari</title>"))
    }

    @Test fun parseDataUriHandlesUrlEncodedSvg() {
        val encodedUri = "data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%3E%3Ctitle%3EAndroid%3C%2Ftitle%3E%3C%2Fsvg%3E"
        val parsed = ImageContent.parseDataUri(encodedUri)
        org.junit.Assert.assertNotNull(parsed)
        assertEquals("image/svg+xml", parsed?.mime)
        assertEquals(true, parsed?.isSvg)
        assertEquals(true, parsed?.bytes?.toString(Charsets.UTF_8)?.contains("<title>Android</title>"))
    }

    @Test fun parseDataUriHandlesBase64Png() {
        // 1x1 transparent PNG
        val pngDataUri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
        val parsed = ImageContent.parseDataUri(pngDataUri)
        org.junit.Assert.assertNotNull(parsed)
        assertEquals("image/png", parsed?.mime)
        assertEquals(false, parsed?.isSvg)
        assertEquals("image/png", ImageContent.mimeOf("", parsed?.bytes))
    }

    @Test fun parseDataUriRejectsMalformedUris() {
        assertNull(ImageContent.parseDataUri("https://example.com/not-data.png"))
        assertNull(ImageContent.parseDataUri("data:no-comma"))
        assertNull(ImageContent.parseDataUri("data:image/svg+xml;base64,???not_base64!!!"))
    }
}
