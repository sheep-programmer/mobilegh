package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** GitHub 把紧跟网址的全角标点算进自动链接；应用里要截回到标点之前。 */
class CjkAutolinkTest {
    @Test fun cutsAutolinkAtFullWidthCommaAndKeepsRestAsText() {
        val github = "<p>示例 <a href=\"https://github.com/example/fsplayer%EF%BC%8C%E5%9F%BA%E4%BA%8E\">" +
            "https://github.com/example/fsplayer，基于</a> b 站 结束</p>"
        assertEquals(
            "<p>示例 <a href=\"https://github.com/example/fsplayer\">https://github.com/example/fsplayer</a>，基于 b 站 结束</p>",
            fixCjkAutolinks(github),
        )
    }

    @Test fun leavesAutolinkWithoutCjkPunctuationUnchanged() {
        val html = "<a href=\"https://github.com/a/b\">https://github.com/a/b</a>，后面的字"
        assertEquals(html, fixCjkAutolinks(html))
    }

    @Test fun leavesNamedLinksUnchanged() {
        val html = "<a href=\"https://example.com/x%EF%BC%8C\">点这里，查看</a>"
        assertEquals(html, fixCjkAutolinks(html))
    }

    @Test fun cutsAtFirstOfSeveralPunctuationMarks() {
        val github = "<a href=\"https://a.dev/p%E3%80%82%EF%BC%9F\">https://a.dev/p。？</a>"
        assertEquals("<a href=\"https://a.dev/p\">https://a.dev/p</a>。？", fixCjkAutolinks(github))
    }
}
