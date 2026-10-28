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

    @Test fun nodesKnownToBeUnreliableStayRemoved() {
        // ghproxy.net 实测下载、克隆几乎全部失败，不应再被加回内置列表
        assertTrue(nodes.none { it.base.contains("ghproxy.net") })
        assertTrue(nodes.any { it.id == "jsd-cf" })
    }
}
