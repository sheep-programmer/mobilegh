package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码块按钮：注入到页面脚本里的文字必须是合法、安全的 JS 字面量，
 * 代码本身只能经 Bridge 参数传递，不能拼进脚本源码。
 */
class HtmlCodeBlockTest {
    @Test fun jsLiteralEscapesQuotesAndBackslashes() {
        assertEquals("\"\"", jsStringLiteral(""))
        assertEquals("\"a\\\"b\\\\c\"", jsStringLiteral("a\"b\\c"))
    }

    @Test fun jsLiteralKeepsNewlinesAndTabsAsEscapes() {
        assertEquals("\"x\\n\\t\"", jsStringLiteral("x\n\t"))
        assertFalse(jsStringLiteral("line1\nline2\r\n").contains('\n'))
    }

    @Test fun jsLiteralNeverLetsScriptTagClose() {
        // 文本里的 </script> 不能提前结束 <script>，< > & 统一转成 \\uXXXX
        assertEquals("\"\\u003c/script\\u003e\"", jsStringLiteral("</script>"))
        assertEquals("\"\\u0026\"", jsStringLiteral("&"))
    }

    @Test fun jsLiteralEscapesLineSeparatorsAndControlChars() {
        assertEquals("\"\\u2028\\u2029\"", jsStringLiteral("\u2028\u2029"))
        assertEquals("\"\\u0001\"", jsStringLiteral("\u0001"))
    }

    @Test fun wrappedPageWiresCopyAndZoomBridges() {
        val html = wrapHtml("<div class=\"highlight\"><pre><code>val a = 1</code></pre></div>", dark = false)
        // 按钮文字走 jsStringLiteral 注入（中文模式下即原文）
        assertTrue(html.contains("copy:\"复制\",zoom:\"放大\""))
        // 代码通过 Bridge 参数传递，点击时才读取 textContent
        assertTrue(html.contains("Bridge.copyCode(code())"))
        assertTrue(html.contains("Bridge.zoomCode(code())"))
        assertTrue(html.contains("document.querySelectorAll('pre')"))
        // 原始代码块没有被丢弃
        assertTrue(html.contains("<pre><code>val a = 1</code></pre>"))
    }

    @Test fun toolbarSitsAboveTheCodeSoItNeverCoversSingleLineCode() {
        val html = wrapHtml("<div class=\"highlight\"><pre>one line</pre></div>", dark = false)
        // 工具栏必须插在 pre 之前（不再绝对定位覆盖代码）
        assertTrue(html.contains("w.appendChild(t); w.appendChild(p);"))
        assertFalse(html.contains("position:absolute;top:6px;right:6px"))
    }

    @Test fun mermaidScriptIsOnlyLoadedWhenThePageHasMermaid() {
        val plain = wrapHtml("<pre>x</pre>", dark = false)
        assertFalse(plain.contains("mermaid.min.js"))
        val withMermaid = wrapHtml("<section data-type=\"mermaid\"><pre lang=\"mermaid\">graph LR; A--&gt;B</pre></section>", dark = true)
        assertTrue(withMermaid.contains(MERMAID_SCRIPT_URL))
        assertTrue(withMermaid.contains("mermaid.render("))
        assertTrue(withMermaid.contains("var DARK_MODE=true;"))
    }

    @Test fun librariesAreOnlyInjectedForTheContentThatNeedsThem() {
        val math = wrapHtml("<math-renderer class=\"js-inline-math\">\$x\$</math-renderer>", dark = false)
        assertTrue(math.contains(KATEX_JS_URL))
        assertTrue(math.contains(KATEX_CSS_URL))
        assertFalse(math.contains(MERMAID_SCRIPT_URL))
        val geo = wrapHtml("<section data-type=\"geojson\"></section>", dark = false)
        assertTrue(geo.contains(GEO_SCRIPT_URL))
        assertFalse(geo.contains(STL_SCRIPT_URL))
        val topo = wrapHtml("<section data-type=\"topojson\"></section>", dark = false)
        assertTrue(topo.contains(GEO_SCRIPT_URL))
        val stl = wrapHtml("<section data-type=\"stl\"></section>", dark = false)
        assertTrue(stl.contains(STL_SCRIPT_URL))
        assertFalse(stl.contains(KATEX_JS_URL))
    }

    @Test fun viewerLoadsTheLibraryForItsOwnDiagramKind() {
        assertTrue(diagramViewerHtml("stl", "solid x\nendsolid x", dark = false).contains(STL_SCRIPT_URL))
        assertTrue(diagramViewerHtml("geojson", "{}", dark = false).contains(GEO_SCRIPT_URL))
        assertTrue(diagramViewerHtml("mermaid", "graph LR; A-->B", dark = false).contains(MERMAID_SCRIPT_URL))
    }
}
