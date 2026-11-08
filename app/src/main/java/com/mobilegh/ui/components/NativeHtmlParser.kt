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
    data class Image(val src: String, val alt: String, val width: Int?, val height: Int?, val link: String? = null) : HtmlBlock
    /** 一排并排的图片：徽章行、并排的截图。按图片自身大小流式排列，放不下自动换行 */
    data class ImageRow(val images: List<Inline.Image>) : HtmlBlock
    data class Video(val src: String) : HtmlBlock
    data class Details(val open: Boolean, val summary: List<Inline>, val blocks: List<HtmlBlock>) : HtmlBlock
    /** 必须在浏览器里运行脚本才能显示的片段（Mermaid 等图表、数学公式）：只有这一块交给 WebView */
    data class Web(val html: String, val baseUrl: String) : HtmlBlock
    data object Rule : HtmlBlock
}

/** 行内内容：带样式的文字、行内图片（徽章等）、换行。 */
internal sealed interface Inline {
    data class Text(val text: String, val style: InlineStyle = InlineStyle()) : Inline
    data class Image(val src: String, val alt: String, val width: Int?, val height: Int?, val link: String?, val badge: Boolean = false) : Inline
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

/** 解析结果：块级内容。图表与公式在其中以 [HtmlBlock.Web] 出现，其余都原生渲染。 */
internal class ParsedHtml(val blocks: List<HtmlBlock>)

/** GitHub 渲染的 HTML 解析成块级模型，供原生 Compose 渲染使用。不依赖 Android，可在单元测试中直接运行。 */
internal object HtmlParser {
    private const val WEB_ONLY =
        "math-renderer, section[data-type], [class*=mermaid], [lang=mermaid], [data-type=geojson], [data-type=topojson], [data-type=stl]"
    /** 含有图表或公式时整块交给 WebView 的元素：拆开会破坏排版（段落里的行内公式、表格、标题） */
    private val WEB_WHOLE = setOf("p", "pre", "table", "h1", "h2", "h3", "h4", "h5", "h6", "dl")
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
        return ParsedHtml(blocks(body, Ctx(baseUrl), emptySet()))
    }

    /** 解析结果缓存：同一段 HTML 只解析一次。列表滑动时直接取结果，不在主线程上重复解析。 */
    private val cache = object : LinkedHashMap<String, ParsedHtml>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedHtml>?) = size > CACHE_ENTRIES
    }
    private const val CACHE_ENTRIES = 600

    private fun cacheKey(html: String, baseUrl: String) = "${html.length}:${html.hashCode()}:$baseUrl"

    /** 有缓存直接返回，没有则解析并缓存。可在任意线程调用。 */
    fun parseCached(html: String, baseUrl: String): ParsedHtml {
        val key = cacheKey(html, baseUrl)
        synchronized(cache) { cache[key] }?.let { return it }
        val parsed = parse(html, baseUrl)
        synchronized(cache) { cache[key] = parsed }
        return parsed
    }

    /** 预解析只用一个后台线程：滑动时不和界面线程抢 CPU */
    private val prewarmDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    /** 预先解析：评论加载完成后在后台逐条解析好，滑到时就不用再解析。每条之间让出线程，随时可被取消 */
    suspend fun prewarm(htmls: List<String>, baseUrl: String) = kotlinx.coroutines.withContext(prewarmDispatcher) {
        for (html in htmls) {
            kotlinx.coroutines.yield()
            if (html.isNotBlank()) parseCached(html, baseUrl)
        }
    }

    /** 相对地址按页面地址解析；页内锚点原样保留，交给渲染层跳转。 */
    private class Ctx(val baseUrl: String) {
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
                        tag in INLINE && child.selectFirst(WEB_ONLY) == null -> inlineElement(child, InlineStyle(), ctx, run)
                        webWhole(child) -> {
                            flush()
                            out += HtmlBlock.Web(child.outerHtml(), ctx.baseUrl)
                        }
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

    /**
     * 是否把 [el] 整块交给 WebView：它本身就是图表 / 公式，或者里面有图表 / 公式而又不能拆开
     * （段落、表格、标题，或者只含行内内容的容器）。含有其他块级内容的容器继续往下拆，只把图表那一块交出去。
     */
    private fun webWhole(el: Element): Boolean {
        if (el.`is`(WEB_ONLY)) return true
        if (el.selectFirst(WEB_ONLY) == null) return false
        val tag = el.normalName()
        if (tag in WEB_WHOLE || tag in INLINE) return true
        return el.children().none { c -> c.normalName() !in INLINE && c.normalName() !in SKIP }
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
        // 整段只有图片（徽章行、并排截图）：一排图片并排显示，而不是每张图占一整行
        val images = inl.filterIsInstance<Inline.Image>()
        val onlyImages = inl.all { it is Inline.Image || it is Inline.Break || (it is Inline.Text && it.text.isBlank()) }
        if (onlyImages && (images.size > 1 || images.single().badge)) return listOf(HtmlBlock.ImageRow(images))
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
                out += HtmlBlock.Image(item.src, item.alt, item.width, item.height, item.link)
            } else {
                seg += item
            }
        }
        emit()
        return out
    }

    /** 徽章：shields.io、各类 badge.svg、状态图标。通常是 SVG，高约 20px，没有写宽高属性 */
    internal fun isBadgeUrl(url: String): Boolean {
        val u = url.lowercase().substringBefore('#')
        return "shields.io" in u || "badge" in u || "/badges/" in u || u.substringBefore('?').endsWith(".svg")
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
                    // camo 代理地址把原始地址编码进了路径；原始地址在 data-canonical-src 里，判断是不是徽章用它
                    val original = el.attr("data-canonical-src").ifBlank { src }
                    out += Inline.Image(src, el.attr("alt"), el.attr("width").toIntOrNull(), el.attr("height").toIntOrNull(), style.link, isBadgeUrl(original))
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
