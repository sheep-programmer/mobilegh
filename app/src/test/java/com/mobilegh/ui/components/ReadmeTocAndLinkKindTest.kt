package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadmeTocAndLinkKindTest {
    @Test fun readmeHeadingsComeFromAnchoredHeadingTagsOnly() {
        val html = """
            <h1 tabindex="-1" dir="auto"><a id="user-content-demo" class="anchor" aria-hidden="true" href="#demo"><svg></svg></a>Demo</h1>
            <p>body text</p>
            <h2 tabindex="-1" dir="auto"><a id="user-content-install" class="anchor" href="#install"></a>Install &amp; <code>run</code></h2>
            <h3 data-id="x">No anchor here</h3>
            <h4><a id="user-content-deep" href="#deep"></a>   Deep
              heading   </h4>
        """.trimIndent()
        assertEquals(
            listOf(
                ReadmeHeading(1, "Demo", "demo"),
                ReadmeHeading(2, "Install & run", "install"),
                ReadmeHeading(4, "Deep heading", "deep"),
            ),
            readmeHeadings(html),
        )
    }

    @Test fun emptyOrAnchorlessDocumentHasNoHeadings() {
        assertTrue(readmeHeadings("").isEmpty())
        assertTrue(readmeHeadings("<h2>Plain</h2>").isEmpty())
    }

    @Test fun headingScrollScriptEscapesTheAnchorAndUsesTheBridge() {
        val script = headingScrollScript("a\"b")
        assertTrue(script.contains("var id=\"a\\\"b\";"))
        assertTrue(script.contains("Bridge.a("))
    }

    @Test fun externalLinksAreClassifiedBySchemeOnly() {
        assertEquals(ExternalLinkKind.Web, externalLinkKind("https://example.com/a"))
        assertEquals(ExternalLinkKind.Web, externalLinkKind("HTTP://example.com"))
        assertEquals(ExternalLinkKind.Mail, externalLinkKind("mailto:someone@example.com?subject=hi"))
        assertEquals(ExternalLinkKind.Phone, externalLinkKind("tel:+15550100"))
        assertEquals(ExternalLinkKind.Other, externalLinkKind("intent://scan#Intent;end"))
        assertEquals(ExternalLinkKind.Other, externalLinkKind("javascript:void(0)"))
    }

    @Test fun codeHighlightSupportIsDecidedByFileName() {
        assertTrue(canHighlight("src/Main.kt"))
        assertTrue(canHighlight("build/Dockerfile"))
        assertTrue(canHighlight(".github/workflows/ci.yml"))
        assertEquals(false, canHighlight("notes/readme.txt"))
    }
}
