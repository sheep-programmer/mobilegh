package com.mobilegh.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.mobilegh.R
import com.mobilegh.data.BackgroundNotificationConfig
import com.mobilegh.data.BackgroundNotificationSettings
import com.mobilegh.data.MobileApprovalNotifications
import com.mobilegh.data.Session
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.service.NotificationPollingWorker
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.MenuRow
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import java.time.DayOfWeek

@Composable
fun NotificationScheduleSection() {
    val ctx = rememberCtx()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var allowed by remember { mutableStateOf(MobileApprovalNotifications.allowed(ctx)) }
    LaunchedEffect(lifecycle) {
        if (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) allowed = MobileApprovalNotifications.allowed(ctx)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = MobileApprovalNotifications.allowed(ctx)
    }
    val config = BackgroundNotificationSettings.config

    fun update(value: BackgroundNotificationConfig) {
        BackgroundNotificationSettings.save(value)
        NotificationPollingWorker.sync(ctx)
        if (value.enabled && Build.VERSION.SDK_INT >= 33 &&
            !MobileApprovalNotifications.allowed(ctx)
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    SectionTitle(tr("后台通知"))
    MenuGroup {
        SwitchRow(
            tr("接收 GitHub 通知"),
            tr("应用不在前台时，至少每 15 分钟检查一次未读通知；仅在本机启用"),
            config.enabled,
            enabled = Session.token != null,
        ) { update(config.copy(enabled = it)) }
        if (config.enabled) {
            if (!allowed) MenuRow(R.drawable.oc_bell, tr("通知权限"), value = tr("未允许")) {
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }.onFailure { ctx.toast(tr("请在系统设置中开启 mobilegh 通知")) }
            }
            SwitchRow(tr("仅在工作时间提醒"), tr("按本机时区控制提醒；应用在前台时始终不提醒"), config.workingHours.enabled) {
                update(config.copy(workingHours = config.workingHours.copy(enabled = it)))
            }
            if (config.workingHours.enabled) WorkingHoursEditor(config)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkingHoursEditor(config: BackgroundNotificationConfig) {
    val ctx = rememberCtx()
    val hours = config.workingHours
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }
    val custom = hours.days != DayOfWeek.entries.toSet()

    fun update(value: BackgroundNotificationConfig) {
        BackgroundNotificationSettings.save(value)
        NotificationPollingWorker.sync(ctx)
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Dropdown(
            if (custom) tr("自定义星期") else tr("每天"),
            listOf(tr("每天"), tr("自定义星期")),
            { index -> update(config.copy(workingHours = hours.copy(days = if (index == 0) DayOfWeek.entries.toSet() else hours.days))) },
        )
        if (custom) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.entries.forEach { day ->
                FilterChip(selected = day in hours.days, onClick = {
                    update(config.copy(workingHours = hours.copy(days = hours.days.toMutableSet().apply {
                        if (!add(day)) remove(day)
                    })))
                }, label = { Text(dayLabel(day)) })
            }
        }
        Text(tr("跨夜时段归属于开始当天；结束时点不再提醒"), color = com.mobilegh.ui.theme.Gh.c.fgMuted, fontSize = 12.sp)
    }
    MenuRow(R.drawable.oc_clock, tr("开始时间"), value = formatMinute(hours.startMinute)) { editingStart = true }
    MenuRow(R.drawable.oc_clock, tr("结束时间"), value = formatMinute(hours.endMinute)) { editingEnd = true }
    if (editingStart) MinutePicker(tr("开始时间"), hours.startMinute, { editingStart = false }) {
        update(config.copy(workingHours = hours.copy(startMinute = it)))
        editingStart = false
    }
    if (editingEnd) MinutePicker(tr("结束时间"), hours.endMinute, { editingEnd = false }) {
        update(config.copy(workingHours = hours.copy(endMinute = it)))
        editingEnd = false
    }
}

@Composable
private fun MinutePicker(title: String, initial: Int, dismiss: () -> Unit, choose: (Int) -> Unit) {
    var hour by remember(initial) { mutableStateOf(initial / 60) }
    var minute by remember(initial) { mutableStateOf(initial % 60) }
    GhDialog(title, dismiss, confirm = tr("确定"), onConfirm = { choose(hour * 60 + minute) }) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Dropdown(trf("{0} 时", hour), (0..23).map { trf("{0} 时", it) }, { hour = it })
            Dropdown(trf("{0} 分", minute), (0..59).map { trf("{0} 分", it) }, { minute = it })
        }
    }
}

private fun formatMinute(value: Int) = "%02d:%02d".format(value / 60, value % 60)

@Composable
private fun dayLabel(day: DayOfWeek) = tr(when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
})
