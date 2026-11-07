package com.mobilegh.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    fun show(url: String) {
        if (url.startsWith("https://") || url.startsWith("http://")) this.url = url
    }

    fun close() {
        url = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkMenuHost() {
    val url = LinkMenu.url ?: return
    val ctx = LocalContext.current
    val g = Gh.c
    ModalBottomSheet(onDismissRequest = { LinkMenu.close() }, containerColor = g.canvas) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                url,
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                fontSize = 12.sp, color = g.fgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            MenuGroup {
                MenuRow(R.drawable.oc_copy, tr("复制链接")) {
                    LinkMenu.close()
                    ctx.copy(url, tr("已复制链接"))
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
