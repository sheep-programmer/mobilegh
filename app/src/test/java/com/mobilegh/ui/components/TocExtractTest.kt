package com.mobilegh.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TocExtractTest {
    @Test fun realReadmeTocEntriesMatchHeadings() {
        val f = File("/tmp/msi.html")
        if (!f.exists()) return
        val html = f.readText()
        val hs = readmeHeadings(html)
        println("TOC=" + hs.take(6).map { it.text + "->" + it.anchor })
        assertTrue("特性" in hs.map { it.text })
        assertTrue(hs.any { it.text == "特性" && it.anchor == "特性" })
    }
}
