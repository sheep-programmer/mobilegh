package com.mobilegh.ui.components

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.mobilegh.data.*
import com.mobilegh.ui.theme.Gh

@Composable
fun MobileApprovalCard(modifier: Modifier = Modifier) {
    val ctx = rememberCtx()
    val g = Gh.c
    val state by MobileApprovalMonitor.state.collectAsStateWithLifecycle()
    val setup by MobileApprovalOAuth.state.collectAsStateWithLifecycle()
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GitHub 数字审批", color = g.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val device = state.device
            if (device == null) {
                Text(if (Session.token == null) "登录 GitHub 后自动启用。收到验证请求时，直接输入浏览器显示的数字。"
                    else "当前登录方式未注册本机验证设备，验证请求不会弹窗。授权一次即可启用。", color = g.fgMuted, fontSize = 13.sp)
                GhButton(if (setup.busy) "正在启用…" else if (Session.token == null) "登录并启用" else "授权启用", primary = true, enabled = !setup.busy) {
                    runCatching { MobileApprovalOAuth.begin(ctx) }.onSuccess { ctx.openBrowser(it) }
                }
                if (setup.waitingForBrowser) TextLink("取消授权") { MobileApprovalOAuth.cancel(ctx) }
            } else {
                Text("@${device.login}", color = g.fg, fontWeight = FontWeight.Medium)
                SwitchRow("自动检测 2FA 请求", "自动弹窗并发送系统通知", state.enabled) { MobileApprovalMonitor.setEnabled(ctx, it) }
                Text(if (!state.enabled) "数字审批已关闭" else if (state.approvedId != null) "验证已批准，返回浏览器继续登录。"
                    else if (state.request != null) "有一条验证请求等待确认" else if (state.connected) "已开启，正在自动检测验证请求" else "正在连接 GitHub…",
                    color = if (state.approvedId != null) g.success else g.fgMuted, fontSize = 13.sp)
                if (state.request != null) GhButton("输入数字并批准", primary = true) { MobileApprovalMonitor.show() }
                if (state.error != null && !state.connected) TextLink("重新授权") {
                    runCatching { MobileApprovalOAuth.begin(ctx) }.onSuccess { ctx.openBrowser(it) }
                }
            }
            setup.notice?.let { Text(it, color = g.fgMuted, fontSize = 13.sp) }
            setup.error?.let { InlineError(it, retry = if (setup.canRetry) ({ MobileApprovalOAuth.retry(ctx) }) else null) }
            if (state.request == null) state.error?.let { Text(it, color = g.danger, fontSize = 13.sp) }
        }
    }
}

/** Mounted once above every screen; digits never survive replacement or dismissal. */
@Composable
fun MobileApprovalHost() {
    val ctx = rememberCtx()
    val state by MobileApprovalMonitor.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val request = state.request
    if (request != null && state.dialogVisible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
        var digits by remember(request) { mutableStateOf("") }
        GhDialog("GitHub 两步验证", { if (!state.busy) MobileApprovalMonitor.dismiss() },
            confirm = if (state.busy) "正在批准…" else "确认并批准",
            confirmEnabled = !state.busy && state.connected && (!request.challengeRequired || digits.isNotBlank()),
            onConfirm = { MobileApprovalMonitor.approve(ctx, request, digits) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("@${state.device?.login}", color = Gh.c.fg, fontWeight = FontWeight.SemiBold)
                Text("${approvalRequestLabel(request.type)} · 请求 ${request.id}", color = Gh.c.fgMuted, fontSize = 13.sp)
                if (request.challengeRequired) GhField(digits, { value ->
                    if (value.length <= 5 && value.all { it in '0'..'9' }) digits = value
                }, "浏览器显示的数字", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                else Text("请确认这是你发起的验证。", color = Gh.c.fgMuted, fontSize = 13.sp)
                state.error?.let { Text(it, color = Gh.c.danger, fontSize = 13.sp) }
            }
        }
    }
    LaunchedEffect(state.approvedId) { if (state.approvedId != null) ctx.toast("GitHub 验证已批准") }
    ApprovalSetupPrompt()
}

/** 已登录但本机未注册验证设备时（旧版本升级、设备码或 Token 登录），监听不会运行；提示一次去授权 */
@Composable
private fun ApprovalSetupPrompt() {
    val ctx = rememberCtx()
    val state by MobileApprovalMonitor.state.collectAsStateWithLifecycle()
    val setup by MobileApprovalOAuth.state.collectAsStateWithLifecycle()
    val prefs = remember { ctx.getSharedPreferences("permissions", android.content.Context.MODE_PRIVATE) }
    var prompted by remember { mutableStateOf(prefs.getBoolean("approval_setup_prompted", false)) }
    if (prompted || Session.token == null || state.device != null || setup.busy) return
    fun done() { prefs.edit().putBoolean("approval_setup_prompted", true).apply(); prompted = true }
    GhDialog("启用 GitHub 数字审批", ::done, confirm = "授权启用", onConfirm = {
        done()
        runCatching { MobileApprovalOAuth.begin(ctx) }.onSuccess { ctx.openBrowser(it) }.onFailure { ctx.toast(it.message ?: "无法开始授权") }
    }) {
        Text("当前登录方式没有注册本机验证设备，GitHub 两步验证请求不会弹窗。授权一次后自动启用，之后可在设置 → 两步验证中管理。", color = Gh.c.fgMuted, fontSize = 14.sp)
    }
}

private fun approvalRequestLabel(type: String) = when (type) {
    "TWO_FACTOR_LOGIN" -> "登录验证"
    "TWO_FACTOR_SUDO_CHALLENGE" -> "敏感操作验证"
    "TWO_FACTOR_PASSWORD_RESET" -> "密码重置验证"
    "DEVICE_VERIFICATION" -> "设备验证"
    else -> "身份验证"
}

@Composable
fun NotificationPermissionHost() {
    val ctx = rememberCtx()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val prefs = remember { ctx.getSharedPreferences("permissions", android.content.Context.MODE_PRIVATE) }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    fun requestBackground() {
        val power = ctx.getSystemService(PowerManager::class.java)
        if (!prefs.getBoolean("background_requested", false) && !power.isIgnoringBatteryOptimizations(ctx.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            if (intent.resolveActivity(ctx.packageManager) != null) {
                prefs.edit().putBoolean("background_requested", true).apply()
                background.launch(intent)
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requestBackground() }
    LaunchedEffect(lifecycle) {
        if (Build.VERSION.SDK_INT >= 33 && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !prefs.getBoolean("notification_requested", false)) {
            prefs.edit().putBoolean("notification_requested", true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) requestBackground()
    }
}

@Composable
fun ApprovalSettingsRows() {
    val ctx = rememberCtx()
    val state by MobileApprovalMonitor.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var notificationsAllowed by remember { mutableStateOf(MobileApprovalNotifications.allowed(ctx)) }
    var backgroundAllowed by remember { mutableStateOf(ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) }
    LaunchedEffect(lifecycle) {
        notificationsAllowed = MobileApprovalNotifications.allowed(ctx)
        backgroundAllowed = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val setup by MobileApprovalOAuth.state.collectAsStateWithLifecycle()
    val device = state.device
    if (device == null) ApprovalSetupRow(setup)
    else {
        val status = when {
            !state.enabled -> "已关闭，验证请求不会弹窗"
            state.request != null -> "@${device.login} · 有一条验证请求等待确认"
            state.connected -> "@${device.login} · 正在检测，收到请求后弹窗并通知"
            state.error != null -> "@${device.login} · ${state.error}"
            else -> "@${device.login} · 正在连接 GitHub…"
        }
        SwitchRow("自动检测 2FA 请求", status, state.enabled) { MobileApprovalMonitor.setEnabled(ctx, it) }
        if (state.enabled && !state.connected && state.error != null) ApprovalSetupRow(setup, "连接失败，重新授权可恢复检测", "重新授权")
    }
    SwitchRow("系统通知", if (notificationsAllowed) "已允许 GitHub 验证提醒" else "开启后可在后台收到验证提醒", notificationsAllowed) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
            .onFailure { ctx.toast("请在系统设置中开启 mobilegh 通知") }
    }
    SwitchRow("允许后台运行", "保持息屏时的验证提醒", backgroundAllowed) {
        val intent = if (backgroundAllowed) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        runCatching { ctx.startActivity(intent) }.onFailure { ctx.toast("请在系统设置中允许 mobilegh 后台运行") }
    }
}

/** 未注册本机验证设备（或需重新授权）时显示真实状态和授权入口，而不是一个不起作用的开关 */
@Composable
private fun ApprovalSetupRow(setup: ApprovalSetupState, idle: String = "授权一次后，验证请求会自动弹窗并通知", action: String = "授权启用") {
    val ctx = rememberCtx()
    val g = Gh.c
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (action == "授权启用") "数字审批未启用" else "数字审批", fontSize = 15.sp, color = g.fg)
            Text(setup.error ?: setup.notice ?: if (setup.waitingForBrowser) "正在等待 GitHub 授权" else if (setup.busy) "正在注册本机验证设备…" else idle,
                fontSize = 12.sp, color = if (setup.error != null) g.danger else g.fgMuted)
        }
        Spacer(Modifier.width(12.dp))
        if (setup.waitingForBrowser) GhButton("取消") { MobileApprovalOAuth.cancel(ctx) }
        else if (setup.canRetry) GhButton("重试", primary = true) { MobileApprovalOAuth.retry(ctx) }
        else GhButton(if (setup.busy) "启用中…" else action, primary = true, enabled = !setup.busy) {
            runCatching { MobileApprovalOAuth.begin(ctx) }.onSuccess { ctx.openBrowser(it) }.onFailure { ctx.toast(it.message ?: "无法开始授权") }
        }
    }
}
