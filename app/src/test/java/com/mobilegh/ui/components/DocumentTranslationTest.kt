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

        val result = translateDocumentBlocks(listOf(heading, list, details, codeBlock)) { if ("[XQ" in it) "broken marker" else "[$it]" }

        assertEquals(heading.copy(inlines = listOf(Inline.Text("[Installation]"))), result[0])
        assertEquals(list.copy(items = listOf(listOf(HtmlBlock.Paragraph(listOf(
            Inline.Text("  [Guide] @octocat [for] owner/repo v1.2.3  ", style),
        ))))), result[1])
        assertEquals(details.copy(summary = listOf(Inline.Text("[More]"))), result[2])
        assertEquals(codeBlock, result[3])
    }

    @Test fun translatesFormattedSentenceAsOneRunAndRestoresProtectedText() = runBlocking {
        val link = InlineStyle(bold = true, link = "https://github.com/owner/repo")
        val code = Inline.Text("git pull", InlineStyle(code = true))
        val block = HtmlBlock.Paragraph(listOf(
            Inline.Text("For owner/repo v1.2.3, read the "),
            Inline.Text("upgrade guide", link),
            Inline.Text(" before running "),
            code,
            Inline.Text("."),
        ))
        val requests = mutableListOf<String>()
        val result = translateDocumentBlocks(listOf(block)) { input ->
            requests += input
            "运行［xq 4 x］之前，请先阅读 [XQ0X] [XQ1X] 的 [XQ2X]升级指南[XQ3X]。"
        }

        assertEquals(listOf("For [XQ0X] [XQ1X], read the [XQ2X]upgrade guide[XQ3X] before running [XQ4X]."), requests)
        assertEquals(listOf(HtmlBlock.Paragraph(listOf(
            Inline.Text("运行"),
            code,
            Inline.Text("之前，请先阅读 owner/repo v1.2.3 的 "),
            Inline.Text("升级指南", link),
            Inline.Text("。"),
        ))), result)
    }

    @Test fun paragraphLanguageOverridesDocumentLanguageAndFallsBackOnlyWhenUncertain() = runBlocking {
        val sample = translationLanguageSample(sequenceOf("@octocat owner/repo v1.2.3", "Read the upgrade guide."))
        assertEquals("Read the upgrade guide.", sample)
        assertEquals("en", resolveTranslationLanguage(sample, { "en" }, { error("Document lookup is unnecessary") }))
        assertEquals("zh", resolveTranslationLanguage("简介", { "und" }, { "zh" }))
        assertEquals(null, resolveTranslationLanguage("", { error("Empty prose") }, { error("Empty prose") }))
    }

    @Test fun relativeReadmeLinksKeepTheQueryAndFragment() {
        val base = "https://github.com/example/project/blob/main/docs/README.md"
        assertEquals("$base#install", HtmlParser.safeResolve(base, "README.md#install"))
        assertEquals("https://github.com/example/project/blob/main/README.md?plain=1#install",
            HtmlParser.safeResolve(base, "../README.md?plain=1#install"))
    }
}
