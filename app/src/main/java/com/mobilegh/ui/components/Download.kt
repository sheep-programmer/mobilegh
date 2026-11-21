package com.mobilegh.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import com.mobilegh.R
import com.mobilegh.data.DownloadState
import com.mobilegh.data.DownloadTask
import com.mobilegh.data.Downloads
import com.mobilegh.data.HISTORY_KEEP
import com.mobilegh.data.MAX_CONCURRENT_DOWNLOADS
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.launch
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** 所有页面统一调用：点击资源后立即在应用内展示下载弹窗。 */
fun Context.downloadFile(rawUrl: String, filename: String? = null, isPrivate: Boolean = false, mimeType: String? = null) {
    Downloads.start(rawUrl, filename, isPrivate, mimeType)
}

/** 放在 AppRoot，弹窗不会因为进入/退出 Release 页而丢失。 */
@Composable
internal fun DownloadOverlayLayout(bottomBarVisible: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .padding(bottom = if (bottomBarVisible) 80.dp else 0.dp).testTag("download-safe-area"),
    ) { content() }
}

@Composable
fun DownloadHost() {
    val g = Gh.c
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()
    val bodyHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.45f).coerceAtLeast(64.dp)
    var clearDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DownloadTask?>(null) }
    pendingDelete?.let { task ->
        // 删除手机上的文件无法恢复，先确认
        GhDialog(tr("删除本地文件？"), { pendingDelete = null }, confirm = tr("删除"), danger = true, onConfirm = {
            pendingDelete = null
            scope.launch {
                if (Downloads.deleteFile(ctx, task)) ctx.toast(tr("本地文件已删除")) else ctx.toast(tr("本地文件不存在或无法删除"))
            }
        }) { Text(trf("只删除手机上的「{0}」，GitHub 上的内容不受影响，删除后无法恢复。", task.filename), color = g.fg, fontSize = 14.sp) }
    }
    Downloads.inspectedFile?.let { info ->
        FileInfoDialog(info) { Downloads.inspectedFile = null }
    }
    val selected = Downloads.tasks.firstOrNull { it.key == Downloads.selectedKey }
    if (selected != null) {
        // 记录是完成的，但文件已经不在手机上：不再显示「下载完成」，改为提供重新下载
        val missing = Downloads.isFileMissing(selected)
        val done = selected.state == DownloadState.Complete && !missing
        val stateTitle = when {
            missing -> tr("文件已不在")
            selected.state == DownloadState.Complete -> tr("下载完成")
            selected.state == DownloadState.Failed -> tr("下载失败")
            selected.state == DownloadState.Cancelled -> tr("已取消下载")
            else -> tr("下载详情")
        }
        AlertDialog(
            onDismissRequest = Downloads::minimize,
            containerColor = g.canvas,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 0.dp,
            modifier = Modifier.dialogFrame(),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Oc(
                        if (done) R.drawable.oc_check_circle_fill else R.drawable.oc_download,
                        when {
                            done -> g.success
                            missing -> g.attention
                            else -> g.accent
                        },
                        22.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stateTitle, color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (selected.state == DownloadState.Complete) {
                        IconButton({ Downloads.revealFolder(ctx, selected) }) {
                            Oc(R.drawable.oc_file_directory, g.fgMuted, 20.dp)
                        }
                    }
                    DownloadCircleAction(R.drawable.oc_minimize, Downloads::minimize, tr("最小化"), size = 40.dp)
                }
            },
            text = {
                Column(Modifier.heightIn(max = bodyHeight).verticalScrollWithBar()) {
                    Text(selected.filename, color = g.fg, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(18.dp))
                    DownloadProgress(selected)
                    // 排队、传输中、已暂停的任务都能暂停或继续（暂停会保留已下的部分）
                    if (selected.canTogglePause) {
                        TextButton({ Downloads.togglePause(selected) }) {
                            Text(if (selected.paused) tr("继续") else tr("暂停"), color = g.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(if (selected.appDirectory) tr("保存到 MobileGH 的下载目录") else tr("保存到手机「下载」文件夹"), fontSize = 12.sp, color = g.fgMuted)
                }
            },
            confirmButton = {
                when {
                    done && selected.actionsArchive -> TextButton({ ApkArchiveDialogState.task = selected }) {
                        Text(tr("查找 APK"), color = g.accent, fontWeight = FontWeight.SemiBold)
                    }
                    done -> TextButton({
                        if (selected.filename.endsWith(".apk", true)) Downloads.installApk(ctx, selected) else Downloads.open(ctx, selected)
                    }) {
                        val label = when {
                            selected.filename.endsWith(".apk", true) -> {
                                if (selected.isAppUpdate || com.mobilegh.data.Update.isPendingUpdate(selected.filename)) tr("安装更新") else tr("安装应用")
                            }
                            else -> tr("打开文件")
                        }
                        Text(label, color = g.accent, fontWeight = FontWeight.SemiBold)
                    }
                    selected.active -> Unit
                    // 文件已不在时也走这里：按同一个 URL 重新下载
                    else -> TextButton({ Downloads.retry(selected) }) {
                        // 失败的任务从断点继续；已取消或文件被删的按同一个 URL 重新下载
                        Text(if (selected.state == DownloadState.Failed) tr("重试") else tr("重新下载"), color = g.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            dismissButton = {
                FlowRow(verticalArrangement = Arrangement.Center) {
                    if (selected.active) TextButton({ Downloads.cancel(selected) }) {
                        Text(tr("取消下载"), color = g.danger)
                    }
                    DownloadCloseButton(Downloads::closeOverlay)
                }
            },
        )
    } else if (Downloads.showHistory) {
        Dialog(onDismissRequest = Downloads::minimize, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val frame = RoundedCornerShape(16.dp)
            Column(
                Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth()
                    .clip(frame).background(g.canvas).border(1.dp, g.borderMuted.copy(alpha = 0.5f), frame),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Oc(R.drawable.oc_download, g.accent, 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(tr("下载记录"), color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (Downloads.activeCount > 0) {
                        DownloadCircleAction(R.drawable.oc_minimize, Downloads::minimize, tr("最小化"), size = 36.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    DownloadCircleAction(R.drawable.oc_x, Downloads::closeOverlay, tr("关闭"), size = 36.dp)
                }
                Box(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)).background(g.canvasSubtle)
                        .border(1.dp, g.borderMuted, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                ) {
                    SwitchRow(
                        title = tr("仅 Wi-Fi 下载"),
                        desc = tr("开启后新的下载不使用移动数据；已经在进行中的任务不受影响"),
                        checked = Downloads.wifiOnly,
                        onChange = { Downloads.changeWifiOnly(it) },
                    )
                }
                if (Downloads.tasks.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(56.dp).clip(CircleShape).background(g.canvasSubtle), contentAlignment = Alignment.Center) {
                            Oc(R.drawable.oc_download, g.fgMuted, 26.dp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(tr("还没有下载记录"), color = g.fgMuted, fontSize = 14.sp)
                    }
                } else {
                    ScrollbarLazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = bodyHeight),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(Downloads.tasks, key = { it.key }) { task ->
                            var menu by remember { mutableStateOf(false) }
                            val missing = Downloads.isFileMissing(task)
                            val card = RoundedCornerShape(12.dp)
                            Row(
                                Modifier.fillMaxWidth().clip(card).border(1.dp, g.borderMuted, card)
                                    .clickable { Downloads.showHistory = false; Downloads.selectedKey = task.key }
                                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                            ) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(g.accent.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                                    Oc(if (task.filename.endsWith(".apk", true)) R.drawable.oc_package else R.drawable.oc_file, g.accent, 18.dp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(task.filename, color = g.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(8.dp))
                                    DownloadProgress(task)
                                }
                                Box {
                                    IconButton({ menu = true }, Modifier.size(36.dp)) { Oc(R.drawable.oc_kebab_horizontal, g.fgMuted, 18.dp) }
                                    CardDropdownMenu(
                                        expanded = menu,
                                        onDismissRequest = { menu = false },
                                        actions = buildList {
                                            // 文件已不在时没有可打开的文件，只保留「重新下载」和移除记录
                                            if (task.state == DownloadState.Complete && !missing) {
                                                if (task.actionsArchive) add(MenuAction(tr("查找 APK"), icon = R.drawable.oc_package) {
                                                    Downloads.showHistory = false
                                                    Downloads.selectedKey = task.key
                                                    ApkArchiveDialogState.task = task
                                                })
                                                add(MenuAction(tr("打开文件"), icon = R.drawable.oc_file) { Downloads.open(ctx, task) })
                                                add(MenuAction(tr("打开所在文件夹"), icon = R.drawable.oc_file_directory) { Downloads.revealFolder(ctx, task) })
                                                add(MenuAction(tr("删除本地文件"), danger = true, icon = R.drawable.oc_trash) { pendingDelete = task })
                                            }
                                            // 排队、传输中、已暂停的任务都能暂停 / 继续
                                            if (task.canTogglePause) {
                                                val paused = task.paused
                                                add(MenuAction(if (paused) tr("继续") else tr("暂停"), icon = if (paused) R.drawable.oc_play else R.drawable.oc_clock) { Downloads.togglePause(task) })
                                            }
                                            if (task.active) {
                                                add(MenuAction(tr("取消下载"), danger = true, icon = R.drawable.oc_x) { Downloads.cancel(task) })
                                            } else {
                                                add(MenuAction(tr("重新下载"), icon = R.drawable.oc_sync) { Downloads.retry(task) })
                                            }
                                            add(MenuAction(tr("从记录中移除"), icon = R.drawable.oc_x_circle) { Downloads.forget(ctx, task) })
                                        },
                                    )
                                }
                            }
                        }
                        if (Downloads.droppedRecords > 0) {
                            item {
                                Text(
                                    trf("较早的 {0} 条记录已移出列表（最多保留 {1} 条），本地文件不受影响", Downloads.droppedRecords, HISTORY_KEEP),
                                    color = g.fgMuted, fontSize = 12.sp, lineHeight = 18.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }
                        }
                    }
                }
                HDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (Downloads.tasks.isNotEmpty()) {
                        Text(
                            tr("清空"), color = g.danger, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, g.danger.copy(alpha = 0.5f), RoundedCornerShape(50))
                                .clickable { clearDialog = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    DownloadCloseButton(Downloads::closeOverlay)
                }
            }
        }
        if (clearDialog) {
            AlertDialog(
                onDismissRequest = { clearDialog = false },
                containerColor = g.canvas,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                modifier = Modifier.dialogFrame(),
                title = { Text(tr("清空下载记录"), color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                text = { Text(tr("是否同时删除已下载到手机的文件？删除后无法恢复。正在下载或排队中的任务会保留。"), color = g.fgMuted, fontSize = 14.sp, lineHeight = 20.sp) },
                confirmButton = {
                    TextButton({
                        clearDialog = false
                        scope.launch {
                            val (n, f) = Downloads.clearHistory(ctx, true)
                            ctx.toast(trf("已清空 {0} 条记录，删除 {1} 个文件", n, f))
                        }
                    }) { Text(tr("同时删除文件"), color = g.danger, fontWeight = FontWeight.SemiBold) }
                },
                dismissButton = {
                    FlowRow {
                        TextButton({
                            clearDialog = false
                            scope.launch {
                                val (n, _) = Downloads.clearHistory(ctx, false)
                                ctx.toast(trf("已清空 {0} 条记录，文件保留在「下载」文件夹", n))
                            }
                        }) { Text(tr("仅清空记录"), color = g.fgMuted) }
                        TextButton({ clearDialog = false }) { Text(tr("取消"), color = g.fgMuted) }
                    }
                },
            )
        }
    } else if (!Downloads.overlayClosed && Downloads.finishedTask == null && (Downloads.activeCount > 0 || Downloads.minimized)) {
        DownloadPill(Downloads.runningCount, Downloads.queuedCount, onOpen = Downloads::openHistory)
    }
    // 下载在后台完成：底部提示一直保留，直到用户点开看一眼或点 ✕ 关掉
    Downloads.finishedTask?.takeIf { selected == null && !Downloads.showHistory && !Downloads.overlayClosed }?.let { FinishedNotice(it) }
}

@Composable
private fun FinishedNotice(task: DownloadTask) {
    val g = Gh.c
    val failed = task.state == DownloadState.Failed
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth().testTag("download-completion-notice")
                .clip(RoundedCornerShape(12.dp)).background(g.canvas)
                .border(1.dp, g.border, RoundedCornerShape(12.dp))
                .clickable {
                    // 查看详情后返回仍能找到，只有明确关闭才移除悬浮提示。
                    Downloads.selectedKey = task.key
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oc(if (failed) R.drawable.oc_x else R.drawable.oc_check, if (failed) g.danger else g.success, 18.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (failed) tr("下载失败") else tr("下载完成"), color = g.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(task.filename, color = g.fgMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(tr("点按查看"), color = g.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            DownloadCircleAction(R.drawable.oc_x, Downloads::closeOverlay, tr("关闭"), size = 36.dp, danger = false)
        }
    }
}

@Composable
private fun DownloadCloseButton(onClick: () -> Unit) {
    val g = Gh.c
    val label = tr("关闭")
    val pill = RoundedCornerShape(50)
    Text(
        label, color = g.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(pill).background(g.canvasSubtle, pill).border(1.dp, g.borderMuted, pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun DownloadCircleAction(
    icon: Int,
    onClick: () -> Unit,
    description: String,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    danger: Boolean = false,
) {
    val g = Gh.c
    GhTooltip(description) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (danger) g.danger.copy(alpha = 0.10f) else g.canvasSubtle, CircleShape)
                .border(1.dp, if (danger) g.danger else g.border, CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Oc(icon, if (danger) g.danger else g.fgMuted, 18.dp)
        }
    }
}

/**
 * 下载中的悬浮胶囊：默认贴在右下角，可以按住拖到屏幕任意位置，拖动范围限制在屏幕内。
 * 只有胶囊本身接收触摸，屏幕其余部分照常点按和滚动。
 */
@Composable
internal fun DownloadPill(running: Int, queued: Int, onOpen: () -> Unit) {
    val g = Gh.c
    var screen by remember { mutableStateOf(IntSize.Zero) }
    var pill by remember { mutableStateOf(IntSize.Zero) }
    // 相对默认位置（右下角）的拖动量，单位像素
    var drag by remember { mutableStateOf(Offset.Zero) }
    fun bounded(offset: Offset): Offset = Offset(
        offset.x.coerceIn(-(screen.width - pill.width).coerceAtLeast(0).toFloat(), 0f),
        offset.y.coerceIn(-(screen.height - pill.height).coerceAtLeast(0).toFloat(), 0f),
    )
    Box(Modifier.fillMaxSize().padding(16.dp).onSizeChanged { screen = it }) {
        Row(
            Modifier.align(Alignment.BottomEnd)
                .offset { bounded(drag).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                .onSizeChanged { pill = it }
                .pointerInput(screen, pill) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        drag = bounded(drag + delta)
                    }
                }
                .testTag("download-minimized-pill")
                .border(1.dp, g.border, RoundedCornerShape(20.dp))
                .background(g.canvas, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oc(R.drawable.oc_download, g.accent, 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                if (queued > 0) trf("下载中 {0} · 排队 {1}", running, queued)
                else if (running > 0) trf("下载中 · {0}", running) else tr("下载记录"),
                color = g.fg, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun DownloadProgress(task: DownloadTask) {
    val g = Gh.c
    val progress = task.progress
    // 记录显示完成，但文件已经不在：状态和说明都改用警示色
    val missing = Downloads.isFileMissing(task)
    val color = when {
        missing -> g.attention
        task.state == DownloadState.Complete -> g.success
        task.state == DownloadState.Failed -> g.danger
        task.state == DownloadState.Cancelled -> g.fgMuted
        else -> g.accent
    }
    // 进行中的任务带着提示（节点无响应、正在换节点、等待网络）时，说明文字用警示色
    val noteColor = if (missing || (task.active && task.message != null)) g.attention else color
    // 排队中的任务没有进展，显示为 0 的静止进度条，不用不确定的转圈动画（那像是在下载）
    if (progress == null && task.active && !task.waitingForSlot) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = color, trackColor = g.neutralMuted)
    } else {
        LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = color, trackColor = g.neutralMuted)
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (task.total > 0) fmtSize(task.downloaded) + " / " + fmtSize(task.total)
            else if (task.downloaded > 0) tr("已下载 ") + fmtSize(task.downloaded) else tr("等待文件大小…"),
            fontSize = 12.sp, color = g.fgMuted,
        )
        Text(
            when {
                missing -> tr("文件已不在")
                // 应用内排队（尚未交给系统）：暂停或排队中；已交给系统但还在连接的仍是「连接中」
                task.paused && (task.state == DownloadState.Queued || task.state == DownloadState.Waiting) -> tr("已暂停")
                task.waitingForSlot -> tr("排队中")
                else -> when (task.state) {
                    DownloadState.Preparing -> tr("准备中")
                    DownloadState.Queued -> tr("连接中")
                    DownloadState.Waiting -> tr("已暂停")
                    DownloadState.Complete -> "100%"
                    DownloadState.Cancelled -> tr("已取消")
                    DownloadState.Failed -> tr("失败")
                    else -> progress?.let { (it * 100).toInt().toString() + "%" } ?: tr("下载中")
                }
            },
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color,
        )
    }
    val note = when {
        missing -> tr("文件已被移动或删除，可以重新下载")
        task.waitingForSlot && !task.paused -> trf("同时下载最多 {0} 个，前面的完成后自动开始", MAX_CONCURRENT_DOWNLOADS)
        else -> task.message
    }
    note?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = noteColor)
    }
}

/** 下载前查看的文件信息 */
typealias FileInfo = com.mobilegh.data.FileInfo

/**
 * 点资源先弹出详情：完整文件名（可选中复制）、大小、类型、下载次数、时间等，
 * 用户确认后才开始下载；已经下载过且文件还在时可直接打开。
 */
@Composable
fun FileInfoDialog(info: FileInfo, onDismiss: () -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    val bodyHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.45f).coerceAtLeast(64.dp)
    val checksum = com.mobilegh.data.trustedSha256(info.digest)
    val done = Downloads.tasks.firstOrNull { it.url == info.url && it.expectedSha256 == checksum && it.state == DownloadState.Complete && Downloads.fileExists(it) }
    val running = Downloads.tasks.firstOrNull { it.url == info.url && it.expectedSha256 == checksum && it.active }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.canvas,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.dialogFrame(),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Oc(R.drawable.oc_package, g.fgMuted, 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(tr("文件信息"), color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(Modifier.heightIn(max = bodyHeight).verticalScrollWithBar()) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(info.name, color = g.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
                }
                Spacer(Modifier.height(14.dp))
                info.details.filter { it.second.isNotBlank() }.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(k, color = g.fgMuted, fontSize = 13.sp, modifier = Modifier.width(76.dp))
                        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
                            Text(v, color = g.fg, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(verticalArrangement = Arrangement.Center) {
                    TextButton({ ctx.copy(info.name, tr("文件名已复制")) }) { Text(tr("复制文件名"), color = g.accent, fontSize = 13.sp) }
                    TextButton({ ctx.copy(info.url, tr("链接已复制")) }) { Text(tr("复制链接"), color = g.accent, fontSize = 13.sp) }
                }
                when {
                    running != null -> Text(tr("正在下载中…"), color = g.accent, fontSize = 12.sp)
                    done != null -> Text(tr("已下载过，文件在手机「下载」文件夹"), color = g.success, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            when {
                running != null -> TextButton({ onDismiss(); Downloads.selectedKey = running.key }) { Text(tr("查看进度"), color = g.accent, fontWeight = FontWeight.SemiBold) }
                done != null -> FlowRow {
                    TextButton({ onDismiss(); Downloads.start(info.url, info.name, info.isPrivate, info.mimeType, digest = info.digest) }) { Text(tr("重新下载"), color = g.fgMuted) }
                    TextButton({
                        onDismiss()
                        if (done.filename.endsWith(".apk", true)) Downloads.installApk(ctx, done) else Downloads.open(ctx, done)
                    }) {
                        val label = when {
                            done.filename.endsWith(".apk", true) -> {
                                if (done.isAppUpdate || com.mobilegh.data.Update.isPendingUpdate(done.filename)) tr("安装更新") else tr("安装应用")
                            }
                            else -> tr("打开")
                        }
                        Text(label, color = g.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
                else -> TextButton({ onDismiss(); Downloads.start(info.url, info.name, info.isPrivate, info.mimeType, digest = info.digest) }) {
                    Text(tr("下载"), color = g.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr("取消"), color = g.fgMuted) } },
    )
}
