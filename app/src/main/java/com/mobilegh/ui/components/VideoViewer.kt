package com.mobilegh.ui.components

import android.net.Uri
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
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr

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
    val player = remember(src) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(src)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onPlayer(player)
        onDispose { player.release() }
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
