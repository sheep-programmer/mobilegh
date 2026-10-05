package com.mobilegh.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Session
import com.mobilegh.data.Totp
import com.mobilegh.nav.LocalNav
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.delay

/** Available before login, so a user can actually use it as a second factor. */
@Composable
fun AuthenticatorScreen(onBack: (() -> Unit)? = null) {
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val g = Gh.c
    var accounts by remember { mutableStateOf(Session.totpAccounts()) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); delay(1000) } }
    BackHandler { if (onBack != null) onBack() else nav.pop() }
    Page("两步验证", onBack = onBack, actions = {
        OcButton(R.drawable.oc_plus, { editing = true })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().background(g.canvas), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(16.dp)) {
            item { OfficialApprovalCard() }
            item { Text("验证器验证码", color = g.fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            item { Text("选择 GitHub 的「验证器应用」，复制这里的六位码完成验证。", color = g.fgMuted, fontSize = 14.sp) }
            if (accounts.isEmpty()) item {
                Card {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("添加 GitHub 验证器", color = g.fg, fontWeight = FontWeight.SemiBold)
                        Text("从 GitHub 两步验证设置导入密钥或 otpauth 链接。", color = g.fgMuted, fontSize = 14.sp)
                        GhButton("添加", primary = true, icon = R.drawable.oc_key) { editing = true }
                    }
                }
            }
            items(accounts, key = { it }) { login ->
                val code = remember(login, tick) { Session.totpCode(login) }
                val left = (30 - tick / 1000 % 30).toInt()
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("GitHub · $login", color = g.fg, fontWeight = FontWeight.SemiBold)
                            MoreMenu(listOf(MenuAction("移除密钥", danger = true) { deleting = login }))
                        }
                        Text(code?.let { "${it.take(3)} ${it.takeLast(3)}" } ?: "密钥无法解密", color = if (code == null) g.danger else g.accent,
                            fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        LinearProgressIndicator(progress = { left / 30f }, modifier = Modifier.fillMaxWidth(), color = g.accent, trackColor = g.neutralMuted)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${left}s 后刷新", color = g.fgMuted, fontSize = 13.sp)
                            if (code != null) TextLink("复制验证码") { ctx.copy(code, "验证码已复制") }
                        }
                    }
                }
            }
            item { TextLink("打开 GitHub 两步验证设置") { ctx.openBrowser("https://github.com/settings/security") } }
        }
    }
    if (editing) {
        var login by remember { mutableStateOf(Session.login.orEmpty()) }
        var secret by remember { mutableStateOf("") }
        val valid = login.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) && Totp.isValid(secret)
        GhDialog("添加验证器", { editing = false }, confirm = "保存", confirmEnabled = valid, onConfirm = {
            Session.setTotpSecret(login, secret)
            secret = ""; editing = false; accounts = Session.totpAccounts()
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GhField(login, { login = it.trim() }, "GitHub 用户名")
                GhField(secret, { secret = it }, "密钥或 otpauth 链接", visualTransformation = PasswordVisualTransformation())
                if (secret.isNotBlank() && !Totp.isValid(secret)) Text("密钥格式无效，仅支持 GitHub 的 SHA1 / 6 位 / 30 秒 TOTP。", color = g.danger, fontSize = 12.sp)
                Text("保存后在 GitHub 输入生成的码完成启用。密钥通过 Android Keystore 加密。", color = g.fgMuted, fontSize = 13.sp)
            }
        }
    }
    deleting?.let { login ->
        GhDialog("移除验证器", { deleting = null }, confirm = "移除", danger = true, onConfirm = {
            Session.setTotpSecret(login, null); accounts = Session.totpAccounts(); deleting = null
        }) { Text("只移除本机 $login 的密钥，不会关闭 GitHub 两步验证。请确保你还保留恢复方式。", fontSize = 14.sp) }
    }
}
