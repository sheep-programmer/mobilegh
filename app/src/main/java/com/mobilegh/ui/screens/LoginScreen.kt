package com.mobilegh.ui.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.BuildConfig
import com.mobilegh.R
import com.mobilegh.data.*
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.*
import okhttp3.CacheControl
import okhttp3.Request
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

private const val TOKEN_URL = "https://github.com/settings/tokens/new?description=MobileGH&scopes=repo%2Cread%3Aorg%2Cnotifications%2Cuser%2Cgist%2Cworkflow%2Cproject%2Cdelete_repo"

private class Verified(val user: User, val scopes: Set<String>?)

private suspend fun verify(token: String): Verified = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("${Api.BASE}/user").header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json").cacheControl(CacheControl.FORCE_NETWORK).build()
    Api.http.routed().newCall(req).await().use {
        if (it.code == 401) throw ApiException(401, tr("Token 无效或已过期"))
        if (!it.isSuccessful) throw ApiException(it.code, trf("验证失败 ({0})", it.code))
        // 细粒度 PAT 不返回 X-OAuth-Scopes，此时权限按「未知」处理，不误报缺失
        Verified(Api.json.decodeFromString(User.serializer(), it.body.string()), DeviceAuth.parseScopes(it.header("X-OAuth-Scopes")))
    }
}

/** Native UI; account authentication takes place in the system browser. */
@Composable
fun LoginScreen(adding: Boolean = false, reauthorizing: Boolean = false) {
    val g = Gh.c
    val ctx = rememberCtx()
    val nav = LocalNav.current
    // Credentials are excluded from Android saved-instance-state.
    var token by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var method by rememberSaveable { mutableIntStateOf(1) }
    val approvalSetup by MobileApprovalOAuth.state.collectAsState()
    var help by remember { mutableStateOf(false) }
    var networkSettings by remember { mutableStateOf(false) }
    var authenticator by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val flow = retain("login-flow") { entry ->
        fun checkAccount(user: User) {
            check(Session.generation == entry.generation) { tr("账号已切换，请重新登录") }
            check(!reauthorizing || entry.login.equals(user.login, true)) { trf("请授权当前账号 @{0}", entry.login) }
        }
        LoginFlow(entry.scope,
            start = { DeviceAuth.start(BuildConfig.GITHUB_CLIENT_ID) },
            poll = { code -> DeviceAuth.poll(BuildConfig.GITHUB_CLIENT_ID, code) },
            complete = { result ->
                val v = verify(result.token)
                currentCoroutineContext().ensureActive()
                checkAccount(v.user)
                Session.signIn(result.token, v.user, result.refreshToken, result.expiresIn, BuildConfig.GITHUB_CLIENT_ID, scopes = v.scopes)
                AppLog.info("auth", tr("OAuth 登录成功"))
            },
            tokenLogin = { clean ->
                val v = verify(clean)
                currentCoroutineContext().ensureActive()
                checkAccount(v.user)
                Session.signIn(clean, v.user, scopes = v.scopes)
                AppLog.info("auth", tr("Token 登录成功"))
            },
            now = { SystemClock.elapsedRealtime() },
        )
    }
    val busy = flow.busy
    val device = flow.device
    var remaining by remember { mutableIntStateOf(0) }

    BackHandler(device != null) { flow.cancel() }
    BackHandler(logs) { logs = false }
    BackHandler(menu && !logs && !authenticator) { menu = false }
    LaunchedEffect(device) {
        if (device == null) return@LaunchedEffect
        // 参考同类客户端：拿到验证码后自动复制并打开浏览器，减少手动步骤
        ctx.copy(device.userCode, trf("验证码 {0} 已复制，请在浏览器粘贴", device.userCode))
        runCatching { ctx.openBrowser(device.verificationUri) }
            .onFailure { flow.notice = trf("浏览器未能打开，请手动访问 {0}", device.verificationUri) }
        while (isActive) {
            remaining = ((flow.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000).toInt()
            delay(1000)
        }
    }

    if (logs) {
        LogsScreen(onBack = { logs = false })
        return
    }
    if (authenticator) {
        AuthenticatorScreen(onBack = { authenticator = false })
        return
    }
    if (menu) {
        LoginMenuPage(
            onBack = { menu = false },
            onUpdate = { showUpdate = true },
            onNetwork = { networkSettings = true },
            onLogs = { logs = true },
            onAuthenticator = { authenticator = true },
            onHelp = { help = true },
        )
        if (networkSettings) NetworkSettingsDialog { networkSettings = false }
        if (showUpdate) UpdateDialog { showUpdate = false }
        if (help) LoginHelpDialog { help = false }
        return
    }
    Column(
        Modifier.fillMaxSize().background(g.canvas).statusBarsPadding().navigationBarsPadding()
            .imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (adding || reauthorizing) OcButton(R.drawable.oc_arrow_left, { nav.pop() }) else Spacer(Modifier.width(40.dp))
            Box {
                OcButton(R.drawable.oc_kebab_horizontal, { menu = true }, description = tr("更多：检查更新、网络、日志"))
                if (Update.available != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(9.dp).background(g.danger, androidx.compose.foundation.shape.CircleShape))
            }
        }
        Spacer(Modifier.height(if (device == null) 32.dp else 12.dp))
        Oc(R.drawable.oc_mark_github, g.fg, if (device == null) 56.dp else 32.dp)
        Spacer(Modifier.height(if (device == null) 16.dp else 12.dp))
        Text(if (device != null) tr("连接 GitHub") else if (reauthorizing) tr("重新授权 GitHub 账号") else if (adding) tr("添加 GitHub 账号") else tr("欢迎使用 mobilegh"),
            fontSize = if (device == null) 24.sp else 22.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
        if (device == null) Text(tr("代码、协作与动态，随时掌握"), Modifier.padding(top = 8.dp), fontSize = 14.sp, color = g.fgMuted)
        Spacer(Modifier.height(if (device == null) 28.dp else 20.dp))
        if (device == null) {
            Chips(listOf(tr("Token 登录"), tr("GitHub 授权")), method) { if (!busy) method = it }
            Spacer(Modifier.height(16.dp))
            if (method == 0) {
                GhField(token, { token = it }, "Personal Access Token", placeholder = tr("粘贴你的 Token"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (token.isNotBlank()) flow.signIn(token) }),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = { OcButton(if (visible) R.drawable.oc_eye else R.drawable.oc_lock, { visible = !visible }, g.fgMuted, description = if (visible) tr("隐藏 Token") else tr("显示 Token")) },
                )
                Spacer(Modifier.height(16.dp))
                GhButton(if (busy) tr("正在登录…") else tr("登录"), Modifier.fillMaxWidth(), primary = true,
                    enabled = token.isNotBlank() && !busy) { flow.signIn(token) }
                TextLink(tr("生成 Token")) { ctx.openBrowser(TOKEN_URL) }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Oc(R.drawable.oc_shield_lock, g.accent, 28.dp)
                        Text(tr("使用 GitHub 账号"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                        Text(tr("登录后自动启用数字审批，验证请求会弹窗并通知你。"), color = g.fgMuted, fontSize = 14.sp)
                        GhButton(tr("继续"), Modifier.fillMaxWidth(), primary = true,
                            enabled = !busy && !approvalSetup.busy, icon = R.drawable.oc_mark_github) {
                            runCatching { MobileApprovalOAuth.begin(ctx, if (reauthorizing) ApprovalOAuthPurpose.Reauthorize else ApprovalOAuthPurpose.SignIn) }.onSuccess { ctx.openBrowser(it) }
                        }
                        if (approvalSetup.busy) {
                            Text(approvalSetup.notice ?: if (approvalSetup.waitingForBrowser) tr("正在等待 GitHub 授权") else tr("正在自动启用数字审批…"), color = g.fgMuted, fontSize = 13.sp)
                            if (approvalSetup.waitingForBrowser) TextLink(tr("取消授权")) { MobileApprovalOAuth.cancel(ctx) }
                        }
                        approvalSetup.error?.let {
                            InlineError(it, retry = if (approvalSetup.canRetry) ({ MobileApprovalOAuth.retry(ctx) }) else null)
                        }
                        TextLink(tr("使用设备授权码登录")) { flow.authorize() }
                    }
                }
            }
        } else {
            val d = device
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (flow.confirming) tr("正在确认账号") else tr("浏览器授权码"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                    Text(d.userCode, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = g.accent)
                    if (!flow.confirming) Text(trf("{0}:{1} 后过期", remaining / 60, (remaining % 60).toString().padStart(2, '0')), color = g.fgMuted, fontSize = 12.sp)
                    Text(tr("此码填写在浏览器；验证数字可在下方输入并批准。"), color = g.fgMuted, fontSize = 12.sp)
                    GhButton(tr("复制并打开 GitHub"), Modifier.fillMaxWidth(), primary = true, enabled = !flow.confirming, icon = R.drawable.oc_link_external) {
                        ctx.copy(d.userCode); ctx.openBrowser(d.verificationUri)
                    }
                    TextLink(tr("仅复制验证码")) { ctx.copy(d.userCode) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = g.fgMuted)
                        Text(if (flow.confirming) tr("GitHub 已授权，正在连接账号") else tr("等待 GitHub 授权"), color = g.fgMuted, fontSize = 13.sp)
                    }
                    flow.notice?.let { Text(it, color = g.fgMuted, fontSize = 13.sp) }
                    TextLink(tr("取消")) { flow.cancel() }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!flow.confirming) MobileApprovalCard()
        }
        if (busy && device == null) CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), color = g.fgMuted)
        flow.error?.let { InlineError(it, Modifier.padding(vertical = 4.dp)) }
        Spacer(Modifier.height(if (device == null) 24.dp else 12.dp))
        // 设备授权码流程里才需要验证器验证码，随手可点；其余入口都收进右上角菜单
        if (device != null) TextLink(tr("使用验证器验证码")) { authenticator = true }
        if (device == null) Text(tr("凭据仅加密保存在本机"), Modifier.padding(top = 12.dp, bottom = 32.dp), color = g.fgMuted, fontSize = 12.sp)
        else Spacer(Modifier.height(16.dp))
    }
    if (networkSettings) NetworkSettingsDialog { networkSettings = false }
    if (showUpdate) UpdateDialog { showUpdate = false }
    if (help) LoginHelpDialog { help = false }
}

@Composable
private fun LoginHelpDialog(onDismiss: () -> Unit) {
    GhDialog(tr("登录帮助"), onDismiss, confirm = tr("知道了"), onConfirm = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Token：查看组织需 read:org，私有仓库需 repo。有效期由 GitHub 设置决定。"), fontSize = 14.sp)
            Text(tr("OAuth：使用系统浏览器登录，支持 GitHub 提供的验证器、通行密钥等两步验证方式。"), fontSize = 14.sp)
            Text(tr("数字审批：GitHub 登录后自动注册本机，收到请求时在弹窗输入浏览器显示的数字。"), fontSize = 14.sp)
            Text(tr("组织仓库仍受应用批准、Token 策略和 SSO 限制，可在设置的组织访问诊断中检查。"), fontSize = 14.sp)
        }
    }
}

/** 登录页右上角打开的菜单页：检查更新、网络、日志等维护入口都放这里，登录页本身只留登录 */
@Composable
private fun LoginMenuPage(
    onBack: () -> Unit,
    onUpdate: () -> Unit,
    onNetwork: () -> Unit,
    onLogs: () -> Unit,
    onAuthenticator: () -> Unit,
    onHelp: () -> Unit,
) {
    val g = Gh.c
    Page(tr("更多"), onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle(tr("应用"))
            MenuGroup {
                MenuRow(
                    R.drawable.oc_sync, tr("检查更新"), g.accent,
                    value = if (Update.checking) tr("检查中…") else if (Update.available != null) tr("发现新版本") else "v${BuildConfig.VERSION_NAME}",
                    onClick = onUpdate,
                )
                MenuRow(R.drawable.oc_log, tr("详细日志"), g.fgMuted, value = tr("导出"), onClick = onLogs)
            }
            SectionTitle(tr("连接"))
            MenuGroup {
                MenuRow(R.drawable.oc_globe, tr("网络连接"), g.success, value = AppNetwork.config.mode.label, onClick = onNetwork)
                MenuRow(R.drawable.oc_shield_lock, tr("两步验证与数字批准"), g.done, onClick = onAuthenticator)
            }
            SectionTitle(tr("帮助"))
            MenuGroup {
                MenuRow(R.drawable.oc_question, tr("登录帮助"), g.severe, onClick = onHelp)
            }
            Text(tr("凭据仅加密保存在本机"), Modifier.padding(start = 16.dp, top = 20.dp, bottom = 32.dp), color = g.fgMuted, fontSize = 12.sp)
        }
    }
}
