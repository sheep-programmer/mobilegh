package com.mobilegh.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** Available before login, so a user can actually use it as a second factor. */
@Composable
fun AuthenticatorScreen(onBack: (() -> Unit)? = null) {
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val g = Gh.c
    var accounts by remember { mutableStateOf(Session.totpAccounts()) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var editing by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); delay(1000) } }
    BackHandler { if (onBack != null) onBack() else nav.pop() }
    if (scanning) {
        QrScannerScreen(
            onScanned = { result ->
                scanning = false
                // 密钥可能不带 otpauth 前缀；两种都交给编辑框让用户确认归属账号
                editingSecret = result.text
                editing = true
            },
            onBack = { scanning = false },
        )
        return
    }
    Page(tr("两步验证"), onBack = onBack, actions = {
        OcButton(R.drawable.oc_scan, { scanning = true }, description = tr("扫码导入"))
        OcButton(R.drawable.oc_plus, { editingSecret = null; editing = true })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().background(g.canvas), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(16.dp)) {
            item { MobileApprovalCard() }
            item { Text(tr("验证器验证码"), color = g.fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            item { Text(tr("选择 GitHub 的「验证器应用」，复制这里的六位码完成验证。"), color = g.fgMuted, fontSize = 14.sp) }
            if (accounts.isEmpty()) item {
                Card {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tr("添加 GitHub 验证器"), color = g.fg, fontWeight = FontWeight.SemiBold)
                        Text(tr("扫码导入 GitHub 两步验证设置页的二维码，或手动粘贴密钥 / otpauth 链接。"), color = g.fgMuted, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhButton(tr("扫码导入"), primary = true, icon = R.drawable.oc_scan) { scanning = true }
                            GhButton(tr("手动添加"), icon = R.drawable.oc_key) { editingSecret = null; editing = true }
                        }
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
                            MoreMenu(listOf(MenuAction(tr("移除密钥"), danger = true) { deleting = login }))
                        }
                        Text(code?.let { "${it.take(3)} ${it.takeLast(3)}" } ?: tr("密钥无法解密"), color = if (code == null) g.danger else g.accent,
                            fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        LinearProgressIndicator(progress = { left / 30f }, modifier = Modifier.fillMaxWidth(), color = g.accent, trackColor = g.neutralMuted)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(trf("{0}s 后刷新", left), color = g.fgMuted, fontSize = 13.sp)
                            if (code != null) TextLink(tr("复制验证码")) { ctx.copy(code, tr("验证码已复制")) }
                        }
                    }
                }
            }
            item { TextLink(tr("打开 GitHub 两步验证设置")) { ctx.openBrowser("https://github.com/settings/security") } }
        }
    }
    deleting?.let { login ->
        GhDialog(tr("移除验证器"), { deleting = null }, confirm = tr("移除"), danger = true, onConfirm = {
            Session.setTotpSecret(login, null); accounts = Session.totpAccounts(); deleting = null
        }) { Text(trf("只移除本机 {0} 的密钥，不会关闭 GitHub 两步验证。请确保你还保留恢复方式。", login), fontSize = 14.sp) }
    }
    if (editing) {
        AddAuthenticatorDialog(
            initialSecret = editingSecret,
            onDismiss = { editing = false; editingSecret = null },
            onSaved = { accounts = Session.totpAccounts(); editing = false; editingSecret = null },
        )
    }
}

/** 编辑框里待保存的预填密钥（扫码导入时先存到这里） */
private var editingSecret by mutableStateOf<String?>(null)

@Composable
private fun AddAuthenticatorDialog(initialSecret: String?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val g = Gh.c
    var login by remember { mutableStateOf(Session.login.orEmpty()) }
    var secret by remember(initialSecret) { mutableStateOf(initialSecret.orEmpty()) }
    var verifyInput by remember { mutableStateOf("") }
    var verifyResult by remember { mutableStateOf<Boolean?>(null) }
    val valid = login.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) && Totp.isValid(secret)
    val normalized = remember(secret) { runCatching { Totp.normalize(secret) }.getOrNull() }
    GhDialog(tr("添加验证器"), onDismiss, confirm = tr("保存"), confirmEnabled = valid, onConfirm = {
        Session.setTotpSecret(login, secret)
        onSaved()
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GhField(login, { login = it.trim() }, tr("GitHub 用户名"))
            GhField(secret, { secret = it; verifyResult = null }, tr("密钥或 otpauth 链接"), visualTransformation = PasswordVisualTransformation())
            if (secret.isNotBlank() && !Totp.isValid(secret)) Text(tr("密钥格式无效，仅支持 GitHub 的 SHA1 / 6 位 / 30 秒 TOTP。"), color = g.danger, fontSize = 12.sp)
            if (normalized != null && Totp.isValid(normalized)) {
                Text(tr("自检：输入 GitHub 网页当前要求的六位码，确认两端一致。"), color = g.fgMuted, fontSize = 13.sp)
                GhField(verifyInput, { verifyInput = it.filter(Char::isDigit).take(6) }, tr("六位验证码"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                if (verifyInput.length == 6) {
                    val ok = remember(verifyInput, normalized) { Totp.verify(normalized, verifyInput) }
                    LaunchedEffect(ok) { verifyResult = ok }
                    Text(
                        when (verifyResult) {
                            true -> tr("两端一致，密钥有效。")
                            false -> tr("验证码不一致：请确认密钥来自当前账号，且手机时间已自动同步。")
                            null -> ""
                        },
                        color = if (verifyResult == true) g.success else g.danger, fontSize = 13.sp,
                    )
                }
            }
            Text(tr("保存后在 GitHub 输入生成的码完成启用。密钥通过 Android Keystore 加密。"), color = g.fgMuted, fontSize = 13.sp)
        }
    }
}
