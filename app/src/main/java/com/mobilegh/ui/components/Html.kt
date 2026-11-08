package com.mobilegh.ui.components

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobilegh.R
import com.mobilegh.data.AppLog
import com.mobilegh.data.ImageFetch
import com.mobilegh.data.MediaProxy
import com.mobilegh.nav.Links
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Screen
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.font.FontWeight
import com.mobilegh.i18n.trf

/**
 * Compose 把宽高打包进同一个 long，要求两者的位数之和不超过 31。所以「安全的最大高度」
 * 取决于组件实际宽度：手机（宽 < 8191px）可以到约 26 万像素，平板（宽 8192–32766px）
 * 就只剩约 6.5 万像素。这里按宽度分档取保守值，既不会触发 Constraints 崩溃，
 * 也不会像以前那样把长 README 硬截在 12000dp。
 */
internal fun maxViewHeightPx(widthPx: Int): Int = when {
    widthPx < 8191 -> 240_000
    widthPx < 32767 -> 60_000
    widthPx < 65535 -> 30_000
    else -> 8_000
}

/** WebView 的最大高度（dp）。JS 回报的 CSS 像素等同于 dp，按密度换算后才是 Compose 的像素。 */
internal fun htmlMaxHeightDp(density: Float, widthPx: Int): Int =
    (maxViewHeightPx(widthPx) / density).toInt()

/** JS 回报的内容高度（CSS 像素，等同 dp）夹在 [24, maxDp] 之间：未加载时至少 24dp，异常高度不超过上限。 */
internal fun htmlContentHeightDp(reportedPx: Int, maxDp: Int): Int = reportedPx.coerceIn(24, maxDp)

/**
 * 显示网页内容（排版完成后调用，之后再调用无效）。
 * 不做淡入：占位色块与网页内容必须在同一帧切换，否则中间会出现一帧空白。
 */
internal fun WebView.revealHtml() {
    if (alpha < 1f) alpha = 1f
}

/** 网页还没排版好时显示的骨架条：静态、与正文同宽同高，表示加载中，而不是空白。 */
@Composable
private fun HtmlSkeleton() {
    val bar = Gh.c.border
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(1f, 0.92f, 0.78f, 0.96f, 0.6f).forEach { fraction ->
            Box(Modifier.fillMaxWidth(fraction).height(10.dp).background(bar, RoundedCornerShape(5.dp)))
        }
    }
}

/**
 * 网页还没排版好时的初始高度（dp）：按纯文字长度与图片数量估算，避免从 24dp 开始、排完再整块长高。
 * 估算只用来占位，排版完成后 JS 回报的真实高度会替换它。
 */
internal fun htmlEstimateHeightDp(html: String, widthDp: Int, maxDp: Int): Int {
    val text = html.replace(Regex("<[^>]+>"), "").length
    val charsPerLine = (widthDp / 8).coerceAtLeast(10)
    val lines = (text + charsPerLine - 1) / charsPerLine
    val images = Regex("<img[\\s>]").findAll(html).count()
    return (lines * 22 + images * 160 + 16).coerceIn(48, maxDp)
}

/**
 * WebView 量好的内容高度（dp）按内容缓存。HtmlView 滚出列表后会被销毁，再滚回来时重新组合，
 * 没有这份缓存就会从 24dp 重新长高，前后位置跟着变。键由内容、基址、屏宽、字号组成，内容变了高度自然失效。
 */
internal object HtmlHeightCache {
    private const val MAX_ENTRIES = 400
    private val map = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String): Int? = map[key]

    @Synchronized
    fun put(key: String, heightDp: Int) {
        map[key] = heightDp
    }
}

/** 高度缓存的键。哈希碰撞只会让首帧高度略有偏差，之后 JS 回报会纠正，所以用哈希而不保存整段 HTML。 */
internal fun htmlHeightKey(doc: String, baseUrl: String, screenWidthDp: Int, textZoom: Int): String =
    "${doc.hashCode()}:${doc.length}:$baseUrl:$screenWidthDp:$textZoom"

/** 请求 HtmlView 滚到某个标题锚点。每次请求都是新对象，同一个标题再次点击也会生效。 */
class HtmlAnchorRequest(val anchor: String)

/** README 目录的一项：标题层级、文字，以及页内锚点（去掉 GitHub 加的 user-content- 前缀）。 */
data class ReadmeHeading(val level: Int, val text: String, val anchor: String)

private val headingTag = Regex("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>")
private val headingAnchor = Regex("""(?<![\w-])id="(?:user-content-)?([^"]+)"""")
private val htmlTagRegex = Regex("<[^>]+>")
private val spaceRun = Regex("\\s+")

/**
 * 从 GitHub 渲染好的 README HTML 字符串里取出标题，给「目录」用。只读标题标签和锚点，不改动 README 的渲染。
 * 没有锚点的标题无法跳转，直接跳过；文字去掉标签并还原常见实体。
 */
internal fun readmeHeadings(html: String): List<ReadmeHeading> =
    headingTag.findAll(html).mapNotNull { m ->
        val anchor = headingAnchor.find(m.value)?.groupValues?.get(1) ?: return@mapNotNull null
        val text = unescapeBasicEntities(htmlTagRegex.replace(m.groupValues[2], "")).replace(spaceRun, " ").trim()
        if (text.isEmpty()) null else ReadmeHeading(m.groupValues[1].toInt(), text, anchor)
    }.toList()

/** 还原标题里最常见的实体；&amp; 放在最后，避免把 &amp;lt; 连续还原两次。 */
private fun unescapeBasicEntities(s: String): String = s
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&")

/** 滚动到锚点的脚本：找到标题后经 Bridge.a 把位置交给原生列表，与点击页内锚点走同一条路径。 */
internal fun headingScrollScript(anchor: String): String =
    "(function(){var id=${jsStringLiteral(anchor)};" +
        "var t=document.getElementById('user-content-'+id)||document.getElementById(id);" +
        "if(t){Bridge.a(Math.round(t.getBoundingClientRect().top+window.scrollY));}})();"

/** 外部链接的分类：网页在浏览器打开，mailto / tel 交给系统应用，其余不支持。 */
internal enum class ExternalLinkKind { Web, Mail, Phone, Other }

internal fun externalLinkKind(url: String): ExternalLinkKind =
    when (url.substringBefore(':', "").lowercase()) {
        "http", "https" -> ExternalLinkKind.Web
        "mailto" -> ExternalLinkKind.Mail
        "tel" -> ExternalLinkKind.Phone
        else -> ExternalLinkKind.Other
    }

/** 渲染进程退出后，同一个页面最多自动重建 WebView 的次数 */
private const val MAX_RENDER_RETRIES = 2

/** 网页里点击链接后的去向。WebView 自己不跳转：GitHub 内部地址走原生页面，其余交给浏览器或系统应用。 */
internal sealed interface WebLinkAction {
    data class Native(val screen: Screen) : WebLinkAction
    data object Browser : WebLinkAction
    data object System : WebLinkAction
    data object Unsupported : WebLinkAction
}

internal fun webLinkAction(url: String): WebLinkAction {
    Links.route(url)?.let { return WebLinkAction.Native(it) }
    return when (externalLinkKind(url)) {
        ExternalLinkKind.Web -> WebLinkAction.Browser
        ExternalLinkKind.Mail, ExternalLinkKind.Phone -> WebLinkAction.System
        ExternalLinkKind.Other -> WebLinkAction.Unsupported
    }
}

/**
 * 渲染 GitHub 返回的 HTML（README、Issue、Release 等），使用 GitHub 的 markdown 样式。
 * WebView 高度随内容自适应，放在可滚动容器中使用。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebHtmlView(
    html: String,
    modifier: Modifier = Modifier,
    baseUrl: String = "https://github.com/",
    onAnchor: ((Int) -> Unit)? = null,
    /** 非空时滚动到该标题（目录点击）；结果经 onAnchor 交给外层列表定位 */
    anchorRequest: HtmlAnchorRequest? = null,
    /**
     * 为 true 时 WebView 按完整内容高度布局，不受父级最大高度约束（requiredHeight）。
     * 父级若要折叠，应在外层 clipToBounds 裁剪；这样 WebView 自身没有可滚动的范围，不会出现内部滚动。
     * 为 false 时高度仍受父级约束（Modifier.height），其他调用方行为不变。
     */
    fullHeight: Boolean = false,
) {
    val nav = LocalNav.current
    val dark = Gh.c.dark
    // Compose 的约束以物理像素编码；可用高度取决于组件宽度，需等测量后才知道。
    val density = LocalDensity.current.density
    val textZoom = (LocalDensity.current.fontScale * 100).toInt().coerceAtLeast(1)
    var measuredWidth by remember { mutableIntStateOf(0) }
    var renderEpoch by remember { mutableIntStateOf(0) }
    val maxHeight = remember(density, measuredWidth) { htmlMaxHeightDp(density, measuredWidth) }
    val heightLimit by rememberUpdatedState(maxHeight)
    val anchor by rememberUpdatedState(onAnchor)
    val doc = remember(html, dark) { wrapHtml(html, dark) }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val cacheKey = remember(doc, baseUrl, screenWidthDp, textZoom) { htmlHeightKey(doc, baseUrl, screenWidthDp, textZoom) }
    val cacheKeyState = rememberUpdatedState(cacheKey)
    // 之前滚出屏幕时留下的快照：有的话先显示它，WebView 重新排版好之后再覆盖
    val snapshot = remember(cacheKey) { HtmlSnapshotCache.get(cacheKey) }
    // 高度先取缓存；没有缓存时用估算值占位。列表项滚出再滚回时 WebView 会重建，若从小高度重新长高，内容就会跳动
    val estimatedDp = remember(html, screenWidthDp, maxHeight) { htmlEstimateHeightDp(html, screenWidthDp, maxHeight) }
    var height by remember { mutableIntStateOf(HtmlHeightCache.get(cacheKey) ?: estimatedDp) }
    LaunchedEffect(cacheKey) { HtmlHeightCache.get(cacheKey)?.let { height = it } }
    // 网页内容是否已经排版好并显示：没有快照、也没排好时，正文区域显示静态占位，而不是透明的空白
    val webReady = remember { mutableStateOf(false) }
    // 在 AndroidView 释放已脱离视图树的实例后销毁，避免提前 destroy 仍附着的 WebView。
    val webRef = remember { arrayOfNulls<WebView>(1) }
    /** 代码块「放大」：非空时弹出全屏代码视图 */
    val codeZoom = remember { mutableStateOf<String?>(null) }
    /** 图表全屏查看（mermaid、GeoJSON、TopoJSON、STL）：非空时弹出 */
    val diagramZoom = remember { mutableStateOf<DiagramRequest?>(null) }
    LaunchedEffect(anchorRequest) {
        val req = anchorRequest ?: return@LaunchedEffect
        webRef[0]?.evaluateJavascript(headingScrollScript(req.anchor), null)
    }
    // 渲染进程退出后换一个 key 重建 WebView；最多重建两次，避免某个页面反复崩溃时无限循环
    key(renderEpoch) {
        Box(
            modifier
                .fillMaxWidth()
                .onSizeChanged { if (it.width != measuredWidth) measuredWidth = it.width }
                // Modifier.height 会把高度夹进父级的最大约束；fullHeight 时改用 requiredHeight，由外层裁剪。
                .then(
                    htmlContentHeightDp(height, maxHeight).dp.let { h ->
                        if (fullHeight) Modifier.requiredHeight(h) else Modifier.height(h)
                    },
                ),
        ) {
        // 加载占位：静态的骨架条，不做动画。快照或排版完成后直接替换，只发生这一次切换，中间不会出现空白
        if (snapshot == null && !webReady.value) HtmlSkeleton()
        // 快照只在网页排好之前占位。网页背景是透明的，排好后若仍垫着快照，高度一变两者错位就会出现重影
        if (!webReady.value) snapshot?.let { bitmap ->
            Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    webRef[0] = this
                    setBackgroundColor(0)
                    // 内容排版完成前保持透明，再淡入：否则滚动时能看见未排版的半成品页面（列表里的闪烁）
                    alpha = 0f
                    // 兜底：页面没报告高度时也要显示出来
                    postDelayed({ revealHtml(); webReady.value = true }, 1500)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    // 页面高度跟随内容（ResizeObserver 上报），WebView 本身不该滚动。若上报的高度偶尔比内容矮，
                    // 手指在正文上拖动会让它内部滚动，顶部就被裁掉；这里把内部滚动位置钉在 0。
                    setOnScrollChangeListener { _, _, y, _, _ -> if (y != 0) scrollTo(0, 0) }
                    settings.javaScriptEnabled = true
                    // README 里的视频多为静音自动播放；默认要求用户手势时，页面里的播放按钮点了也不生效
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.textZoom = textZoom
                    // 长按正文里的链接：弹出复制 / 分享 / 浏览器打开。其它长按（选中文字等）保持 WebView 原来的行为
                    setOnLongClickListener {
                        val hit = hitTestResult
                        val target = hit.extra
                        if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && !target.isNullOrBlank()) {
                            LinkMenu.show(target)
                            true
                        } else {
                            false
                        }
                    }
                    addJavascriptInterface(object {
                        /** 同一个 WebView 只记一次：页面里有循环图片时高度会反复超限 */
                        @Volatile private var warned = false

                        @JavascriptInterface
                        fun h(v: Int) = post {
                            if (webRef[0] !== this@apply) return@post
                            // JS 返回的是内容像素；异常页面/循环图片可能回报几十万像素，
                            // 直接换算成 dp 会触发 Compose Constraints 崩溃。
                            if (v > heightLimit && !warned) {
                                warned = true
                                AppLog.warn("webview", tr("HTML 高度异常：") + v + tr("px，已限制"))
                            }
                            val measured = v.coerceIn(24, heightLimit)
                            height = measured
                            HtmlHeightCache.put(cacheKeyState.value, measured)
                            this@apply.revealHtml()
                            webReady.value = true
                            // 排版完成后在主线程上把网页画进位图，供再次滚入时作为占位（只画 WebView 自己，不带屏幕上的其它控件）
                            this@apply.postDelayed({ this@apply.captureSnapshot(cacheKeyState.value) }, 300)
                        }

                        @JavascriptInterface
                        fun a(y: Int) = post { if (webRef[0] === this@apply) anchor?.invoke(y) }

                        /** 点击网页里的图片：打开全屏查看器 */
                        @JavascriptInterface
                        fun img(src: String, alt: String) = post { if (webRef[0] === this@apply) ImageViewer.open(src, alt) }

                        /** 点击视频：交给原生全屏播放器（倍速、拖动、保存），WebView 自带的播放器做不到 */
                        @JavascriptInterface
                        fun video(src: String) = post {
                            // 网页已经释放（滚出屏幕）时忽略，避免给已销毁的页面打开播放器
                            if (webRef[0] === this@apply) VideoViewer.open(src)
                        }

                        /** 代码块「复制」：代码文本作为参数传入，不拼进 JS 源码 */
                        @JavascriptInterface
                        fun copyCode(text: String) = post { if (webRef[0] === this@apply) c.copy(text) }

                        /** 代码块「放大」：全屏查看，横向、纵向都能滚动 */
                        @JavascriptInterface
                        fun zoomCode(text: String) = post { if (webRef[0] === this@apply) codeZoom.value = text }

                        /** 图表「放大」：全屏查看，可缩放、拖动或旋转，并能切换到源码 */
                        @JavascriptInterface
                        fun diagram(kind: String, src: String) = post { if (webRef[0] === this@apply) diagramZoom.value = DiagramRequest(kind, src) }
                    }, "Bridge")
                    webViewClient = GhWebClient(c, nav) { if (renderEpoch < MAX_RENDER_RETRIES) renderEpoch++ }
                }
            },
            onRelease = { wv ->
                // 离开屏幕前留一张快照：再次滚入时先显示它，而不是空白等待重新排版
                // 重建时新的 WebView 可能先于旧的释放登记；只清理自己
                if (webRef[0] === wv) webRef[0] = null
                wv.removeJavascriptInterface("Bridge")
                wv.stopLoading()
                wv.destroy()
            },
            update = { wv ->
                if (wv.settings.textZoom != textZoom) wv.settings.textZoom = textZoom
                val contentKey = doc to baseUrl
                if (wv.tag != contentKey) {
                    wv.tag = contentKey
                    wv.loadDataWithBaseURL(baseUrl, doc, "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        }
    }
    codeZoom.value?.let { code -> CodeZoomDialog(code) { codeZoom.value = null } }
    diagramZoom.value?.let { req -> DiagramZoomDialog(req.kind, req.src, dark) { diagramZoom.value = null } }
}

/** 代码块全屏查看：默认自动换行；右上角三个点可切换换行、调整字号，设置会记住。 */
@Composable
internal fun CodeZoomDialog(code: String, onDismiss: () -> Unit) {
    val ctx = rememberCtx()
    val g = Gh.c
    val prefs = remember { ctx.getSharedPreferences("code_view", Context.MODE_PRIVATE) }
    var wrap by remember { mutableStateOf(prefs.getBoolean("wrap", true)) }
    var fontSp by remember { mutableIntStateOf(prefs.getInt("font", 13)) }
    var menu by remember { mutableStateOf(false) }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    fun setFont(v: Int) {
        fontSp = v.coerceIn(10, 22)
        prefs.edit().putInt("font", fontSp).apply()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(g.canvas).statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().background(g.canvasSubtle).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OcButton(R.drawable.oc_x, onDismiss, g.fg)
                Text(
                    tr("代码"), color = g.fg, fontSize = 15.sp,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                OcButton(R.drawable.oc_copy, { ctx.copy(code) }, g.fg)
                Box {
                    OcButton(R.drawable.oc_kebab_horizontal, { menu = true }, g.fg)
                    DropdownMenu(menu, { menu = false }, containerColor = g.canvas) {
                        DropdownMenuItem(
                            text = { Text(tr("自动换行"), color = g.fg) },
                            leadingIcon = { if (wrap) Oc(R.drawable.oc_check, g.fg, 16.dp) },
                            onClick = {
                                wrap = !wrap
                                prefs.edit().putBoolean("wrap", wrap).apply()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(trf("增大字号（当前 {0}）", fontSp), color = g.fg) },
                            onClick = { setFont(fontSp + 1) },
                        )
                        DropdownMenuItem(
                            text = { Text(trf("缩小字号（当前 {0}）", fontSp), color = g.fg) },
                            onClick = { setFont(fontSp - 1) },
                        )
                    }
                }
            }
            HDivider()
            val scroll = if (wrap) Modifier.columnScrollbar(vertical).verticalScroll(vertical) else Modifier.columnScrollbar(vertical).verticalScroll(vertical).horizontalScroll(horizontal)
            Box(Modifier.weight(1f).fillMaxWidth().then(scroll).padding(16.dp)) {
                Text(
                    code, color = g.fg, fontFamily = FontFamily.Monospace,
                    fontSize = fontSp.sp, lineHeight = (fontSp * 1.45f).sp, softWrap = wrap,
                )
            }
        }
    }
}

/** 全屏图表的请求：类型（mermaid、geojson、topojson、stl）与源码。 */
internal data class DiagramRequest(val kind: String, val src: String)

/**
 * 图表全屏查看：mermaid、GeoJSON、TopoJSON 支持拖动平移、双指或滚轮缩放；STL 支持拖动旋转、缩放。
 * 底部按钮：+ / 重置 / −；右上角可切换到源码。
 */
@Composable
internal fun DiagramZoomDialog(kind: String, source: String, dark: Boolean, onDismiss: () -> Unit) {
    val ctx = rememberCtx()
    val nav = LocalNav.current
    val g = Gh.c
    var showSource by remember { mutableStateOf(false) }
    val viewer = remember { arrayOfNulls<WebView>(1) }
    val sourceVertical = rememberScrollState()
    val sourceHorizontal = rememberScrollState()
    fun js(script: String) {
        viewer[0]?.evaluateJavascript(script, null)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(g.canvas).statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().background(g.canvasSubtle).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OcButton(R.drawable.oc_x, onDismiss, g.fg)
                Text(
                    tr("图表"), color = g.fg, fontSize = 15.sp,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                if (!showSource) {
                    ZoomStep("+") { js("zoomIn()") }
                    ZoomStep(tr("重置")) { js("resetView()") }
                    ZoomStep("−") { js("zoomOut()") }
                }
                OcButton(R.drawable.oc_code, { showSource = !showSource }, if (showSource) g.accent else g.fg)
                if (showSource) OcButton(R.drawable.oc_copy, { ctx.copy(source) }, g.fg)
            }
            HDivider()
            if (showSource) {
                SelectionContainer {
                    Box(
                        Modifier.weight(1f).fillMaxWidth()
                            .columnScrollbar(sourceVertical).verticalScroll(sourceVertical)
                            .horizontalScroll(sourceHorizontal)
                            .padding(16.dp),
                    ) {
                        Text(
                            source, color = g.fg, fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp, lineHeight = 19.sp, softWrap = false,
                        )
                    }
                }
            } else {
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            setBackgroundColor(0)
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            overScrollMode = WebView.OVER_SCROLL_NEVER
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = GhWebClient(c, nav)
                            loadDataWithBaseURL("https://github.com/", diagramViewerHtml(kind, source, dark), "text/html", "utf-8", null)
                        }.also { viewer[0] = it }
                    },
                    onRelease = { wv ->
                        viewer[0] = null
                        wv.stopLoading()
                        wv.destroy()
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ZoomStep(label: String, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Gh.c.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** 全屏查看页：按类型加载对应的库。STL 用三维轨道视角；其余是 SVG，可拖动和缩放。 */
internal fun diagramViewerHtml(kind: String, source: String, dark: Boolean): String {
    val script = when (kind) {
        "stl" -> STL_SCRIPT_URL
        "geojson", "topojson" -> GEO_SCRIPT_URL
        else -> MERMAID_SCRIPT_URL
    }
    return """
<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
html,body{margin:0;height:100%;overflow:hidden;background:transparent;}
#stage{position:fixed;left:0;top:0;right:0;bottom:0;overflow:hidden;touch-action:none;-webkit-user-select:none;user-select:none;}
#box{position:absolute;left:0;top:0;transform-origin:0 0;}
#box svg{display:block;}
#msg{padding:16px;font:14px/1.5 sans-serif;color:#d1242f;}
</style>
<script src='$script'></script></head>
<body><div id="stage"><div id="box"></div></div>
<script>
${DIAGRAM_JS}
(function(){
  var KIND=${jsStringLiteral(kind)};
  var SRC=${jsStringLiteral(source)};
  var DARK=$dark;
  var stage=document.getElementById('stage'), box=document.getElementById('box');
  function showError(e){var m=document.createElement('div');m.id='msg';m.textContent=String((e&&e.message)||e);stage.innerHTML='';stage.appendChild(m);}
  if(KIND==='stl'){
    try{
      var h=stlMount(stage,SRC,DARK);
      window.zoomIn=function(){h.zoom(1.25);};
      window.zoomOut=function(){h.zoom(0.8);};
      window.resetView=function(){h.reset();};
    }catch(e){showError(e);}
    return;
  }
  var S={k:1,x:0,y:0}, W=0, H=0, MIN=0.2, MAX=8;
  function apply(){box.style.transform='translate('+S.x+'px,'+S.y+'px) scale('+S.k+')';}
  function fit(){
    var vw=stage.clientWidth, vh=stage.clientHeight;
    if(!W||!H||!vw||!vh) return;
    var k=Math.max(MIN,Math.min(vw/W,2));
    S.k=k; S.x=(vw-W*k)/2; S.y=Math.max(8,(vh-H*k)/2); apply();
  }
  function zoomAt(px,py,f){
    var nk=Math.min(MAX,Math.max(MIN,S.k*f)); var q=nk/S.k;
    S.x=px-(px-S.x)*q; S.y=py-(py-S.y)*q; S.k=nk; apply();
  }
  window.zoomIn=function(){var r=stage.getBoundingClientRect();zoomAt(r.width/2,r.height/2,1.25);};
  window.zoomOut=function(){var r=stage.getBoundingClientRect();zoomAt(r.width/2,r.height/2,0.8);};
  window.resetView=fit;
  // 单指或鼠标拖动平移；双指捏合缩放并跟随两指中点平移
  var P={}, lastD=0, lastM=null;
  function pts(){var a=[];for(var k in P)a.push(P[k]);return a;}
  function pinchStart(){var a=pts();if(a.length>=2){lastD=Math.hypot(a[0][0]-a[1][0],a[0][1]-a[1][1]);lastM=[(a[0][0]+a[1][0])/2,(a[0][1]+a[1][1])/2];}}
  stage.addEventListener('pointerdown',function(e){
    P[e.pointerId]=[e.clientX,e.clientY];
    try{stage.setPointerCapture(e.pointerId);}catch(x){}
    pinchStart();
  });
  stage.addEventListener('pointermove',function(e){
    if(!P[e.pointerId]) return;
    var prev=P[e.pointerId]; P[e.pointerId]=[e.clientX,e.clientY];
    var a=pts();
    if(a.length===1){S.x+=e.clientX-prev[0];S.y+=e.clientY-prev[1];apply();}
    else if(a.length>=2){
      var d=Math.hypot(a[0][0]-a[1][0],a[0][1]-a[1][1]);
      var m=[(a[0][0]+a[1][0])/2,(a[0][1]+a[1][1])/2];
      if(lastD>0) zoomAt(m[0],m[1],d/lastD);
      S.x+=m[0]-lastM[0];S.y+=m[1]-lastM[1];apply();
      lastD=d; lastM=m;
    }
  });
  function up(e){if(!P[e.pointerId]) return;delete P[e.pointerId];pinchStart();}
  stage.addEventListener('pointerup',up);
  stage.addEventListener('pointercancel',up);
  stage.addEventListener('wheel',function(e){e.preventDefault();zoomAt(e.clientX,e.clientY,e.deltaY<0?1.1:0.9);},{passive:false});
  window.addEventListener('resize',fit);
  renderSvg(KIND,SRC,DARK).then(function(svg){
    box.innerHTML=svg;
    var s=box.querySelector('svg');
    if(!s) throw new Error('no svg');
    var vb=s.viewBox&&s.viewBox.baseVal;
    if(vb&&vb.width){W=vb.width;H=vb.height;}else{var bb=s.getBBox();W=bb.width;H=bb.height;}
    s.setAttribute('width',W); s.setAttribute('height',H); s.style.maxWidth='none';
    fit();
    requestAnimationFrame(fit);
  }).catch(showError);
})();
</script></body></html>
"""
}

class GhWebClient(
    private val ctx: Context,
    private val nav: Navigator,
    /** 渲染进程退出后由外层决定是否重建 WebView；为空时当前卡片留空。 */
    private val onRenderGone: (() -> Unit)? = null,
) : WebViewClient() {
    /** Chromium 渲染进程崩溃或被系统回收时，返回 true 让 App 继续运行；已退出的 WebView 不能复用，由外层释放。 */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        AppLog.error("webview", tr("网页渲染进程退出：didCrash=") + detail.didCrash())
        onRenderGone?.invoke()
        return true
    }

    /** 应用内置的前端库与 KaTeX 字体：只放行白名单里的文件，读取失败或不在白名单时返回 404。 */
    private fun builtinAsset(path: String?): WebResourceResponse {
        val rel = path?.removePrefix("/").orEmpty()
        val stream = if (BUILTIN_WEB_ASSET.matches(rel)) runCatching { ctx.assets.open("web/$rel") }.getOrNull() else null
        if (stream == null) return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
        val mime = when {
            rel.endsWith(".css") -> "text/css"
            rel.endsWith(".woff2") -> "font/woff2"
            else -> "application/javascript"
        }
        // 字体是跨域加载的，需要 CORS 头才能生效
        return WebResourceResponse(mime, "utf-8", stream).apply { responseHeaders = mapOf("Access-Control-Allow-Origin" to "*") }
    }

    /** 页面内的链接点击一律在这里接管，WebView 不会自己加载新页面（否则会在应用里打开内嵌网页）。 */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // 子框架的跳转保持系统默认，避免页面内部的 about:blank、data: 等误触发提示或页面跳转
        if (!request.isForMainFrame) return false
        val url = request.url.toString()
        openHtmlLink(ctx, nav, url)
        return true
    }

    /**
     * 接管网页里的图片请求：仓库内图片转成 raw 地址，按节点依次尝试加速，
     * 并校验返回的确实是图片；私有仓库的图片直连并带 Token。
     */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // 应用内置的前端库：映射到本地资源，见 builtinAsset
        if (request.url.host == WEB_ASSET_HOST) return builtinAsset(request.url.path)
        if (request.isForMainFrame || request.method != "GET") return null
        val url = request.url.toString()
        val target = ImageFetch.normalize(url)
        val host = request.url.host ?: return null
        val handled = target.startsWith("https://raw.githubusercontent.com/") ||
            (host == "githubusercontent.com" || host.endsWith(".githubusercontent.com")) ||
            (host == "github.com" && url.contains("/user-attachments/"))
        if (!handled) return null
        // 视频交给 WebView 自己的媒体栈：它会按 Range 边下边播，并自己处理解码与缓冲。
        // 经应用转发（WebResourceResponse）时播放器会一直停在缓冲状态，所以这里必须放行。
        val range = request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
        if (MediaProxy.wants(url, range)) return null
        val r = ImageFetch.fetch(target)
            // 图片链路拿不到（超过 16 MB 的大 GIF、镜像全部失败）时，改从源站流式转发
            ?: return mediaResponse(url, null)
                ?: WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
        // 带上长度和缓存头：WebView 能提前排版并复用已解码的图片，滚动时不必再走一遍拦截器。
        val headers = mapOf(
            "Content-Length" to r.bytes.size.toString(),
            "Cache-Control" to "max-age=600",
            "Access-Control-Allow-Origin" to "*",
        )
        return WebResourceResponse(
            r.mime ?: ImageFetch.mimeOf(target, r.bytes), null, 200, "OK", headers,
            java.io.ByteArrayInputStream(r.bytes),
        )
    }
    /** 流式转发：body 是流，WebView 读完或关闭时才断开源站连接，不会整段读进内存。 */
    private fun mediaResponse(url: String, range: String?): WebResourceResponse? {
        val s = MediaProxy.open(url, range) ?: return null
        return WebResourceResponse(s.mime ?: "application/octet-stream", null, s.code, s.reason, s.headers, s.body)
    }
}

fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/**
 * 把任意文本转成可直接写进 JS 源码的双引号字面量。除 JSON 字符串规则外，
 * 还转义 < > & 和行分隔符，避免文本里的 </script> 提前结束脚本。
 */
internal fun jsStringLiteral(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (ch in s) {
        when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch < ' ' || ch == '<' || ch == '>' || ch == '&' || ch == '\u2028' || ch == '\u2029' ->
                sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            else -> sb.append(ch)
        }
    }
    return sb.append('"').toString()
}

/** 图表与公式的公共脚本：README 页面和全屏查看器共用。这里不使用 Kotlin 字符串模板，正则里的美元符号用 \x24 表示。 */
private val DIAGRAM_JS = """
  var mmInit=false, mmSeq=0;
  function srcOf(box){
    var t=box.querySelector&&box.querySelector('.js-render-enrichment-target');
    if(t&&t.getAttribute('data-json')){try{var d=JSON.parse(t.getAttribute('data-json'));if(d&&typeof d.data==='string') return d.data;}catch(e){}}
    var pre=box.tagName==='PRE'?box:box.querySelector('pre');
    return pre?pre.textContent.replace(/\n$/,''):'';
  }
  // 返回 Promise<svg 字符串>。mermaid 用 mermaid.js；GeoJSON/TopoJSON 用 d3-geo 投影（不加载在线底图）。
  function renderSvg(kind,src,dark){
    if(kind==='mermaid'){
      if(!mmInit){mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:dark?'dark':'default'});mmInit=true;}
      return mermaid.render('mmd'+(++mmSeq),src).then(function(res){return res.svg;});
    }
    return new Promise(function(resolve){
      var data=JSON.parse(src), fc=data;
      if(kind==='topojson'){
        var feats=[], objs=data.objects||{};
        Object.keys(objs).forEach(function(k){var f=GeoRender.feature(data,objs[k]);feats=feats.concat(f.type==='FeatureCollection'?f.features:[f]);});
        fc={type:'FeatureCollection',features:feats};
      }
      var W=800,H=500;
      var path=GeoRender.geoPath(GeoRender.geoMercator().fitSize([W,H],fc));
      var d=path(fc)||'';
      var fill=dark?'#1f3b63':'#ddf4ff', stroke=dark?'#58a6ff':'#0969da';
      resolve('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 '+W+' '+H+'" style="width:100%;height:auto;display:block"><path d="'+d+'" fill="'+fill+'" fill-opacity="0.6" stroke="'+stroke+'" stroke-width="1.2" fill-rule="evenodd"/></svg>');
    });
  }
  // 数学公式：<math-renderer> 里是原始 LaTeX（$...$ 或 $$...$$），交给 KaTeX 渲染。
  function renderMath(){
    if(!window.katex) return;
    document.querySelectorAll('math-renderer').forEach(function(m){
      var t=m.textContent.trim(), display=m.classList.contains('js-display-math'), tex=t;
      if(/^\x24\x24[\s\S]*\x24\x24$/.test(t)){tex=t.slice(2,-2);display=true;}
      else if(/^\x24[\s\S]*\x24$/.test(t)){tex=t.slice(1,-1);}
      try{katex.render(tex.trim(),m,{displayMode:display,throwOnError:false});}catch(e){m.textContent=tex;}
    });
  }
  function stlMount(el,src,dark){
    if(!window.StlView) throw new Error('three.js');
    return StlView(el,src,dark);
  }
  """

/** 应用内置的前端库（见 assets/web/THIRD_PARTY_LICENSES.txt）。WebView 把这些地址映射到本地资源，不走网络。 */
internal const val WEB_ASSET_HOST = "appassets.androidplatform.net"
internal const val MERMAID_SCRIPT_URL = "https://$WEB_ASSET_HOST/mermaid/mermaid.min.js"
internal const val KATEX_JS_URL = "https://$WEB_ASSET_HOST/katex/katex.min.js"
internal const val KATEX_CSS_URL = "https://$WEB_ASSET_HOST/katex/katex.min.css"
internal const val GEO_SCRIPT_URL = "https://$WEB_ASSET_HOST/geo.min.js"
internal const val STL_SCRIPT_URL = "https://$WEB_ASSET_HOST/stl.min.js"

/** 只放行内置的前端库和 KaTeX 字体，其他路径一律 404。 */
private val BUILTIN_WEB_ASSET = Regex("(mermaid/mermaid\\.min\\.js|geo\\.min\\.js|stl\\.min\\.js|katex/katex\\.min\\.(js|css)|katex/fonts/KaTeX_[A-Za-z0-9_-]+\\.woff2)")

/** 只有页面里真的有对应内容时才加载对应的库（mermaid 约 3.5 MB，只在需要时下载到 WebView）。 */
fun wrapHtml(body: String, dark: Boolean): String {
    val tags = buildString {
        if (body.contains("math-renderer")) {
            append("<link rel='stylesheet' href='$KATEX_CSS_URL'>")
            append("<script src='$KATEX_JS_URL'></script>")
        }
        if (Regex("mermaid", RegexOption.IGNORE_CASE).containsMatchIn(body)) append("<script src='$MERMAID_SCRIPT_URL'></script>")
        if (Regex("data-type=\"(geojson|topojson)\"").containsMatchIn(body)) append("<script src='$GEO_SCRIPT_URL'></script>")
        if (body.contains("data-type=\"stl\"")) append("<script src='$STL_SCRIPT_URL'></script>")
    }
    return wrapHtmlDocument(fixCjkAutolinks(body), dark, tags)
}

/** 全角标点：GitHub 的自动链接会把紧跟在网址后面的全角标点也算进网址（如「网址，基于」）。 */
internal val CJK_LINK_CUT = Regex("[，。！？；：、）》」』】]")
private val AUTOLINK_ANCHOR = Regex("<a href=\"([^\"]*)\">(https?://[^<]*)</a>")

/**
 * 把被全角标点「吸进」网址的自动链接截断：链接只包到第一个全角标点之前，后面的文字恢复成普通文字。
 * 只处理链接文字本身就是网址的情况；其它链接原样保留。
 */
internal fun fixCjkAutolinks(html: String): String = AUTOLINK_ANCHOR.replace(html) { m ->
    val href = m.groupValues[1]
    val text = m.groupValues[2]
    val cut = CJK_LINK_CUT.find(text)?.range?.first ?: return@replace m.value
    val url = text.substring(0, cut)
    val rest = text.substring(cut)
    val encodedRest = java.net.URLEncoder.encode(rest, "UTF-8")
    val newHref = when {
        href.endsWith(encodedRest) -> href.dropLast(encodedRest.length)
        href.startsWith(url) -> url
        else -> href
    }
    "<a href=\"$newHref\">$url</a>$rest"
}

private fun wrapHtmlDocument(body: String, dark: Boolean, extraTags: String): String = """
<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>${if (dark) DARK_VARS else LIGHT_VARS}$MARKDOWN_CSS</style>$extraTags</head>
<body><div id="c" class="markdown-body">$body</div>
<script>
(function(){
  var c=document.getElementById('c');
  // 代码块：外面包一层相对定位的容器，右上角放「复制」「放大」按钮。
  // 按钮文字由 jsStringLiteral 转义后注入；代码文本只在点击时作为 Bridge 参数传给原生，不拼进脚本。
  var CB={copy:${jsStringLiteral(tr("复制"))},zoom:${jsStringLiteral(tr("放大"))},source:${jsStringLiteral(tr("源码"))},diagram:${jsStringLiteral(tr("图表"))},error:${jsStringLiteral(tr("图表无法渲染："))}};
  var tool=function(label,fn){var b=document.createElement('button');b.type='button';b.className='cb-btn';b.textContent=label;
    b.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();fn();});return b;};
  // 代码块：工具栏放在代码上方、不覆盖代码，所以单行代码的末尾也不会被挡住。
  document.querySelectorAll('pre').forEach(function(p){
    if(p.closest('section[data-type="mermaid"]')||p.getAttribute('lang')==='mermaid'||p.classList.contains('language-mermaid')) return;
    var w=document.createElement('div');w.className='cb-wrap';
    p.parentNode.insertBefore(w,p);
    var code=function(){return p.textContent.replace(/\n$/,'');};
    var t=document.createElement('div');t.className='cb-tools';
    t.appendChild(tool(CB.copy,function(){try{Bridge.copyCode(code());}catch(x){}}));
    t.appendChild(tool(CB.zoom,function(){try{Bridge.zoomCode(code());}catch(x){}}));
    w.appendChild(t); w.appendChild(p);
  });
  // 图表与公式：mermaid、GeoJSON/TopoJSON 渲染成 SVG，STL 用 three.js 渲染，数学公式用 KaTeX。
  // 源码保留在隐藏的 pre 里，「源码」按钮切换；「放大」交给原生全屏查看器。
  var DARK_MODE=${if (dark) "true" else "false"};
  ${DIAGRAM_JS}
  function mountDiagram(box){
    var kind=box.tagName==='SECTION'?box.getAttribute('data-type'):'mermaid';
    var pre=box.tagName==='PRE'?box:box.querySelector('pre');
    if(!pre) return;
    var src=srcOf(box);
    var w=document.createElement('div');w.className='cb-wrap mm-wrap';
    var t=document.createElement('div');t.className='cb-tools';
    var canvas=document.createElement('div');canvas.className='mm-canvas';
    if(kind==='stl') canvas.style.height='340px';
    var handle=null, srcMode=false;
    var toggle=null;
    var fail=function(e){var m=document.createElement('div');m.className='mm-err';m.textContent=CB.error+String((e&&e.message)||e).slice(0,300);w.insertBefore(m,canvas);canvas.style.display='none';pre.style.display='block';if(toggle) toggle.style.display='none';r();};
    var draw=function(){
      try{
        if(kind==='stl'){ if(!handle) handle=stlMount(canvas,src,DARK_MODE); }
        else renderSvg(kind,src,DARK_MODE).then(function(svg){canvas.innerHTML=svg;r();},fail);
      }catch(e){fail(e);}
    };
    toggle=tool(CB.source,function(){
      srcMode=!srcMode;
      canvas.style.display=srcMode?'none':'';
      pre.style.display=srcMode?'block':'none';
      toggle.textContent=srcMode?CB.diagram:CB.source;
      if(srcMode&&handle){handle.dispose();handle=null;}
      if(!srcMode) draw();
      r();
    });
    t.appendChild(toggle);
    t.appendChild(tool(CB.zoom,function(){try{Bridge.diagram(kind,src);}catch(x){}}));
    pre.style.display='none';
    box.parentNode.replaceChild(w,box);
    w.appendChild(t); w.appendChild(canvas); w.appendChild(pre);
    draw();
  }
  renderMath();
  var DG=[];
  document.querySelectorAll('section[data-type="mermaid"], section[data-type="geojson"], section[data-type="topojson"], section[data-type="stl"], pre[lang="mermaid"], pre.language-mermaid, pre>code.language-mermaid').forEach(function(n){
    var box=n.tagName==='SECTION'?n:(n.tagName==='CODE'?n.parentNode:n);
    if(box.tagName==='PRE'&&box.closest('section[data-type]')) return;
    if(DG.indexOf(box)<0) DG.push(box);
  });
  DG.forEach(mountDiagram);
  // ResizeObserver 每次布局都会回调；高度没变就不往原生层报，否则每次重排都打一条日志
  var last=-1;
  // 内容高度 = 容器盒子与所有子元素底边的最大值：绝对定位、负边距等溢出的图片也要算进去，否则会被裁掉一半
  function ext(){var b=c.getBoundingClientRect();var m=b.height;var all=c.getElementsByTagName('*');for(var i=0;i<all.length;i++){var y=all[i].getBoundingClientRect().bottom-b.top;if(y>m)m=y;}return m;}
  function r(){var v=Math.ceil(ext())+2;if(v===last)return;last=v;Bridge.h(v);}
  new ResizeObserver(r).observe(c); r();
  // 首次排版时图片高度还是 0，照片加载完会把内容撑高十几万像素。等到 load 事件再报一次，
  // 否则原生层会按占位高度分配 WebView，页面底部的内容就再也滚不到。
  window.addEventListener('load',r);
  setTimeout(r,1500); setTimeout(r,4000);
  var IMG=/\.(png|jpe?g|gif|webp|svg|avif|bmp)([?#]|$)/i;
  document.addEventListener('click',function(e){
    var vid=e.target.closest('video');
    if(vid){e.preventDefault();e.stopPropagation();var vs=vid.currentSrc||vid.src||((vid.querySelector('source')||{}).src)||'';if(vs){try{Bridge.video(vs);}catch(x){}}return;}
    var img=e.target.closest('img');
    if(img&&!img.closest('.tl-head')){
      var la=img.closest('a'), lh=la?(la.getAttribute('href')||''):'';
      // 图片本身或链接指向图片文件时打开查看器；徽章等链接到别处的图片照常跳转
      if(!la||IMG.test(lh)||la.href===img.src||/\/(blob|raw)\//.test(lh)&&IMG.test(lh)){
        e.preventDefault(); e.stopPropagation();
        try{Bridge.img(img.currentSrc||img.src, img.getAttribute('alt')||'');}catch(x){}
        return;
      }
    }
    var a=e.target.closest('a'); if(!a) return;
    var h=a.getAttribute('href')||'';
    if(/\.(mp4|webm|mov|m4v|ogv)([?#]|$)/i.test(h)){e.preventDefault();e.stopPropagation();try{Bridge.video(a.href);}catch(x){}return;}
    if(h.charAt(0)==='#'){
      e.preventDefault();
      var id=decodeURIComponent(h.substring(1));
      var t=document.getElementById('user-content-'+id)||document.getElementById(id)||document.getElementsByName(id)[0];
      if(t){Bridge.a(Math.round(t.getBoundingClientRect().top+window.scrollY));}
    }
  },true);
  // 视频：去掉原生控件（点击会被影子 DOM 吃掉），外层包一层自绘播放图标；点击由上面的监听交给原生播放器
  document.querySelectorAll('video').forEach(function(v){
    if(v.parentNode&&v.parentNode.classList&&v.parentNode.classList.contains('vid-wrap')) return;
    v.removeAttribute('controls'); v.setAttribute('playsinline','');
    var w=document.createElement('span'); w.className='vid-wrap';
    v.parentNode.insertBefore(w,v); w.appendChild(v);
  });
  document.querySelectorAll('img').forEach(function(i){i.addEventListener('load',r);});
})();
</script></body></html>
"""

private const val LIGHT_VARS = """
:root{--fg:#1f2328;--muted:#59636e;--accent:#0969da;--border:#d1d9e0;--border-muted:#d1d9e0b3;--subtle:#f6f8fa;
--code-bg:#818b981f;--canvas:#ffffff;--success:#1a7f37;--danger:#d1242f;--done:#8250df;--attention:#9a6700;
--pl-c:#59636e;--pl-k:#cf222e;--pl-s:#0a3069;--pl-c1:#0550ae;--pl-en:#6639ba;--pl-e:#953800;--pl-ent:#0550ae;--pl-v:#953800;--pl-smi:#1f2328;--pl-sr:#116329;
--pl-mi1-bg:#dafbe1;--pl-mi1:#116329;--pl-md-bg:#ffebe9;--pl-md:#82071e;--pl-mh:#0550ae;}"""

private const val DARK_VARS = """
:root{--fg:#f0f6fc;--muted:#9198a1;--accent:#4493f8;--border:#3d444d;--border-muted:#3d444db3;--subtle:#151b23;
--code-bg:#656c7633;--canvas:#0d1117;--success:#3fb950;--danger:#f85149;--done:#ab7df8;--attention:#d29922;
--pl-c:#9198a1;--pl-k:#ff7b72;--pl-s:#a5d6ff;--pl-c1:#79c0ff;--pl-en:#d2a8ff;--pl-e:#ffa657;--pl-ent:#7ee787;--pl-v:#ffa657;--pl-smi:#f0f6fc;--pl-sr:#7ee787;
--pl-mi1-bg:#0f5323;--pl-mi1:#aff5b4;--pl-md-bg:#67060c;--pl-md:#ffdcd7;--pl-mh:#1f6feb;}"""

private const val MARKDOWN_CSS = """
html,body{margin:0;padding:0;background:transparent;}
.markdown-body{color:var(--fg);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans",Helvetica,Arial,sans-serif;
font-size:15px;line-height:1.6;word-wrap:break-word;padding:0 16px 8px 16px;display:flow-root;}
.markdown-body>*:first-child,.markdown-body article>*:first-child,.markdown-body .markdown-heading:first-child>*{margin-top:0!important;}
a{color:var(--accent);text-decoration:none;}
p,blockquote,ul,ol,dl,table,pre,details{margin-top:0;margin-bottom:14px;}
h1,h2,h3,h4,h5,h6{margin-top:22px;margin-bottom:14px;font-weight:600;line-height:1.25;}
h1{font-size:1.75em;padding-bottom:.3em;border-bottom:1px solid var(--border-muted);}
h2{font-size:1.4em;padding-bottom:.3em;border-bottom:1px solid var(--border-muted);}
h3{font-size:1.2em;}h4{font-size:1em;}h5{font-size:.9em;}h6{font-size:.85em;color:var(--muted);}
.markdown-heading{position:relative;}
.anchor,.octicon-link{display:none!important;}
img{max-width:100%;box-sizing:border-box;background:transparent;}
/* 视频按容器宽度缩放；否则会按原始分辨率渲染，右侧被裁掉 */
video{max-width:100%;height:auto;box-sizing:border-box;background:#000;display:block;}
/* 视频外面套一层，用自绘的播放图标代替原生控件：原生控件在影子 DOM 里，点击到不了页面脚本 */
.vid-wrap{position:relative;display:block;max-width:100%;line-height:0;}
.vid-wrap::after{content:'';position:absolute;left:50%;top:50%;width:64px;height:64px;margin:-32px 0 0 -32px;border-radius:50%;background:rgba(0,0,0,.55);pointer-events:none;}
.vid-wrap::before{content:'';position:absolute;left:50%;top:50%;margin:-14px 0 0 -9px;border-left:24px solid #fff;border-top:14px solid transparent;border-bottom:14px solid transparent;z-index:1;pointer-events:none;}
iframe{max-width:100%;}
a img{display:inline-block;}
p img[align=right]{margin-left:8px;}
hr{height:.25em;padding:0;margin:24px 0;background:var(--border);border:0;}
blockquote{margin-left:0;margin-right:0;padding:0 1em;color:var(--muted);border-left:.25em solid var(--border);}
ul,ol{padding-left:2em;}li+li{margin-top:.25em;}
code,tt,kbd,pre,samp{font-family:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,"Liberation Mono",monospace;}
code,tt{padding:.2em .4em;margin:0;font-size:85%;white-space:break-spaces;background:var(--code-bg);border-radius:6px;}
pre{padding:14px;overflow:auto;font-size:85%;line-height:1.45;background:var(--subtle);border-radius:6px;word-wrap:normal;}
pre code,pre tt{padding:0;margin:0;background:transparent;border:0;white-space:pre;font-size:100%;}
.highlight{margin-bottom:14px;}.highlight pre{margin-bottom:0;}
.cb-wrap{margin:0 0 14px;}.cb-wrap>pre{margin:0;}.mm-canvas{overflow-x:auto;text-align:center;padding:8px 0;}.mm-canvas svg{max-width:100%;height:auto;}.mm-err{color:#d1242f;font-size:12px;padding:6px 0;}
.cb-tools{display:flex;justify-content:flex-end;gap:4px;padding:0 0 6px;}
.cb-btn{font:500 12px/1.2 -apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans",Helvetica,Arial,sans-serif;padding:3px 8px;margin:0;border:1px solid var(--border);border-radius:6px;background:var(--canvas);color:var(--muted);cursor:pointer;}
.cb-btn:active{color:var(--fg);background:var(--subtle);}
kbd{display:inline-block;padding:3px 5px;font-size:11px;line-height:10px;color:var(--fg);vertical-align:middle;background:var(--subtle);border:1px solid var(--border);border-bottom-color:var(--border);border-radius:6px;box-shadow:inset 0 -1px 0 var(--border);}
table{border-spacing:0;border-collapse:collapse;display:block;width:max-content;max-width:100%;overflow:auto;}
table th{font-weight:600;}
table th,table td{padding:6px 13px;border:1px solid var(--border);}
table tr{background:var(--canvas);border-top:1px solid var(--border-muted);}
table tr:nth-child(2n){background:var(--subtle);}
details summary{cursor:pointer;}
.task-list-item{list-style-type:none;}.task-list-item input{margin:0 .3em .25em -1.4em;vertical-align:middle;}
.contains-task-list{padding-left:2em;}
.markdown-alert{padding:8px 16px;margin-bottom:14px;border-left:.25em solid var(--border);}
.markdown-alert>:last-child{margin-bottom:0;}
.markdown-alert-title{display:flex;align-items:center;font-weight:500;line-height:1;margin-bottom:6px!important;}
.markdown-alert-title svg{margin-right:8px;fill:currentColor;}
.markdown-alert-note{border-left-color:var(--accent);}.markdown-alert-note .markdown-alert-title{color:var(--accent);}
.markdown-alert-tip{border-left-color:var(--success);}.markdown-alert-tip .markdown-alert-title{color:var(--success);}
.markdown-alert-important{border-left-color:var(--done);}.markdown-alert-important .markdown-alert-title{color:var(--done);}
.markdown-alert-warning{border-left-color:var(--attention);}.markdown-alert-warning .markdown-alert-title{color:var(--attention);}
.markdown-alert-caution{border-left-color:var(--danger);}.markdown-alert-caution .markdown-alert-title{color:var(--danger);}
.footnotes{font-size:12px;color:var(--muted);border-top:1px solid var(--border);}
.pl-c{color:var(--pl-c);}.pl-c1,.pl-s .pl-v{color:var(--pl-c1);}.pl-e,.pl-en{color:var(--pl-en);}
.pl-smi,.pl-s .pl-s1{color:var(--pl-smi);}.pl-ent{color:var(--pl-ent);}.pl-k{color:var(--pl-k);}
.pl-s,.pl-pds,.pl-s .pl-pse .pl-s1,.pl-sr,.pl-sr .pl-cce,.pl-sr .pl-sre,.pl-sr .pl-sra{color:var(--pl-s);}
.pl-v,.pl-smw{color:var(--pl-v);}.pl-mh,.pl-mh .pl-en,.pl-ms{font-weight:bold;color:var(--pl-mh);}
.pl-mi1{color:var(--pl-mi1);background:var(--pl-mi1-bg);}.pl-md{color:var(--pl-md);background:var(--pl-md-bg);}
.pl-mb{font-weight:bold;}.pl-mi{font-style:italic;}.pl-sr .pl-cce{font-weight:bold;color:var(--pl-sr);}
.user-mention,.team-mention{font-weight:600;color:var(--fg);}
.emoji{height:1.2em;width:1.2em;vertical-align:middle;}
g-emoji{font-size:1.1em;vertical-align:-0.05em;}
.octicon{fill:currentColor;vertical-align:text-bottom;}
.snippet-clipboard-content .zeroclipboard-container,.zeroclipboard-container{display:none;}
.container-lg{max-width:none!important;padding:0!important;}
/* 讨论串 */
.tl{margin:0 -16px;}
.tl-item{border:1px solid var(--border);border-radius:8px;margin:0 12px 14px 12px;overflow:hidden;background:var(--canvas);}
.tl-head{display:flex;align-items:center;gap:8px;padding:8px 12px;background:var(--subtle);border-bottom:1px solid var(--border);font-size:13px;color:var(--muted);}
.tl-head img{width:22px;height:22px;border-radius:50%;}
.tl-head b{color:var(--fg);}
.tl-badge{margin-left:auto;border:1px solid var(--border);border-radius:2em;padding:0 7px;font-size:11px;}
.tl-body{padding:12px;}
.tl-body>*:last-child{margin-bottom:0;}
.tl-empty{color:var(--muted);font-style:italic;}
.tl-event{display:flex;align-items:center;gap:8px;margin:0 12px 14px 28px;font-size:13px;color:var(--muted);}
.tl-event .dot{width:10px;height:10px;border-radius:50%;background:var(--border);}
.tl-event.approved .dot{background:var(--success);}.tl-event.changes .dot{background:var(--danger);}
"""
