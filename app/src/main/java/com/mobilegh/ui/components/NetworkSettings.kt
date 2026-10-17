package com.mobilegh.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.data.AppNetwork
import com.mobilegh.data.NetworkProxy
import com.mobilegh.data.ProxyMode
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun NetworkSettingsDialog(onDismiss: () -> Unit) {
    val ctx = rememberCtx()
    val g = Gh.c
    val saved = AppNetwork.config
    var mode by remember { mutableStateOf(saved.mode) }
    var host by remember { mutableStateOf(saved.host) }
    var port by remember { mutableStateOf(saved.port.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<AppNetwork.Connection>()) }
    val scope = rememberCoroutineScope()

    fun draft() = NetworkProxy(mode, host, port.toIntOrNull() ?: 0).validated()
    fun changed() { error = null; results = emptyList() }

    GhDialog("网络连接", { if (!saving) onDismiss() }, confirm = if (saving) "保存中…" else "保存", confirmEnabled = !checking && !saving, onConfirm = {
        val value = try { draft() } catch (e: Exception) {
            error = e.message; return@GhDialog
        }
        saving = true
        scope.launch {
            try {
                AppNetwork.save(value)
                ctx.toast("网络设置已保存，请重试登录或刷新页面")
                onDismiss()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                error = e.message ?: "无法保存网络设置"
            } finally { saving = false }
        }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("公开内容加速不能加速登录。这里配置 App 请求使用的代理，GitHub 地址与 HTTPS 校验保持不变。", color = g.fgMuted, fontSize = 13.sp)
            ProxyMode.entries.forEach { option ->
                RadioRow(mode == option, option.label, enabled = !checking && !saving) {
                    mode = option; changed()
                }
            }
            if (mode != ProxyMode.System) {
                GhField(host, { if (!checking && !saving) { host = it; changed() } }, "代理主机", placeholder = "127.0.0.1 或局域网 IP")
                GhField(port, { if (!checking && !saving) { port = it; changed() } }, "代理端口",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("填写你信任的代理软件显示的地址和端口。127.0.0.1 指本手机；电脑代理需填写电脑局域网 IP，并允许局域网连接。暂不支持需要账号密码的代理。", color = g.fgMuted, fontSize = 12.sp)
            } else {
                Text("使用 VPN 时，请在代理软件的分应用规则里包含 MobileGH 和登录浏览器。App 无法替你修改其他软件的规则。", color = g.fgMuted, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhButton(if (checking) "检查中…" else "检查连接", enabled = !checking && !saving) {
                    val value = try { draft() } catch (e: Exception) {
                        error = e.message; return@GhButton
                    }
                    checking = true
                    results = emptyList()
                    error = null
                    scope.launch {
                        try { results = AppNetwork.check(value) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "连接检查失败" }
                        finally { checking = false }
                    }
                }
                TextLink("系统网络设置") {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                        .onFailure { ctx.toast("无法打开系统网络设置") }
                }
            }
            results.forEach {
                Text("${it.label}：${it.message}", color = if (it.reachable) g.success else g.danger, fontSize = 13.sp)
            }
            error?.let { InlineError(it) }
            Text("检查连接只访问官方站点，不发送 Token；连通不代表授权成功。此设置不影响外部浏览器、系统下载器和推送长连接，它们仍需系统代理 / VPN。", color = g.fgMuted, fontSize = 12.sp)
        }
    }
}

@Composable
fun NetworkSettingsRow() {
    var open by remember { mutableStateOf(false) }
    MenuRow(com.mobilegh.R.drawable.oc_globe, "连接代理", value = AppNetwork.config.mode.label, onClick = { open = true })
    if (open) NetworkSettingsDialog { open = false }
}
