package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 私有仓库的下载地址构造：GitHub 的网页下载地址只认浏览器会话，API token
 * 请求一律 404，所以私有资源必须走 API / raw 主机，公开仓库保持原样。
 */
class DownloadUrlTest {
    @Test fun privateReleaseAssetUsesApiAssetEndpoint() {
        assertEquals(
            "https://api.github.com/repos/me/private-app/releases/assets/633431474",
            releaseAssetDownloadUrl(
                owner = "me", name = "private-app", assetId = 633431474,
                browserUrl = "https://github.com/me/private-app/releases/download/v1.0/app-debug.apk",
                isPrivate = true,
            ),
        )
    }

    @Test fun publicReleaseAssetKeepsBrowserUrl() {
        assertEquals(
            "https://github.com/me/public-app/releases/download/v1.0/app.apk",
            releaseAssetDownloadUrl(
                owner = "me", name = "public-app", assetId = 42,
                browserUrl = "https://github.com/me/public-app/releases/download/v1.0/app.apk",
                isPrivate = false,
            ),
        )
    }

    @Test fun privateAssetWithoutIdFallsBackToBrowserUrl() {
        val browser = "https://github.com/me/private-app/releases/download/v1.0/app.apk"
        assertEquals(browser, releaseAssetDownloadUrl("me", "private-app", 0, browser, isPrivate = true))
    }

    @Test fun privateFileUsesRawHost() {
        assertEquals(
            "https://raw.githubusercontent.com/me/private-app/main/app/build/app-debug.apk",
            repoFileDownloadUrl("me", "private-app", "main", "app/build/app-debug.apk", isPrivate = true),
        )
    }

    @Test fun publicFileKeepsWebPathSoMirrorCanAccelerate() {
        assertEquals(
            "https://github.com/me/public-app/raw/main/README.md",
            repoFileDownloadUrl("me", "public-app", "main", "README.md", isPrivate = false),
        )
    }

    @Test fun actionsArtifactNeedsJsonAccept() {
        assertEquals(
            "application/vnd.github+json",
            privateDownloadAccept("https://api.github.com/repos/me/app/actions/artifacts/11416273537/zip"),
        )
        assertEquals(
            "application/octet-stream",
            privateDownloadAccept("https://api.github.com/repos/me/app/releases/assets/633431474"),
        )
    }
}
