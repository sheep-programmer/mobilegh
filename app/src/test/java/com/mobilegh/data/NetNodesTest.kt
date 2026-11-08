package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内置加速节点清单的基本约束：新增或改动节点时防止写错地址、重复或遗漏默认节点 */
class NetNodesTest {
    private val nodes get() = Net.builtins

    @Test fun idsAndBaseAddressesAreUnique() {
        assertEquals(nodes.map { it.id }.size, nodes.map { it.id }.toSet().size)
        val bases = nodes.filter { it.type != NodeType.Direct }.map { it.base }
        assertEquals(bases.size, bases.toSet().size)
    }

    @Test fun everyRemoteNodeUsesHttpsAndPrefixNodesEndWithASlash() {
        nodes.filter { it.type != NodeType.Direct }.forEach {
            assertTrue("${it.id} 必须是 https 地址：${it.base}", it.base.startsWith("https://"))
            if (it.type == NodeType.Prefix) assertTrue("${it.id} 是前缀代理，地址必须以 / 结尾", it.base.endsWith("/"))
            else assertFalse("${it.id} 是 CDN 域名，地址不能以 / 结尾", it.base.endsWith("/"))
        }
    }

    @Test fun thereAreEnoughDownloadProxiesAndRawCdns() {
        // 下载与克隆只有前缀代理能做；至少要有几个，单个失效时才有后备
        assertTrue(nodes.count { it.type == NodeType.Prefix } >= 5)
        assertTrue(nodes.count { it.type == NodeType.JsDelivr } >= 3)
    }

    @Test fun newlyAddedProxiesArePresentAndGhproxyNetIsLast() {
        // 这批节点实测通过（Range、完整下载、clone 入口）；ghproxy.net 速度波动大，排在最后做后备
        listOf("sixyin", "chjina", "dpik", "noki", "gh-proxy-hk", "gh-proxy-cdn", "gh-proxy-edge").forEach { id ->
            assertTrue("缺少节点 $id", nodes.any { it.id == id && it.type == NodeType.Prefix })
        }
        assertEquals("ghproxy-net", nodes.last().id)
    }

    @Test fun nodesFailingRealDownloadsStayOut() {
        // 实测下载内容不对或超时：g.blfrp.cn 返回的文件校验不通过，wget.la 下载超时
        assertTrue(nodes.none { it.base.contains("blfrp") || it.base.contains("wget.la") })
        assertTrue(nodes.any { it.id == "jsd-cf" })
    }

    @Test fun downloadChainIsCappedAndAlwaysEndsWithDirect() {
        val url = "https://github.com/o/r/releases/download/v1/a.apk"
        val proxies = nodes.filter { it.type == NodeType.Prefix }
        val targets = orderDownloadTargets(proxies, url, emptyMap(), emptySet(), "sixyin", maxProxies = Net.MAX_DOWNLOAD_PROXIES)
        assertEquals(Net.MAX_DOWNLOAD_PROXIES + 1, targets.size)
        assertEquals("direct", targets.last().nodeId)
        assertEquals("sixyin", targets.first().nodeId)
    }

    @Test fun fastMeasuredNodeComesFirstEvenWhenListedLater() {
        val url = "https://github.com/o/r/releases/download/v1/a.apk"
        val proxies = nodes.filter { it.type == NodeType.Prefix }
        val speeds = mapOf("ghproxy-net" to 9_000_000L, "gh-proxy" to 1_000_000L)
        val t = orderDownloadTargets(proxies, url, emptyMap(), emptySet(), "gh-proxy", speeds = speeds, maxProxies = 3)
        assertEquals(listOf("ghproxy-net", "gh-proxy"), t.take(2).map { it.nodeId })
        assertEquals(3 + 1, t.size)
    }
}
