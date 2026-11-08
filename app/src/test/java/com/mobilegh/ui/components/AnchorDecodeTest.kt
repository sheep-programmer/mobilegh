package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** README 目录里的锚点链接：中文标题被百分号编码，跳转前要能还原。 */
class AnchorDecodeTest {
    private val base = "https://github.com/x500x/msitool/blob/main/"

    @Test fun percentEncodedChineseAnchorsDecode() {
        assertEquals("特性", percentDecode("%E7%89%B9%E6%80%A7"))
        assertEquals("故障排除 FAQ", percentDecode("%E6%95%85%E9%9A%9C%E6%8E%92%E9%99%A4%20FAQ"))
        assertEquals("图形界面（Fluent UI）", percentDecode("%E5%9B%BE%E5%BD%A2%E7%95%8C%E9%9D%A2%EF%BC%88Fluent%20UI%EF%BC%89"))
    }

    @Test fun plusIsNotSpaceAndPlainAnchorsAreUntouched() {
        assertEquals("c++", percentDecode("c++"))
        assertEquals("特性", percentDecode("特性"))
        assertEquals(100, percentDecode("%zz").let { 100 }) // 非法编码不崩
    }

    @Test fun headingRegistersBothPrefixedAndPlainAnchors() {
        val h = HtmlParser.parse(
            "<h2 dir=\"auto\"><a id=\"user-content-特性\" class=\"anchor\" href=\"#特性\"></a>特性</h2>", base,
        ).blocks.single() as HtmlBlock.Heading
        assertTrue("user-content-特性" in h.anchors)
        assertTrue("特性" in h.anchors)
    }

    /** GitHub 现在的真实结构：锚点是 h2 的兄弟节点（包在 div.markdown-heading 里）。 */
    @Test fun headingAnchorInSiblingElementIsRegistered() {
        val html = "<div class=\"markdown-heading\" dir=\"auto\"><h2 class=\"heading-element\" dir=\"auto\">目录</h2>" +
            "<a id=\"user-content-目录\" class=\"anchor\" href=\"#目录\"><svg></svg></a></div>"
        val blocks = HtmlParser.parse(html, base).blocks
        assertEquals(1, blocks.size)
        val h = blocks.single() as HtmlBlock.Heading
        assertEquals("目录", (h.inlines.filterIsInstance<Inline.Text>().joinToString("") { it.text }).trim())
        assertTrue("user-content-目录" in h.anchors)
        assertTrue("目录" in h.anchors)
    }

    @Test fun tocLinkStaysAFragmentForInPageJump() {
        val p = HtmlParser.parse(
            "<p><a href=\"#%E7%89%B9%E6%80%A7\">特性</a></p>", base,
        ).blocks.single() as HtmlBlock.Paragraph
        val link = p.inlines.filterIsInstance<Inline.Text>().first { it.style.link != null }
        assertEquals("#%E7%89%B9%E6%80%A7", link.style.link)
    }

    @Test fun headingSlugGeneratesGithubCompatibleAnchors() {
        assertEquals("特性", headingSlug("特性"))
        assertEquals("快速开始", headingSlug("快速开始"))
        assertEquals("图形界面fluent-ui", headingSlug("图形界面（Fluent UI）"))
        assertEquals("故障排除-faq", headingSlug("故障排除 FAQ"))
        assertEquals("todo", headingSlug("TODO"))
        assertEquals("1-简介", headingSlug("1. 简介"))
    }
}
