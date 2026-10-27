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

    @Test fun fileDownloadEncodesRefAndEachPathSegment() {
        assertEquals(
            "https://github.com/me/public-app/raw/feature/a%23b/docs/a%20%23b%3F.txt",
            repoFileDownloadUrl("me", "public-app", "feature/a#b", "docs/a #b?.txt", isPrivate = false),
        )
        assertEquals(
            "https://raw.githubusercontent.com/me/private-app/feature/a%23b/docs/a%20%23b%3F.txt",
            repoFileDownloadUrl("me", "private-app", "feature/a#b", "docs/a #b?.txt", isPrivate = true),
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

    /**
     * 源码 zip 包：公开走网页 archive 地址（镜像节点能改写），私有走 API zipball。
     * zipball 端发 octet-stream 会被 GitHub 直接 415，实测必须用 JSON 的 Accept。
     */
    @Test fun publicRepoZipUsesWebArchivePath() {
        assertEquals(
            "https://github.com/me/public-app/archive/main.zip",
            repoZipDownloadUrl("me", "public-app", "main", isPrivate = false),
        )
    }

    @Test fun privateRepoZipUsesApiZipball() {
        assertEquals(
            "https://api.github.com/repos/me/private-app/zipball/main",
            repoZipDownloadUrl("me", "private-app", "main", isPrivate = true),
        )
    }

    @Test fun repoArchiveEncodesRefWithoutEncodingSlash() {
        assertEquals(
            "https://github.com/me/public-app/archive/feature/a%23b.zip",
            repoZipDownloadUrl("me", "public-app", "feature/a#b", isPrivate = false),
        )
        assertEquals(
            "https://api.github.com/repos/me/private-app/zipball/feature/a%23b",
            repoZipDownloadUrl("me", "private-app", "feature/a#b", isPrivate = true),
        )
    }

    @Test fun releaseSourceArchivesUseTheSameVisibilityAndEncodingRules() {
        assertEquals(
            "https://github.com/me/public-app/archive/refs/tags/v1%20%23x.tar.gz",
            repoArchiveDownloadUrl("me", "public-app", "refs/tags/v1 #x", "tar.gz", isPrivate = false),
        )
        assertEquals(
            "https://api.github.com/repos/me/private-app/tarball/refs/tags/v1%20%23x",
            repoArchiveDownloadUrl("me", "private-app", "refs/tags/v1 #x", "tar.gz", isPrivate = true),
        )
    }

    @Test fun zipballNeedsJsonAccept() {
        assertEquals(
            "application/vnd.github+json",
            privateDownloadAccept("https://api.github.com/repos/me/private-app/zipball/main"),
        )
        assertEquals(
            "application/vnd.github+json",
            privateDownloadAccept("https://api.github.com/repos/me/private-app/tarball/main"),
        )
    }
}
