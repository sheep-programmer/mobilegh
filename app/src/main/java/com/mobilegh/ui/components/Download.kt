package com.mobilegh.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.mobilegh.R
import com.mobilegh.data.DownloadState
import com.mobilegh.data.DownloadTask
import com.mobilegh.data.Downloads
import com.mobilegh.ui.theme.Gh

/** 所有页面统一调用：点击资源后立即在应用内展示下载弹窗。 */
fun Context.downloadFile(rawUrl: String, filename: String? = null, isPrivate: Boolean = false, mimeType: String? = null) {
    Downloads.start(rawUrl, filename, isPrivate, mimeType)
}

/** 放在 AppRoot，弹窗不会因为进入/退出 Release 页而丢失。 */
@Composable
fun DownloadHost() {
    val g = Gh.c
    val ctx = rememberCtx()
    var clearDialog by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        Downloads.setForeground(true)
        onStopOrDispose { Downloads.setForeground(false) }
    }
    val selected = Downloads.tasks.firstOrNull { it.key == Downloads.selectedKey }
    if (selected != null) {
        val stateTitle = when (selected.state) {
            DownloadState.Complete -> "下载完成"
            DownloadState.Failed -> "下载失败"
            DownloadState.Cancelled -> "已取消下载"
            else -> "下载文件"
        }
        AlertDialog(
            onDismissRequest = { Downloads.selectedKey = null },
            containerColor = g.canvas,
            shape = RoundedCornerShape(12.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Oc(
                        if (selected.state == DownloadState.Complete) R.drawable.oc_check_circle_fill else R.drawable.oc_download,
                        if (selected.state == DownloadState.Complete) g.success else g.accent,
                        22.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stateTitle, color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (selected.state == DownloadState.Complete) {
                        IconButton({ Downloads.revealFolder(ctx, selected) }) {
                            Oc(R.drawable.oc_file_directory, g.fgMuted, 20.dp)
                        }
                    }
                }
            },
            text = {
                Column {
                    Text(selected.filename, color = g.fg, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(18.dp))
                    DownloadProgress(selected)
                    Spacer(Modifier.height(14.dp))
                    Text(if (selected.appDirectory) "保存到 MobileGH 的下载目录" else "保存到手机「下载」文件夹", fontSize = 12.sp, color = g.fgMuted)
                }
            },
            confirmButton = {
                when {
                    selected.state == DownloadState.Complete -> TextButton({
                        if (selected.filename.endsWith(".apk", true)) Downloads.installApk(ctx, selected) else Downloads.open(ctx, selected)
                    }) { Text(if (selected.filename.endsWith(".apk", true)) "安装更新" else "打开文件", color = g.accent) }
                    selected.active -> TextButton({ Downloads.selectedKey = null }) { Text("后台下载", color = g.accent) }
                    else -> TextButton({ Downloads.retry(selected) }) { Text("重新下载", color = g.accent) }
                }
            },
            dismissButton = {
                TextButton({ if (selected.active) Downloads.cancel(selected) else Downloads.selectedKey = null }) {
                    Text(if (selected.active) "取消下载" else "关闭", color = if (selected.active) g.danger else g.fgMuted)
                }
            },
        )
    } else if (Downloads.showHistory) {
        AlertDialog(
            onDismissRequest = { Downloads.showHistory = false },
            containerColor = g.canvas,
            shape = RoundedCornerShape(12.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("下载记录", color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (Downloads.tasks.isNotEmpty()) {
                        TextButton({ clearDialog = true }) { Text("清空", color = g.danger, fontSize = 13.sp) }
                    }
                }
            },
            text = {
                if (Downloads.tasks.isEmpty()) {
                    Text("还没有下载记录", color = g.fgMuted)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(Downloads.tasks, key = { it.key }) { task ->
                            var menu by remember { mutableStateOf(false) }
                            Column(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(
                                        Modifier.weight(1f).clickable { Downloads.showHistory = false; Downloads.selectedKey = task.key },
                                    ) {
                                        Text(task.filename, color = g.fg, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(6.dp))
                                        DownloadProgress(task)
                                    }
                                    Box {
                                        IconButton({ menu = true }) { Oc(R.drawable.oc_kebab_horizontal, g.fgMuted, 18.dp) }
                                        DropdownMenu(menu, { menu = false }, Modifier.background(g.canvas)) {
                                            if (task.state == DownloadState.Complete) {
                                                DropdownMenuItem(
                                                    text = { Text("打开文件", fontSize = 14.sp, color = g.fg) },
                                                    onClick = { menu = false; Downloads.open(ctx, task) },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("打开所在文件夹", fontSize = 14.sp, color = g.fg) },
                                                    onClick = { menu = false; Downloads.revealFolder(ctx, task) },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("删除本地文件", fontSize = 14.sp, color = g.danger) },
                                                    onClick = {
                                                        menu = false
                                                        if (Downloads.deleteFile(ctx, task)) ctx.toast("本地文件已删除") else ctx.toast("本地文件不存在或无法删除")
                                                    },
                                                )
                                            }
                                            if (task.active) {
                                                DropdownMenuItem(
                                                    text = { Text("取消下载", fontSize = 14.sp, color = g.danger) },
                                                    onClick = { menu = false; Downloads.cancel(task) },
                                                )
                                            } else {
                                                DropdownMenuItem(
                                                    text = { Text("重新下载", fontSize = 14.sp, color = g.fg) },
                                                    onClick = { menu = false; Downloads.retry(task) },
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("从记录中移除", fontSize = 14.sp, color = g.fgMuted) },
                                                onClick = { menu = false; Downloads.forget(ctx, task) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ Downloads.showHistory = false }) { Text("关闭", color = g.accent) } },
        )
        if (clearDialog) {
            AlertDialog(
                onDismissRequest = { clearDialog = false },
                containerColor = g.canvas,
                shape = RoundedCornerShape(12.dp),
                title = { Text("清空下载记录", color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                text = { Text("是否同时删除已下载到手机的文件？删除后无法恢复。", color = g.fgMuted, fontSize = 14.sp, lineHeight = 20.sp) },
                confirmButton = {
                    TextButton({
                        clearDialog = false
                        val (n, f) = Downloads.clearHistory(ctx, true)
                        ctx.toast("已清空 $n 条记录，删除 $f 个文件")
                    }) { Text("同时删除文件", color = g.danger) }
                },
                dismissButton = {
                    Row {
                        TextButton({
                            clearDialog = false
                            val (n, _) = Downloads.clearHistory(ctx, false)
                            ctx.toast("已清空 $n 条记录，文件保留在「下载」文件夹")
                        }) { Text("仅清空记录", color = g.fgMuted) }
                        TextButton({ clearDialog = false }) { Text("取消", color = g.accent) }
                    }
                },
            )
        }
    } else if (Downloads.activeCount > 0) {
        Box(Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 104.dp), contentAlignment = Alignment.BottomEnd) {
            Row(
                Modifier.border(1.dp, g.border, RoundedCornerShape(20.dp))
                    .background(g.canvas, RoundedCornerShape(20.dp))
                    .clickable { Downloads.showHistory = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Oc(R.drawable.oc_download, g.accent, 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("下载中 · " + Downloads.activeCount, color = g.fg, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DownloadProgress(task: DownloadTask) {
    val g = Gh.c
    val progress = task.progress
    val color = when (task.state) {
        DownloadState.Complete -> g.success
        DownloadState.Failed -> g.danger
        DownloadState.Cancelled -> g.fgMuted
        else -> g.accent
    }
    if (progress == null && task.active) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = color, trackColor = g.neutralMuted)
    } else {
        LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = color, trackColor = g.neutralMuted)
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            if (task.total > 0) fmtSize(task.downloaded) + " / " + fmtSize(task.total)
            else if (task.downloaded > 0) "已下载 " + fmtSize(task.downloaded) else "等待文件大小…",
            fontSize = 12.sp, color = g.fgMuted,
        )
        Text(
            when (task.state) {
                DownloadState.Preparing -> "准备中"
                DownloadState.Queued -> "连接中"
                DownloadState.Waiting -> "等待网络"
                DownloadState.Complete -> "100%"
                DownloadState.Cancelled -> "已取消"
                DownloadState.Failed -> "失败"
                else -> progress?.let { (it * 100).toInt().toString() + "%" } ?: "下载中"
            },
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color,
        )
    }
    task.message?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = color)
    }
}
