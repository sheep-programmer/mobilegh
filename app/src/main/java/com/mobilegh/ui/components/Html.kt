package com.mobilegh.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mobilegh.data.AppLog
import com.mobilegh.data.ImageFetch
import com.mobilegh.data.MediaProxy
import com.mobilegh.nav.Links
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr

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

/**
 * 渲染 GitHub 返回的 HTML（README、Issue、Release 等），使用 GitHub 的 markdown 样式。
 * WebView 高度随内容自适应，放在可滚动容器中使用。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlView(
    html: String,
    modifier: Modifier = Modifier,
    baseUrl: String = "https://github.com/",
    onAnchor: ((Int) -> Unit)? = null,
) {
    val nav = LocalNav.current
    val dark = Gh.c.dark
    // Compose 的约束以物理像素编码；可用高度取决于组件宽度，需等测量后才知道。
    val density = LocalDensity.current.density
    val textZoom = (LocalDensity.current.fontScale * 100).toInt().coerceAtLeast(1)
    var measuredWidth by remember { mutableIntStateOf(0) }
    val maxHeight = remember(density, measuredWidth) { htmlMaxHeightDp(density, measuredWidth) }
    val heightLimit by rememberUpdatedState(maxHeight)
    var height by remember { mutableIntStateOf(0) }
    val anchor by rememberUpdatedState(onAnchor)
    val doc = remember(html, dark) { wrapHtml(html, dark) }
    // 在 AndroidView 释放已脱离视图树的实例后销毁，避免提前 destroy 仍附着的 WebView。
    val webRef = remember { arrayOfNulls<WebView>(1) }
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                webRef[0] = this
                setBackgroundColor(0)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.textZoom = textZoom
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
                        height = v.coerceIn(24, heightLimit)
                    }

                    @JavascriptInterface
                    fun a(y: Int) = post { if (webRef[0] === this@apply) anchor?.invoke(y) }

                    /** 点击网页里的图片：打开全屏查看器 */
                    @JavascriptInterface
                    fun img(src: String, alt: String) = post { if (webRef[0] === this@apply) ImageViewer.open(src, alt) }

                    /** 点击视频：交给原生全屏播放器（倍速、拖动、保存），WebView 自带的播放器做不到 */
                    @JavascriptInterface
                    fun video(src: String) = post { if (webRef[0] === this@apply) VideoViewer.open(src) }
                }, "Bridge")
                webViewClient = GhWebClient(c, nav)
            }
        },
        onRelease = { wv ->
            webRef[0] = null
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
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { if (it.width != measuredWidth) measuredWidth = it.width }
            .height(height.coerceIn(24, maxHeight).dp),
    )
}

class GhWebClient(private val ctx: Context, private val nav: Navigator) : WebViewClient() {
    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
        // Chromium 的网页渲染进程崩溃时让 App 继续运行；当前卡片留空，用户可以返回或刷新。
        AppLog.error("webview", tr("网页渲染进程退出：didCrash=") + detail.didCrash())
        runCatching { view.stopLoading(); view.destroy() }
        return true
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        Links.route(url)?.let { nav.pushLink(it); return true }
        if (url.startsWith("http")) ctx.openBrowser(url)
        return true
    }

    /**
     * 接管网页里的图片请求：仓库内图片转成 raw 地址，按节点依次尝试加速，
     * 并校验返回的确实是图片；私有仓库的图片直连并带 Token。
     */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame || request.method != "GET") return null
        val url = request.url.toString()
        val target = ImageFetch.normalize(url)
        val host = request.url.host ?: return null
        val handled = target.startsWith("https://raw.githubusercontent.com/") ||
            (host == "githubusercontent.com" || host.endsWith(".githubusercontent.com")) ||
            (host == "github.com" && url.contains("/user-attachments/"))
        if (!handled) return null
        // 视频和播放器的 Range 请求不进图片缓存：直接从源站流式转发，才能边下边播、拖动进度
        val range = request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
        if (MediaProxy.wants(url, range)) return mediaResponse(url, range)
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

fun wrapHtml(body: String, dark: Boolean): String = """
<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>${if (dark) DARK_VARS else LIGHT_VARS}$MARKDOWN_CSS</style></head>
<body><div id="c" class="markdown-body">$body</div>
<script>
(function(){
  var c=document.getElementById('c');
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
