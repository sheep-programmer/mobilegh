package com.mobilegh.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgCompatTest {
    @Test fun standardSvgStaysUntouched() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 40"><rect width="100" height="40" fill="#123456"/></svg>""".encodeToByteArray()
        assertSame(svg, SvgCompat.normalize(svg))
    }

    @Test fun normalizesAutoStartReverseAndAutoDimensions() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="auto" height="fit-content" viewBox="0 0 200 100">
              <defs><marker id="arrow" orient="auto-start-reverse"><path d="M0 0L10 5L0 10Z"/></marker></defs>
              <rect width="100" height="50" rx="auto"/>
            </svg>
        """.trimIndent().encodeToByteArray()
        val out = SvgCompat.normalize(svg).decodeToString()
        assertFalse(out.contains("auto-start-reverse"))
        assertTrue(out.contains("""orient="auto""""))
        assertFalse(out.contains("""width="auto""""))
        assertFalse(out.contains("""height="fit-content""""))
        assertFalse(out.contains("""rx="auto""""))
    }

    @Test fun normalizesCssTransformsAndHtmlEntities() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100">
              <g transform="none">
                <g transform="translate(10px, 20px) rotate(45deg) translateX(5px) scaleY(2)">
                  <text x="10" y="20">A&nbsp;B&mdash;C&copy;</text>
                </g>
              </g>
            </svg>
        """.trimIndent().encodeToByteArray()
        val out = SvgCompat.normalize(svg).decodeToString()
        assertFalse(out.contains("""transform="none""""))
        assertTrue(out.contains("""transform="translate(10, 20) rotate(45) translate(5, 0) scale(1, 2)""""))
        assertTrue(out.contains("A&#160;B&#8212;C&#169;"))
    }
}
