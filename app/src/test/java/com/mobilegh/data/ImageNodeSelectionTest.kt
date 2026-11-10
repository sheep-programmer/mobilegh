package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片节点的记忆必须按资源类型分开：同一个代理可能只支持 raw 文件，对头像返回 403，
 * 对 camo 返回 404。另外 camo 只有 GitHub 自己能提供，应当直连优先。
 */
class ImageNodeSelectionTest {
    private val avatar = "https://avatars.githubusercontent.com/u/1?v=4&s=80"
    private val raw = "https://raw.githubusercontent.com/o/r/main/photo.png"
    private val camo = "https://camo.githubusercontent.com/abc123/68747470733a2f2f6578616d706c652e636f6d2f612e706e67"

    @Test fun urlsAreClassifiedByHost() {
        assertEquals("avatar", Net.imageClass(avatar))
        assertEquals("raw", Net.imageClass(raw))
        assertEquals("camo", Net.imageClass(camo))
        assertEquals("other", Net.imageClass("https://example.com/a.png"))
    }

    @Test fun avatarFailuresDoNotRemoveTheNodeFromRawCandidates() {
        Net.markImageFail(avatar, "ghfast")
        assertFalse(Net.imageCandidates(avatar).any { it.first == "ghfast" })
        assertTrue(Net.imageCandidates(raw).any { it.first == "ghfast" })
    }

    @Test fun lastGoodNodeIsTriedFirstForTheSameKindOfImage() {
        Net.markImageOk(avatar, "gh-proxy")
        val first = Net.imageCandidates(avatar).first().first
        // 直连永远排在候选最后；这里只验证成功过的节点被提到加速节点里的第一位
        assertEquals("gh-proxy", first)
    }

    @Test fun camoIsFetchedDirectlyFirst() {
        // 公共代理对 camo 一律 403/404，先试代理只会给每张 README 图片加几秒延迟
        assertEquals(Net.DIRECT.id, Net.imageCandidates(camo).first().first)
    }

    @Test fun avatarIsFetchedDirectlyFirstWhenNoNodeMemorized() {
        // 头像在没有历史成功节点时直连优先，百毫秒直出，免去镜像 403 导致的长时等待
        Net.markImageFail(avatar, "gh-proxy")
        val candidates = Net.imageCandidates("https://avatars.githubusercontent.com/u/9999?v=4")
        assertEquals(Net.DIRECT.id, candidates.first().first)
    }

    @Test fun directIsInInitialRaceWindow() {
        val candidates = listOf(
            "cdn1" to "https://cdn1.example.com/img.png",
            "cdn2" to "https://cdn2.example.com/img.png",
            "cdn3" to "https://cdn3.example.com/img.png",
            "cdn4" to "https://cdn4.example.com/img.png",
            "cdn5" to "https://cdn5.example.com/img.png",
            Net.DIRECT.id to "https://raw.githubusercontent.com/img.png"
        )
        val prioritized = ImageFetch.prioritizeRaceCandidates(candidates, "cdn2")
        assertEquals("cdn2", prioritized[0].first)
        assertEquals(Net.DIRECT.id, prioritized[1].first)
    }

    @Test fun githubApiCandidateIsInInitialRaceWindow() {
        val candidates = listOf(
            "cdn1" to "https://cdn1.example.com/img.png",
            "cdn2" to "https://cdn2.example.com/img.png",
            "cdn3" to "https://cdn3.example.com/img.png",
            "cdn4" to "https://cdn4.example.com/img.png",
            "cdn5" to "https://cdn5.example.com/img.png",
            Net.GITHUB_API_ID to "https://api.github.com/repos/o/r/contents/img.png",
            Net.DIRECT.id to "https://raw.githubusercontent.com/o/r/main/img.png"
        )
        val prioritized = ImageFetch.prioritizeRaceCandidates(candidates, null)
        assertTrue(prioritized.take(4).any { it.first == Net.DIRECT.id })
        assertTrue(prioritized.take(4).any { it.first == Net.GITHUB_API_ID })
    }

    @Test fun githubApiContentsUrlEncodesSegmentsAndRef() {
        val raw = listOf("owner", "repo", "main", "docs", "Screenshot", "全键盘_下滑_light.jpg")
        val url = Net.githubApiContentsUrl(raw)
        assertTrue(url.startsWith("https://api.github.com/repos/owner/repo/contents/docs/Screenshot/"))
        assertTrue(url.contains("%E5%85%A8%E9%94%AE%E7%9B%98_%E4%B8%8B%E6%BB%91_light.jpg"))
        assertTrue(url.endsWith("?ref=main"))
    }
}

/** 内置节点列表要保持可用：下线节点留在列表里会让测速和图片回退白等一轮超时 */
class NetBuiltinNodesTest {
    @Test fun everyBuiltinNodeHasAHttpsBaseExceptDirect() {
        Net.builtins.forEach {
            if (it.type == NodeType.Direct) assertEquals("", it.base) else assertTrue(it.base.startsWith("https://"))
        }
    }

    @Test fun defaultNodesExistInBuiltins() {
        // 默认值只能在 builtins 里，否则 rawNode()/dlNode() 会一直回退到其它节点
        assertTrue(Net.nodes.any { it.id == "jsd-gcore" && it.type == NodeType.JsDelivr })
        assertTrue(Net.nodes.any { it.id == "gh-proxy" && it.type == NodeType.Prefix })
    }

    @Test fun unpublishedNodeIsGone() {
        assertTrue(Net.builtins.none { it.base.contains("jasonzeng") })
    }

    @Test fun downloadDefaultsToAPrefixProxy() {
        // 下载必须走能转发 release 资源的代理节点；jsDelivr 不支持二进制资源
        assertEquals(NodeType.Prefix, Net.dlNode().type)
    }
}
