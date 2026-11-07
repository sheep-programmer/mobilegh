package com.mobilegh.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.i18n.tr
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.theme.Gh
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GitHub 渲染的 HTML（README、Issue / PR 正文与评论、Wiki、文件预览、编辑器预览）。
 *
 * 常规内容用原生 Compose 渲染：没有网页排版，也就没有 WebView 滚动时的空白与闪烁。
 * 数学公式、Mermaid 与地理 / 三维图表需要浏览器里的脚本，这类内容仍交给 [WebHtmlView]。
 */
@Composable
fun HtmlView(
    html: String,
    modifier: Modifier = Modifier,
    baseUrl: String = "https://github.com/",
    onAnchor: ((Int) -> Unit)? = null,
    /** 非空时滚动到该标题（目录点击）；结果经 onAnchor 交给外层列表定位 */
    anchorRequest: HtmlAnchorRequest? = null,
    /** 原生渲染时内容按自身高度排列，参数保留给 WebView 路径使用 */
    fullHeight: Boolean = false,
) {
    val parsed = remember(html, baseUrl) { HtmlParser.parse(html, baseUrl) }
    if (parsed.needsWeb) {
        WebHtmlView(html, modifier, baseUrl, onAnchor, anchorRequest, fullHeight)
    } else {
        NativeHtml(parsed.blocks, modifier, onAnchor, anchorRequest)
    }
}

/**
 * 原生渲染时各块共用的锚点信息：标题与根节点在窗口中的位置，以及跳转时交给列表的回调。
 * 位置在布局时写入，跳转时读取，不参与重组。
 */
internal class HtmlScroll(private val density: Float) {
    var rootY: Float = 0f
    val headingY = HashMap<String, Float>()
    var onAnchor: ((Int) -> Unit)? = null

    /** 跳到标题：位置换算成 dp，与网页版 Bridge.a 上报的单位一致 */
    fun jumpTo(anchor: String) {
        val y = headingY[anchor] ?: return
        onAnchor?.invoke(((y - rootY) / density).roundToInt())
    }
}

private val LocalHtmlScroll = staticCompositionLocalOf<HtmlScroll?> { null }

@Composable
internal fun NativeHtml(
    blocks: List<HtmlBlock>,
    modifier: Modifier,
    onAnchor: ((Int) -> Unit)?,
    anchorRequest: HtmlAnchorRequest?,
) {
    val density = LocalDensity.current.density
    val scroll = remember(density) { HtmlScroll(density) }
    scroll.onAnchor = onAnchor
    LaunchedEffect(anchorRequest) { anchorRequest?.let { scroll.jumpTo(it.anchor) } }
    CompositionLocalProvider(LocalHtmlScroll provides scroll) {
        SelectionContainer {
            Column(
                // 与网页版 markdown-body 的左右内边距一致（约 16dp）
                modifier.fillMaxWidth().padding(horizontal = 16.dp).onGloballyPositioned { scroll.rootY = it.positionInRoot().y },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                blocks.forEach { RenderBlock(it, muted = false) }
            }
        }
    }
}

@Composable
private fun RenderBlock(block: HtmlBlock, muted: Boolean) {
    val g = Gh.c
    val color = if (muted) g.fgMuted else g.fg
    when (block) {
        is HtmlBlock.Heading -> HeadingBlock(block)
        is HtmlBlock.Paragraph -> HtmlText(block.inlines, color = color, fontSize = 15.sp, lineHeight = 22.sp)
        is HtmlBlock.ListBlock -> ListView(block, muted)
        is HtmlBlock.Code -> CodeBlock(block.text, block.language)
        is HtmlBlock.Quote -> QuoteBlock(block)
        is HtmlBlock.Table -> TableBlock(block)
        is HtmlBlock.Image -> BlockImage(block)
        is HtmlBlock.Video -> VideoTile(block.src)
        is HtmlBlock.Details -> DetailsBlock(block)
        HtmlBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(g.border))
    }
}

@Composable
private fun HeadingBlock(block: HtmlBlock.Heading) {
    val g = Gh.c
    val scroll = LocalHtmlScroll.current
    val size = when (block.level) {
        1 -> 24.sp
        2 -> 20.sp
        3 -> 17.sp
        else -> 15.sp
    }
    val anchors = block.anchors
    Column(
        Modifier.padding(top = 8.dp).onGloballyPositioned { c ->
            if (scroll != null) anchors.forEach { scroll.headingY[it] = c.positionInRoot().y }
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HtmlText(block.inlines, color = g.fg, fontSize = size, fontWeight = FontWeight.SemiBold)
        if (block.level <= 2) Box(Modifier.fillMaxWidth().height(1.dp).background(g.borderMuted))
    }
}

@Composable
private fun ListView(block: HtmlBlock.ListBlock, muted: Boolean) {
    val g = Gh.c
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.items.forEachIndexed { i, item ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    if (block.ordered) "${block.start + i}." else "•",
                    Modifier.width(28.dp),
                    color = if (muted) g.fgMuted else g.fg,
                    fontSize = 15.sp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.forEach { RenderBlock(it, muted) }
                }
            }
        }
    }
}

@Composable
private fun QuoteBlock(block: HtmlBlock.Quote) {
    val g = Gh.c
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(g.border))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.blocks.forEach { RenderBlock(it, muted = true) }
        }
    }
}

@Composable
private fun CodeBlock(code: String, language: String?) {
    val g = Gh.c
    val ctx = rememberCtx()
    var zoom by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, g.borderMuted, shape).background(g.canvasSubtle)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(language.orEmpty(), Modifier.weight(1f), color = g.fgMuted, fontSize = 11.sp, maxLines = 1)
            Text(
                tr("复制"),
                Modifier.clickable { ctx.copy(code) }.padding(8.dp),
                color = g.fgMuted,
                fontSize = 12.sp,
            )
            Text(
                tr("放大"),
                Modifier.clickable { zoom = code }.padding(8.dp),
                color = g.fgMuted,
                fontSize = 12.sp,
            )
        }
        Text(
            code,
            Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
            color = g.fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            softWrap = false,
        )
    }
    zoom?.let { text -> CodeZoomDialog(text) { zoom = null } }
}

@Composable
private fun TableBlock(block: HtmlBlock.Table) {
    val g = Gh.c
    val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0, block.aligns.size)
    if (columns == 0) return
    val shape = RoundedCornerShape(6.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val colWidth = (maxWidth / columns).coerceAtLeast(120.dp)
        Column(
            Modifier.horizontalScroll(rememberScrollState()).clip(shape).border(1.dp, g.borderMuted, shape),
        ) {
            if (block.header.isNotEmpty()) {
                TableRow(block.header, columns, colWidth, block.aligns, header = true)
            }
            block.rows.forEach { TableRow(it, columns, colWidth, block.aligns, header = false) }
        }
    }
}

@Composable
private fun TableRow(cells: List<List<Inline>>, columns: Int, colWidth: Dp, aligns: List<TableAlign>, header: Boolean) {
    val g = Gh.c
    Row(Modifier.height(IntrinsicSize.Min).background(if (header) g.canvasSubtle else Color.Transparent)) {
        for (c in 0 until columns) {
            val align = aligns.getOrElse(c) { TableAlign.None }
            Box(Modifier.width(colWidth).border(0.5.dp, g.borderMuted).padding(horizontal = 8.dp, vertical = 6.dp)) {
                HtmlText(
                    cells.getOrElse(c) { emptyList() },
                    color = g.fg,
                    fontSize = 14.sp,
                    fontWeight = if (header) FontWeight.SemiBold else null,
                    textAlign = when (align) {
                        TableAlign.Left -> TextAlign.Start
                        TableAlign.Center -> TextAlign.Center
                        TableAlign.Right -> TextAlign.End
                        TableAlign.None -> null
                    },
                )
            }
        }
    }
}

@Composable
private fun DetailsBlock(block: HtmlBlock.Details) {
    val g = Gh.c
    var open by remember(block) { mutableStateOf(block.open) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾ " else "▸ ", color = g.fgMuted, fontSize = 14.sp)
            val title = block.summary.ifEmpty { listOf(Inline.Text(tr("详情"))) }
            HtmlText(title, Modifier.weight(1f), color = g.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.blocks.forEach { RenderBlock(it, muted = false) }
            }
        }
    }
}

/** 块级图片：缓存里有就直接显示；否则先占位（按宽高属性保留比例），加载完成后替换。 */
@Composable
private fun BlockImage(block: HtmlBlock.Image) {
    val g = Gh.c
    val src = block.src
    var bmp by remember(src) { mutableStateOf(Images.cached(src, 1024)) }
    LaunchedEffect(src) {
        if (bmp == null) bmp = Images.load(src, 1024)
    }
    val w = block.width
    val h = block.height
    val ratio = if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else null
    val alt = block.alt
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { ImageViewer.open(src, alt) }
    val loaded = bmp
    if (loaded != null) {
        Image(loaded, alt.ifBlank { null }, base, contentScale = ContentScale.FillWidth)
    } else {
        val sized = if (ratio != null) base.aspectRatio(ratio) else base.height(160.dp)
        Box(sized.background(g.neutralMuted))
    }
}

@Composable
private fun VideoTile(src: String) {
    val g = Gh.c
    Box(
        Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(6.dp)).background(g.neutralMuted)
            .clickable { VideoViewer.open(src) },
        contentAlignment = Alignment.Center,
    ) {
        Text("▶ " + tr("视频"), color = g.fg, fontSize = 15.sp)
    }
}

/** 行内图片（徽章等）：尺寸来自 HTML 属性，不额外占位。 */
@Composable
private fun InlineImage(src: String, alt: String, width: Int, height: Int) {
    NetImage(
        src,
        Modifier.size(width.dp, height.dp),
        contentScale = ContentScale.Fit,
        placeholder = false,
        contentDescription = alt.ifBlank { null },
    )
}

private class BuiltText(val text: AnnotatedString, val inline: Map<String, InlineTextContent>)

private fun buildInlineText(inlines: List<Inline>, accent: Color, codeBg: Color): BuiltText {
    val content = HashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        inlines.forEachIndexed { index, item ->
            when (item) {
                is Inline.Text -> {
                    val s = item.style
                    val link = s.link
                    if (link != null) pushStringAnnotation(MD_LINK_TAG, link)
                    pushStyle(
                        SpanStyle(
                            fontWeight = if (s.bold) FontWeight.Bold else null,
                            fontStyle = if (s.italic) FontStyle.Italic else null,
                            textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                            fontFamily = if (s.code) FontFamily.Monospace else null,
                            background = if (s.code) codeBg else Color.Unspecified,
                            color = if (link != null) accent else Color.Unspecified,
                            fontSize = if (s.sub) 11.sp else TextUnit.Unspecified,
                            baselineShift = if (s.sub) BaselineShift.Subscript else null,
                        ),
                    )
                    append(item.text)
                    pop()
                    if (link != null) pop()
                }
                is Inline.Image -> {
                    val id = "img$index"
                    val h = item.height ?: 20
                    val w = item.width ?: h
                    content[id] = InlineTextContent(Placeholder(w.sp, h.sp, PlaceholderVerticalAlign.TextCenter)) { _ ->
                        InlineImage(item.src, item.alt, w, h)
                    }
                    val link = item.link
                    if (link != null) pushStringAnnotation(MD_LINK_TAG, link)
                    appendInlineContent(id, item.alt.ifBlank { "image" })
                    if (link != null) pop()
                }
                Inline.Break -> append("\n")
            }
        }
    }
    return BuiltText(text, content)
}

/**
 * 带链接的文字：短按打开链接，长按弹出链接菜单（复制 / 分享 / 浏览器打开）。
 * 长按的其余位置交给外层的文字选择。页内锚点（#标题）滚动到对应标题。
 */
@Composable
private fun HtmlText(
    inlines: List<Inline>,
    modifier: Modifier = Modifier,
    color: Color,
    fontSize: TextUnit = 15.sp,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
) {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val scroll = LocalHtmlScroll.current
    val built = remember(inlines, g.dark) {
        buildInlineText(inlines, g.accent, if (g.dark) Color(0x33656C76) else Color(0x1F818B98))
    }
    val text = built.text
    val layoutRef = remember { AtomicReference<TextLayoutResult?>(null) }
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val onLink: (String) -> Unit = { url ->
        if (url.startsWith("#")) scroll?.jumpTo(url.removePrefix("#")) else openHtmlLink(ctx, nav, url)
    }
    Text(
        text,
        modifier.pointerInput(text, nav, ctx, scroll) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val offset = layoutRef.get()?.getOffsetForPosition(down.position)
                val link = offset?.let { o -> text.getStringAnnotations(MD_LINK_TAG, o, o).firstOrNull()?.item }
                var released = false
                val ended = withTimeoutOrNull(longPressMs) {
                    released = waitForUpOrCancellation() != null
                    true
                }
                if (ended != null) {
                    // 在超时之前抬起：是点按，打开链接
                    if (released && link != null) onLink(link)
                    return@awaitEachGesture
                }
                // 超时仍按着：是长按，落在链接上才弹出菜单；其余位置保持给文字选择
                if (link != null) {
                    LinkMenu.show(link)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        },
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        textAlign = textAlign,
        inlineContent = built.inline,
        onTextLayout = { layoutRef.set(it) },
    )
}

/** 打开 HTML 里的链接：应用内页面、浏览器，或系统应用（邮件、拨号）。 */
internal fun openHtmlLink(ctx: Context, nav: Navigator, url: String) {
    when (val action = webLinkAction(url)) {
        is WebLinkAction.Native -> nav.pushLink(action.screen)
        WebLinkAction.Browser -> ctx.openBrowser(url)
        WebLinkAction.System -> openSystemIntent(ctx, url)
        WebLinkAction.Unsupported -> ctx.toast(tr("无法打开此链接"))
    }
}

/** mailto 交给邮件应用（ACTION_SENDTO），tel 交给拨号盘（ACTION_DIAL，不需要通话权限）；没有可处理的应用时提示。 */
private fun openSystemIntent(ctx: Context, url: String) {
    val action = if (externalLinkKind(url) == ExternalLinkKind.Phone) Intent.ACTION_DIAL else Intent.ACTION_SENDTO
    val intent = Intent(action, Uri.parse(url))
    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        ctx.toast(tr("无法打开此链接"))
    }
}
