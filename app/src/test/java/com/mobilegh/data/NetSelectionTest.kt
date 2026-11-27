package com.mobilegh.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetSelectionTest {
    private lateinit var previous: SelectionSnapshot

    @Before fun saveSelection() {
        previous = SelectionSnapshot(
            Net.rawMode,
            Net.downloadMode,
            Net.manualRawId,
            Net.manualDownloadId,
            Net.bestRawId,
            Net.bestDlId,
            Net.latency.toMap(),
        )
    }

    @After fun restoreSelection() {
        Net.rawMode = previous.rawMode
        Net.downloadMode = previous.downloadMode
        Net.manualRawId = previous.manualRawId
        Net.manualDownloadId = previous.manualDownloadId
        Net.bestRawId = previous.bestRawId
        Net.bestDlId = previous.bestDlId
        Net.latency.clear()
        Net.latency.putAll(previous.latency)
    }

    @Test fun manualFileAndDownloadSelectionsAreIndependent() {
        Net.rawMode = 1
        Net.downloadMode = 1
        Net.manualRawId = "jsd-mirror"
        Net.manualDownloadId = "ghfast"

        assertEquals("jsd-mirror", Net.rawNode().id)
        assertEquals("ghfast", Net.dlNode().id)

        Net.manualRawId = "jsd-fastly"
        assertEquals("jsd-fastly", Net.rawNode().id)
        assertEquals("ghfast", Net.dlNode().id)
    }

    @Test fun incompatibleSavedDownloadSelectionNeverFallsBackToAutomaticMirror() {
        Net.downloadMode = 1
        Net.manualDownloadId = "jsd-mirror"
        Net.bestDlId = "ghfast"

        assertEquals(Net.DIRECT.id, Net.dlNode().id)
    }

    @Test fun manualFileImagesDoNotWanderAcrossOtherMirrors() {
        Net.rawMode = 1
        Net.manualRawId = "jsd-mirror"

        val mirrorIds = Net.imageCandidates(
            "https://raw.githubusercontent.com/public-fixture/repository/main/photo.png",
        ).map { it.first }.filter { it != Net.DIRECT.id && it != Net.GITHUB_API_ID }

        assertEquals(listOf("jsd-mirror"), mirrorIds.distinct())
    }

    @Test fun freshSourceDoesNotBorrowTheIndependentDownloadMirror() {
        Net.rawMode = 1
        Net.manualRawId = "jsd-mirror"
        Net.downloadMode = 1
        Net.manualDownloadId = "ghfast"

        assertNull(Net.freshRawUrl("public-fixture", "repository", "main", "README.md"))
    }

    @Test fun disablingFilesDoesNotDisableManualDownloads() {
        Net.rawMode = 2
        Net.downloadMode = 1
        Net.manualDownloadId = "ghfast"

        assertEquals(Net.DIRECT.id, Net.rawNode().id)
        val targets = Net.downloadTargets("https://github.com/public-fixture/repository/releases/download/v1/app.apk")
        assertEquals(listOf("ghfast", Net.DIRECT.id), targets.map { it.nodeId })
    }

    @Test fun automaticFreshSourceUsesAHealthyFileProxyNotTheManualDownloadNode() {
        Net.rawMode = 0
        Net.bestRawId = "jsd-mirror"
        Net.downloadMode = 1
        Net.manualDownloadId = "ghfast"
        Net.latency.clear()
        Net.latency["ghfast"] = -1
        Net.latency["gh-proxy"] = 100

        val url = Net.freshRawUrl("public-fixture", "repository", "main", "README.md")

        assertTrue("expected a healthy file-class Prefix proxy, actual=$url", url?.startsWith("https://gh-proxy.com/") == true)
    }

    @Test fun automaticDirectChoiceIsActuallyTheFirstDownloadTarget() {
        Net.downloadMode = 0
        Net.bestDlId = Net.DIRECT.id

        val targets = Net.downloadTargets("https://github.com/public-fixture/repository/releases/download/v1/app.apk")

        assertEquals(Net.DIRECT.id, targets.first().nodeId)
    }

    @Test fun failedAutomaticDirectIsNotForcedFirst() {
        assertFalse(shouldPreferDirectFirst(0, Net.DIRECT, setOf(Net.DIRECT.id)))
        assertTrue(shouldPreferDirectFirst(0, Net.DIRECT, emptySet()))
        assertFalse(shouldPreferDirectFirst(1, Net.DIRECT, emptySet()))
    }

    @Test fun automaticDownloadBenchmarkCanPreferDirect() {
        val prefix = Net.builtins.first { it.type == NodeType.Prefix }
        val selected = selectAutoDownloadNode(
            listOf(Net.DIRECT, prefix),
            mapOf(Net.DIRECT.id to 100L, prefix.id to 150L),
        )

        assertEquals(Net.DIRECT.id, selected)
    }

    @Test fun cloneUrlRequiresAPrefixNodeAndKeepsTheFullUrl() {
        Net.downloadMode = 1
        Net.manualDownloadId = "ghfast"
        val url = "https://github.com/public-fixture/repository.git"

        assertEquals("ghfast.top" to "https://ghfast.top/$url", Net.cloneUrl(url))

        // jsDelivr 不转发 git 协议，手动选中它时只能直连，不能拼出无效的克隆地址。
        Net.manualDownloadId = "jsd-mirror"
        assertNull(Net.cloneUrl(url))

        // 关闭加速同样不代理克隆。
        Net.downloadMode = 2
        Net.manualDownloadId = "ghfast"
        assertNull(Net.cloneUrl(url))
    }

    @Test fun freshRawUrlFallsBackToTheDefaultProxyWhenNothingIsHealthy() {
        Net.rawMode = 0
        Net.latency.clear()
        Net.nodes.forEach { Net.latency[it.id] = -1 }

        val url = Net.freshRawUrl("public-fixture", "repository", "main", "README.md")

        val fallback = Net.nodes.first { it.id == Net.DEFAULT_DL }
        assertTrue("expected the default proxy, actual=$url", url?.startsWith(fallback.base) == true)
    }

    @Test fun markedSuccessIsPreferredAndMarkedFailureMovesToTheBack() {
        Net.downloadMode = 0
        Net.bestDlId = Net.DEFAULT_DL
        val url = "https://github.com/public-fixture/repository/releases/download/v1/app.apk"
        try {
            Net.markDownloadFail("ghfast")
            val afterFailure = Net.downloadTargets(url).map { it.nodeId }
            // 失败节点排到所有未失败节点之后；候选链只保留前 8 个代理，所以它这次不会被尝试。
            assertFalse("a marked-failed node must not be tried in this download", "ghfast" in afterFailure)
            assertEquals(Net.DIRECT.id, afterFailure.last())

            Net.markDownloadOk("ghfast")
            assertEquals("ghfast", Net.downloadTargets(url).first().nodeId)
        } finally {
            // 恢复默认偏好，避免成功标记泄漏到其他测试的顺序断言。
            Net.markDownloadOk(Net.DEFAULT_DL)
        }
    }

    @Test fun camoBadgesIncludeHttpsCanonicalUrlFallback() {
        val canonical = "https://img.shields.io/github/license/cursimple/cursimple-app"
        val hex = canonical.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val camo = "https://camo.githubusercontent.com/083e7b1e3ab411cf9ab78bb2a520ca0b275e5e5f02fd38bb636e1dec8bf6e669/$hex"

        assertEquals(canonical, ImageContent.decodeCamoCanonicalUrl(camo))
        val candidates = ImageFetch.raceCandidatesForTarget(camo, "test-key")
        assertEquals(listOf(Net.DIRECT.id to camo, "canonical" to canonical), candidates)

        // Non-HTTPS canonical URLs must never be added as a fallback.
        val httpCanonical = "http://example.com/badge.svg".toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val httpCamo = "https://camo.githubusercontent.com/083e7b1e3ab411cf9ab78bb2a520ca0b275e5e5f02fd38bb636e1dec8bf6e669/$httpCanonical"
        assertNull(ImageContent.decodeCamoCanonicalUrl(httpCamo))
        assertEquals(listOf(Net.DIRECT.id to httpCamo), ImageFetch.raceCandidatesForTarget(httpCamo, "test-key-2"))
    }

    private data class SelectionSnapshot(
        val rawMode: Int,
        val downloadMode: Int,
        val manualRawId: String,
        val manualDownloadId: String,
        val bestRawId: String,
        val bestDlId: String,
        val latency: Map<String, Long>,
    )
}
