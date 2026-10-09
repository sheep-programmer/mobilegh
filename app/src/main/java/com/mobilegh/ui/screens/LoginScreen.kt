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

private const val TOKEN_URL = "https://github.com/settings/tokens/new?description=MobileGH&scopes=repo%2Cread%3Aorg%2Cnotifications%2Cuser%2Cgist%2Cworkflow"

private suspend fun verify(token: String): User = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("${Api.BASE}/user").header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json").cacheControl(CacheControl.FORCE_NETWORK).build()
    Api.http.newCall(req).await().use {
        if (it.code == 401) throw ApiException(401, "Token 无效或已过期")
        if (!it.isSuccessful) throw ApiException(it.code, "验证失败 (${it.code})")
        Api.json.decodeFromString(User.serializer(), it.body.string())
    }
}

/** Native UI; account authentication takes place in the system browser. */
@Composable
fun LoginScreen(adding: Boolean = false) {
    val g = Gh.c
    val ctx = rememberCtx()
    val nav = LocalNav.current
    // Credentials are excluded from Android saved-instance-state.
    var token by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var method by rememberSaveable { mutableIntStateOf(1) }
    val approvalSetup by MobileApprovalOAuth.state.collectAsState()
    var help by remember { mutableStateOf(false) }
    var authenticator by remember { mutableStateOf(false) }
    val flow = retain("login-flow") { entry ->
        LoginFlow(entry.scope,
            start = { DeviceAuth.start(BuildConfig.GITHUB_CLIENT_ID) },
            poll = { code -> DeviceAuth.poll(BuildConfig.GITHUB_CLIENT_ID, code) },
            complete = { result ->
                val user = verify(result.token)
                currentCoroutineContext().ensureActive()
                Session.signIn(result.token, user, result.refreshToken, result.expiresIn, BuildConfig.GITHUB_CLIENT_ID)
                AppLog.info("auth", "OAuth 登录成功")
            },
            tokenLogin = { clean ->
                val user = verify(clean)
                currentCoroutineContext().ensureActive()
                Session.signIn(clean, user)
                AppLog.info("auth", "Token 登录成功")
            },
            now = { SystemClock.elapsedRealtime() },
        )
    }
    val busy = flow.busy
    val device = flow.device
    var remaining by remember { mutableIntStateOf(0) }

    BackHandler(device != null) { flow.cancel() }
    LaunchedEffect(device) {
        if (device == null) return@LaunchedEffect
        while (isActive) {
            remaining = ((flow.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000).toInt()
            delay(1000)
        }
    }

    if (authenticator) {
        AuthenticatorScreen(onBack = { authenticator = false })
        return
    }
    Column(
        Modifier.fillMaxSize().background(g.canvas).statusBarsPadding().navigationBarsPadding()
            .imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (adding) OcButton(R.drawable.oc_arrow_left, { nav.pop() }) else Spacer(Modifier.width(40.dp))
            OcButton(R.drawable.oc_light_bulb, { help = true })
        }
        Spacer(Modifier.height(if (device == null) 32.dp else 12.dp))
        Oc(R.drawable.oc_mark_github, g.fg, if (device == null) 56.dp else 32.dp)
        Spacer(Modifier.height(if (device == null) 16.dp else 12.dp))
        Text(if (device != null) "连接 GitHub" else if (adding) "添加 GitHub 账号" else "欢迎使用 mobilegh",
            fontSize = if (device == null) 24.sp else 22.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
        if (device == null) Text("代码、协作与动态，随时掌握", Modifier.padding(top = 8.dp), fontSize = 14.sp, color = g.fgMuted)
        Spacer(Modifier.height(if (device == null) 28.dp else 20.dp))
        if (device == null) {
            Chips(listOf("Token 登录", "GitHub 授权"), method) { if (!busy) method = it }
            Spacer(Modifier.height(16.dp))
            if (method == 0) {
                GhField(token, { token = it }, "Personal Access Token", placeholder = "粘贴你的 Token",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (token.isNotBlank()) flow.signIn(token) }),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = { OcButton(if (visible) R.drawable.oc_eye else R.drawable.oc_lock, { visible = !visible }, g.fgMuted) },
                )
                Spacer(Modifier.height(16.dp))
                GhButton(if (busy) "正在登录…" else "登录", Modifier.fillMaxWidth(), primary = true,
                    enabled = token.isNotBlank() && !busy) { flow.signIn(token) }
                TextLink("生成 Token") { ctx.openBrowser(TOKEN_URL) }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Oc(R.drawable.oc_shield_lock, g.accent, 28.dp)
                        Text("使用 GitHub 账号", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                        Text("登录后自动启用数字审批，验证请求会弹窗并通知你。", color = g.fgMuted, fontSize = 14.sp)
                        GhButton("继续", Modifier.fillMaxWidth(), primary = true,
                            enabled = !busy && !approvalSetup.busy, icon = R.drawable.oc_mark_github) {
                            runCatching { MobileApprovalOAuth.begin(ctx) }.onSuccess { ctx.openBrowser(it) }
                        }
                        if (approvalSetup.busy) {
                            Text(if (approvalSetup.waitingForBrowser) "正在等待 GitHub 授权" else "正在自动启用数字审批…", color = g.fgMuted, fontSize = 13.sp)
                            if (approvalSetup.waitingForBrowser) TextLink("取消授权") { MobileApprovalOAuth.cancel(ctx) }
                        }
                        approvalSetup.error?.let { Text(it, color = g.danger, fontSize = 13.sp) }
                        TextLink("使用设备授权码登录") { flow.authorize() }
                    }
                }
            }
        } else {
            val d = device
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (flow.confirming) "正在确认账号" else "浏览器授权码", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                    Text(d.userCode, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = g.accent)
                    if (!flow.confirming) Text("${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')} 后过期", color = g.fgMuted, fontSize = 12.sp)
                    Text("此码填写在浏览器；验证数字可在下方输入并批准。", color = g.fgMuted, fontSize = 12.sp)
                    GhButton("复制并打开 GitHub", Modifier.fillMaxWidth(), primary = true, enabled = !flow.confirming, icon = R.drawable.oc_link_external) {
                        ctx.copy(d.userCode); ctx.openBrowser(d.verificationUri)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = g.fgMuted)
                        Text(if (flow.confirming) "GitHub 已授权，正在连接账号" else "等待 GitHub 授权", color = g.fgMuted, fontSize = 13.sp)
                    }
                    flow.notice?.let { Text(it, color = g.fgMuted, fontSize = 13.sp) }
                    TextLink("取消") { flow.cancel() }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!flow.confirming) MobileApprovalCard()
        }
        if (busy && device == null) CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), color = g.fgMuted)
        flow.error?.let { Text(it, Modifier.padding(vertical = 12.dp), color = g.danger, fontSize = 14.sp) }
        Spacer(Modifier.height(if (device == null) 24.dp else 12.dp))
        TextLink(if (device == null) "两步验证与数字批准" else "使用验证器验证码") { authenticator = true }
        if (device == null) Text("凭据仅加密保存在本机", Modifier.padding(top = 12.dp, bottom = 32.dp), color = g.fgMuted, fontSize = 12.sp)
        else Spacer(Modifier.height(16.dp))
    }
    if (help) GhDialog("登录帮助", { help = false }, confirm = "知道了", onConfirm = { help = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Token：查看组织需 read:org，私有仓库需 repo。有效期由 GitHub 设置决定。", fontSize = 14.sp)
            Text("OAuth：使用系统浏览器登录，支持 GitHub 提供的验证器、通行密钥等两步验证方式。", fontSize = 14.sp)
            Text("数字审批：GitHub 登录后自动注册本机，收到请求时在弹窗输入浏览器显示的数字。", fontSize = 14.sp)
            Text("组织仓库仍受应用批准、Token 策略和 SSO 限制，可在设置的组织访问诊断中检查。", fontSize = 14.sp)
        }
    }
}
