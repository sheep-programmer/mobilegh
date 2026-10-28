package com.mobilegh.ui.screens

import com.mobilegh.ui.components.ApprovalSettingsRows
import com.mobilegh.data.AppLock
import com.mobilegh.ui.components.Biometrics
import com.mobilegh.ui.components.findFragmentActivity
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.RadioRow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.BuildConfig
import com.mobilegh.R
import com.mobilegh.data.Api
import com.mobilegh.data.AppLog
import com.mobilegh.data.DeviceAuth
import com.mobilegh.data.Downloads
import com.mobilegh.data.GitHub
import com.mobilegh.data.Net
import com.mobilegh.data.NodeType
import com.mobilegh.data.Session
import com.mobilegh.data.Totp
import com.mobilegh.data.Update
import kotlinx.coroutines.delay
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.MenuIcon
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.MenuRow
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.Segmented
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

// ======================= 两步验证（TOTP） =======================

/**
 * 存本账号的 TOTP 密钥后，登录时验证器这一步会自动填码——不用再掏第二台设备。
 * 密钥经 Keystore 加密存本机，只在本机算码。
 */
@Composable
private fun TwoFactorSection() {
    val nav = LocalNav.current
    SectionTitle(tr("两步验证"))
    MenuGroup {
        ApprovalSettingsRows()
        MenuRow(R.drawable.oc_shield_lock, tr("两步验证与数字批准"), count = Session.totpAccounts().size.takeIf { it > 0 }) {
            nav.push(Screen.Authenticator)
        }
        MenuRow(R.drawable.oc_organization, tr("组织访问诊断")) { nav.push(Screen.OrgDiagnostics) }
    }
}

// ======================= 设置 =======================

/** 应用锁：开关本身（开启和关闭）都需要先通过一次系统验证 */
@Composable
private fun AppLockSection() {
    val g = Gh.c
    val ctx = rememberCtx()
    var pickTimeout by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun toggle(on: Boolean) {
        val activity = ctx.findFragmentActivity() ?: return
        error = null
        if (!Biometrics.available(ctx)) {
            if (on) error = tr("请先在系统设置中设置锁屏密码或录入指纹、面容") else AppLock.setLockEnabled(false)
            return
        }
        Biometrics.authenticate(
            activity, if (on) tr("开启应用锁") else tr("关闭应用锁"), tr("验证身份以更改设置"),
            onSuccess = {
                AppLock.setLockEnabled(on)
                ctx.toast(if (on) tr("应用锁已开启") else tr("应用锁已关闭"))
            },
            onError = { error = it },
        )
    }

    SectionTitle(tr("应用锁"))
    MenuGroup {
        SwitchRow(tr("启动时需要验证"), tr("使用指纹、面容或锁屏密码保护 MobileGH"), AppLock.enabled) { toggle(it) }
        if (AppLock.enabled) {
            MenuRow(R.drawable.oc_clock, tr("离开后重新锁定"), value = AppLock.timeouts[AppLock.timeoutIndex].second) { pickTimeout = true }
        }
    }
    error?.let { InlineError(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }

    if (pickTimeout) GhDialog(tr("离开后重新锁定"), { pickTimeout = false }) {
        Column {
            AppLock.timeouts.forEachIndexed { i, (_, label) ->
                RadioRow(i == AppLock.timeoutIndex, label, if (i == 0) tr("每次回到应用都需要验证") else trf("离开超过 {0} 后需要验证", label)) {
                    AppLock.setTimeout(i)
                    pickTimeout = false
                }
            }
        }
    }
}

/** 设置的页面：先是一张分类清单，每个分类再进入自己的子页，不再把所有选项堆在一个长页面里 */
private enum class SettingsPage(private val titleZh: String) {
    Home("设置"), Appearance("外观与语言"), Security("安全与隐私"), TwoFactor("两步验证与数字批准"),
    Network("网络与加速"), Quota("Token 与 API 配额"), Storage("存储与诊断"), About("关于与更新");
    val title: String get() = tr(titleZh)
}

@Composable
fun SettingsScreen() {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    var page by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    var confirmOut by remember { mutableStateOf(false) }
    var addNode by remember { mutableStateOf(false) }
    var apiDialog by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = page != SettingsPage.Home) { page = SettingsPage.Home }
    Page(
        page.title,
        onBack = if (page != SettingsPage.Home) ({ page = SettingsPage.Home }) else null,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            when (page) {
                SettingsPage.Home -> SettingsHome(open = { page = it }, onLogout = { confirmOut = true })
                SettingsPage.Appearance -> AppearanceSettings()
                SettingsPage.Security -> {
                    LoginLifetimeSection()
                    AppLockSection()
                    SectionTitle(tr("桌面小组件"))
                    MenuGroup {
                        SwitchRow(tr("隐藏通知详情"), tr("桌面仅显示未读数量，不显示仓库名称和标题；应用锁不保护桌面内容"),
                            com.mobilegh.data.WidgetStore.hideInboxDetails) { com.mobilegh.data.WidgetStore.changeInboxPrivacy(it) }
                    }
                }
                SettingsPage.TwoFactor -> TwoFactorSection()
                SettingsPage.Network -> NetworkSection(onAdd = { addNode = true }, onApi = { apiDialog = true })
                SettingsPage.Quota -> QuotaSection()
                SettingsPage.Storage -> {
                    SectionTitle(tr("存储"))
                    MenuGroup {
                        MenuRow(R.drawable.oc_download, tr("下载记录"), count = Downloads.activeCount.takeIf { it > 0 }) { Downloads.showHistory = true }
                        MenuRow(R.drawable.oc_log, tr("日志与诊断"), value = AppLog.entries.size.toString() + tr(" 条")) { nav.push(Screen.Logs) }
                        MenuRow(R.drawable.oc_trash, tr("清除缓存")) {
                            Api.clearCache()
                            ctx.cacheDir.listFiles()?.filter { it.name != "http" }?.forEach { it.deleteRecursively() }
                            ctx.toast(tr("缓存已清除"))
                        }
                    }
                }
                SettingsPage.About -> {
                    SectionTitle("MobileGH")
                    MenuGroup {
                        MenuRow(
                            R.drawable.oc_download,
                            tr("检查更新"),
                            value = when {
                                Update.checking -> tr("检查中…")
                                Update.available != null -> trf("有新版 v{0}", Update.available!!.version)
                                else -> "v${BuildConfig.VERSION_NAME}"
                            },
                        ) { showUpdate = true }
                        MenuRow(R.drawable.oc_mark_github, tr("MobileGH 源码"), value = "v${BuildConfig.VERSION_NAME}") { nav.push(Screen.Repo("sheep-programmer", "mobilegh")) }
                    }
                    SectionTitle(tr("GitHub 账号设置"))
                    MenuGroup {
                        MenuRow(R.drawable.oc_link_external, tr("管理 GitHub Token")) { ctx.openBrowser("https://github.com/settings/tokens") }
                        MenuRow(R.drawable.oc_shield_lock, tr("已授权的 OAuth 应用")) { ctx.openBrowser("https://github.com/settings/applications") }
                        MenuRow(R.drawable.oc_bell, tr("GitHub 通知设置")) { ctx.openBrowser("https://github.com/settings/notifications") }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmOut) GhDialog(tr("退出登录"), { confirmOut = false }, confirm = tr("退出"), danger = true, onConfirm = {
        confirmOut = false
        Session.signOut()
    }) { Text(trf("将从本机移除 {0} 的 Token。", Session.login), color = g.fg) }

    if (addNode) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        GhDialog(tr("添加自定义加速节点"), { addNode = false }, confirm = tr("添加"), confirmEnabled = url.length > 10, onConfirm = {
            if (Net.addCustom(name, url)) { addNode = false; ctx.toast(tr("已添加，正在测速")) } else ctx.toast(tr("地址必须以 https:// 开头"))
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("填写 gh-proxy 类型的前缀代理地址，节点会以「前缀 + GitHub 原始链接」的方式访问。"), fontSize = 13.sp, color = g.fgMuted)
                GhField(name, { name = it }, tr("名称（可选）"))
                GhField(url, { url = it }, tr("前缀地址，如 https://ghfast.top/"))
            }
        }
    }

    if (apiDialog) {
        var url by remember { mutableStateOf(Net.apiProxy) }
        GhDialog(tr("API 反向代理（高级）"), { apiDialog = false }, confirm = tr("保存"), onConfirm = {
            apiDialog = false
            Net.setApi(url)
            ctx.toast(if (url.isBlank()) tr("已恢复官方 API") else tr("已启用 API 代理"))
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("所有 API 请求（包括你的 Token）都会经过该地址。只填写你自己部署的代理（如 Cloudflare Worker 反代 api.github.com），切勿使用来路不明的公共代理。留空则使用官方 api.github.com。"),
                    fontSize = 13.sp, color = g.attention,
                )
                GhField(url, { url = it }, tr("代理地址"), placeholder = "https://gh-api.example.workers.dev")
            }
        }
    }

    if (showUpdate) UpdateDialog { showUpdate = false }
}

/** 设置首页：账号 + 分类入口 + 退出登录 */
@Composable
private fun SettingsHome(open: (SettingsPage) -> Unit, onLogout: () -> Unit) {
    val g = Gh.c
    val nav = LocalNav.current
    SectionTitle(tr("账号"))
    MenuGroup {
        Session.accounts.forEach { a ->
            val cur = a.login == Session.login
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !cur) { Session.switchTo(a.login) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(a.avatarUrl, 36.dp)
                Spacer(Modifier.width(12.dp))
                Text(a.login, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = g.fg)
                if (cur) Pill(tr("当前"), g.success) else Text(tr("切换"), color = g.accent, fontSize = 13.sp)
            }
        }
        MenuRow(R.drawable.oc_plus, tr("添加账号")) { nav.push(Screen.Login) }
    }
    Spacer(Modifier.height(16.dp))
    MenuGroup {
        MenuRow(R.drawable.oc_sun, tr("外观与语言"), g.accent, value = themeName() + " · " + languageName()) { open(SettingsPage.Appearance) }
        MenuRow(R.drawable.oc_lock, tr("安全与隐私"), g.done, value = if (AppLock.enabled) tr("应用锁已开启") else null) { open(SettingsPage.Security) }
        MenuRow(R.drawable.oc_shield_lock, tr("两步验证与数字批准"), g.success, count = Session.totpAccounts().size.takeIf { it > 0 }) { open(SettingsPage.TwoFactor) }
        MenuRow(R.drawable.oc_globe, tr("网络与加速"), g.attention, value = accelerationName()) { open(SettingsPage.Network) }
    }
    Spacer(Modifier.height(16.dp))
    MenuGroup {
        MenuRow(R.drawable.oc_key, tr("Token 与 API 配额"), g.severe) { open(SettingsPage.Quota) }
        MenuRow(R.drawable.oc_log, tr("存储与诊断"), g.fgMuted, value = AppLog.entries.size.toString() + tr(" 条日志")) { open(SettingsPage.Storage) }
        MenuRow(
            R.drawable.oc_info, tr("关于与更新"), g.accent,
            value = if (Update.available != null) tr("有新版") else "v${BuildConfig.VERSION_NAME}",
        ) { open(SettingsPage.About) }
    }
    Spacer(Modifier.height(24.dp))
    GhButton(tr("退出当前账号"), Modifier.padding(horizontal = 16.dp).fillMaxWidth(), danger = true, icon = R.drawable.oc_sign_out, onClick = onLogout)
}

@Composable
private fun themeName() = listOf(tr("跟随系统"), tr("浅色"), tr("深色"))[Session.themeMode.coerceIn(0, 2)]

@Composable
private fun languageName() = when (com.mobilegh.i18n.I18n.choice) {
    com.mobilegh.i18n.AppLanguage.Chinese -> tr("简体中文")
    com.mobilegh.i18n.AppLanguage.English -> "English"
    com.mobilegh.i18n.AppLanguage.System -> tr("跟随系统")
}

@Composable
private fun accelerationName() = listOf(tr("自动加速"), tr("手动选择"), tr("已关闭"))[Net.mode.coerceIn(0, 2)]

/** 外观与语言：主题、界面语言、代码换行 */
@Composable
private fun AppearanceSettings() {
    val g = Gh.c
    val ctx = rememberCtx()
    SectionTitle(tr("主题"))
    Column(Modifier.padding(horizontal = 16.dp)) {
        Segmented(listOf(tr("跟随系统"), tr("浅色"), tr("深色")), Session.themeMode, Modifier.fillMaxWidth()) { Session.setTheme(it) }
    }
    SectionTitle(tr("界面语言"))
    Column(Modifier.padding(horizontal = 16.dp)) {
        // 语言名用各自的文字显示，不随界面语言翻译
        Segmented(
            listOf(tr("简体中文"), "English", tr("跟随系统")), com.mobilegh.i18n.I18n.choice.ordinal, Modifier.fillMaxWidth(),
        ) { index ->
            com.mobilegh.i18n.I18n.select(com.mobilegh.i18n.AppLanguage.entries[index])
            ctx.findFragmentActivity()?.recreate()
        }
        Text(
            tr("默认简体中文。切换后立即生效；「跟随系统」在系统语言为中文时显示中文，其余显示英文。"),
            Modifier.padding(top = 8.dp), fontSize = 12.sp, lineHeight = 18.sp, color = g.fgMuted,
        )
    }
    SectionTitle(tr("阅读"))
    MenuGroup {
        SwitchRow(tr("代码自动换行"), tr("查看源码和日志时自动折行"), Session.codeWrap) { Session.toggleWrap() }
    }
}

/** 登录时长：OAuth 授权有效期由 GitHub 决定，这里给出重新授权与检查入口 */
@Composable
private fun LoginLifetimeSection() {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    SectionTitle(tr("登录时长"))
    MenuGroup {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MenuIcon(R.drawable.oc_clock, g.success)
                Spacer(Modifier.width(14.dp))
                Text(tr("授权有效期"), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                Pill(tr("由 GitHub 决定"), g.fgMuted)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                tr("OAuth 授权的有效期取决于 GitHub 服务端设置。旧授权不会自动延长。\n") +
                    tr("Token 登录的有效期由你在 GitHub 设置的到期日决定；授权被撤销时仍需重新登录。"),
                fontSize = 12.sp, lineHeight = 18.sp, color = g.fgMuted,
            )
        }
        MenuRow(R.drawable.oc_mark_github, tr("重新登录，获取新授权")) { nav.push(Screen.Reauthorize) }
        MenuRow(R.drawable.oc_link_external, tr("检查 OAuth 过期设置")) { ctx.openBrowser(BuildConfig.GITHUB_CLIENT_SETTINGS_URL) }
    }
    Text(
        tr("仅 OAuth 应用所有者可修改。Opt-in 表示固定过期已关闭；若显示 Opt-out，点击它并完成身份验证即可关闭。"),
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp), fontSize = 12.sp, lineHeight = 18.sp, color = g.fgMuted,
    )
}

/** Token 权限与 API 配额 */
@Composable
private fun QuotaSection() {
    val g = Gh.c
    val nav = LocalNav.current
    val rate = rememberLoader("rate") { GitHub.rateLimit() }
    val scopes = rememberLoader("scopes") { GitHub.tokenScopes() }
    SectionTitle(tr("Token 与 API 配额"))
    Card(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(tr("Token 权限（scopes）"), fontSize = 13.sp, color = g.fgMuted)
            Text(
                scopes.data?.ifBlank { tr("Fine-grained Token（无 scope 列表）") } ?: if (scopes.loading) tr("加载中…") else tr("未知"),
                fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = g.fg, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            // 与应用所需权限比对，缺什么、影响什么、怎么补，一眼可见（细粒度 Token 无 scope 列表，不报缺失）
            val granted = DeviceAuth.parseScopes(scopes.data?.takeIf { it.isNotBlank() })
            LaunchedEffect(scopes.data) { if (granted != null) Session.applyScopes(granted) }
            val missingScopes = DeviceAuth.missingScopes(granted)
            if (missingScopes.isNotEmpty()) {
                Text(trf("缺少 {0} 项权限，以下功能不可用：", missingScopes.size), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = g.attention)
                missingScopes.forEach {
                    Text("• $it：${DeviceAuth.scopeFeature(it)}", fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 3.dp))
                }
                TextLink(tr("重新登录补齐权限")) { nav.push(Screen.Reauthorize) }
                Spacer(Modifier.height(8.dp))
            }
            val res = rate.data?.resources
            listOf("core" to "REST API", "graphql" to "GraphQL", "search" to tr("搜索")).forEach { (k, label) ->
                res?.get(k)?.let { r ->
                    com.mobilegh.ui.components.RatioRow(trf("{0}  剩余 {1}/{2}", label, r.remaining, r.limit), r.remaining, r.limit, if (r.remaining < r.limit / 10) g.danger else g.success)
                }
            }
            res?.get("core")?.let {
                Text(trf("重置时间：{0}", java.time.Instant.ofEpochSecond(it.reset).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)), fontSize = 12.sp, color = g.fgMuted)
            }
            Spacer(Modifier.height(8.dp))
            Text(tr("本应用利用 ETag 条件请求缓存数据，未变化的内容不消耗配额。"), fontSize = 12.sp, color = g.fgMuted)
        }
    }
}

@Composable
private fun NetworkSection(onAdd: () -> Unit, onApi: () -> Unit) {
    val g = Gh.c
    LaunchedEffect(Unit) {
        // 结果超过 30 分钟则重新测速
        if (System.currentTimeMillis() - Net.lastTest > 30 * 60_000) Net.speedTestAsync()
    }
    SectionTitle(tr("网络加速")) {
        if (Net.testing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = g.fgMuted)
        else com.mobilegh.ui.components.TextLink(tr("立即测速")) { Net.speedTestAsync() }
    }
    MenuGroup {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        MenuIcon(R.drawable.oc_zap, g.attention)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("加速模式"), fontSize = 15.sp, color = g.fg)
            Text(
                when (Net.mode) {
                    0 -> trf("自动：源码/图片 {0} · 下载 {1}", Net.rawNode().name, Net.downloadNode().name)
                    1 -> trf("手动：源码/图片 {0} · 下载 {1}", Net.rawNode().name, Net.downloadNode().name)
                    else -> tr("已关闭，全部直连 GitHub")
                },
                fontSize = 12.sp, color = g.fgMuted,
            )
        }
        val modes = listOf(tr("自动（推荐）"), tr("手动选择"), tr("关闭"))
        Dropdown(modes[Net.mode], modes, { Net.changeMode(it) })
    }
    }
    Spacer(Modifier.height(8.dp))
    MenuGroup {
        Column {
            val raw = Net.rawNode().id
            val dl = Net.downloadNode().id
            Net.nodes.forEachIndexed { i, n ->
                if (i > 0) HDivider()
                val ms = Net.latency[n.id]
                Row(
                    Modifier.fillMaxWidth().clickable { Net.setManual(n.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(8.dp).background(
                            when {
                                ms == null -> g.neutralMuted
                                ms < 0 -> g.danger
                                ms < 600 -> g.success
                                ms < 1500 -> g.attention
                                else -> g.severe
                            },
                            CircleShape,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.name, fontSize = 14.sp, color = g.fg, fontWeight = if (n.id == raw || n.id == dl) FontWeight.SemiBold else null)
                        Text(
                            when (n.type) {
                                NodeType.Direct -> tr("官方地址")
                                NodeType.JsDelivr -> tr("CDN · 图片/文件（分支内容可能有缓存）")
                                NodeType.Prefix -> if (n.custom) tr("自定义代理 · 文件/下载/克隆") else tr("代理 · 文件/下载/克隆")
                            },
                            fontSize = 11.sp, color = g.fgMuted,
                        )
                    }
                    if (n.id == raw) { Pill(tr("文件"), g.accent); Spacer(Modifier.width(4.dp)) }
                    if (n.id == dl && n.type != NodeType.Direct) { Pill(tr("下载"), g.done); Spacer(Modifier.width(4.dp)) }
                    Text(
                        when { ms == null -> "—"; ms < 0 -> tr("超时"); else -> "${ms}ms" },
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = if (ms != null && ms < 0) g.danger else g.fgMuted,
                    )
                    if (n.custom) OcButton(R.drawable.oc_x, { Net.removeCustom(n.id) }, g.fgMuted)
                }
            }
        }
    }
    Text(
        tr("加速仅用于公开内容：README 图片、源码原文、Release 下载与克隆地址。Token 不会发送给加速节点；私有仓库始终直连。点按节点可手动指定。"),
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp), fontSize = 12.sp, lineHeight = 18.sp, color = g.fgMuted,
    )
    MenuGroup {
    MenuRow(R.drawable.oc_plus, tr("添加自定义节点"), onClick = onAdd)
    com.mobilegh.ui.components.NetworkSettingsRow()
    MenuRow(R.drawable.oc_globe, tr("API 反向代理（高级）"), value = if (Net.apiProxy.isBlank()) tr("官方") else tr("已启用"), onClick = onApi)
    }
}
