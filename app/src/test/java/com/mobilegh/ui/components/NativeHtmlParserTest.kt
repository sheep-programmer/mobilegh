package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub 渲染的 HTML 解析成原生块级模型：这些断言对应渲染时实际看到的结构。 */
class NativeHtmlParserTest {
    private val base = "https://github.com/a/b/blob/main/"

    private fun parse(html: String) = HtmlParser.parse(html, base)

    private fun plain(inlines: List<Inline>): String =
        inlines.filterIsInstance<Inline.Text>().joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()

    @Test fun headingsKeepAnchorsWithoutUserContentPrefix() {
        val h = parse("<h2 dir=\"auto\"><a id=\"user-content-为什么用它\" class=\"anchor\" href=\"#为什么用它\"></a>为什么用它</h2>")
            .blocks.single() as HtmlBlock.Heading
        assertEquals(2, h.level)
        assertTrue(h.anchors.contains("为什么用它"))
        assertEquals("为什么用它", plain(h.inlines))
    }

    @Test fun relativeLinksResolveAgainstPageBase() {
        val p = parse("<p><a href=\"docs/x.md\">doc</a></p>").blocks.single() as HtmlBlock.Paragraph
        val link = p.inlines.filterIsInstance<Inline.Text>().first { it.style.link != null }
        assertEquals("https://github.com/a/b/blob/main/docs/x.md", link.style.link)
        assertEquals("doc", link.text)
    }

    @Test fun pageAnchorLinksStayAsFragments() {
        val p = parse("<p><a href=\"#安装\">跳到安装</a></p>").blocks.single() as HtmlBlock.Paragraph
        val link = p.inlines.filterIsInstance<Inline.Text>().first { it.style.link != null }
        assertEquals("#安装", link.style.link)
    }

    @Test fun autolinkStopsBeforeFullWidthPunctuation() {
        val html = "<p>示例 <a href=\"https://github.com/example/fsplayer%EF%BC%8C%E5%9F%BA%E4%BA%8E\">" +
            "https://github.com/example/fsplayer，基于</a> b 站</p>"
        val inlines = (parse(html).blocks.single() as HtmlBlock.Paragraph).inlines.filterIsInstance<Inline.Text>()
        val linked = inlines.filter { it.style.link != null }
        assertEquals(listOf("https://github.com/example/fsplayer"), linked.map { it.text })
        assertTrue(inlines.any { it.text == "，基于" && it.style.link == null })
    }

    @Test fun taskItemsGetCheckboxPrefix() {
        val list = parse(
            "<ul><li class=\"task-list-item\"><input type=\"checkbox\" disabled checked> 已完成</li>" +
                "<li><input type=\"checkbox\" disabled> 待办</li></ul>",
        ).blocks.single() as HtmlBlock.ListBlock
        assertEquals("☑ 已完成", plain((list.items[0].single() as HtmlBlock.Paragraph).inlines))
        assertEquals("☐ 待办", plain((list.items[1].single() as HtmlBlock.Paragraph).inlines))
    }

    @Test fun orderedListKeepsStart() {
        val list = parse("<ol start=\"3\"><li>x</li><li>y</li></ol>").blocks.single() as HtmlBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test fun tableKeepsHeaderAndAlignment() {
        val t = parse(
            "<table><thead><tr><th align=\"center\">A</th><th>B</th></tr></thead>" +
                "<tbody><tr><td>1</td><td align=\"right\">2</td></tr></tbody></table>",
        ).blocks.single() as HtmlBlock.Table
        assertEquals(listOf(TableAlign.Center, TableAlign.None), t.aligns)
        assertEquals(listOf("A", "B"), t.header.map { plain(it) })
        assertEquals(1, t.rows.size)
        assertEquals(listOf("1", "2"), t.rows[0].map { plain(it) })
    }

    @Test fun codeBlockKeepsLanguageAndLines() {
        val c = parse("<div class=\"highlight highlight-source-kotlin\"><pre>fun a() {\n  return 1\n}\n</pre></div>")
            .blocks.single() as HtmlBlock.Code
        assertEquals("kotlin", c.language)
        assertEquals("fun a() {\n  return 1\n}", c.text)
    }

    @Test fun codeBlockLanguageFromCodeClass() {
        val c = parse("<pre><code class=\"language-python\">print(1)\n</code></pre>").blocks.single() as HtmlBlock.Code
        assertEquals("python", c.language)
        assertEquals("print(1)", c.text)
    }

    @Test fun onlyDiagramBlockGoesToWebViewRestStaysNative() {
        val blocks = parse(
            "<p>前文</p>" +
                "<section class=\"js-render-needs-enrichment\" data-type=\"mermaid\"><div><pre lang=\"mermaid\">graph TD</pre></div></section>" +
                "<p>后文</p>",
        ).blocks
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is HtmlBlock.Paragraph)
        val web = blocks[1] as HtmlBlock.Web
        assertTrue(web.html.startsWith("<section"))
        assertTrue(web.html.contains("data-type=\"mermaid\""))
        assertEquals(base, web.baseUrl)
        assertTrue(blocks[2] is HtmlBlock.Paragraph)
    }

    @Test fun paragraphWithInlineMathIsOneWebBlock() {
        val b = parse("<p>面积 <math-renderer>x^2</math-renderer> 平方米</p>").blocks.single()
        assertTrue(b is HtmlBlock.Web)
        assertTrue((b as HtmlBlock.Web).html.contains("math-renderer"))
    }

    @Test fun diagramInsideListOnlyReplacesThatPart() {
        val list = parse("<ul><li>说明<pre lang=\"mermaid\">graph TD</pre></li></ul>").blocks.single() as HtmlBlock.ListBlock
        val item = list.items.single()
        assertTrue(item[0] is HtmlBlock.Paragraph)
        assertTrue(item[1] is HtmlBlock.Web)
    }

    @Test fun plainTextMentioningMermaidStaysNative() {
        assertTrue(parse("<p>plain mermaid word</p>").blocks.single() is HtmlBlock.Paragraph)
        assertTrue(parse("<pre><code>ok</code></pre>").blocks.single() is HtmlBlock.Code)
    }

    @Test fun badgesStayInlineButLargeImagesBecomeBlocks() {
        val badge = parse("<p>状态 <img src=\"https://x/b.svg\" alt=\"ci\" height=\"20\"> 结束</p>").blocks.single()
        assertTrue(badge is HtmlBlock.Paragraph)
        assertTrue((badge as HtmlBlock.Paragraph).inlines.any { it is Inline.Image })

        val big = parse("<p><img src=\"https://x/p.png\" alt=\"pic\"></p>").blocks.single()
        assertTrue(big is HtmlBlock.Image)
    }

    @Test fun detailsKeepSummaryBodyAndOpenState() {
        val d = parse("<details><summary>展开</summary><p>内容</p></details>").blocks.single() as HtmlBlock.Details
        assertEquals("展开", plain(d.summary))
        assertEquals("内容", plain((d.blocks.single() as HtmlBlock.Paragraph).inlines))
        assertFalse(d.open)
        assertTrue((parse("<details open><summary>a</summary>b</details>").blocks.single() as HtmlBlock.Details).open)
    }

    @Test fun videoUsesResolvedSource() {
        val v = parse("<video src=\"/a/b/clip.mp4\" controls></video>").blocks.single() as HtmlBlock.Video
        assertEquals("https://github.com/a/b/clip.mp4", v.src)
    }

    @Test fun textAndBlockElementsAreSeparated() {
        val blocks = parse("<div>前言<p>第二段</p>尾注</div>").blocks
        assertEquals(3, blocks.size)
        assertEquals("前言", plain((blocks[0] as HtmlBlock.Paragraph).inlines))
        assertEquals("第二段", plain((blocks[1] as HtmlBlock.Paragraph).inlines))
        assertEquals("尾注", plain((blocks[2] as HtmlBlock.Paragraph).inlines))
    }

    @Test fun trimInlinesDropsEdgeWhitespaceAndBreaks() {
        val t = trimInlines(listOf(Inline.Break, Inline.Text("  a  "), Inline.Break, Inline.Text("   ")))
        assertEquals(listOf(Inline.Text("a")), t)
    }
}

class NativeHtmlBadgeTest {
    private val base = "https://github.com/a/b/blob/main/"
    private fun parse(html: String) = HtmlParser.parse(html, base)

    @Test fun badgeRowBecomesOneImageRowAndKeepsLinks() {
        val html = "<p><a href=\"https://ci.example/run\"><img src=\"https://github.com/a/b/workflows/ci/badge.svg\" alt=\"Build\"></a>\n" +
            "<a href=\"https://crates.io/crates/x\"><img src=\"https://camo.githubusercontent.com/abc\" data-canonical-src=\"https://img.shields.io/crates/v/x.svg\" alt=\"Crates\"></a></p>"
        val row = parse(html).blocks.single() as HtmlBlock.ImageRow
        assertEquals(2, row.images.size)
        assertEquals("https://ci.example/run", row.images[0].link)
        assertEquals("https://crates.io/crates/x", row.images[1].link)
        assertTrue(row.images.all { it.badge })
    }

    @Test fun singleBadgeAlsoBecomesRowButSingleScreenshotStaysBlock() {
        val badge = parse("<p><img src=\"https://img.shields.io/x.svg\" alt=\"v\"></p>").blocks.single()
        assertTrue(badge is HtmlBlock.ImageRow)
        val shot = parse("<p><a href=\"https://x.io\"><img src=\"https://x/p.png\" alt=\"pic\"></a></p>").blocks.single() as HtmlBlock.Image
        assertEquals("https://x.io", shot.link)
    }

    @Test fun galleryOfScreenshotsKeepsWidthAttributesInOneRow() {
        val row = parse("<p><img src=\"1.png\" width=\"220\"> <img src=\"2.png\" width=\"220\"></p>").blocks.single() as HtmlBlock.ImageRow
        assertEquals(listOf(220, 220), row.images.map { it.width })
    }

    @Test fun badgeUrlDetection() {
        assertTrue(HtmlParser.isBadgeUrl("https://img.shields.io/crates/v/x.svg"))
        assertTrue(HtmlParser.isBadgeUrl("https://github.com/a/b/actions/workflows/ci.yml/badge.svg?branch=main"))
        assertTrue(HtmlParser.isBadgeUrl("https://x.io/logo.svg"))
        assertEquals(false, HtmlParser.isBadgeUrl("https://x.io/photo.png"))
    }

    @Test fun textBetweenImagesKeepsParagraphFlow() {
        val blocks = parse("<p>版本 <img src=\"https://img.shields.io/x.svg\" height=\"20\"> 发布</p>").blocks
        assertTrue(blocks.single() is HtmlBlock.Paragraph)
    }

    @Test fun sponsorLogosWithHeightBecomeOneFlowRowNotInlineText() {
        // magpie README 赞助商：<a><img width=144 height=36></a> 多张，中间有空格和 <br>；塞进文字行会上下重叠
        val html = "<p><a href=\"https://a\"><img src=\"a.svg\" width=\"144\" height=\"36\"></a>  " +
            "<a href=\"https://b\"><img src=\"b.svg\" width=\"144\" height=\"36\"></a><br>\n" +
            "<a href=\"https://c\"><img src=\"c.png\" width=\"144\" height=\"36\"></a></p>"
        val row = parse(html).blocks.single() as HtmlBlock.ImageRow
        assertEquals(3, row.images.size)
        assertEquals(listOf(144, 144, 144), row.images.map { it.width })
    }

    @Test fun singleLogoWithHeightKeepsWidthAndTextMixedTallImageSplits() {
        val logo = parse("<p><img src=\"l.png\" width=\"144\" height=\"36\"></p>").blocks.single() as HtmlBlock.Image
        assertEquals(144, logo.width)
        val blocks = parse("<p>前 <img src=\"l.png\" width=\"100\" height=\"36\"> 后</p>").blocks
        assertTrue(blocks.any { it is HtmlBlock.Image })
        val small = parse("<p>前 <img src=\"i.png\" height=\"16\"> 后</p>").blocks.single()
        assertTrue(small is HtmlBlock.Paragraph)
    }

    @Test fun tableCellKeepsScreenshotAsImageInline() {
        val t = parse("<table><thead><tr><th>A</th></tr></thead><tbody><tr><td><img src=\"s.png\" alt=\"s\"><br>首页</td></tr></tbody></table>")
            .blocks.single() as HtmlBlock.Table
        val img = t.rows[0][0].filterIsInstance<Inline.Image>().single()
        assertEquals(null, img.height)
        assertEquals(false, HtmlParser.isInlineImage(img))
    }

    @Test fun centeredDivKeepsAlignmentForHeadingTextAndImages() {
        val html = "<div align=\"center\"><p><img src=\"logo.png\" width=\"120\"></p><h1>MobileGH</h1><p>简介</p></div><p>左对齐</p>"
        val blocks = parse(html).blocks
        assertEquals(2, blocks.size)
        val box = blocks[0] as HtmlBlock.Aligned
        assertEquals(BlockAlign.Center, box.align)
        assertEquals(3, box.blocks.size)
        assertTrue(box.blocks[0] is HtmlBlock.Image)
        assertTrue(box.blocks[1] is HtmlBlock.Heading)
        assertTrue(blocks[1] is HtmlBlock.Paragraph)
    }

    @Test fun centerTagAndTextAlignStyleAreRecognised() {
        assertTrue(parse("<center><p>x</p></center>").blocks.single() is HtmlBlock.Aligned)
        assertEquals(BlockAlign.Center, (parse("<p style=\"text-align: center\">x</p>").blocks.single() as HtmlBlock.Aligned).align)
        assertEquals(BlockAlign.End, (parse("<p align=\"right\">x</p>").blocks.single() as HtmlBlock.Aligned).align)
    }

    @Test fun imageLinkToImageFileOpensViewerDirectly() {
        val row = parse("<p><a href=\"docs/s/01.png\"><img src=\"docs/s/01.png\" width=\"220\"></a> <a href=\"docs/s/02.png\"><img src=\"docs/s/02.png\" width=\"220\"></a></p>")
            .blocks.single() as HtmlBlock.ImageRow
        assertTrue(row.images.all { it.link == null && it.viewSrc == null })
        // 缩略图链接到大图：看图器显示大图；blob 页面地址换成 raw
        val thumb = parse("<p><a href=\"https://github.com/a/b/blob/main/big.png\"><img src=\"small.png\"></a></p>").blocks.single() as HtmlBlock.Image
        assertEquals(null, thumb.link)
        assertEquals("https://github.com/a/b/raw/main/big.png", thumb.viewSrc)
        // 链接到普通页面的图片仍然是链接
        val page = parse("<p><a href=\"https://x.io/page\"><img src=\"p.png\"></a></p>").blocks.single() as HtmlBlock.Image
        assertEquals("https://x.io/page", page.link)
    }
}
