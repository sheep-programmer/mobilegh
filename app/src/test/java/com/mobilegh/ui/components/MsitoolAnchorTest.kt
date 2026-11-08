package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 用 msitool 的真实 README 验证：目录里的锚点必须能对应到标题。 */
class MsitoolAnchorTest {
    @Test fun tocAnchorsResolveToHeadings() {
        val f = File("/tmp/msi.html")
        if (!f.exists()) return
        val blocks = HtmlParser.parse(f.readText(), "https://github.com/x500x/msitool/blob/main/").blocks
        fun walk(b: HtmlBlock): List<HtmlBlock.Heading> = when (b) {
            is HtmlBlock.Heading -> listOf(b)
            is HtmlBlock.ListBlock -> b.items.flatten().flatMap { walk(it) }
            is HtmlBlock.Quote -> b.blocks.flatMap { walk(it) }
            is HtmlBlock.Details -> b.blocks.flatMap { walk(it) }
            is HtmlBlock.Aligned -> b.blocks.flatMap { walk(it) }
            else -> emptyList()
        }
        val headings = blocks.flatMap { walk(it) }
        val anchors = headings.flatMap { it.anchors }.toSet()
        println("HEADINGS=" + headings.size)
        assertTrue("特性" in anchors || "user-content-特性" in anchors)

        println("BLOCK_TYPES=" + blocks.map { it::class.simpleName })
        val allLists = mutableListOf<HtmlBlock.ListBlock>()
        fun findLists(b: HtmlBlock) {
            when (b) {
                is HtmlBlock.ListBlock -> {
                    allLists += b
                    b.items.flatten().forEach { findLists(it) }
                }
                is HtmlBlock.Quote -> b.blocks.forEach { findLists(it) }
                is HtmlBlock.Details -> b.blocks.forEach { findLists(it) }
                is HtmlBlock.Aligned -> b.blocks.forEach { findLists(it) }
                else -> Unit
            }
        }
        blocks.forEach { findLists(it) }
        println("ALL_LISTS count=" + allLists.size)
        val tocList = allLists.getOrNull(1)
        assertNotNull("TOC list block should exist", tocList)
        println("TOC_LIST items count=" + tocList!!.items.size)
        val tocLinks = tocList.items.mapNotNull { item ->
            val p = item.singleOrNull() as? HtmlBlock.Paragraph ?: return@mapNotNull null
            p.inlines.filterIsInstance<Inline.Text>().firstOrNull { it.style.link != null }?.style?.link
        }
        println("TOC_LINKS count=" + tocLinks.size + ": " + tocLinks)
        assertTrue("Should find TOC links", tocLinks.isNotEmpty())

        val scroll = HtmlScroll(2.75f)
        headings.forEachIndexed { idx, h ->
            h.anchors.forEach { scroll.headingY[it] = (idx * 100).toFloat() }
        }
        var jumpTarget: Int? = null
        scroll.onAnchor = { jumpTarget = it }

        for (link in tocLinks) {
            jumpTarget = null
            scroll.jumpTo(link.removePrefix("#"))
            assertNotNull("Link $link should resolve to a heading", jumpTarget)
        }
    }
}
