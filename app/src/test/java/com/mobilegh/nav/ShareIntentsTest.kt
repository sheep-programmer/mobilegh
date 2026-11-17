package com.mobilegh.nav

import com.mobilegh.data.SharedAttachment
import org.junit.Assert.*
import org.junit.Test

class ShareIntentsTest {
    @Test fun textAndCachedImagesBecomeAnIssueShare() {
        val share = ShareIntents.parse(
            "text/plain", "  steps to reproduce  ",
            listOf(SharedAttachment("/cache/a.png", "image/png", "a.png"))
        )
        assertEquals("steps to reproduce", share?.text)
        assertEquals("a.png", share?.attachments?.single()?.name)
    }

    @Test fun clipDataImagesAreAcceptedWithoutExtraText() {
        val share = ShareIntents.parse(
            "image/jpeg", null,
            listOf(SharedAttachment("/cache/a.jpg", "image/jpeg", "a.jpg"))
        )
        assertEquals("", share?.text)
        assertEquals(1, share?.attachments?.size)
    }

    @Test fun wildcardImageIntentUsesEachResolvedAttachmentType() {
        val share = ShareIntents.parse(
            "image/*", null,
            listOf(SharedAttachment("/cache/a.png", "image/png", "a.png"))
        )
        assertEquals("a.png", share?.attachments?.single()?.name)
        assertNull(ShareIntents.parse(
            "image/*", null,
            listOf(SharedAttachment("/cache/a.svg", "image/svg+xml", "a.svg"))
        ))
    }

    @Test fun unsupportedMimeAndEmptySharesAreRejected() {
        assertNull(ShareIntents.parse("video/mp4", "hello", emptyList()))
        assertNull(ShareIntents.parse("text/plain", "  ", emptyList()))
        assertNull(ShareIntents.parse("image/gif", null, emptyList()))
        assertNull(ShareIntents.parse("text/plain", null,
            listOf(SharedAttachment("/cache/a.pdf", "application/pdf", "a.pdf"))))
    }

    @Test fun attachmentMetadataIsValidatedBeforeAnyCopy() {
        fun image(size: Long? = 1L, mime: String = "image/png") = ShareAttachmentMetadata(mime, size)
        ShareIntents.validateMetadata(listOf(image(null), image(10L, "image/jpeg"), image(20L, "image/gif")))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ShareIntents.validateMetadata(List(11) { image() })
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ShareIntents.validateMetadata(listOf(image(10 * 1024 * 1024L + 1)))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ShareIntents.validateMetadata(List(6) { image(9 * 1024 * 1024L) })
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ShareIntents.validateMetadata(listOf(image(mime = "application/pdf")))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ShareIntents.validateMetadata(listOf(image(-1)))
        }
        assertEquals(10, ShareIntents.MAX_ATTACHMENTS)
        assertEquals(50 * 1024 * 1024L, ShareIntents.MAX_TOTAL_SIZE)
    }

    @Test fun githubLinksStillUseNativeNavigationBeforeIssueCreation() {
        val text = "看 https://github.com/octocat/Hello-World/issues/1"
        assertEquals(Screen.IssueDetail("octocat", "Hello-World", 1, false), ShareIntents.link(text)?.target)
    }
}
