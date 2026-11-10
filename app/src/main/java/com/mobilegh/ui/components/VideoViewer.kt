package com.mobilegh.ui.components

import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mobilegh.R
import com.mobilegh.data.VideoRoute
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 点击 README / Issue 里的视频后打开的全屏播放器。
 * 用 ExoPlayer 直接按 Range 流式播放，支持拖动进度、倍速、保存到本机；不依赖网页里的 <video>。
 */
object VideoViewer {
    var url by mutableStateOf<String?>(null)
        private set

    fun open(src: String) {
        if (src.isBlank() || src.startsWith("data:")) return
        url = src
    }

    fun close() { url = null }
}

@Composable
fun VideoViewerHost() {
    val src = VideoViewer.url ?: return
    Dialog(
        onDismissRequest = { VideoViewer.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        VideoViewerContent(src)
    }
}

private val SPEEDS = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

/** [onPlayer] 只给测试用：拿到播放器后可以读播放状态，正常使用不传。 */
@Composable
internal fun VideoViewerContent(src: String, onPlayer: (ExoPlayer) -> Unit = {}) {
    val g = Gh.c
    val ctx = LocalContext.current
    val fileName = remember(src) { src.substringBefore('?').substringAfterLast('/').ifBlank { "video.mp4" } }
    var speed by remember { mutableFloatStateOf(1f) }
    // 播放器跟随对话框生命周期：关闭时释放，避免后台继续占用解码器和网络
    val player = remember(src) { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(player) {
        onPlayer(player)
        onDispose { player.release() }
    }
    // 先选线路（加速节点或原地址）再开始加载；选择出错时直接用原地址，不影响播放
    LaunchedEffect(player) {
        val playable = runCatching { VideoRoute.pick(src) }.getOrDefault(src)
        // ExoPlayer 只能在主线程访问；选线路时可能切到了后台线程，这里切回主线程再操作播放器
        withContext(Dispatchers.Main) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(playable)))
            player.prepare()
            player.playWhenReady = true
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // 全屏对话框不会自动避开系统栏：顶部让出状态栏，底部让出导航栏，否则关闭按钮会被时间和图标挡住
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OcButton(R.drawable.oc_x, { VideoViewer.close() }, Color.White, description = tr("关闭"))
            Text(
                fileName, Modifier.weight(1f).padding(horizontal = 6.dp),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            OcButton(R.drawable.oc_download, { ctx.downloadFile(src, fileName, isPrivate = false, mimeType = "video/mp4") }, Color.White, description = tr("保存到本机"))
        }
        AndroidView(
            factory = { c -> PlayerView(c).apply { this.player = player; useController = true } },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        // 倍速：ExoPlayer 的默认控制条没有这个按钮，所以单独放一行
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SPEEDS.forEach { s ->
                val selected = s == speed
                Box(
                    Modifier.padding(horizontal = 4.dp).size(width = 52.dp, height = 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) g.accent else Color.White.copy(alpha = 0.15f))
                        .clickable(role = Role.Button) {
                            speed = s
                            player.playbackParameters = PlaybackParameters(s)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        speedLabel(s),
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** 1x、1.25x 这类写法：整数不带小数点 */
private fun speedLabel(s: Float): String = (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "x"

/**
 * YouTube 视频的应用内播放器。YouTube 的视频地址不是直链，不能交给 ExoPlayer，
 * 这里用网页嵌入播放器（youtube-nocookie）全屏显示；有问题时可以用「在浏览器打开」。
 */
object YouTubeViewer {
    var id by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set

    fun open(id: String, title: String = "") {
        this.id = id
        this.title = title
    }

    fun close() { id = null }
}

@Composable
fun YouTubeViewerHost() {
    val id = YouTubeViewer.id ?: return
    Dialog(
        onDismissRequest = { YouTubeViewer.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        YouTubeViewerContent(id, YouTubeViewer.title)
    }
}

/**
 * 用 YouTube 的 IFrame API 播放：播放器报错（如视频所有者禁止嵌入，错误 101 / 150 / 152）时回调原生层，
 * 显示说明并提供「在浏览器打开」。嵌入页的基地址设为 youtube.com，播放器据此判断来源，避免来源被拒绝。
 */
private fun youTubeEmbedHtml(id: String): String =
    "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}" +
        "#p{position:absolute;left:0;top:0;width:100%;height:100%}</style></head><body>" +
        "<div id=\"p\"></div>" +
        "<script src=\"https://www.youtube.com/iframe_api\"></script>" +
        "<script>function onYouTubeIframeAPIReady(){new YT.Player('p',{width:'100%',height:'100%',videoId:'$id'," +
        "playerVars:{playsinline:1,rel:0},events:{onError:function(e){Bridge.failed(e.data)}}});}</script>" +
        "</body></html>"

/** 播放器报错的回调：JavaScript 线程调用，由调用方切回主线程 */
private class YouTubeBridge(private val onFailed: (Int) -> Unit) {
    @android.webkit.JavascriptInterface
    fun failed(code: Int) = onFailed(code)
}

@Composable
private fun YouTubeViewerContent(id: String, title: String) {
    val failed = remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OcButton(R.drawable.oc_x, { YouTubeViewer.close() }, Color.White, description = tr("关闭"))
            Text(
                title.ifBlank { "YouTube" }, Modifier.weight(1f).padding(horizontal = 6.dp),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            OcButton(R.drawable.oc_link_external, { ExternalLinkDialog.show("https://youtu.be/$id", title) }, Color.White, description = tr("在浏览器打开"))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
        AndroidView(
            factory = { c ->
                val frame = FrameLayout(c)
                val web = WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                }
                web.addJavascriptInterface(YouTubeBridge { web.post { failed.value = true } }, "Bridge")
                frame.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                // 全屏按钮：播放器请求全屏时，把它的视图盖在网页之上；退出时收起
                web.webChromeClient = object : WebChromeClient() {
                    private var custom: View? = null
                    private var callback: WebChromeClient.CustomViewCallback? = null

                    override fun onShowCustomView(view: View, cb: WebChromeClient.CustomViewCallback) {
                        if (custom != null) { cb.onCustomViewHidden(); return }
                        custom = view
                        callback = cb
                        frame.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        web.visibility = View.GONE
                    }

                    override fun onHideCustomView() {
                        custom?.let { frame.removeView(it) }
                        custom = null
                        callback?.onCustomViewHidden()
                        callback = null
                        web.visibility = View.VISIBLE
                    }
                }
                web.loadDataWithBaseURL("https://www.youtube.com/", youTubeEmbedHtml(id), "text/html", "utf-8", null)
                frame
            },
            // 先从容器里摘下网页再销毁，不在仍附着于视图树时销毁
            onRelease = { frame ->
                (frame.getChildAt(0) as? WebView)?.let { web ->
                    frame.removeAllViews()
                    web.stopLoading()
                    web.destroy()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (failed.value) YouTubeUnavailable(id, title)
        }
    }
}

/** 视频所有者禁止了网页嵌入（或播放器报错）：说明原因，并提供在浏览器打开的入口 */
@Composable
private fun YouTubeUnavailable(id: String, title: String) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr("这个视频无法在应用内播放"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(tr("视频所有者可能禁止了网页嵌入，可以到 YouTube 上观看。"), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        GhButton(tr("在浏览器打开"), primary = true, icon = R.drawable.oc_link_external) {
            ExternalLinkDialog.show("https://youtu.be/$id", title)
        }
    }
}
