package com.mobilegh.ui.components

import com.mobilegh.ui.theme.DarkGh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeLanguageDetectionTest {

    @Test
    fun detectsJson() {
        val json = """
            {
              "name": "mobilegh",
              "private": true
            }
        """.trimIndent()
        assertEquals("json", detectCodeLanguage(json))
    }

    @Test
    fun detectsXmlAndHtml() {
        val xml = """<?xml version="1.0" encoding="utf-8"?><manifest></manifest>"""
        assertEquals("xml", detectCodeLanguage(xml))

        val html = """
            <!DOCTYPE html>
            <html>
              <body>Hello</body>
            </html>
        """.trimIndent()
        assertEquals("xml", detectCodeLanguage(html))
    }

    @Test
    fun detectsBashAndShellCommands() {
        val script = """
            #!/bin/bash
            echo "Installing..."
        """.trimIndent()
        assertEquals("bash", detectCodeLanguage(script))

        val cli = "$ ./gradlew assembleDebug"
        assertEquals("bash", detectCodeLanguage(cli))

        val curl = "curl -fsSL https://example.com/install.sh | sh"
        assertEquals("bash", detectCodeLanguage(curl))
    }

    @Test
    fun detectsPython() {
        val py = """
            def add(a: int, b: int) -> int:
                return a + b
        """.trimIndent()
        assertEquals("python", detectCodeLanguage(py))
    }

    @Test
    fun detectsKotlin() {
        val kt = """
            fun main() {
                val message = "Hello, world!"
                println(message)
            }
        """.trimIndent()
        assertEquals("kotlin", detectCodeLanguage(kt))
    }

    @Test
    fun detectsRust() {
        val rs = """
            fn main() {
                let mut count = 0;
                println!("Count: {}", count);
            }
        """.trimIndent()
        assertEquals("rust", detectCodeLanguage(rs))
    }

    @Test
    fun detectsGo() {
        val go = """
            package main

            import "fmt"

            func main() {
                fmt.Println("Hello")
            }
        """.trimIndent()
        assertEquals("go", detectCodeLanguage(go))
    }

    @Test
    fun fallsBackToTextForPlainText() {
        val plain = "This is a simple plain text description with no code markers."
        assertEquals("text", detectCodeLanguage(plain))
        assertEquals("text", detectCodeLanguage(""))
    }

    @Test
    fun canHighlightSupportsFullLanguageNames() {
        assertTrue(canHighlight("kotlin"))
        assertTrue(canHighlight("python"))
        assertTrue(canHighlight("javascript"))
        assertTrue(canHighlight("typescript"))
        assertTrue(canHighlight("rust"))
        assertTrue(canHighlight("golang"))
        assertTrue(canHighlight("bash"))
        assertTrue(canHighlight("json"))
        assertTrue(canHighlight("yaml"))
    }

    @Test
    fun highlightLinesProducesColoredSpansForLanguageNames() {
        val sc = syntaxColors(DarkGh)
        val lines = highlightLines("fun calculate(): Int = 42", "kotlin", sc)
        assertEquals(1, lines.size)
        assertTrue("Lines should contain syntax span styles", lines[0].spanStyles.isNotEmpty())
    }
}
