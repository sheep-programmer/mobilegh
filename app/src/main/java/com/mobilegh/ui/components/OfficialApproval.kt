package com.mobilegh.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mobilegh.R
import com.mobilegh.data.GitHubCompanion
import com.mobilegh.ui.theme.Gh

/** App installation is observable. An approval result is never inferred from coming back. */
@Composable
fun OfficialApprovalCard(modifier: Modifier = Modifier, waitingForOAuth: Boolean = false) {
    val ctx = rememberCtx()
    val owner = LocalLifecycleOwner.current
    val g = Gh.c
    var status by remember { mutableStateOf(GitHubCompanion.availability(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf(false) }
    DisposableEffect(owner, ctx) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = GitHubCompanion.availability(ctx)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Oc(R.drawable.oc_shield_lock, g.accent, 24.dp)
                Text("GitHub Mobile 批准", Modifier.weight(1f), color = g.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Pill(if (status.launchable) "已安装" else if (status.installed) "无法打开" else "未安装", if (status.launchable) g.success else g.fgMuted)
            }
            Text("浏览器要求输入两位数字时，打开官方 App 查看请求并输入数字。官方 App 需登录同一 GitHub 账号。", color = g.fgMuted, fontSize = 13.sp)
            if (status.launchable) {
                GhButton("打开 GitHub Mobile", Modifier.fillMaxWidth(), icon = R.drawable.oc_link_external) {
                    error = GitHubCompanion.open(ctx)
                    opened = error == null
                }
            } else {
                GhButton(if (status.installed) "重试打开" else "安装 GitHub Mobile", Modifier.fillMaxWidth(), icon = R.drawable.oc_link_external) {
                    status = GitHubCompanion.availability(ctx)
                    if (status.installed) error = GitHubCompanion.open(ctx)
                    else if (!GitHubCompanion.openStore(ctx)) ctx.openBrowser(GitHubCompanion.STORE_URL)
                }
            }
            if (waitingForOAuth) Text("批准后回到浏览器完成 MobileGH 授权，连接结果会自动更新。", color = g.fgMuted, fontSize = 12.sp)
            else if (opened) Text("在官方 App 批准后，回到发起验证的浏览器继续。", color = g.fgMuted, fontSize = 12.sp)
            error?.let { Text(it, color = g.danger, fontSize = 13.sp) }
        }
    }
}
