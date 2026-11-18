package com.mobilegh.ui.components

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentTranslationTest {
    @Test fun preservesHeadingIdsLinksCodeAndMediaWhileTranslatingProse() = runBlocking {
        val heading = HtmlBlock.Heading(2, listOf("installation"), listOf(Inline.Text("Installation")))
        val style = InlineStyle(link = "https://example.org/doc", bold = true)
        val code = Inline.Text("run ./install.sh", InlineStyle(code = true))
        val image = Inline.Image("https://example.org/cover.png", "Cover", 80, 60, "https://example.org/")
        val details = HtmlBlock.Details(false, listOf(Inline.Text("More")), listOf(HtmlBlock.Paragraph(listOf(code, image))))
        val list = HtmlBlock.ListBlock(false, 1, listOf(listOf(HtmlBlock.Paragraph(listOf(Inline.Text("  Guide @octocat for owner/repo v1.2.3  ", style))))))
        val codeBlock = HtmlBlock.Code("sh", "make install")

        val result = translateDocumentBlocks(listOf(heading, list, details, codeBlock)) { "[$it]" }

        assertEquals(heading.copy(inlines = listOf(Inline.Text("[Installation]"))), result[0])
        assertEquals(list.copy(items = listOf(listOf(HtmlBlock.Paragraph(listOf(
            Inline.Text("  [Guide] @octocat [for] owner/repo v1.2.3  ", style),
        ))))), result[1])
        assertEquals(details.copy(summary = listOf(Inline.Text("[More]"))), result[2])
        assertEquals(codeBlock, result[3])
    }

    @Test fun relativeReadmeLinksKeepTheQueryAndFragment() {
        val base = "https://github.com/example/project/blob/main/docs/README.md"
        assertEquals("$base#install", HtmlParser.safeResolve(base, "README.md#install"))
        assertEquals("https://github.com/example/project/blob/main/README.md?plain=1#install",
            HtmlParser.safeResolve(base, "../README.md?plain=1#install"))
    }
}
