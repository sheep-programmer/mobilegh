package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 下载候选链的顺序：上次成功的/当前选中的节点优先，失败过的排到最后，
 * 直连 GitHub 永远兜底；jsDelivr 这类不转发 Release 资源的节点不能进候选。
 */
class DownloadTargetsTest {
    private val direct = CdnNode("direct", "直连 GitHub", NodeType.Direct, "")
    private val nodeA = CdnNode("a", "节点 A", NodeType.Prefix, "https://a.example/")
    private val nodeB = CdnNode("b", "节点 B", NodeType.Prefix, "https://b.example/")
    private val jsdelivr = CdnNode("jsd", "jsDelivr", NodeType.JsDelivr, "https://cdn.jsdelivr.net")
    private val url = "https://github.com/me/app/releases/download/v1/app.apk"

    private fun order(
        candidates: List<CdnNode>,
        latency: Map<String, Long> = emptyMap(),
        failed: Set<String> = emptySet(),
        preferred: String = "a",
    ) = orderDownloadTargets(candidates, url, latency, failed, preferred, direct).map { it.nodeId }

    @Test fun directIsAlwaysTheLastResort() {
        assertEquals(listOf("a", "direct"), order(listOf(nodeA)))
    }

    @Test fun jsDelivrNeverProxiesDownloads() {
        assertEquals(listOf("a", "direct"), order(listOf(jsdelivr, nodeA)))
    }

    @Test fun fastestNodeComesFirst() {
        assertEquals(listOf("a", "b", "direct"), order(listOf(nodeA, nodeB), latency = mapOf("a" to 120L, "b" to 340L)))
        assertEquals(listOf("b", "a", "direct"), order(listOf(nodeA, nodeB), latency = mapOf("a" to 900L, "b" to 150L), preferred = "x"))
    }

    @Test fun currentNodeWinsOverAFasterOne() {
        assertEquals(listOf("b", "a", "direct"), order(listOf(nodeA, nodeB), latency = mapOf("a" to 20L, "b" to 500L), preferred = "b"))
    }

    @Test fun nodesThatFailedGoLastButAreStillTriedBeforeDirect() {
        assertEquals(listOf("b", "a", "direct"), order(listOf(nodeA, nodeB), failed = setOf("a")))
    }

    @Test fun untestedNodesComeAfterTestedOnes() {
        // preferred 指向一个不存在的节点时，排序完全由测速结果决定：测过的优先，没测过的排后面
        assertEquals(listOf("a", "b", "direct"), order(listOf(nodeA, nodeB), latency = mapOf("a" to 200L), preferred = "x"))
    }

    @Test fun prefixNodeKeepsTheOriginalUrlAsItsPath() {
        val targets = orderDownloadTargets(listOf(nodeA), url, emptyMap(), emptySet(), "a", direct)
        assertEquals(
            listOf("https://a.example/$url", url),
            targets.map { it.url },
        )
        assertEquals(listOf("节点 A", "直连 GitHub"), targets.map { it.nodeName })
    }
}
