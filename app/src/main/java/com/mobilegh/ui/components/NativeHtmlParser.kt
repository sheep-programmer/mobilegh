package com.mobilegh.ui.components

import com.mobilegh.data.AppLog
import com.mobilegh.data.ImageContent
import com.mobilegh.i18n.trf
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
    data class Image(val src: String, val alt: String, val width: Int?, val height: Int?, val link: String? = null, val viewSrc: String? = null) : HtmlBlock
    /** 一排并排的图片：徽章行、并排的截图。按图片自身大小流式排列，放不下自动换行 */
    data class ImageRow(val images: List<Inline.Image>) : HtmlBlock
    data class Video(val src: String) : HtmlBlock
    /** YouTube 视频（链接封面或 iframe）：显示封面，点播放键后在应用内播放。thumb 为封面地址，没有时用 YouTube 标准封面 */
    data class YouTube(val id: String, val thumb: String?, val title: String) : HtmlBlock
    data class Details(val open: Boolean, val summary: List<Inline>, val blocks: List<HtmlBlock>) : HtmlBlock
    /** 必须在浏览器里运行脚本才能显示的片段（Mermaid 等图表、数学公式）：只有这一块交给 WebView */
    data class Web(val html: String, val baseUrl: String) : HtmlBlock
    /** 居中 / 靠右的容器：`<div align="center">`、`<center>`、`style="text-align:center"`，里面的文字和图片都按它对齐 */
    data class Aligned(val align: BlockAlign, val blocks: List<HtmlBlock>) : HtmlBlock
    data object Rule : HtmlBlock
}

internal enum class BlockAlign { Center, End }

/** 行内内容：带样式的文字、行内图片（徽章等）、换行。 */
internal sealed interface Inline {
    data class Text(val text: String, val style: InlineStyle = InlineStyle()) : Inline
    data class Image(val src: String, val alt: String, val width: Int?, val height: Int?, val link: String?, val badge: Boolean = false, val viewSrc: String? = null) : Inline
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
        // 解析在界面组合时调用：内容异常（嵌套过深、结构畸形）若直接抛出，会让整个 Issue / Release 页面崩溃。
        // 失败时退回纯文本显示，并把原因写入诊断日志。
        val parsed = try {
            parse(html, baseUrl)
        } catch (e: Throwable) {
            AppLog.error("render", trf("HTML 解析失败，已退回纯文本：{0}", e.javaClass.simpleName), e)
            plainFallback(html)
        }
        synchronized(cache) { cache[key] = parsed }
        return parsed
    }

    /** 解析失败时的兜底：只保留文字，不做任何结构解析，长度也有上限 */
    private fun plainFallback(html: String): ParsedHtml {
        val text = runCatching { Jsoup.parse(html).text() }
            .getOrElse { html.replace(Regex("<[^>]*>"), " ") }
            .take(20_000)
        return ParsedHtml(if (text.isBlank()) emptyList() else listOf(HtmlBlock.Paragraph(listOf(Inline.Text(text)))))
    }

    /** 预解析只用一个后台线程：滑动时不和界面线程抢 CPU */
    private val prewarmDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    /** 提取前排关键图片地址，供后台预先拉取到本地缓存 */
    fun extractImageUrls(blocks: List<HtmlBlock>, limit: Int = 16): List<String> {
        val urls = LinkedHashSet<String>()
        fun scanInlines(inlines: List<Inline>) {
            for (i in inlines) {
                if (urls.size >= limit) return
                if (i is Inline.Image && i.src.isNotBlank() && !i.src.startsWith("data:")) {
                    urls.add(i.src)
                }
            }
        }
        fun scanBlocks(list: List<HtmlBlock>) {
            for (b in list) {
                if (urls.size >= limit) return
                when (b) {
                    is HtmlBlock.Image -> if (b.src.isNotBlank() && !b.src.startsWith("data:")) urls.add(b.src)
                    is HtmlBlock.ImageRow -> b.images.forEach { if (urls.size < limit && it.src.isNotBlank() && !it.src.startsWith("data:")) urls.add(it.src) }
                    is HtmlBlock.Paragraph -> scanInlines(b.inlines)
                    is HtmlBlock.Heading -> scanInlines(b.inlines)
                    is HtmlBlock.Table -> {
                        b.header.forEach { scanInlines(it) }
                        b.rows.forEach { row -> row.forEach { scanInlines(it) } }
                    }
                    is HtmlBlock.Aligned -> scanBlocks(b.blocks)
                    is HtmlBlock.Details -> scanBlocks(b.blocks)
                    is HtmlBlock.ListBlock -> b.items.forEach { scanBlocks(it) }
                    is HtmlBlock.Quote -> scanBlocks(b.blocks)
                    else -> Unit
                }
            }
        }
        scanBlocks(blocks)
        return urls.toList()
    }

    /** 预先解析：文档/评论加载完成后在后台逐条解析好，并静默预下载前排图片，滑到时秒开 */
    suspend fun prewarm(htmls: List<String>, baseUrl: String) = kotlinx.coroutines.withContext(prewarmDispatcher) {
        for (html in htmls) {
            kotlinx.coroutines.yield()
            if (html.isNotBlank()) {
                val parsed = parseCached(html, baseUrl)
                val images = extractImageUrls(parsed.blocks)
                for (imgUrl in images) {
                    com.mobilegh.data.ImageFetch.prefetch(imgUrl)
                }
            }
        }
    }

    /** 相对地址按页面地址解析；页内锚点原样保留，交给渲染层跳转。 */
    private class Ctx(val baseUrl: String) {
        fun url(raw: String): String = safeResolve(baseUrl, raw)
    }

    /** 安全解析相对 URL，对空格、特殊字符及非 ASCII/CJK 字符做分段编码，避免 URI.create 抛 IllegalArgumentException */
    internal fun safeResolve(baseUrl: String, rel: String): String {
        val trimmed = rel.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("data:")) return trimmed
        if (trimmed.contains("://")) return trimmed
        val baseUri = runCatching { URI(baseUrl) }.getOrNull()
        val queryPart = if (trimmed.contains('?')) "?" + trimmed.substringAfter('?') else ""
        val pathPart = trimmed.substringBefore('?')
        val encodedPath = pathPart.split('/').joinToString("/") { seg ->
            runCatching {
                val decoded = java.net.URLDecoder.decode(seg, "UTF-8")
                java.net.URLEncoder.encode(decoded, "UTF-8")
                    .replace("+", "%20")
                    .replace("%2F", "/")
            }.getOrDefault(seg)
        }
        val encodedRel = encodedPath + queryPart
        val resolved = runCatching { baseUri?.resolve(encodedRel)?.toString() }.getOrNull()
        if (resolved != null) return resolved

        return if (trimmed.startsWith("/")) {
            if (baseUrl.contains("://")) {
                val scheme = baseUrl.substringBefore("://")
                val host = baseUrl.substringAfter("://").substringBefore('/')
                "$scheme://$host$trimmed"
            } else trimmed
        } else {
            val dir = if (baseUrl.endsWith("/")) baseUrl else baseUrl.substringBeforeLast('/') + "/"
            dir + trimmed
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

    private val TEXT_ALIGN = Regex("""text-align\s*:\s*(center|right)""", RegexOption.IGNORE_CASE)

    /** 元素自身声明的对齐：align 属性、style 里的 text-align，或 `<center>` 标签 */
    internal fun blockAlignOf(el: Element): BlockAlign? {
        if (el.normalName() == "center") return BlockAlign.Center
        val v = el.attr("align").trim().lowercase().ifEmpty { TEXT_ALIGN.find(el.attr("style"))?.groupValues?.get(1)?.lowercase().orEmpty() }
        return when (v) {
            "center" -> BlockAlign.Center
            "right" -> BlockAlign.End
            else -> null
        }
    }

    private fun blockElement(el: Element, ctx: Ctx, out: MutableList<HtmlBlock>) {
        val align = blockAlignOf(el)
        if (align == null || el.normalName() in setOf("td", "th", "table")) return blockElementPlain(el, ctx, out)
        val inner = mutableListOf<HtmlBlock>()
        blockElementPlain(el, ctx, inner)
        if (inner.isNotEmpty()) out += HtmlBlock.Aligned(align, inner)
    }

    private fun blockElementPlain(el: Element, ctx: Ctx, out: MutableList<HtmlBlock>) {
        val tag = el.normalName()
        when {
            tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> {
                // 同时登记两种写法：网页里的目录链接可能指向 "user-content-特性"，也可能只写 "特性"。
                // GitHub 现在的标题结构是 <div class="markdown-heading"><h2>…</h2><a id="user-content-…">，
                // 锚点是 h2 的兄弟节点而不是子节点，所以要把父级和紧随其后的 anchor 一起算进来。
                val rawText = el.text().trim()
                val ids = buildList {
                    add(el.id())
                    addAll(el.select("[id]").map { it.id() })
                    el.parent()?.takeIf { it.className().contains("markdown-heading") }?.let { p ->
                        add(p.id())
                        addAll(p.select("a[id]").map { it.id() })
                    }
                    el.nextElementSibling()?.takeIf { it.normalName() == "a" }?.let { add(it.id()) }
                    if (rawText.isNotBlank()) {
                        add(rawText)
                        add(headingSlug(rawText))
                    }
                }
                val anchors = ids.flatMap { listOf(it, anchorName(it)) }.filter { it.isNotBlank() }.distinct()
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
            // GitHub 把标题包在 <div class="markdown-heading"><h2>…</h2><a class="anchor">…</a></div> 里，
            // 只渲染里面的标题，不把锚点链接（svg 图标）当成内容
            tag == "div" && el.className().contains("markdown-heading") -> {
                el.children().firstOrNull { it.normalName().length == 2 && it.normalName()[0] == 'h' }
                    ?.let { blockElementPlain(it, ctx, out) }
            }
            tag == "hr" -> out += HtmlBlock.Rule
            tag == "video" -> {
                val src = el.attr("src").ifBlank { el.selectFirst("source")?.attr("src").orEmpty() }
                    .ifBlank { el.attr("data-canonical-src") }
                if (src.isNotBlank()) out += HtmlBlock.Video(ctx.url(src))
            }
            // 内嵌的 YouTube 播放器（iframe）：同样显示封面，点按在应用内播放
            tag == "iframe" -> youtubeId(el.attr("src"))?.let { out += HtmlBlock.YouTube(it, null, el.attr("title")) }
            else -> out += blocks(el, ctx, emptySet())
        }
    }

    private fun flow(inl: List<Inline>): List<HtmlBlock> {
        val images = inl.filterIsInstance<Inline.Image>()
        if (images.isEmpty()) return listOf(HtmlBlock.Paragraph(inl))
        // 整段只有图片（徽章行、赞助商 logo、并排截图）：一排图片并排流式显示。
        // 这一步必须先于「行内图片」判断：带 height=36 的 logo 若塞进文字行，行高固定，会上下重叠
        val onlyImages = inl.all { it is Inline.Image || it is Inline.Break || (it is Inline.Text && it.text.isBlank()) }
        if (onlyImages && (images.size > 1 || images.single().badge || isInlineImage(images.single()))) {
            return listOf(HtmlBlock.ImageRow(images))
        }
        if (images.all { isInlineImage(it) }) return listOf(HtmlBlock.Paragraph(inl))
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
                // 链接到 YouTube 的封面（或封面本身来自 YouTube）是视频，不当成普通图片：显示封面并可在应用内播放
                val yt = youtubeId(item.link) ?: youtubeId(item.src)
                out += if (yt != null) HtmlBlock.YouTube(yt, item.src, item.alt)
                else HtmlBlock.Image(item.src, item.alt, item.width, item.height, item.link, item.viewSrc)
            } else {
                seg += item
            }
        }
        emit()
        return out
    }

    private val IMAGE_EXT = Regex("""\.(png|jpe?g|gif|webp|bmp|avif|svg)$""", RegexOption.IGNORE_CASE)

    internal fun isImageFileUrl(url: String): Boolean =
        url.startsWith("http", true) && IMAGE_EXT.containsMatchIn(url.substringBefore('#').substringBefore('?'))

    /** 仓库文件页地址（/blob/）换成能直接取图片的 /raw/ 地址；其他地址不变 */
    internal fun rawImageUrl(url: String): String =
        if (url.contains("github.com/") && url.contains("/blob/")) url.replaceFirst("/blob/", "/raw/") else url

    /** 徽章：shields.io、各类 badge.svg、状态图标。通常是 SVG，高约 20px，没有写宽高属性 */
    internal fun isBadgeUrl(url: String): Boolean {
        val u = url.lowercase().substringBefore('#')
        return "shields.io" in u || "badge" in u || "/badges/" in u || u.substringBefore('?').endsWith(".svg")
    }

    /** 高度不超过一行字（24px）的图片当作文字中的徽章 / 图标（行内），更大的图片单独成块显示。 */
    internal fun isInlineImage(i: Inline.Image) = (i.height ?: 0) in 1..24

    private val YOUTUBE_ID = Regex("""[A-Za-z0-9_-]{11}""")

    /**
     * 从 YouTube 相关地址取出视频编号：youtu.be/ID、youtube.com/watch?v=ID、/shorts/ID、/embed/ID，
     * 以及封面图 img.youtube.com/vi/ID/...。不是视频的地址（频道、搜索等）返回 null。
     */
    internal fun youtubeId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val (rawHost, path, query) = ImageContent.parseUrl(url.trim()) ?: return null
        val host = rawHost.removePrefix("www.").removePrefix("m.")
        val segs = path.split('/').filter { it.isNotEmpty() }
        val id = when (host) {
            "youtu.be" -> segs.firstOrNull()
            "img.youtube.com", "i.ytimg.com" -> if (segs.firstOrNull() in setOf("vi", "vi_webp")) segs.getOrNull(1) else null
            "youtube.com", "youtube-nocookie.com" -> when (segs.firstOrNull()) {
                "shorts", "embed", "live", "v" -> segs.getOrNull(1)
                "watch" -> query?.split('&')?.map { it.substringBefore('#') }?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                else -> null
            }
            else -> null
        }
        return id?.takeIf { YOUTUBE_ID.matches(it) }
    }

    internal fun youtubeThumb(id: String) = "https://img.youtube.com/vi/$id/hqdefault.jpg"

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
                    // 外层链接指向的就是图片文件（点缩略图看大图）：直接进看图器，不再先跳到仓库的文件页
                    val imageLink = style.link?.takeIf { isImageFileUrl(it) }
                    out += Inline.Image(
                        src, el.attr("alt"), el.attr("width").toIntOrNull(), el.attr("height").toIntOrNull(),
                        if (imageLink != null) null else style.link, isBadgeUrl(original),
                        viewSrc = imageLink?.let { rawImageUrl(it) }?.takeIf { it != rawImageUrl(src) },
                    )
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

/** 把标题文字转换为 GitHub 锚点 slug（全小写、去除非字母数字标点、空格转连字符） */
internal fun headingSlug(text: String): String {
    val lower = text.lowercase(java.util.Locale.ROOT)
    val clean = StringBuilder()
    for (i in 0 until lower.length) {
        val c = lower[i]
        if (c.isLetterOrDigit() || c.isWhitespace() || c == '-') {
            clean.append(c)
        }
    }
    return clean.toString().trim().replace(Regex("""\s+"""), "-").replace(Regex("""-+"""), "-")
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
