package com.mobilegh.ui.components

import android.net.Uri
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mobilegh.R
import com.mobilegh.data.VideoRoute
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    VideoDialog(onClose = VideoViewer::close) { VideoViewerContent(src) }
}

@Composable
private fun VideoDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        content = content,
    )
}

private val SPEEDS = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

/** [onPlayer] 只给测试用：拿到播放器后可以读播放状态，正常使用不传。 */
@Composable
internal fun VideoViewerContent(src: String, onPlayer: (ExoPlayer) -> Unit = {}) {
    val player = rememberVideoPlayer(src)
    DisposableEffect(player) {
        onPlayer(player)
        onDispose { }
    }
    FullVideo(src, player, VideoViewer::close)
}

@Composable
private fun rememberVideoPlayer(src: String): ExoPlayer {
    val context = LocalContext.current
    val player = remember(src, context) {
        ExoPlayer.Builder(context).setAudioAttributes(AudioAttributes.DEFAULT, true).build()
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    LaunchedEffect(player) {
        val playable = try {
            VideoRoute.pick(src)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            src
        }
        withContext(Dispatchers.Main.immediate) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(playable)))
            player.prepare()
            player.playWhenReady = true
        }
    }
    return player
}

@Composable
private fun VideoSurface(player: ExoPlayer, modifier: Modifier = Modifier) {
    var failed by remember(player) { mutableStateOf(player.playerError != null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerErrorChanged(error: PlaybackException?) { failed = error != null }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context -> PlayerView(context).apply { useController = true } },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.matchParentSize(),
        )
        if (failed) GhButton(tr("重试"), icon = R.drawable.oc_sync) {
            player.prepare()
            player.play()
        }
    }
}

@Composable
private fun FullVideo(src: String, player: ExoPlayer, onClose: () -> Unit) {
    val g = Gh.c
    val ctx = LocalContext.current
    val fileName = remember(src) { src.substringBefore('?').substringAfterLast('/').ifBlank { "video.mp4" } }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // 全屏对话框不会自动避开系统栏：顶部让出状态栏，底部让出导航栏，否则关闭按钮会被时间和图标挡住
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OcButton(R.drawable.oc_x, onClose, Color.White, description = tr("关闭"))
            Text(
                fileName, Modifier.weight(1f).padding(horizontal = 6.dp),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            OcButton(R.drawable.oc_download, { ctx.downloadFile(src, fileName, isPrivate = false, mimeType = "video/mp4") }, Color.White, description = tr("保存到本机"))
        }
        VideoSurface(player, Modifier.fillMaxWidth().weight(1f))
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

@Composable
internal fun InlineVideo(src: String, modifier: Modifier, preview: @Composable () -> Unit) = key(src) {
    var started by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val player = if (started) rememberVideoPlayer(src) else null
    InlineMediaFrame(modifier, started, expanded, { started = true }, {
        started = true
        expanded = true
    }, preview) {
        if (player != null) VideoSurface(player, Modifier.fillMaxSize())
    }
    if (expanded && player != null) VideoDialog({ expanded = false }) {
        FullVideo(src, player) { expanded = false }
    }
}

@Composable
private fun InlineMediaFrame(
    modifier: Modifier,
    started: Boolean,
    expanded: Boolean,
    onPlay: () -> Unit,
    onExpand: () -> Unit,
    preview: @Composable () -> Unit,
    surface: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (expanded) return@Box
        if (started) {
            surface()
        } else {
            Box(Modifier.matchParentSize()) { preview() }
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                    .clickable(onClickLabel = tr("播放"), role = Role.Button, onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Oc(R.drawable.oc_play, Color.White, 24.dp, contentDescription = tr("播放"))
            }
        }
        Row(
            Modifier.align(if (started) Alignment.TopEnd else Alignment.BottomEnd).padding(6.dp)
                .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.64f))
                .clickable(role = Role.Button, onClick = onExpand)
                .heightIn(min = 36.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oc(R.drawable.oc_screen_full, Color.White, 14.dp)
            Spacer(Modifier.width(4.dp))
            Text(tr("放大播放"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
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
        "<script>var player;function onYouTubeIframeAPIReady(){player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$id'," +
        "playerVars:{playsinline:1,fs:0,autoplay:1,rel:0},events:{onError:function(e){Bridge.failed(e.data)}}});}</script>" +
        "<script src=\"https://www.youtube.com/iframe_api\" onerror=\"Bridge.failed(0)\"></script>" +
        "</body></html>"

/** 播放器报错的回调：JavaScript 线程调用，由调用方切回主线程 */
private class YouTubeBridge(private val onFailed: (Int) -> Unit) {
    @android.webkit.JavascriptInterface
    fun failed(code: Int) = onFailed(code)
}

@Composable
internal fun InlineYouTube(id: String, title: String, modifier: Modifier, preview: @Composable () -> Unit) = key(id) {
    var started by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val failed = remember { mutableStateOf(false) }
    val context = LocalContext.current
    val web = if (started) remember(context) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            addJavascriptInterface(YouTubeBridge { post { failed.value = true } }, "Bridge")
            loadDataWithBaseURL("https://www.youtube.com/", youTubeEmbedHtml(id), "text/html", "utf-8", null)
        }
    } else null
    if (web != null) {
        DisposableEffect(web) {
            onDispose {
                (web.parent as? ViewGroup)?.removeView(web)
                web.stopLoading()
                web.destroy()
            }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
            web.evaluateJavascript("if(window.player && player.pauseVideo)player.pauseVideo()", null)
        }
    }
    val surface: @Composable () -> Unit = {
        if (web != null) YouTubeSurface(web, id, title, failed.value)
    }
    InlineMediaFrame(modifier, started, expanded, { started = true }, {
        started = true
        expanded = true
    }, preview, surface)
    if (expanded && web != null) VideoDialog({ expanded = false }) {
        Column(Modifier.fillMaxSize().background(Color.Black).navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OcButton(R.drawable.oc_x, { expanded = false }, Color.White, description = tr("关闭"))
                Text(
                    title.ifBlank { "YouTube" }, Modifier.weight(1f).padding(horizontal = 6.dp),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                OcButton(R.drawable.oc_link_external, { ExternalLinkDialog.show("https://youtu.be/$id", title) }, Color.White, description = tr("在浏览器打开"))
            }
            Box(Modifier.fillMaxWidth().weight(1f)) { surface() }
        }
    }
}

@Composable
private fun YouTubeSurface(web: WebView, id: String, title: String, failed: Boolean) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { FrameLayout(it) },
            update = { frame ->
                if (web.parent !== frame) {
                    (web.parent as? ViewGroup)?.removeView(web)
                    frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            },
            onRelease = { it.removeAllViews() },
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) YouTubeUnavailable(id, title)
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
