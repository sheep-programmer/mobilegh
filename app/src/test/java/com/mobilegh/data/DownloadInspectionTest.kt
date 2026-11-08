package com.mobilegh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadInspectionTest {
    @Test
    fun identifiesDownloadableUrlsCorrectly() {
        // User attachments
        assertTrue(Downloads.isDownloadableUrl("https://github.com/user-attachments/files/34540973/ios-unsigned.zip"))
        assertTrue(Downloads.isDownloadableUrl("https://github.com/user-attachments/assets/b8374d9e-1234-5678"))
        assertTrue(Downloads.isDownloadableUrl("https://github.com/octocat/Hello-World/files/34540973/test.zip"))

        // Release downloads & archives
        assertTrue(Downloads.isDownloadableUrl("https://github.com/octocat/Hello-World/releases/download/v1.0.0/app.apk"))
        assertTrue(Downloads.isDownloadableUrl("https://github.com/octocat/Hello-World/archive/refs/tags/v1.0.0.zip"))
        assertTrue(Downloads.isDownloadableUrl("https://github.com/octocat/Hello-World/archive/refs/tags/v1.0.0.tar.gz"))
        assertTrue(Downloads.isDownloadableUrl("https://codeload.github.com/octocat/Hello-World/zip/refs/heads/main"))
        assertTrue(Downloads.isDownloadableUrl("https://objects.githubusercontent.com/github-production-repository-file/123"))

        // Direct download file extension
        assertTrue(Downloads.isDownloadableUrl("https://example.com/downloads/package.apk"))
        assertTrue(Downloads.isDownloadableUrl("https://example.com/files/tool.zip"))

        // Non-download web/code pages
        assertFalse(Downloads.isDownloadableUrl("https://github.com/octocat/Hello-World"))
        assertFalse(Downloads.isDownloadableUrl("https://github.com/octocat/Hello-World/issues/1"))
        assertFalse(Downloads.isDownloadableUrl("https://github.com/octocat/Hello-World/pull/2"))
        assertFalse(Downloads.isDownloadableUrl("https://github.com/octocat/Hello-World/blob/main/README.md"))
        assertFalse(Downloads.isDownloadableUrl("https://github.com/octocat/Hello-World/tree/main/src"))
    }
}
