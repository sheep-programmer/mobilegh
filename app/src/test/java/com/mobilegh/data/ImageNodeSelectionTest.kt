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
}
