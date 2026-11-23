package com.mobilegh.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImageRetryTest {
    @Test
    fun corruptDiskCacheIsRejectedAndRemoved() {
        val root = Files.createTempDirectory("mobilegh-image-cache").toFile()
        val url = "https://example.invalid/corrupt-cache.png"
        try {
            ImageFetch.init(root)
            val keyMethod = ImageFetch::class.java.getDeclaredMethod("diskKey", String::class.java)
                .apply { isAccessible = true }
            val key = keyMethod.invoke(ImageFetch, url) as String
            val file = File(root, "image_cache/$key")
            file.parentFile?.mkdirs()
            val mime = "image/png".toByteArray(Charsets.UTF_8)
            file.writeBytes(byteArrayOf(mime.size.toByte()) + mime + byteArrayOf(1, 2, 3, 4))

            assertNull(ImageFetch.cached(url))
            assertFalse(file.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun transientFailureIsRetriedAndCanRecover() = runTest {
        var attempts = 0

        val result = ImageFetch.retryImageLoad(
            maxAttempts = 3,
            retryDelayMs = 0,
            isMissing = { false },
        ) { _ ->
            attempts++
            if (attempts == 1) null else "decoded"
        }

        assertEquals("decoded", result)
        assertEquals(2, attempts)
    }

    @Test
    fun missingImageStopsWithoutRepeatedRequests() = runTest {
        var attempts = 0

        val result = ImageFetch.retryImageLoad(
            maxAttempts = 3,
            retryDelayMs = 0,
            isMissing = { true },
        ) { _ ->
            attempts++
            null
        }

        assertNull(result)
        assertEquals(1, attempts)
    }

    @Test
    fun transientHttpFailuresDoNotQuarantineAnImageNode() {
        assertFalse(shouldQuarantineImageNode(code = 408, transportFailure = false, invalidBody = false))
        assertFalse(shouldQuarantineImageNode(code = 429, transportFailure = false, invalidBody = false))
        assertFalse(shouldQuarantineImageNode(code = 503, transportFailure = false, invalidBody = false))
        assertFalse(shouldQuarantineImageNode(code = null, transportFailure = true, invalidBody = false))
        assertEquals(true, shouldQuarantineImageNode(code = 403, transportFailure = false, invalidBody = false))
        assertEquals(true, shouldQuarantineImageNode(code = null, transportFailure = false, invalidBody = true))
    }
}
