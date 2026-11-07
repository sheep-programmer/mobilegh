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
        assertEquals(listOf("为什么用它"), h.anchors)
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

    @Test fun diagramsAndMathNeedWebView() {
        assertTrue(parse("<pre lang=\"mermaid\">graph TD</pre>").needsWeb)
        assertTrue(parse("<math-renderer>x</math-renderer>").needsWeb)
        assertTrue(parse("<div class=\"highlight highlight-source-mermaid\"><pre>graph TD</pre></div>").needsWeb)
    }

    @Test fun plainTextMentioningMermaidStaysNative() {
        assertFalse(parse("<p>plain mermaid word</p>").needsWeb)
        assertFalse(parse("<pre><code>ok</code></pre>").needsWeb)
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
