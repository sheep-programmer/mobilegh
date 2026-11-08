package com.mobilegh.ui.screens

import com.mobilegh.ui.components.dialogFrame
import com.mobilegh.ui.components.ModalDialogProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.BuildConfig
import com.mobilegh.data.Update
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.launch
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** 启动检查发现新版本时自动弹出更新提示（放在应用根部；同一版本只弹一次） */
@Composable
fun UpdatePromptHost() {
    val info = Update.available ?: return
    var show by remember(info.version) { mutableStateOf(Update.shouldPrompt(info)) }
    if (!show) return
    LaunchedEffect(info.version) { Update.markPrompted(info) }
    UpdateDialog(onDismiss = { show = false }, onSkip = { Update.skip(info); show = false })
}

@Composable
fun UpdateDialog(onDismiss: () -> Unit, onSkip: (() -> Unit)? = null) {
    val g = Gh.c
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        // 自动弹出的更新提示要明确选择（下载 / 稍后 / 跳过）：点框外不关闭；手动打开的更新页保持原样
        properties = if (onSkip != null) ModalDialogProperties else androidx.compose.ui.window.DialogProperties(),
        modifier = Modifier.dialogFrame(),
        containerColor = g.canvas,
        shape = RoundedCornerShape(12.dp),
        title = { Text(tr("在线更新"), color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(trf("当前版本 v{0} · 设备架构 {1}", BuildConfig.VERSION_NAME, Update.abi), fontSize = 13.sp, color = g.fgMuted)
                Spacer(Modifier.height(12.dp))
                val info = Update.available
                when {
                    Update.checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = g.accent, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(tr("正在读取最新 Release…"), color = g.fg, fontSize = 14.sp)
                        }
                    }
                    info != null -> {
                        Text(trf("发现新版本 v{0}", info.version), color = g.fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(info.assetName + " · " + fmtSize(info.size), fontSize = 12.sp, color = g.fgMuted)
                        info.notes?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it.take(600), fontSize = 12.sp, lineHeight = 18.sp, color = g.fgMuted, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    else -> {
                        Text(Update.error ?: tr("已是最新版本"), color = if (Update.error != null) g.danger else g.fg, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            val info = Update.available
            if (info != null) {
                TextButton({
                    onDismiss()
                    Update.download(info)
                    ctx.toast(tr("正在下载，完成后在下载记录中安装"))
                }) { Text(tr("下载更新"), color = g.accent, fontWeight = FontWeight.SemiBold) }
            } else {
                TextButton({ scope.launch { Update.check() } }, enabled = !Update.checking) {
                    Text(tr("重新检查"), color = if (Update.checking) g.fgMuted else g.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            Row {
                // 自动弹出时可以跳过这个版本：之后不再自动提示，设置里仍能手动更新
                if (onSkip != null && Update.available != null) TextButton(onSkip) { Text(tr("跳过此版本"), color = g.fgMuted) }
                TextButton(onDismiss) { Text(if (onSkip != null) tr("稍后") else tr("关闭"), color = g.fgMuted) }
            }
        },
    )
    LaunchedEffect(Unit) { if (Update.available == null && !Update.checking) Update.check() }
}
