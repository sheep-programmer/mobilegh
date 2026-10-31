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
        assertTrue(html.contains("var CB={copy:\"复制\",zoom:\"放大\"};"))
        // 代码通过 Bridge 参数传递，点击时才读取 textContent
        assertTrue(html.contains("Bridge.copyCode(code())"))
        assertTrue(html.contains("Bridge.zoomCode(code())"))
        assertTrue(html.contains("document.querySelectorAll('pre')"))
        // 原始代码块没有被丢弃
        assertTrue(html.contains("<pre><code>val a = 1</code></pre>"))
    }
}
