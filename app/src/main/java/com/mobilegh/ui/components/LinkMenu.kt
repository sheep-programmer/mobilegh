package com.mobilegh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh

/**
 * 长按正文里的链接时弹出的操作：复制链接、分享、在浏览器打开。
 *
 * 全局只有一个实例，由 AppRoot 挂载的 [LinkMenuHost] 显示；WebView 正文和 Compose 文字里的链接都从这里进入。
 */
object LinkMenu {
    var url by mutableStateOf<String?>(null)
        private set

    /** 链接在正文里显示的文字；页内锚点（#标题）没有网址，复制和分享都用它 */
    var text by mutableStateOf<String?>(null)
        private set

    fun show(url: String, text: String? = null) {
        val target = url.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return
        this.url = target
        this.text = text?.takeIf { it.isNotBlank() }
    }

    fun close() {
        url = null
        text = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkMenuHost() {
    val url = LinkMenu.url ?: return
    val label = LinkMenu.text
    val ctx = LocalContext.current
    val g = Gh.c
    val isDownloadable = remember(url) { com.mobilegh.data.Downloads.isDownloadableUrl(url) }
    ModalBottomSheet(onDismissRequest = { LinkMenu.close() }, containerColor = g.canvas) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // 上面是链接文字（长按时选中的那段），下面是真实地址；只有地址时只显示一行
                if (label != null && label != url) Text(
                    label, fontSize = 14.sp, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    url, fontSize = 12.sp, color = g.fgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            MenuGroup {
                if (isDownloadable) {
                    MenuRow(R.drawable.oc_package, tr("查看文件信息")) {
                        LinkMenu.close()
                        com.mobilegh.data.Downloads.inspectUrl(url, label)
                    }
                }
                MenuRow(R.drawable.oc_copy, tr("复制链接")) {
                    LinkMenu.close()
                    ctx.copy(url, tr("已复制链接"))
                }
                if (label != null && label != url) {
                    MenuRow(R.drawable.oc_copy, tr("复制链接文字")) {
                        LinkMenu.close()
                        ctx.copy(label, tr("已复制链接文字"))
                    }
                }
                MenuRow(R.drawable.oc_share_android, tr("分享")) {
                    LinkMenu.close()
                    ctx.share(url)
                }
                MenuRow(R.drawable.oc_link_external, tr("在浏览器打开")) {
                    LinkMenu.close()
                    ctx.openBrowser(url)
                }
            }
        }
    }
}

/**
 * 单击外部链接时的确认弹窗：防止阅读或滑动时误触直接切走应用，展示目标链接与标题，点击「打开」才调起外部浏览器。
 */
object ExternalLinkDialog {
    data class Request(val url: String, val label: String? = null)

    var current by mutableStateOf<Request?>(null)
        private set

    fun show(url: String, label: String? = null) {
        val target = url.trim()
        if (target.startsWith("https://", ignoreCase = true) || target.startsWith("http://", ignoreCase = true)) {
            current = Request(target, label?.trim()?.takeIf { it.isNotBlank() && it != target })
        }
    }

    fun dismiss() {
        current = null
    }
}

@Composable
fun ExternalLinkDialogHost() {
    val req = ExternalLinkDialog.current ?: return
    val ctx = LocalContext.current
    val g = Gh.c
    GhDialog(
        title = tr("用浏览器打开？"),
        onDismiss = { ExternalLinkDialog.dismiss() },
        confirm = tr("打开"),
        onConfirm = {
            val target = req.url
            ExternalLinkDialog.dismiss()
            ctx.openBrowser(target)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                tr("即将离开应用并在系统浏览器中打开以下链接："),
                fontSize = 13.sp,
                color = g.fgMuted,
            )
            if (req.label != null) {
                Text(
                    req.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = g.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(g.canvasSubtle)
                    .border(1.dp, g.borderMuted, RoundedCornerShape(8.dp))
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    req.url,
                    Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = g.accent,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                OcButton(R.drawable.oc_copy, { ctx.copy(req.url, tr("已复制链接")) }, g.fgMuted)
            }
        }
    }
}
