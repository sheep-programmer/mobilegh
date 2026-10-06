package com.mobilegh.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
fun OfficialApprovalCard(
    modifier: Modifier = Modifier,
    waitingForOAuth: Boolean = false,
    onContinueInBrowser: (() -> Unit)? = null,
) {
    val ctx = rememberCtx()
    val owner = LocalLifecycleOwner.current
    val g = Gh.c
    var status by remember { mutableStateOf(GitHubCompanion.availability(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    // A saved UI hint only: the official app was opened, not that approval succeeded.
    var opened by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(owner, ctx) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = GitHubCompanion.availability(ctx)
                // Launch errors are transient; returning never confirms approval.
                error = null
            }
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
            Text(
                if (waitingForOAuth) "官方 App 需登录同一账号。"
                else "浏览器提示两位数字时，在同账号的官方 App 中手动批准。",
                color = g.fgMuted, fontSize = 13.sp,
            )
            GhButton(
                if (status.launchable) "打开 GitHub Mobile" else if (status.installed) "重试打开" else "安装 GitHub Mobile",
                Modifier.fillMaxWidth(), icon = R.drawable.oc_link_external,
            ) {
                status = GitHubCompanion.availability(ctx)
                error = null
                if (status.installed) {
                    error = GitHubCompanion.open(ctx)
                    if (error == null) opened = true
                    status = GitHubCompanion.availability(ctx)
                } else if (!GitHubCompanion.openStore(ctx)) {
                    ctx.openBrowser(GitHubCompanion.STORE_URL)
                }
            }
            if (waitingForOAuth) {
                Text("手动批准后，回到浏览器完成授权。", color = g.fgMuted, fontSize = 12.sp)
                if (opened && onContinueInBrowser != null) {
                    GhButton("继续浏览器授权", Modifier.fillMaxWidth(), icon = R.drawable.oc_link_external) {
                        onContinueInBrowser()
                    }
                }
            } else if (opened) Text("手动批准后，回到浏览器继续。", color = g.fgMuted, fontSize = 12.sp)
            error?.let { Text(it, color = g.danger, fontSize = 13.sp) }
        }
    }
}
