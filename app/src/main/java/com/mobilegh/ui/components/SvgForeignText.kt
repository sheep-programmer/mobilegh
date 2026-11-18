package com.mobilegh.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

internal object SvgForeignText {
    private const val svgNamespace = "http://www.w3.org/2000/svg"
    private const val htmlNamespace = "http://www.w3.org/1999/xhtml"
    private val boxAttributes = setOf("x", "y", "width", "height", "style", "selection")
    private val textStyles = setOf("color", "font-family", "font-size", "font-weight", "line-height", "text-align", "letter-spacing")
    private val whitespace = Regex("[ \t\r\n]+")
    private val colorValue = Regex("#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?|[a-zA-Z]{1,32}|rgb\\([0-9.,% ]{5,48}\\)")

    fun normalize(bytes: ByteArray): ByteArray {
        if (bytes.size > 512 * 1024) return bytes
        return try {
            val source = bytes.decodeToString(throwOnInvalidSequence = true)
            if ("foreignObject" !in source || source.contains("<!DOCTYPE", ignoreCase = true)) return bytes
            val parser = Parser.xmlParser().setTrackErrors(1)
            val document = parser.parseInput(source, "")
            if (parser.errors.isNotEmpty()) return bytes
            var changed = false
            for (element in document.getElementsByTag("foreignObject")) {
                val replacement = textBox(element) ?: continue
                element.replaceWith(replacement)
                changed = true
            }
            if (!changed) return bytes
            document.outputSettings().prettyPrint(false)
            document.outerHtml().encodeToByteArray()
        } catch (_: Exception) {
            bytes
        }
    }

    private fun textBox(element: Element): Element? {
        if (element.tag().namespace() != svgNamespace || element.parents().any { it.tagName() == "switch" }) return null
        if (element.attributes().any { it.key !in boxAttributes }) return null
        val div = element.children().singleOrNull() ?: return null
        if (element.textNodes().any { it.text().isNotBlank() } || div.tagName() != "div" || div.tag().namespace() != htmlNamespace) return null
        if (div.attributes().any { it.key != "xmlns" && it.key != "style" } || div.childNodes().any { it !is TextNode }) return null
        val label = div.wholeText().replace(whitespace, " ").trim(' ')
        if (label.isEmpty() || label.length > 512) return null

        val style = styles(element.attr("style")) ?: return null
        style.putAll(styles(div.attr("style")) ?: return null)
        if (style["letter-spacing"] !in setOf(null, "normal", "0", "0em", "0px")) return null
        val fontSize = (style["font-size"] ?: "16px").pixels()?.takeIf { it in 1f..512f } ?: return null
        val lineHeight = when (val value = style["line-height"]) {
            null, "normal" -> fontSize * 1.2f
            else -> (if (value.endsWith("px")) value.pixels() else value.toFloatOrNull()?.times(fontSize))
                ?.takeIf { it in 0f..2048f } ?: return null
        }
        val family = style["font-family"]?.split(',')?.firstNotNullOfOrNull { name ->
            when (name.trim(' ', '\'', '"').lowercase()) {
                "arial", "helvetica", "sans-serif", "system-ui" -> "sans-serif"
                "times new roman", "times", "serif" -> "serif"
                "courier new", "courier", "monospace" -> "monospace"
                else -> null
            }
        } ?: "serif"
        val weight = when (style["font-weight"]) {
            null, "normal", "400" -> Typeface.NORMAL
            "bold", "700" -> Typeface.BOLD
            else -> return null
        }
        val color = style["color"] ?: "black"
        if (!colorValue.matches(color)) return null

        val left = (element.attr("x").ifEmpty { "0" }).pixels() ?: return null
        val top = (element.attr("y").ifEmpty { "0" }).pixels() ?: return null
        val width = element.attr("width").pixels()?.takeIf { it > 0f } ?: return null
        val height = element.attr("height").pixels()?.takeIf { it > 0f } ?: return null
        val (anchor, textX) = when (style["text-align"]) {
            null, "start", "left" -> "start" to 0f
            "center" -> "middle" to width / 2f
            "end", "right" -> "end" to width
            else -> return null
        }
        val metrics = Paint().apply {
            typeface = Typeface.create(family, weight)
            textSize = fontSize
        }.fontMetrics
        val baseline = (lineHeight - metrics.ascent - metrics.descent) / 2f

        return Element("svg").attr("xmlns", svgNamespace).attr("x", left.toString()).attr("y", top.toString())
            .attr("width", width.toString()).attr("height", height.toString()).attr("overflow", "hidden").apply {
                appendElement("text").attr("x", textX.toString()).attr("y", baseline.toString())
                    .attr("text-anchor", anchor).attr("fill", color).attr("font-size", fontSize.toString())
                    .attr("font-family", family).attr("font-weight", if (weight == Typeface.BOLD) "bold" else "normal")
                    .text(label)
            }
    }

    private fun String.pixels(): Float? = trim().removeSuffix("px").toFloatOrNull()?.takeIf { it in -100_000f..100_000f }

    private fun styles(css: String): MutableMap<String, String>? {
        val result = mutableMapOf<String, String>()
        for (declaration in css.split(';')) {
            if (declaration.isBlank()) continue
            val name = declaration.substringBefore(':').trim().lowercase()
            val value = declaration.substringAfter(':', "").trim()
            if (name !in textStyles || value.isEmpty()) return null
            result[name] = value
        }
        return result
    }
}
