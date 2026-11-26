package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.BuildConfig
import com.mobilegh.data.Update
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.ReleaseNotes
import com.mobilegh.ui.components.dialogFrame
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.components.verticalScrollWithBar
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialog(onDismiss: () -> Unit, onSkip: (() -> Unit)? = null) {
    val g = Gh.c
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()
    val info = Update.available
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = onSkip == null),
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight).dialogFrame().background(g.canvas),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(tr("在线更新"), color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            key(info?.tag) {
                val scroll = rememberScrollState()
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScrollWithBar(scroll).padding(vertical = 10.dp)) {
                    Text(trf("当前版本 v{0} · 设备架构 {1}", BuildConfig.VERSION_NAME, Update.abi), fontSize = 13.sp, color = g.fgMuted)
                    Spacer(Modifier.height(8.dp))
                    when {
                        Update.checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = g.accent, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(tr("正在读取最新 Release…"), color = g.fg, fontSize = 14.sp)
                        }
                        info != null -> {
                            Text(trf("发现新版本 v{0}", info.version), color = g.fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(info.assetName + " · " + fmtSize(info.size), fontSize = 12.sp, color = g.fgMuted)
                            Spacer(Modifier.height(12.dp))
                            Text(tr("发布说明"), color = g.fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            if (info.notes.isNullOrBlank() && info.notesHtml.isNullOrBlank()) {
                                Text(tr("此版本未提供发布说明"), Modifier.padding(top = 6.dp), color = g.fgMuted, fontSize = 13.sp)
                            } else ReleaseNotes(info.notes, info.notesHtml, info.releaseUrl, Modifier.padding(top = 6.dp, end = 8.dp))
                        }
                        else -> Text(Update.error ?: tr("已是最新版本"), color = if (Update.error != null) g.danger else g.fg, fontSize = 14.sp)
                    }
                }
            }
            HDivider()
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            ) {
                if (onSkip != null && info != null) GhButton(tr("跳过此版本"), onClick = onSkip)
                GhButton(if (onSkip != null) tr("稍后") else tr("关闭"), onClick = onDismiss)
                if (info != null && !Update.checking) {
                    val existing = com.mobilegh.data.Downloads.tasks.firstOrNull {
                        (it.isAppUpdate || Update.isPendingUpdate(it.filename)) && it.filename == info.assetName
                    }
                    when {
                        existing != null && existing.state == com.mobilegh.data.DownloadState.Complete && !com.mobilegh.data.Downloads.isFileMissing(existing) -> {
                            GhButton(tr("立即安装"), primary = true) {
                                onDismiss()
                                com.mobilegh.data.Downloads.installApk(ctx, existing)
                            }
                        }
                        existing != null && existing.active -> {
                            GhButton(tr("查看更新进度"), primary = true) {
                                onDismiss()
                                com.mobilegh.data.Downloads.selectedKey = existing.key
                            }
                        }
                        else -> {
                            GhButton(tr("下载更新"), primary = true) {
                                onDismiss()
                                Update.download(info)
                            }
                        }
                    }
                } else {
                    GhButton(tr("重新检查"), enabled = !Update.checking) { scope.launch { Update.check() } }
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (Update.available == null && !Update.checking) Update.check() }
}
