package com.mobilegh.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import com.mobilegh.R
import com.mobilegh.i18n.trf
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableIntStateOf
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import com.mobilegh.data.VideoRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.widthIn
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
 * 全部用原生 Compose 渲染：没有网页排版，也就没有 WebView 滚动时的空白、闪烁与重影。
 * 只有数学公式、Mermaid 与地理 / 三维图表需要浏览器里的脚本，这几块各自嵌一个小的 [WebHtmlView]，
 * 其余正文不受影响。
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
    // 解析结果有缓存：长列表里的评论通常已在后台预先解析好，滑入屏幕时不再在主线程上解析
    val parsed = remember(html, baseUrl) { HtmlParser.parseCached(html, baseUrl) }
    NativeHtml(parsed.blocks, modifier, onAnchor, anchorRequest, baseUrl)
}

/**
 * 原生渲染时各块共用的锚点信息：标题与根节点在窗口中的位置，以及跳转时交给列表的回调。
 * 位置在布局时写入，跳转时读取，不参与重组。
 */
internal class HtmlScroll(private val density: Float) {
    var rootCoordinates: LayoutCoordinates? = null
    val headingCoordinates = HashMap<String, LayoutCoordinates>()
    var rootY: Float = 0f
    val headingY = HashMap<String, Float>()
    var onAnchor: ((Int) -> Unit)? = null

    /**
     * 跳到标题：优先使用 Compose 布局坐标系 localPositionOf 计算精确相对位移（不受已滚动距离影响），
     * 位置换算成 dp，与列表滚动 offset 对齐。
     * 链接里的锚点可能被百分号编码（中文标题写作 `#%E7%89%B9%E6%80%A7`），先解码再匹配。
     */
    fun jumpTo(anchor: String): Boolean {
        val decoded = percentDecode(anchor)
        val normalized = anchor.trim().removePrefix("#")
        val decodedNorm = decoded.trim().removePrefix("#")
        val clean = normalized.removePrefix("user-content-")
        val decodedClean = decodedNorm.removePrefix("user-content-")
        val slug = headingSlug(decodedClean)
        com.mobilegh.data.AppLog.info("anchor", "jumpTo anchor=$anchor decoded=$decoded norm=$normalized slug=$slug known=${headingY.keys.take(12)}")

        val candidates = listOf(
            normalized,
            decodedNorm,
            clean,
            decodedClean,
            slug,
            headingSlug(clean),
            "user-content-$clean",
            "user-content-$slug",
        ).filter { it.isNotBlank() }.distinct()

        val targetCoords = candidates.firstNotNullOfOrNull { headingCoordinates[it] }
            ?: headingCoordinates.entries.firstOrNull { entry ->
                val k = entry.key.removePrefix("user-content-")
                candidates.any { c -> entry.key.equals(c, ignoreCase = true) || k.equals(c, ignoreCase = true) }
            }?.value

        val root = rootCoordinates
        val dpOffset = if (targetCoords != null && targetCoords.isAttached && root != null && root.isAttached) {
            val localY = root.localPositionOf(targetCoords, Offset.Zero).y
            (localY / density).roundToInt()
        } else {
            val y = candidates.firstNotNullOfOrNull { headingY[it] }
                ?: headingY.entries.firstOrNull { entry ->
                    val k = entry.key.removePrefix("user-content-")
                    candidates.any { c -> entry.key.equals(c, ignoreCase = true) || k.equals(c, ignoreCase = true) }
                }?.value
                ?: return false
            ((y - rootY) / density).roundToInt()
        }
        com.mobilegh.data.AppLog.info("anchor", "jumpTo hit dpOffset=$dpOffset callback=${onAnchor != null}")
        if (onAnchor != null) {
            onAnchor?.invoke(dpOffset)
            return true
        }
        return false
    }
}

/**
 * 只解码 %XX（UTF-8 多字节连续解码），不把 '+' 当空格——锚点里的加号是标题的一部分。
 */
internal fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length) {
            val v = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (v != null) { bytes.write(v); i += 3; continue }
        }
        bytes.write(c.toString().toByteArray(Charsets.UTF_8)); i++
    }
    return runCatching { String(bytes.toByteArray(), Charsets.UTF_8) }.getOrDefault(s)
}

private val LocalHtmlScroll = staticCompositionLocalOf<HtmlScroll?> { null }
private val LocalBaseUrl = staticCompositionLocalOf<String?> { null }

@Composable
internal fun NativeHtml(
    blocks: List<HtmlBlock>,
    modifier: Modifier,
    onAnchor: ((Int) -> Unit)?,
    anchorRequest: HtmlAnchorRequest?,
    baseUrl: String? = null,
) {
    val density = LocalDensity.current.density
    val scroll = remember(density) { HtmlScroll(density) }
    scroll.onAnchor = onAnchor
    LaunchedEffect(anchorRequest) { anchorRequest?.let { scroll.jumpTo(it.anchor) } }
    CompositionLocalProvider(LocalHtmlScroll provides scroll, LocalBaseUrl provides baseUrl) {
        SelectionContainer {
            Column(
                // 与网页版评论正文的内边距一致：左右 16dp，上下留出空隙，不和头部、表情栏挤在一起
                modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                    .onGloballyPositioned {
                        scroll.rootCoordinates = it
                        scroll.rootY = it.positionInRoot().y
                    },
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
        is HtmlBlock.ImageRow -> ImageRowBlock(block)
        is HtmlBlock.Video -> VideoTile(block.src)
        is HtmlBlock.YouTube -> YouTubeTile(block)
        is HtmlBlock.Details -> DetailsBlock(block)
        is HtmlBlock.Web -> WebHtmlView(block.html, Modifier.fillMaxWidth(), block.baseUrl)
        is HtmlBlock.Aligned -> Column(
            Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = if (block.align == BlockAlign.Center) Alignment.CenterHorizontally else Alignment.End,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalBlockAlign provides block.align) {
                block.blocks.forEach { RenderBlock(it, muted) }
            }
        }
        HtmlBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(g.border))
    }
}

/** 当前所在的居中 / 靠右容器；文字据此对齐，图片行据此排布 */
private val LocalBlockAlign = androidx.compose.runtime.compositionLocalOf<BlockAlign?> { null }

private fun BlockAlign?.textAlign(): TextAlign? = when (this) {
    BlockAlign.Center -> TextAlign.Center
    BlockAlign.End -> TextAlign.End
    null -> null
}

@Composable
private fun HeadingBlock(block: HtmlBlock.Heading) {
    val g = Gh.c
    val scroll = LocalHtmlScroll.current
    val (size, lineHeight) = when (block.level) {
        1 -> 24.sp to 32.sp
        2 -> 20.sp to 28.sp
        3 -> 17.sp to 24.sp
        else -> 15.sp to 22.sp
    }
    val anchors = block.anchors
    Column(
        Modifier.padding(top = if (block.level == 1) 20.dp else 16.dp, bottom = 4.dp).onGloballyPositioned { c ->
            if (scroll != null) {
                anchors.forEach {
                    scroll.headingCoordinates[it] = c
                    scroll.headingY[it] = c.positionInRoot().y
                }
            }
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HtmlText(block.inlines, color = g.fg, fontSize = size, fontWeight = FontWeight.SemiBold, lineHeight = lineHeight)
        if (block.level <= 2) Box(Modifier.fillMaxWidth().height(1.dp).background(g.borderMuted))
    }
}

@Composable
private fun ListView(block: HtmlBlock.ListBlock, muted: Boolean) {
    val g = Gh.c
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.items.forEachIndexed { i, item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    if (block.ordered) "${block.start + i}." else "•",
                    Modifier.width(22.dp).padding(top = 1.dp),
                    color = if (muted) g.fgMuted else g.fg,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
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
private fun CodeActionBtn(
    icon: Int,
    text: String,
    onClick: () -> Unit,
) {
    val g = Gh.c
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(g.canvas)
            .border(1.dp, g.borderMuted, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(icon, g.fgMuted, 12.dp)
        Spacer(Modifier.width(4.dp))
        Text(text, color = g.fg, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CodeBlock(code: String, language: String?) {
    val g = Gh.c
    val ctx = rememberCtx()
    var zoom by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(6.dp)
    val codeScroll = rememberScrollState()

    val displayLang = remember(code, language) {
        val raw = language?.trim()?.lowercase().orEmpty()
        if (raw.isNotEmpty() && raw !in setOf("plaintext", "txt")) {
            raw
        } else {
            detectCodeLanguage(code)
        }
    }

    val sc = syntaxColors(g)
    val highlighted = remember(code, displayLang, g.dark) {
        if (displayLang == "text") {
            AnnotatedString(code)
        } else {
            val lines = highlightLines(code, displayLang, sc)
            val b = AnnotatedString.Builder()
            lines.forEachIndexed { i, line ->
                if (i > 0) b.append("\n")
                b.append(line)
            }
            b.toAnnotatedString()
        }
    }

    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, g.borderMuted, shape).background(g.canvasSubtle)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(displayLang, Modifier.weight(1f), color = g.fgMuted, fontSize = 11.sp, maxLines = 1)
            CodeActionBtn(R.drawable.oc_copy, tr("复制")) { ctx.copy(code) }
            Spacer(Modifier.width(6.dp))
            CodeActionBtn(R.drawable.oc_screen_full, tr("放大")) { zoom = code }
        }
        Text(
            highlighted,
            Modifier
                .fillMaxWidth()
                .horizontalScrollWithBar(codeScroll)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            color = g.fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            softWrap = false,
        )
    }
    zoom?.let { text -> CodeZoomDialog(text, displayLang) { zoom = null } }
}

@Composable
private fun TableBlock(block: HtmlBlock.Table) {
    val g = Gh.c
    val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0, block.aligns.size)
    if (columns == 0) return
    val shape = RoundedCornerShape(6.dp)
    val tableScroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 2~3 列表格（常见截图画廊）优先适应可用宽度，避免最右侧图片被截断；更多列再按 120dp 横向滚动
        val minCol = if (columns <= 3) 80.dp else 120.dp
        val colWidth = (maxWidth / columns).coerceAtLeast(minCol)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, g.borderMuted, shape)
                .horizontalScrollWithBar(tableScroll),
        ) {
            Column(Modifier.padding(bottom = 6.dp)) {
                if (block.header.isNotEmpty()) {
                    TableRow(block.header, columns, colWidth, block.aligns, header = true)
                }
                block.rows.forEach { TableRow(it, columns, colWidth, block.aligns, header = false) }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<List<Inline>>, columns: Int, colWidth: Dp, aligns: List<TableAlign>, header: Boolean) {
    val g = Gh.c
    Row(Modifier.height(IntrinsicSize.Min).background(if (header) g.canvasSubtle else Color.Transparent)) {
        for (c in 0 until columns) {
            val align = aligns.getOrElse(c) { TableAlign.None }
            // fillMaxHeight：同一行的格子拉到同样高，边框连成完整的表格线，不会出现矮一截的格子
            Box(Modifier.width(colWidth).fillMaxHeight().border(0.5.dp, g.borderMuted).padding(horizontal = 8.dp, vertical = 6.dp)) {
                CellContent(
                    cells.getOrElse(c) { emptyList() }, colWidth - 16.dp - 1.dp,
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

/**
 * 表格单元格：只有文字和小徽章时按行内排版；含截图等大图时，文字和图片上下排开，
 * 图片按自身大小显示、最宽不超过单元格（以前一律按 20×20 的行内占位，截图缩成一个小点）。
 */
@Composable
private fun CellContent(cell: List<Inline>, maxWidth: Dp, fontWeight: FontWeight?, textAlign: TextAlign?) {
    val g = Gh.c
    if (cell.none { it is Inline.Image && !HtmlParser.isInlineImage(it) }) {
        HtmlText(cell, color = g.fg, fontSize = 14.sp, fontWeight = fontWeight, textAlign = textAlign)
        return
    }
    val parts = mutableListOf<Any>()
    var seg = mutableListOf<Inline>()
    fun emit() { trimInlines(seg).takeIf { it.isNotEmpty() }?.let { parts.add(it) }; seg = mutableListOf() }
    for (item in cell) {
        if (item is Inline.Image && !HtmlParser.isInlineImage(item)) { emit(); parts.add(item) } else seg += item
    }
    emit()
    Column(
        Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = when (textAlign) {
            TextAlign.Center -> Alignment.CenterHorizontally
            TextAlign.End -> Alignment.End
            else -> Alignment.Start
        },
    ) {
        parts.forEach { part ->
            if (part is Inline.Image) RowImage(part, maxWidth)
            else {
                @Suppress("UNCHECKED_CAST")
                HtmlText(part as List<Inline>, color = g.fg, fontSize = 14.sp, fontWeight = fontWeight, textAlign = textAlign)
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
    // 加载失败：显示提示，点按重试（以前失败后一直是灰块，不知道发生了什么）
    var failed by remember(src) { mutableStateOf(false) }
    var attempt by remember(src) { mutableIntStateOf(0) }
    LaunchedEffect(src, attempt) {
        if (bmp == null || attempt > 0) {
            failed = false
            bmp = runCatching { Images.load(src, 1024, force = attempt > 0) }.getOrNull()
            failed = bmp == null
        }
    }
    val w = block.width
    val h = block.height
    val ratio = if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else null
    val alt = block.alt
    val nav = LocalNav.current
    val ctx = rememberCtx()
    // 图片外面套了链接（如徽章、点击跳转的横幅）：点击放大查看（可在查看器内点按外链，或长按直接跳转）
    val link = block.link
    // 按 HTML 的 width 属性显示（如 logo 写了 width="104"），没写才铺满；超过可用宽度时自动收窄
    val widthMod = if (w != null && w > 0) Modifier.width(w.dp) else Modifier.fillMaxWidth()
    val base = widthMod.clip(RoundedCornerShape(6.dp))
        .combinedClickable(
            onClick = { ImageViewer.open(block.viewSrc ?: src, alt, link) },
            onLongClick = if (link != null) { { openHtmlLink(ctx, nav, link, alt) } } else null,
        )
    val loaded = bmp
    val shape = RoundedCornerShape(8.dp)
    if (loaded != null) {
        Image(loaded, alt.ifBlank { null }, base, contentScale = ContentScale.FillWidth)
    } else if (failed) {
        Box(
            widthMod
                .heightIn(min = 76.dp, max = 130.dp)
                .clip(shape)
                .background(g.canvasSubtle)
                .border(1.dp, g.borderMuted, shape)
                .clickable { failed = false; attempt++ }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Oc(R.drawable.oc_file_media, g.fgMuted, 16.dp)
                    Text(
                        alt.ifBlank { tr("无法加载图片") },
                        color = g.fg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(g.canvas)
                        .border(1.dp, g.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Oc(R.drawable.oc_sync, g.accent, 12.dp)
                    Text(tr("重新加载"), color = g.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    } else {
        val sized = if (ratio != null) base.aspectRatio(ratio) else if (w != null && w > 0) base.aspectRatio(1f) else base.height(120.dp)
        Box(
            sized
                .clip(shape)
                .background(g.canvasSubtle)
                .border(1.dp, g.borderMuted, shape),
            contentAlignment = Alignment.Center,
        ) {
            Oc(R.drawable.oc_file_media, g.fgMuted.copy(alpha = 0.35f), 22.dp)
        }
    }
}

/**
 * 一排并排的图片（徽章行、并排的截图）：按图片自身大小流式排列，放不下自动换行。
 * 尺寸优先用 HTML 的 width / height 属性，没有就用图片的逻辑尺寸（SVG 徽章约 20dp 高）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ImageRowBlock(block: HtmlBlock.ImageRow) {
    val blockAlign = LocalBlockAlign.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rowMaxW = maxWidth
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = when (blockAlign) {
                BlockAlign.Center -> Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                BlockAlign.End -> Arrangement.spacedBy(6.dp, Alignment.End)
                null -> Arrangement.spacedBy(6.dp)
            },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            block.images.forEach { RowImage(it, rowMaxW) }
        }
    }
}

@Composable
private fun RowImage(img: Inline.Image, maxWidth: Dp = Dp.Unspecified) {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    var retry by remember(img.src) { mutableIntStateOf(0) }
    var bmp by remember(img.src) { mutableStateOf(Images.cached(img.src, 1024)) }
    var failed by remember(img.src) { mutableStateOf(false) }
    LaunchedEffect(img.src, retry) {
        if (bmp == null || retry > 0) {
            failed = false
            val loaded = runCatching { Images.load(img.src, 1024, force = retry > 0) }.getOrNull()
            if (loaded != null) {
                bmp = loaded
            } else {
                failed = true
            }
        }
    }
    val link = img.link
    val isBadge = HtmlParser.isInlineImage(img)
    val click = Modifier.clip(RoundedCornerShape(3.dp))
        .combinedClickable(
            onClick = {
                if (isBadge && link != null) openHtmlLink(ctx, nav, link, img.alt)
                else ImageViewer.open(img.viewSrc ?: img.src, img.alt, link)
            },
            onLongClick = {
                if (isBadge) ImageViewer.open(img.viewSrc ?: img.src, img.alt, link)
                else if (link != null) openHtmlLink(ctx, nav, link, img.alt)
            },
        )
    val loaded = bmp
    if (loaded == null) {
        val placeholderH = when {
            img.height != null -> img.height.dp
            (img.width ?: 0) > 120 -> 120.dp
            else -> 24.dp
        }
        val shape = RoundedCornerShape(4.dp)
        if (failed) {
            Box(
                Modifier
                    .widthIn(max = maxWidth)
                    .then(if (img.width != null) Modifier.width(img.width.dp) else Modifier)
                    .height(placeholderH)
                    .clip(shape)
                    .background(g.canvasSubtle)
                    .border(1.dp, g.borderMuted, shape)
                    .clickable {
                        failed = false
                        retry++
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Oc(R.drawable.oc_sync, g.accent, 12.dp)
                    Text(
                        img.alt.ifBlank { tr("重试") },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = g.fgMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Box(
                Modifier
                    .widthIn(max = maxWidth)
                    .size((img.width ?: 72).dp, placeholderH)
                    .clip(shape)
                    .background(g.canvasSubtle)
                    .border(1.dp, g.borderMuted, shape),
                contentAlignment = Alignment.Center,
            ) {
                Oc(R.drawable.oc_file_media, g.fgMuted.copy(alpha = 0.35f), 12.dp)
            }
        }
    } else {
        val scale = Images.scaleOf(loaded)
        val naturalW = loaded.width / scale
        val aspect = loaded.width.toFloat() / loaded.height.coerceAtLeast(1)
        val w = when {
            img.width != null -> img.width.toFloat()
            img.height != null -> img.height * aspect
            else -> naturalW
        }
        // width 会被限制在可用宽度内，再按宽高比定高度，超宽的图不会变形
        Image(
            loaded, img.alt.ifBlank { null },
            click.widthIn(max = maxWidth).width(w.dp).aspectRatio(aspect),
            contentScale = ContentScale.FillWidth,
        )
    }
}

/** 视频首帧缓存：列表滚动、重组时同一个视频不重复抽帧 */
private object VideoFrameCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(16)
    fun get(src: String): ImageBitmap? = cache.get(src)
    fun put(src: String, frame: ImageBitmap) { cache.put(src, frame) }
}

/** 抽取视频约第 1 秒的一帧作为封面；失败返回 null，页面只显示占位，不影响之后的播放。 */
private suspend fun extractVideoFrame(src: String): ImageBitmap? = withContext(Dispatchers.IO) {
    val playable = runCatching { VideoRoute.pick(src) }.getOrDefault(src)
    val retriever = android.media.MediaMetadataRetriever()
    try {
        retriever.setDataSource(playable, HashMap())
        val option = android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        (retriever.getFrameAtTime(1_000_000L, option) ?: retriever.getFrameAtTime(0L, option))?.asImageBitmap()
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * 视频占位：显示首帧封面，中间一个播放键、右下角「放大播放」。
 * 只有点播放键或「放大播放」才会全屏播放；点画面其它地方没有反应，避免滑动时误触。
 */
@Composable
private fun VideoTile(src: String) {
    val g = Gh.c
    var frame by remember(src) { mutableStateOf(VideoFrameCache.get(src)) }
    LaunchedEffect(src) {
        if (frame == null) {
            extractVideoFrame(src)?.let {
                VideoFrameCache.put(src, it)
                frame = it
            }
        }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.fillMaxWidth().height(180.dp).clip(shape).background(Color.Black).border(1.dp, g.borderMuted, shape),
        contentAlignment = Alignment.Center,
    ) {
        frame?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        if (frame == null) {
            Text(tr("视频"), Modifier.align(Alignment.TopStart).padding(10.dp), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        }
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                .clickable(role = Role.Button) { VideoViewer.open(src) },
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", Modifier.padding(start = 3.dp), color = Color.White, fontSize = 22.sp)
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(role = Role.Button) { VideoViewer.open(src) }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oc(R.drawable.oc_screen_full, Color.White, 12.dp)
            Spacer(Modifier.width(4.dp))
            Text(tr("放大播放"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * YouTube 视频：显示封面，中间一个播放键，点播放键（或封面）在应用内全屏播放。
 * 以前它被当成普通图片：点击只会弹出链接菜单，看起来像一张打不开的图。
 */
@Composable
private fun YouTubeTile(block: HtmlBlock.YouTube) {
    val g = Gh.c
    val shape = RoundedCornerShape(8.dp)
    val play = { YouTubeViewer.open(block.id, block.title) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Color.Black)
                .border(1.dp, g.borderMuted, shape)
                .clickable(role = Role.Button, onClick = play),
            contentAlignment = Alignment.Center,
        ) {
            NetImage(
                block.thumb ?: HtmlParser.youtubeThumb(block.id),
                Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                placeholder = false,
                targetDim = 1024,
            )
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", Modifier.padding(start = 4.dp), color = Color.White, fontSize = 26.sp)
            }
        }
        Text(
            block.title.ifBlank { "YouTube" },
            color = g.fgMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
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
        targetDim = maxOf(width, height) * 3,
    )
}

private class BuiltText(
    val text: AnnotatedString,
    val inline: Map<String, InlineTextContent>,
    /** 每段链接在文字里的字符范围，长按时把它整段选中 */
    val links: List<LinkSpan> = emptyList(),
)

internal data class LinkSpan(val url: String, val start: Int, val end: Int, val label: String)

private fun buildInlineText(inlines: List<Inline>, accent: Color, codeBg: Color): BuiltText {
    val content = HashMap<String, InlineTextContent>()
    val links = mutableListOf<LinkSpan>()
    // 连续的带链接文字合并成一段：一个链接里的多段文字（粗体、代码等）算作同一个范围
    var open: LinkSpan? = null
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
                    val start = length
                    append(item.text)
                    pop()
                    if (link != null) pop()
                    if (link != null) {
                        val label = item.text
                        open = if (open?.url == link && open?.end == start) {
                            open!!.copy(end = length, label = open!!.label + label)
                        } else {
                            open?.let { links.add(it) }
                            LinkSpan(link, start, length, label)
                        }
                    } else {
                        open?.let { links.add(it) }
                        open = null
                    }
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
                    val start = length
                    appendInlineContent(id, item.alt.ifBlank { "image" })
                    if (link != null) pop()
                    if (link != null) {
                        open?.let { links.add(it) }
                        open = LinkSpan(link, start, length, item.alt)
                    }
                }
                Inline.Break -> append("\n")
            }
        }
    }
    open?.let { links.add(it) }
    return BuiltText(text, content, links)
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
    val blockAlign = LocalBlockAlign.current
    val built = remember(inlines, g.dark) {
        buildInlineText(inlines, g.accent, if (g.dark) Color(0x33656C76) else Color(0x1F818B98))
    }
    val text = built.text
    val layoutRef = remember { AtomicReference<TextLayoutResult?>(null) }
    // 长按链接时把整段链接选中：选区由这里控制，交给外层 SelectionContainer
    var forced by remember(text) { mutableStateOf<IntRange?>(null) }
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val baseUrl = LocalBaseUrl.current
    val onLink: (String, String?) -> Unit = { url, label ->
        val cleanUrl = url.trim()
        if (cleanUrl.startsWith("#")) {
            val jumped = scroll?.jumpTo(cleanUrl.removePrefix("#")) == true
            if (!jumped) {
                openHtmlLink(ctx, nav, cleanUrl, label)
            }
        } else if ("#" in cleanUrl) {
            val before = cleanUrl.substringBefore('#').trim().removeSuffix("/")
            val after = cleanUrl.substringAfter('#').trim()
            val currentBase = baseUrl?.trim()?.removeSuffix("/").orEmpty()
            val isCurrentDoc = before.isEmpty() || before == currentBase ||
                (currentBase.endsWith("/README.md", ignoreCase = true) && before.endsWith("/README.md", ignoreCase = true))
            val jumped = if (isCurrentDoc) scroll?.jumpTo(after) == true else false
            if (!jumped) {
                openHtmlLink(ctx, nav, cleanUrl, label)
            }
        } else {
            openHtmlLink(ctx, nav, cleanUrl, label)
        }
    }
    val highlight = if (g.dark) Color(0x4D388BFD) else Color(0x4D0969DA)
    // 菜单关掉后撤掉高亮
    if (LinkMenu.url == null && forced != null) forced = null
    // 长按链接时整段链接高亮：自己画，避免 Compose 的选词只选中一个词、还弹出「全选 / 朗读」工具栏
    val body = (if (blockAlign != null && textAlign == null) modifier.fillMaxWidth() else modifier)
        .drawWithContent {
            val range = forced
            val l = layoutRef.get()
            if (range != null && l != null) {
                runCatching { drawPath(l.getPathForRange(range.first, range.last + 1), highlight) }
            }
            drawContent()
        }
    val clickable = if (built.links.isEmpty()) body else body.pointerInput(text, nav, ctx, scroll) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val layout = layoutRef.get() ?: return@awaitEachGesture
                if (text.isEmpty()) return@awaitEachGesture

                // 检查触控点是否落在文本布局的有效范围（行高与左右文字边界）
                if (down.position.y < 0f || down.position.y > layout.size.height) {
                    return@awaitEachGesture
                }
                val line = layout.getLineForVerticalPosition(down.position.y)
                if (down.position.y < layout.getLineTop(line) || down.position.y > layout.getLineBottom(line)) {
                    return@awaitEachGesture
                }
                val lineLeft = layout.getLineLeft(line)
                val lineRight = layout.getLineRight(line)
                if (down.position.x < lineLeft || down.position.x > lineRight) {
                    return@awaitEachGesture
                }
                val offset = layout.getOffsetForPosition(down.position)
                if (offset !in 0 until text.length) {
                    return@awaitEachGesture
                }

                // 严格命中链接范围：start until end
                val linkSpan = built.links.firstOrNull { offset in it.start until it.end }
                val link = linkSpan?.url
                    ?: text.getStringAnnotations(MD_LINK_TAG, offset, (offset + 1).coerceAtMost(text.length))
                        .firstOrNull()?.item
                    ?: return@awaitEachGesture

                val startPos = down.position
                val touchSlop = viewConfiguration.touchSlop
                // 至少 500ms 的长按阈值，避免滑动或者快速轻按误判为长按
                val longPressThreshold = maxOf(500L, longPressMs)
                var isTap = false
                var isCanceled = false

                val timedOut = withTimeoutOrNull(longPressThreshold) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed) {
                            isCanceled = true
                            return@withTimeoutOrNull false
                        }
                        if (change.changedToUp()) {
                            change.consume()
                            isTap = true
                            return@withTimeoutOrNull false
                        }
                        if ((change.position - startPos).getDistance() > touchSlop) {
                            isCanceled = true
                            return@withTimeoutOrNull false
                        }
                    }
                    false
                } == null

                if (isCanceled) {
                    // 滑动位移超出 touchSlop，或者被外层滚动容器消费：立刻退出，不消费事件，不做选择或弹窗
                    return@awaitEachGesture
                }

                if (isTap) {
                    // 在长按阈值内抬手且未滑动：普通点按，打开链接
                    onLink(link, linkSpan?.label)
                    return@awaitEachGesture
                }

                if (timedOut) {
                    // 按住不动且位移严格在 touchSlop 阈值内超过 500ms：触发长按选择与弹窗
                    val span = linkSpan ?: built.links.firstOrNull { it.url == link }
                    forced = span?.let { it.start until it.end }
                    if (link.startsWith("https://") || link.startsWith("http://")) {
                        LinkMenu.show(link, span?.label)
                    } else if (link.startsWith("#") || "#" in link) {
                        val target = if (link.startsWith("#")) link.removePrefix("#") else link.substringAfter("#")
                        LinkMenu.show(link, span?.label?.takeIf { it.isNotBlank() } ?: percentDecode(target))
                    }
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
    }
    Text(
        text = text,
        modifier = clickable,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        textAlign = textAlign ?: blockAlign.textAlign(),
        // 行高不裁掉首行上方与末行下方的留白：字形较高的字体（部分系统字体、放大字号）最后一行不会被下方内容盖住
        style = LocalTextStyle.current.merge(
            TextStyle(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)),
        ),
        inlineContent = built.inline,
        onTextLayout = { layoutRef.set(it) },
    )
}

/** 打开 HTML 里的链接：应用内页面、浏览器，或系统应用（邮件、拨号）。 */
internal fun openHtmlLink(ctx: Context, nav: Navigator, url: String, label: String? = null) {
    val clean = url.trim()
    val ext = runCatching { java.net.URI(clean).path.trimEnd('/').substringAfterLast('.').lowercase() }.getOrNull()
    if (ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico")) {
        ImageViewer.open(clean, label.orEmpty())
        return
    }
    if (com.mobilegh.data.Downloads.isDownloadableUrl(clean)) {
        com.mobilegh.data.Downloads.inspectUrl(clean, label)
        return
    }
    when (val action = webLinkAction(clean)) {
        is WebLinkAction.Native -> {
            if (nav.top.screen == action.screen) {
                nav.push(action.screen)
            } else {
                nav.pushLink(action.screen)
            }
        }
        WebLinkAction.Browser -> ExternalLinkDialog.show(clean, label)
        WebLinkAction.System -> openSystemIntent(ctx, clean)
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
