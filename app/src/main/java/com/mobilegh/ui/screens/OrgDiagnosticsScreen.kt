package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.BuildConfig
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.OrganizationAccess
import com.mobilegh.data.Session
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun OrgDiagnosticsScreen() {
    val g = Gh.c
    val ctx = rememberCtx()
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val directory = rememberLoader("org-diagnostics") { GitHub.orgAccess(it) }
    var org by remember { mutableStateOf("") }
    var checks by remember { mutableStateOf<List<OrganizationAccess.Check>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val kind = when {
        Session.token?.startsWith("ghp_") == true -> "Classic PAT"
        Session.token?.startsWith("github_pat_") == true -> "Fine-grained PAT"
        Session.token?.startsWith("gho_") == true -> "OAuth"
        else -> "其他授权"
    }
    Page("组织访问诊断", actions = { OcButton(R.drawable.oc_sync, { directory.refresh() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${Session.login} · $kind", fontWeight = FontWeight.SemiBold, color = g.fg)
                        Text("权限：${directory.data?.scopes?.ifBlank { "此类 Token 不返回 scopes；请检查资源所有者和授权仓库" } ?: "未读取到 scopes"}", color = g.fgMuted, fontSize = 13.sp)
                        Text("登录和两步验证只确认身份。组织应用批准、仓库权限、PAT 策略与 SSO 仍由 GitHub 控制。", fontSize = 13.sp, color = g.fgMuted)
                    }
                }
            }
            if (directory.loading) item { Loading(Modifier.fillMaxWidth().height(64.dp)) }
            directory.error?.let { msg -> item { Text(msg, color = g.danger, fontSize = 13.sp) } }
            directory.data?.warnings.orEmpty().forEach { warning -> item { Text(warning, color = g.attention, fontSize = 13.sp) } }
            item {
                Text("核对指定组织", fontWeight = FontWeight.SemiBold, color = g.fg)
                GhField(org, { org = it.trim() }, "组织名称", Modifier.padding(top = 8.dp), placeholder = "例如 cursimple")
                val names = directory.data?.orgs.orEmpty().map { it.login }
                if (names.isNotEmpty()) Chips(names, names.indexOf(org)) { org = names[it] }
                GhButton(if (checking) "检查中…" else "检查成员与仓库权限", Modifier.fillMaxWidth().padding(top = 10.dp), primary = true,
                    enabled = !checking && org.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}"))) {
                    checking = true; checks = emptyList(); error = null
                    val selected = org
                    scope.launch {
                        try { checks = OrganizationAccess.check(selected)
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { error = e.friendly()
                        } finally { checking = false }
                    }
                }
            }
            checks.forEach { check -> item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(check.name, fontWeight = FontWeight.SemiBold, color = if (check.ok) g.success else g.attention)
                        Text(check.message, fontSize = 14.sp, color = g.fgMuted)
                        check.ssoUrl?.let { url -> TextLink("完成 SSO 授权") { ctx.openBrowser(url) } }
                    }
                }
            } }
            error?.let { msg -> item { Text(msg, color = g.danger, fontSize = 13.sp) } }
            item {
                MenuGroup(Modifier.fillMaxWidth()) {
                    MenuRow(R.drawable.oc_shield_lock, "检查 OAuth 组织授权") {
                        ctx.openBrowser("https://github.com/settings/connections/applications/${BuildConfig.GITHUB_CLIENT_ID}")
                    }
                    MenuRow(R.drawable.oc_key, "检查 Token 权限与 SSO") { ctx.openBrowser("https://github.com/settings/tokens") }
                    MenuRow(R.drawable.oc_mail, "待处理组织邀请") { nav.push(Screen.Invitations) }
                    MenuRow(R.drawable.oc_key, "更换授权") { nav.push(Screen.Login) }
                }
            }
        }
    }
}
