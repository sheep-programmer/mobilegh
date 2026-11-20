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
                val replacement = textBox(element) ?: flexBadge(element) ?: continue
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

    /**
     * Some badge generators put their vector logo and count in an XHTML flex row.
     * AndroidSVG cannot draw foreignObject. Convert only this bounded, static structure;
     * unsupported HTML/CSS stays untouched rather than executing HTML or fetching resources.
     */
    private fun flexBadge(element: Element): Element? {
        if (element.tag().namespace() != svgNamespace || element.parents().any { it.tagName() == "switch" }) return null
        if (element.attributes().any { it.key !in boxAttributes && it.key != "class" }) return null
        val sheet = element.children().singleOrNull { it.tagName() == "style" } ?: return null
        val box = element.children().singleOrNull { it.tagName() == "div" } ?: return null
        if (element.childrenSize() != 2 || box.tag().namespace() != htmlNamespace) return null
        val rules = mutableMapOf<String, Map<String, String>>()
        val css = sheet.data().ifBlank { sheet.wholeText() }
        val rulePattern = Regex("""\.([a-zA-Z][\w-]*)\s*\{([^{}]*)\}""")
        if (rulePattern.replace(css, "").isNotBlank()) return null
        for (match in rulePattern.findAll(css)) {
            val declarations = mutableMapOf<String, String>()
            for (entry in match.groupValues[2].split(';').filter { it.isNotBlank() }) {
                val key = entry.substringBefore(':').trim()
                val value = entry.substringAfter(':', "").trim()
                if (key !in setOf("width", "height", "overflow", "font-family", "font-weight", "display", "color",
                        "font-size", "line-height", "border", "background-color", "border-radius", "padding", "gap", "margin-left") ||
                    value.isEmpty()) return null
                declarations[key] = value
            }
            rules[match.groupValues[1]] = declarations
        }
        fun cssOf(node: Element): Map<String, String>? {
            if (node.attributes().any { it.key !in setOf("class", "xmlns") }) return null
            return node.classNames().fold(emptyMap()) { combined, name -> combined + (rules[name] ?: emptyMap()) }
        }
        val style = cssOf(box) ?: return null
        if (style["display"] != "flex" || box.childrenSize() != 2) return null
        val left = box.child(0)
        val right = box.child(1)
        val leftStyle = cssOf(left) ?: return null
        val rightStyle = cssOf(right) ?: return null
        if (left.tagName() != "div" || right.tagName() != "div" || rightStyle["display"] != "flex") return null
        val logo = left.children().singleOrNull()?.takeIf { it.tagName() == "svg" && it.tag().namespace() == svgNamespace } ?: return null
        if (logo.getAllElements().any { it.tagName() !in setOf("svg", "g", "path", "rect", "circle", "ellipse", "polygon", "polyline", "line", "defs", "clipPath", "title") ||
                it.attributes().any { attr -> attr.key.startsWith("on", true) || attr.key.contains("href", true) || attr.value.contains("url(", true) } }) return null
        if (right.children().isEmpty() || right.children().any { it.tagName() != "span" || it.childNodes().any { child -> child !is TextNode } || cssOf(it) == null }) return null
        val label = right.children().joinToString(" ") { it.text() }.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val parent = element.parents().firstOrNull { it.tagName() == "svg" } ?: return null
        fun dimension(name: String): Float? = element.attr(name).let {
            if (it == "100%") parent.attr(name).pixels() else it.pixels()
        }?.takeIf { it in 1f..2048f }
        val width = dimension("width") ?: return null
        val height = dimension("height") ?: return null
        val fontSize = style["font-size"]?.pixels()?.takeIf { it in 1f..128f } ?: return null
        if (style["font-family"]?.lowercase() !in setOf("arial", "sans-serif") || style["font-weight"] !in setOf(null, "400", "normal")) return null
        val lineHeight = style["line-height"]?.pixels() ?: return null
        val logoWidth = logo.attr("width").pixels()?.takeIf { it > 0 } ?: return null
        val logoHeight = logo.attr("height").pixels()?.takeIf { it > 0 && it <= height } ?: return null
        if (leftStyle["height"]?.pixels() != logoHeight || rightStyle["height"]?.pixels() != lineHeight) return null
        val border = style["border"]?.split(Regex("\\s+")) ?: return null
        if (border.size != 3 || border[1] != "solid" || !colorValue.matches(border[2])) return null
        val stroke = border[0].pixels()?.takeIf { it in 0f..4f } ?: return null
        val padding = style["padding"]?.split(Regex("\\s+"))?.map { it.pixels() ?: return null } ?: return null
        if (padding.size != 4 || padding.any { it !in 0f..32f }) return null
        val gap = rightStyle["margin-left"]?.pixels()?.takeIf { it in 0f..32f } ?: return null
        val background = style["background-color"]?.takeIf { colorValue.matches(it) } ?: return null
        val color = (style["color"] ?: "black").takeIf { colorValue.matches(it) } ?: return null
        val radius = style["border-radius"]?.pixels()?.takeIf { it in 0f..32f } ?: return null
        val metrics = Paint().apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = fontSize }.fontMetrics
        val x = stroke + padding[3]
        val y = stroke + padding[0]
        return Element("svg").attr("xmlns", svgNamespace)
            .attr("x", element.attr("x").ifBlank { "0" }).attr("y", element.attr("y").ifBlank { "0" })
            .attr("width", "$width").attr("height", "$height").attr("overflow", "hidden").apply {
                appendElement("rect").attr("x", "${stroke / 2}").attr("y", "${stroke / 2}")
                    .attr("width", "${width - stroke}").attr("height", "${height - stroke}")
                    .attr("rx", "$radius").attr("fill", background).attr("stroke", border[2]).attr("stroke-width", "$stroke")
                appendChild(logo.clone().attr("x", "$x").attr("y", "$y"))
                appendElement("text").attr("x", "${x + logoWidth + gap}")
                    .attr("y", "${y + (lineHeight - metrics.ascent - metrics.descent) / 2}")
                    .attr("fill", color).attr("font-size", "$fontSize").attr("font-family", "sans-serif").text(label)
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
