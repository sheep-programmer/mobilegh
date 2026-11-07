package com.mobilegh.ui.components

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

/** 原生渲染的块级内容。 */
internal sealed interface HtmlBlock {
    data class Heading(val level: Int, val anchors: List<String>, val inlines: List<Inline>) : HtmlBlock
    data class Paragraph(val inlines: List<Inline>) : HtmlBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<HtmlBlock>>) : HtmlBlock
    data class Code(val language: String?, val text: String) : HtmlBlock
    data class Quote(val blocks: List<HtmlBlock>) : HtmlBlock
    data class Table(val aligns: List<TableAlign>, val header: List<List<Inline>>, val rows: List<List<List<Inline>>>) : HtmlBlock
    data class Image(val src: String, val alt: String, val width: Int?, val height: Int?) : HtmlBlock
    data class Video(val src: String) : HtmlBlock
    data class Details(val open: Boolean, val summary: List<Inline>, val blocks: List<HtmlBlock>) : HtmlBlock
    data object Rule : HtmlBlock
}

/** 行内内容：带样式的文字、行内图片（徽章等）、换行。 */
internal sealed interface Inline {
    data class Text(val text: String, val style: InlineStyle = InlineStyle()) : Inline
    data class Image(val src: String, val alt: String, val width: Int?, val height: Int?, val link: String?) : Inline
    data object Break : Inline
}

internal data class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val sub: Boolean = false,
    val link: String? = null,
)

internal enum class TableAlign { None, Left, Center, Right }

/** 解析结果。[needsWeb] 为真时内容含有必须在浏览器里运行脚本才能显示的部分（公式、图表）。 */
internal class ParsedHtml(val blocks: List<HtmlBlock>, val needsWeb: Boolean)

/** GitHub 渲染的 HTML 解析成块级模型，供原生 Compose 渲染使用。不依赖 Android，可在单元测试中直接运行。 */
internal object HtmlParser {
    private const val WEB_ONLY =
        "math-renderer, [class*=mermaid], [lang=mermaid], [data-type=geojson], [data-type=topojson], [data-type=stl]"
    private val SKIP = setOf("script", "style", "svg", "head", "template", "noscript", "button", "meta", "link")
    private val INLINE = setOf(
        "a", "abbr", "b", "bdi", "bdo", "br", "cite", "code", "data", "del", "dfn", "em", "font", "g-emoji", "i", "img",
        "input", "ins", "kbd", "label", "mark", "q", "s", "samp", "small", "span", "strike", "strong", "sub", "sup",
        "time", "tt", "u", "var",
    )
    private val WS = Regex("\\s+")
    private val LANG = Regex("language-([\\w+#.-]+)")
    private val SOURCE_LANG = Regex("highlight-source-([\\w+#.-]+)")

    fun parse(html: String, baseUrl: String): ParsedHtml {
        val body = Jsoup.parseBodyFragment(html).body()
        val needsWeb = body.select(WEB_ONLY).isNotEmpty()
        return ParsedHtml(blocks(body, Ctx(baseUrl), emptySet()), needsWeb)
    }

    /** 相对地址按页面地址解析；页内锚点原样保留，交给渲染层跳转。 */
    private class Ctx(baseUrl: String) {
        private val base = runCatching { URI(baseUrl) }.getOrNull()

        fun url(raw: String): String {
            val u = raw.trim()
            if (u.isEmpty() || u.startsWith("#") || u.startsWith("data:")) return u
            return runCatching { base?.resolve(u)?.toString() ?: u }.getOrDefault(u)
        }
    }

    /** 收集 [parent] 下的块级内容；连续的行内内容合并成段落。 */
    private fun blocks(parent: Node, ctx: Ctx, skip: Set<String>): MutableList<HtmlBlock> {
        val out = mutableListOf<HtmlBlock>()
        val run = mutableListOf<Inline>()
        fun flush() {
            val inl = trimInlines(run)
            run.clear()
            if (inl.isNotEmpty()) out += flow(inl)
        }
        for (child in parent.childNodes()) {
            when (child) {
                is TextNode -> run += Inline.Text(collapse(child.wholeText))
                is Element -> {
                    val tag = child.normalName()
                    when {
                        tag in SKIP || tag in skip -> Unit
                        tag in INLINE -> inlineElement(child, InlineStyle(), ctx, run)
                        else -> {
                            flush()
                            blockElement(child, ctx, out)
                        }
                    }
                }
            }
        }
        flush()
        return out
    }

    private fun blockElement(el: Element, ctx: Ctx, out: MutableList<HtmlBlock>) {
        val tag = el.normalName()
        when {
            tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> {
                val anchors = (listOf(el.id()) + el.select("[id]").map { it.id() })
                    .map { anchorName(it) }.filter { it.isNotBlank() }.distinct()
                val inl = trimInlines(collect(el, InlineStyle(), ctx))
                if (inl.isNotEmpty()) out += HtmlBlock.Heading(tag[1] - '0', anchors, inl)
            }
            tag == "pre" -> out += HtmlBlock.Code(languageOf(el), el.wholeText().trimEnd('\n'))
            tag == "div" && el.className().contains("highlight") && el.selectFirst("pre") != null -> {
                val pre = el.selectFirst("pre")!!
                val lang = languageOf(pre) ?: SOURCE_LANG.find(el.className())?.groupValues?.get(1)
                out += HtmlBlock.Code(lang, pre.wholeText().trimEnd('\n'))
            }
            tag == "ul" || tag == "ol" -> {
                val items = el.children().filter { it.normalName() == "li" }.map { blocks(it, ctx, emptySet()) }
                out += HtmlBlock.ListBlock(tag == "ol", el.attr("start").toIntOrNull() ?: 1, items)
            }
            tag == "blockquote" -> out += HtmlBlock.Quote(blocks(el, ctx, emptySet()))
            tag == "table" -> out += table(el, ctx)
            tag == "details" -> {
                val summary = el.children().firstOrNull { it.normalName() == "summary" }
                val title = summary?.let { trimInlines(collect(it, InlineStyle(), ctx)) }.orEmpty()
                out += HtmlBlock.Details(el.hasAttr("open"), title, blocks(el, ctx, setOf("summary")))
            }
            tag == "hr" -> out += HtmlBlock.Rule
            tag == "video" -> {
                val src = el.attr("src").ifBlank { el.selectFirst("source")?.attr("src").orEmpty() }
                    .ifBlank { el.attr("data-canonical-src") }
                if (src.isNotBlank()) out += HtmlBlock.Video(ctx.url(src))
            }
            else -> out += blocks(el, ctx, emptySet())
        }
    }

    private fun flow(inl: List<Inline>): List<HtmlBlock> {
        if (inl.none { it is Inline.Image && !isInlineImage(it) }) return listOf(HtmlBlock.Paragraph(inl))
        val out = mutableListOf<HtmlBlock>()
        var seg = mutableListOf<Inline>()
        fun emit() {
            val t = trimInlines(seg)
            seg = mutableListOf()
            if (t.isNotEmpty()) out += HtmlBlock.Paragraph(t)
        }
        for (item in inl) {
            if (item is Inline.Image && !isInlineImage(item)) {
                emit()
                out += HtmlBlock.Image(item.src, item.alt, item.width, item.height)
            } else {
                seg += item
            }
        }
        emit()
        return out
    }

    /** 高度不超过 48px 的图片当作文字中的徽章（行内），更大的图片单独成块显示。 */
    private fun isInlineImage(i: Inline.Image) = (i.height ?: 0) in 1..48

    private fun collect(el: Element, style: InlineStyle, ctx: Ctx): MutableList<Inline> =
        mutableListOf<Inline>().also { inlineChildren(el, style, ctx, it) }

    private fun inlineChildren(el: Element, style: InlineStyle, ctx: Ctx, out: MutableList<Inline>) {
        for (child in el.childNodes()) {
            when (child) {
                is TextNode -> out += Inline.Text(if (style.code) child.wholeText else collapse(child.wholeText), style)
                is Element -> inlineElement(child, style, ctx, out)
            }
        }
    }

    private fun inlineElement(el: Element, style: InlineStyle, ctx: Ctx, out: MutableList<Inline>) {
        when (val tag = el.normalName()) {
            in SKIP -> Unit
            "br" -> out += Inline.Break
            "img" -> {
                val src = ctx.url(el.attr("src").ifBlank { el.attr("data-canonical-src") })
                if (src.isNotBlank()) {
                    out += Inline.Image(src, el.attr("alt"), el.attr("width").toIntOrNull(), el.attr("height").toIntOrNull(), style.link)
                }
            }
            "a" -> {
                val href = el.attr("href").trim()
                val visible = el.text()
                // 标题旁的锚点图标：没有文字的页内锚点不显示
                if (href.startsWith("#") && visible.isBlank()) return
                val link = if (href.isBlank()) style.link else ctx.url(href)
                // GitHub 自动链接会把紧跟网址的全角标点也算进网址：链接只包到标点之前
                val cut = if (visible.startsWith("http")) CJK_LINK_CUT.find(visible)?.range?.first else null
                if (cut != null && el.childNodeSize() == 1 && el.childNode(0) is TextNode) {
                    val url = visible.substring(0, cut)
                    out += Inline.Text(url, style.copy(link = url))
                    out += Inline.Text(visible.substring(cut), style)
                } else {
                    inlineChildren(el, style.copy(link = link), ctx, out)
                }
            }
            "strong", "b" -> inlineChildren(el, style.copy(bold = true), ctx, out)
            "em", "i" -> inlineChildren(el, style.copy(italic = true), ctx, out)
            "del", "s", "strike" -> inlineChildren(el, style.copy(strike = true), ctx, out)
            "code", "tt", "kbd", "samp" -> inlineChildren(el, style.copy(code = true), ctx, out)
            "sub" -> inlineChildren(el, style.copy(sub = true), ctx, out)
            "input" -> if (el.attr("type").equals("checkbox", ignoreCase = true)) {
                out += Inline.Text(if (el.hasAttr("checked")) "☑ " else "☐ ", style)
            }
            "g-emoji" -> out += Inline.Text(el.text(), style)
            else -> {
                if (tag.isEmpty()) return
                inlineChildren(el, style, ctx, out)
            }
        }
    }

    private fun table(el: Element, ctx: Ctx): HtmlBlock.Table {
        val all = el.select("tr")
        val headRow = el.select("thead tr").firstOrNull()
            ?: all.firstOrNull()?.takeIf { r -> r.children().any { it.normalName() == "th" } }
        val body = all.filter { it != headRow }
        val aligns = (headRow ?: body.firstOrNull())?.let { cells(it).map(::alignOf) }.orEmpty()
        val header = headRow?.let { cells(it).map { c -> trimInlines(collect(c, InlineStyle(), ctx)) } }.orEmpty()
        val rows = body.map { row -> cells(row).map { c -> trimInlines(collect(c, InlineStyle(), ctx)) } }
        return HtmlBlock.Table(aligns, header, rows)
    }

    private fun cells(row: Element): List<Element> =
        row.children().filter { it.normalName() == "th" || it.normalName() == "td" }

    private fun alignOf(cell: Element): TableAlign {
        val a = cell.attr("align").lowercase()
        val s = cell.attr("style").lowercase()
        return when {
            a == "center" || s.contains("center") -> TableAlign.Center
            a == "right" || s.contains("right") -> TableAlign.Right
            a == "left" || s.contains("left") -> TableAlign.Left
            else -> TableAlign.None
        }
    }

    private fun languageOf(pre: Element): String? {
        pre.attr("lang").takeIf { it.isNotBlank() }?.let { return it }
        val cls = pre.selectFirst("code")?.className() ?: return null
        return LANG.find(cls)?.groupValues?.get(1)
    }

    private fun anchorName(id: String) = id.removePrefix("user-content-")

    private fun collapse(s: String) = s.replace(WS, " ")
}

/** 去掉首尾空白（含换行产生的空格和多余的换行符），空文字段一并去掉。 */
internal fun trimInlines(src: List<Inline>): List<Inline> {
    val list = src.toMutableList()
    fun trimEdge(first: Boolean) {
        while (list.isNotEmpty()) {
            val i = if (first) 0 else list.lastIndex
            when (val item = list[i]) {
                is Inline.Break -> list.removeAt(i)
                is Inline.Text -> {
                    val t = if (first) item.text.trimStart() else item.text.trimEnd()
                    if (t.isEmpty()) {
                        list.removeAt(i)
                    } else {
                        list[i] = item.copy(text = t)
                        return
                    }
                }
                else -> return
            }
        }
    }
    trimEdge(first = true)
    trimEdge(first = false)
    return list.filterNot { it is Inline.Text && it.text.isEmpty() }
}
