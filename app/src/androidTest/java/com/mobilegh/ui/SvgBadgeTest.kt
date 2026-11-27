package com.mobilegh.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.ui.components.Images
import com.mobilegh.ui.components.SvgForeignText
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SvgBadgeTest {
    @Test fun atomGitFlexBadgeRendersVectorLogoAndLiveCount() {
        val source = InstrumentationRegistry.getInstrumentation().context.assets.open("atomgit-star-badge.svg").use { it.readBytes() }
        val normalized = SvgForeignText.normalize(source)
        assertNotSame("XHTML badge must be converted into native SVG", source, normalized)
        assertTrue(normalized.decodeToString().contains("278 Stars"))
        assertTrue(!normalized.decodeToString().contains("foreignObject"))
        val changedCount = source.decodeToString().replace(">278<", ">42<").encodeToByteArray()
        assertTrue("Count must come from the image payload", SvgForeignText.normalize(changedCount).decodeToString().contains("42 Stars"))
        val unsupported = source.decodeToString().replace("display: flex;", "display: grid;").encodeToByteArray()
        assertSame("Unsupported layouts must stay untouched", unsupported, SvgForeignText.normalize(unsupported))
        val bitmap = requireNotNull(Images.decode(source, 660)).asAndroidBitmap()
        fun pixels(from: Int, to: Int, predicate: (Int) -> Boolean): Int =
            (3 until 17).sumOf { y -> (from until to).count { x -> predicate(bitmap.getPixel(x * 3, y * 3)) } }
        assertTrue("Gold logo must render", pixels(3, 63) {
            android.graphics.Color.alpha(it) > 200 && android.graphics.Color.red(it) > 180 && android.graphics.Color.blue(it) < 80
        } > 200)
        assertTrue("Count and label must render", pixels(67, 129) {
            android.graphics.Color.alpha(it) > 200 && android.graphics.Color.red(it) < 90 &&
                android.graphics.Color.green(it) < 90 && android.graphics.Color.blue(it) < 90
        } > 60)
    }

    @Test fun badgeRendersHtmlLabelsAndKeepsVectorArtwork() {
        val source = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 250 53" width="250" height="55">
              <rect fill="white" width="250" height="53"/>
              <foreignObject x="6" y="10" width="198" height="17" style="font-size: 9px; color: rgb(67, 39, 135); font-family: Arial; font-weight: 400; text-align: center; letter-spacing: 0em; line-height: 1.5;">
                <div xmlns="http://www.w3.org/1999/xhtml">GITHUB TRENDING</div>
              </foreignObject>
              <svg x="10" y="8" width="48" height="45" viewBox="0 0 80 80">
                <path fill="#49278e" d="M0 45h80v25H0Z"/>
              </svg>
              <foreignObject x="64" y="24" width="230" height="35" style="font-size: 14px; color: rgb(67, 39, 135); font-family: Arial; font-weight: 700; text-align: left; line-height: 1.5;">
                <div xmlns="http://www.w3.org/1999/xhtml">#3 Repository Of The Day</div>
              </foreignObject>
              <foreignObject x="-36" y="9" width="141" height="36" style="font-size: 18px; color: rgb(74, 14, 153); text-align: center; line-height: 1.5;">
                <div xmlns="http://www.w3.org/1999/xhtml">3</div>
              </foreignObject>
            </svg>
        """.trimIndent()
        val bitmap = requireNotNull(Images.decode(source.toByteArray(), 750)).asAndroidBitmap()

        assertTrue("Vector artwork must remain", bitmap.purplePixels(12..55, 34..45) > 100)
        assertTrue("Centered heading must render", bitmap.purplePixels(55..150, 11..25) > 65)
        assertTrue("Ranking label must render", bitmap.purplePixels(64..235, 26..46) > 100)
        assertTrue("Rank must render inside the emblem", bitmap.purplePixels(25..44, 10..32) > 20)
    }

    @Test fun stellarRaftDiagramsDecode() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (name in listOf("stellar-ignite-state.svg", "stellar-architecture.svg")) {
            val bytes = assets.open(name).use { it.readBytes() }
            val bmp = requireNotNull(Images.decode(bytes, 1024)) { "$name must decode" }.asAndroidBitmap()
            assertTrue("$name must have positive dimensions", bmp.width > 500 && bmp.height > 150)
            var opaque = 0
            for (y in 0 until bmp.height step 10) {
                for (x in 0 until bmp.width step 10) {
                    if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 200) opaque++
                }
            }
            assertTrue("$name must render visible pixels", opaque > 100)
        }
    }

    @Test fun svg2AndWebExportedCompatibilityRenders() {
        val source = """
            <svg xmlns="http://www.w3.org/2000/svg" width="auto" height="auto" viewBox="0 0 240 120">
              <defs>
                <marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                  <path d="M0 0 L10 5 L0 10 z" fill="#2563eb"/>
                </marker>
              </defs>
              <g transform="none">
                <rect x="10" y="10" width="220" height="100" rx="auto" fill="#0f172a"/>
                <g transform="translate(20px, 20px) translateX(10px) rotate(0deg)">
                  <line x1="0" y1="30" x2="140" y2="30" stroke="#2563eb" stroke-width="3" marker-end="url(#arrow)"/>
                  <text x="0" y="65" fill="#ffffff" font-size="14">Step&nbsp;1&mdash;OK&copy;</text>
                </g>
              </g>
            </svg>
        """.trimIndent().encodeToByteArray()
        val bmp = requireNotNull(Images.decode(source, 720)) { "SVG 2 / web-exported SVG must decode" }.asAndroidBitmap()
        assertTrue("Decoded SVG must use viewBox aspect ratio", bmp.width > bmp.height && bmp.width >= 240)
        var opaque = 0
        for (y in 0 until bmp.height step 8) {
            for (x in 0 until bmp.width step 8) {
                if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 200) opaque++
            }
        }
        assertTrue("Rendered SVG must have visible pixels", opaque > 50)
    }

    @Test fun unsupportedMarkupAndSvgFallbackStayUnchanged() {
        val source = """
            <svg xmlns="http://www.w3.org/2000/svg" width="250" height="55">
              <foreignObject width="250" height="55"><div xmlns="http://www.w3.org/1999/xhtml"><span>Rich text</span></div></foreignObject>
              <switch>
                <foreignObject width="250" height="55"><div xmlns="http://www.w3.org/1999/xhtml">HTML label</div></foreignObject>
                <text x="10" y="35">SVG fallback</text>
              </switch>
            </svg>
        """.trimIndent().toByteArray()
        assertSame(source, SvgForeignText.normalize(source))
    }

    private fun Bitmap.purplePixels(columns: IntRange, rows: IntRange): Int = rows.sumOf { row ->
        columns.count { column ->
            val color = getPixel(column * 3, row * 3)
            android.graphics.Color.alpha(color) > 200 &&
                android.graphics.Color.blue(color) - android.graphics.Color.red(color) > 35
        }
    }
}
