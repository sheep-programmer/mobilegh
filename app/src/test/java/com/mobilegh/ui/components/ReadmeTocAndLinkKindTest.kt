package com.mobilegh.ui.components

import com.mobilegh.nav.Screen
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

    /** README 里点链接：GitHub 内部地址走应用原生页面，其余交给浏览器或系统应用，WebView 自己永远不跳转。 */
    @Test fun readmeLinksNeverNavigateInsideTheWebView() {
        val repo = webLinkAction("https://github.com/torvalds/linux")
        assertTrue(repo is WebLinkAction.Native)
        assertEquals(Screen.Repo("torvalds", "linux"), (repo as WebLinkAction.Native).screen)
        assertTrue(webLinkAction("https://github.com/torvalds") is WebLinkAction.Native)
        assertTrue(webLinkAction("https://github.com/torvalds/linux/issues/1") is WebLinkAction.Native)
        assertEquals(WebLinkAction.Browser, webLinkAction("https://example.com/docs"))
        assertEquals(WebLinkAction.System, webLinkAction("mailto:someone@example.com"))
        assertEquals(WebLinkAction.System, webLinkAction("tel:10086"))
        assertEquals(WebLinkAction.Unsupported, webLinkAction("javascript:void(0)"))
    }

    /** v0.30.0 曾误删这两个重写：没有前者链接会在应用内嵌网页里打开，没有后者渲染进程一退出整个 App 就崩溃。 */
    @Test fun webClientKeepsLinkInterceptionAndRenderCrashGuard() {
        val declared = GhWebClient::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue("shouldOverrideUrlLoading", "shouldOverrideUrlLoading" in declared)
        assertTrue("onRenderProcessGone", "onRenderProcessGone" in declared)
    }

    @Test fun codeHighlightSupportIsDecidedByFileName() {
        assertTrue(canHighlight("src/Main.kt"))
        assertTrue(canHighlight("build/Dockerfile"))
        assertTrue(canHighlight(".github/workflows/ci.yml"))
        assertEquals(false, canHighlight("notes/readme.txt"))
    }
}
