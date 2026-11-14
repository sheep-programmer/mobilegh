package com.mobilegh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHubEmoji
import com.mobilegh.data.UserStatus
import com.mobilegh.data.UserStatusApi
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserStatusBottomSheet(
    current: UserStatus?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val g = Gh.c
    val scope = rememberCoroutineScope()
    var emoji by remember { mutableStateOf(current?.displayEmoji.orEmpty()) }
    var message by remember { mutableStateOf(current?.message.orEmpty()) }
    var busy by remember { mutableStateOf(current?.indicatesLimitedAvailability ?: false) }
    var expirationIndex by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val presetEmojis = listOf("💬", "😋", "🎯", "🌴", "💻", "🤒", "🚀", "⚡", "🎉", "☕", "🔥", "💡", "🤖", "✨", "❤️", "👀", "👍", "🌱")
    val presetStatuses = listOf(
        Triple("🎯", tr("专注中"), false),
        Triple("🌴", tr("休假中"), true),
        Triple("💻", tr("居家办公"), false),
        Triple("🤒", tr("身体不适"), true),
        Triple("🚀", tr("发布新功能"), false),
        Triple("😋", tr("享受美食"), false),
    )
    val expirationOptions = listOf(
        tr("不自动清除") to null,
        tr("30 分钟") to 30L,
        tr("1 小时") to 60L,
        tr("4 小时") to 240L,
        tr("今天结束") to -1L,
        tr("本周末") to -7L,
    )

    fun calculateExpiresAt(): String? {
        val duration = expirationOptions.getOrNull(expirationIndex)?.second ?: return null
        val now = Instant.now()
        return when {
            duration > 0 -> now.plus(duration, ChronoUnit.MINUTES).toString()
            duration == -1L -> LocalDate.now().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toString()
            duration == -7L -> LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toString()
            else -> null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = g.canvas,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = g.borderMuted) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 顶栏：标题与关闭按钮
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tr("设置状态"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Oc(R.drawable.oc_x, g.fgMuted, 18.dp)
                }
            }

            // 状态输入条
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, g.borderMuted.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .background(g.canvasSubtle, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(g.btnBg, CircleShape)
                        .border(1.dp, g.borderMuted, CircleShape)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (emoji.isNotBlank()) emoji else "💬", fontSize = 18.sp)
                }
                Box(Modifier.weight(1f)) {
                    if (message.isEmpty()) {
                        Text(tr("正在忙什么？"), color = g.fgMuted, fontSize = 15.sp)
                    }
                    BasicTextField(
                        value = message,
                        onValueChange = { message = it },
                        textStyle = TextStyle(color = g.fg, fontSize = 15.sp),
                        cursorBrush = SolidColor(g.accent),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                if (emoji.isNotBlank() || message.isNotBlank()) {
                    IconButton(onClick = { emoji = ""; message = "" }, modifier = Modifier.size(24.dp)) {
                        Oc(R.drawable.oc_x, g.fgMuted, 14.dp)
                    }
                }
            }

            // 表情快速选择条
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("选择表情"), fontSize = 12.sp, color = g.fgMuted)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presetEmojis.forEach { e ->
                        val selected = emoji == e
                        Box(
                            Modifier
                                .size(36.dp)
                                .background(if (selected) g.accent.copy(alpha = 0.15f) else g.canvasSubtle, CircleShape)
                                .border(if (selected) 1.5.dp else 1.dp, if (selected) g.accent else g.borderMuted, CircleShape)
                                .clip(CircleShape)
                                .clickable { emoji = if (selected) "" else e },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(e, fontSize = 17.sp)
                        }
                    }
                }
            }

            // 快速预设状态胶囊
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("快速设置"), fontSize = 12.sp, color = g.fgMuted)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presetStatuses.forEach { (e, msg, isBusy) ->
                        Row(
                            Modifier
                                .border(1.dp, g.borderMuted, RoundedCornerShape(16.dp))
                                .background(g.canvasSubtle, RoundedCornerShape(16.dp))
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    emoji = e
                                    message = msg
                                    busy = isBusy
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(e, fontSize = 14.sp)
                            Text(msg, fontSize = 13.sp, color = g.fg)
                        }
                    }
                }
            }

            HDivider()

            // 忙碌状态 Switch
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { busy = !busy }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("标记为忙碌（Busy）"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = g.fg)
                    Text(tr("他人将能看到你当前处理事务受限"), fontSize = 12.sp, color = g.fgMuted)
                }
                Switch(
                    checked = busy,
                    onCheckedChange = { busy = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = g.fg, checkedTrackColor = g.accent),
                )
            }

            // 自动清除时间
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tr("自动清除"), fontSize = 14.sp, color = g.fg)
                Dropdown(expirationOptions[expirationIndex].first, expirationOptions.map { it.first }, { expirationIndex = it })
            }

            error?.let { Text(it, color = g.danger, fontSize = 12.sp) }

            // 底部操作按钮
            val hasExistingStatus = current != null && !current.isEmpty
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (hasExistingStatus) {
                    GhButton(
                        tr("清除状态"),
                        Modifier.weight(1f),
                        icon = R.drawable.oc_trash,
                        danger = true,
                        enabled = !saving,
                    ) {
                        saving = true
                        error = null
                        scope.launch {
                            try {
                                UserStatusApi.clearStatus()
                                onSaved()
                                onDismiss()
                            } catch (e: Exception) {
                                error = e.message ?: tr("清除状态失败")
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
                GhButton(
                    if (saving) tr("保存中…") else tr("保存"),
                    Modifier.weight(if (hasExistingStatus) 1.5f else 1f),
                    primary = true,
                    enabled = !saving,
                ) {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            val expiresAt = calculateExpiresAt()
                            if (emoji.isBlank() && message.isBlank()) {
                                UserStatusApi.clearStatus()
                            } else {
                                UserStatusApi.setStatus(emoji, message, busy, expiresAt)
                            }
                            onSaved()
                            onDismiss()
                        } catch (e: Exception) {
                            error = e.message ?: tr("保存状态失败")
                        } finally {
                            saving = false
                        }
                    }
                }
            }
        }
    }
}
