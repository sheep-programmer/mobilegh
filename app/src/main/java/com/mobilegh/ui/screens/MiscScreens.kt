package com.mobilegh.ui.screens

import com.mobilegh.ui.components.ApprovalSettingsRows

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.BuildConfig
import com.mobilegh.R
import com.mobilegh.data.Api
import com.mobilegh.data.AppLog
import com.mobilegh.data.Downloads
import com.mobilegh.data.GitHub
import com.mobilegh.data.Gist
import com.mobilegh.data.Net
import com.mobilegh.data.NodeType
import com.mobilegh.data.Session
import com.mobilegh.data.Totp
import com.mobilegh.data.Update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MenuRow
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.fmtDateTime
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.highlightLines
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.share
import com.mobilegh.ui.components.syntaxColors
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.CodeStyle
import com.mobilegh.ui.components.MdText
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ======================= Gist =======================

@Composable
fun GistsScreen(login: String?) {
    val nav = LocalNav.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val mine = rememberPager("gists:$login") { p, f -> GitHub.gists(login, p, f) }
    val starred = rememberPager("gists:starred") { p, f -> GitHub.starredGists(p, f) }
    Page("Gists", subtitle = login ?: Session.login) { pad ->
        Column(Modifier.padding(pad)) {
            if (login == null) {
                Chips(listOf("我的", "已 Star"), tab) { tab = it }
                HDivider()
            }
            PagedList(if (tab == 0 || login != null) mine else starred, empty = "没有 Gist") { gist ->
                GistRow(gist) { nav.push(Screen.GistDetail(gist.id)) }
            }
        }
    }
}

@Composable
private fun GistRow(g0: Gist, onClick: () -> Unit) {
    val g = Gh.c
    val first = g0.files.keys.firstOrNull() ?: g0.id
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(g0.owner?.avatarUrl, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text("${g0.owner?.login ?: ""} / ", color = g.fgMuted, fontSize = 14.sp)
            Text(first, fontWeight = FontWeight.SemiBold, color = g.fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
            Spacer(Modifier.width(6.dp))
            if (!g0.public) Pill("私密")
        }
        if (!g0.description.isNullOrBlank()) {
            MdText(g0.description, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = g.fgMuted, maxLines = 2)
        }
        Text("${g0.files.size} 个文件 · ${g0.comments} 条评论 · 更新于 ${relTime(g0.updatedAt)}", fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun GistDetailScreen(id: String) {
    val g = Gh.c
    val ctx = rememberCtx()
    val sc = syntaxColors(g)
    val gist = rememberLoader("gist:${g.dark}") { f ->
        val d = GitHub.gist(id, f)
        d to withContext(Dispatchers.Default) {
            d.files.values.map { file -> file.filename to highlightLines(file.content.orEmpty(), file.filename, sc) }
        }
    }
    Page(
        gist.data?.first?.files?.keys?.firstOrNull() ?: "Gist", subtitle = gist.data?.first?.owner?.login,
        actions = {
            MoreMenu(listOf(
                MenuAction("分享") { ctx.share("https://gist.github.com/$id") },
                MenuAction("在浏览器打开") { ctx.openBrowser("https://gist.github.com/$id") },
            ))
        },
    ) { pad ->
        LoadBox(gist, Modifier.padding(pad).fillMaxSize()) { (d, files) ->
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            if (!d.description.isNullOrBlank()) Text(d.description, fontSize = 15.sp, color = g.fg)
                            Text("创建于 ${fmtDateTime(d.createdAt)} · ${if (d.public) "公开" else "私密"}", fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    files.forEach { (fname, lines) ->
                        item(key = "h:$fname") {
                            HDivider()
                            Row(Modifier.fillMaxWidth().background(g.canvasSubtle).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Oc(R.drawable.oc_file_code, g.fgMuted)
                                Spacer(Modifier.width(8.dp))
                                Text(fname, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = g.fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                OcButton(R.drawable.oc_copy, { ctx.copy(d.files[fname]?.content.orEmpty()) }, g.fgMuted)
                            }
                            HDivider()
                        }
                        itemsIndexed(lines, key = { i, _ -> "$fname:$i" }) { i, line -> GistLine(i, line) }
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun GistLine(i: Int, line: AnnotatedString) {
    val g = Gh.c
    Row(Modifier.fillMaxWidth()) {
        Text("${i + 1}", Modifier.width(40.dp).padding(end = 8.dp), style = CodeStyle, color = g.fgMuted.copy(alpha = 0.7f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text(line, Modifier.weight(1f).padding(end = 8.dp), style = CodeStyle, color = g.fg)
    }
}

// ======================= 两步验证（TOTP） =======================

/**
 * 存本账号的 TOTP 密钥后，登录时验证器这一步会自动填码——不用再掏第二台设备。
 * 密钥经 Keystore 加密存本机，只在本机算码。
 */
@Composable
private fun TwoFactorSection() {
    val nav = LocalNav.current
    SectionTitle("两步验证")
    MenuGroup {
        ApprovalSettingsRows()
        MenuRow(R.drawable.oc_shield_lock, "两步验证与数字批准", count = Session.totpAccounts().size.takeIf { it > 0 }) {
            nav.push(Screen.Authenticator)
        }
        MenuRow(R.drawable.oc_organization, "组织访问诊断") { nav.push(Screen.OrgDiagnostics) }
    }
}

// ======================= 设置 =======================

@Composable
fun SettingsScreen() {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val rate = rememberLoader("rate") { GitHub.rateLimit() }
    val scopes = rememberLoader("scopes") { GitHub.tokenScopes() }
    var confirmOut by remember { mutableStateOf(false) }
    var addNode by remember { mutableStateOf(false) }
    var apiDialog by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }

    Page("设置") { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("账号")
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
                    if (cur) Pill("当前", g.success) else Text("切换", color = g.accent, fontSize = 13.sp)
                }
            }
            MenuRow(R.drawable.oc_plus, "添加账号") { nav.push(Screen.Login) }
            }

            SectionTitle("登录时长")
            Card(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Oc(R.drawable.oc_clock, g.success, 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("授权有效期", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                        Pill("由 GitHub 决定", g.fgMuted)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "OAuth 授权的有效期取决于 GitHub 服务端设置。旧授权不会自动延长。\n" +
                            "Token 登录的有效期由你在 GitHub 设置的到期日决定；授权被撤销时仍需重新登录。",
                        fontSize = 12.sp, lineHeight = 18.sp, color = g.fgMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    MenuRow(R.drawable.oc_mark_github, "重新登录，获取新授权") {
                        nav.push(Screen.Login)
                    }
                    MenuRow(R.drawable.oc_link_external, "检查 OAuth 过期设置") {
                        ctx.openBrowser(BuildConfig.GITHUB_CLIENT_SETTINGS_URL)
                    }
                    Text(
                        "仅 OAuth 应用所有者可修改。Opt-in 表示固定过期已关闭；若显示 Opt-out，点击它并完成身份验证即可关闭。",
                        fontSize = 12.sp, lineHeight = 18.sp, color = g.fgMuted,
                    )
                }
            }

            SectionTitle("外观")
            MenuGroup {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Oc(if (Gh.c.dark) R.drawable.oc_moon else R.drawable.oc_sun, g.fgMuted, 18.dp)
                Spacer(Modifier.width(14.dp))
                Text("主题", Modifier.weight(1f), fontSize = 15.sp, color = g.fg)
                val modes = listOf("跟随系统", "浅色", "深色")
                Dropdown(modes[Session.themeMode], modes, { Session.setTheme(it) })
            }
            SwitchRow("代码自动换行", "查看源码和日志时自动折行", Session.codeWrap) { Session.toggleWrap() }
            }

            TwoFactorSection()

            NetworkSection(onAdd = { addNode = true }, onApi = { apiDialog = true })

            SectionTitle("Token 与 API 配额")
            Card(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Token 权限（scopes）", fontSize = 13.sp, color = g.fgMuted)
                Text(
                    scopes.data?.ifBlank { "Fine-grained Token（无 scope 列表）" } ?: if (scopes.loading) "加载中…" else "未知",
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = g.fg, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                )
                val res = rate.data?.resources
                listOf("core" to "REST API", "graphql" to "GraphQL", "search" to "搜索").forEach { (k, label) ->
                    res?.get(k)?.let { r ->
                        com.mobilegh.ui.components.RatioRow("$label  剩余 ${r.remaining}/${r.limit}", r.remaining, r.limit, if (r.remaining < r.limit / 10) g.danger else g.success)
                    }
                }
                res?.get("core")?.let {
                    Text("重置时间：${java.time.Instant.ofEpochSecond(it.reset).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)}", fontSize = 12.sp, color = g.fgMuted)
                }
                Spacer(Modifier.height(8.dp))
                Text("本应用利用 ETag 条件请求缓存数据，未变化的内容不消耗配额。", fontSize = 12.sp, color = g.fgMuted)
            }
            }

            SectionTitle("存储")
            MenuGroup {
            MenuRow(R.drawable.oc_download, "下载记录", count = Downloads.activeCount.takeIf { it > 0 }) { Downloads.showHistory = true }
            MenuRow(R.drawable.oc_log, "日志与诊断", value = AppLog.entries.size.toString() + " 条") { nav.push(Screen.Logs) }
            MenuRow(R.drawable.oc_trash, "清除缓存") {
                Api.clearCache()
                ctx.cacheDir.listFiles()?.filter { it.name != "http" }?.forEach { it.deleteRecursively() }
                ctx.toast("缓存已清除")
            }
            }

            SectionTitle("关于")
            MenuGroup {
            MenuRow(
                R.drawable.oc_download,
                "检查更新",
                value = when {
                    Update.checking -> "检查中…"
                    Update.available != null -> "有新版 v${Update.available!!.version}"
                    else -> "v${BuildConfig.VERSION_NAME}"
                },
            ) { showUpdate = true }
            MenuRow(R.drawable.oc_info, "MobileGH", value = "v${BuildConfig.VERSION_NAME}") {}
            MenuRow(R.drawable.oc_link_external, "管理 GitHub Token") { ctx.openBrowser("https://github.com/settings/tokens") }
            MenuRow(R.drawable.oc_shield_lock, "已授权的 OAuth 应用") { ctx.openBrowser("https://github.com/settings/applications") }
            MenuRow(R.drawable.oc_bell, "GitHub 通知设置") { ctx.openBrowser("https://github.com/settings/notifications") }
            }

            Spacer(Modifier.height(20.dp))
            GhButton("退出当前账号", Modifier.padding(horizontal = 16.dp).fillMaxWidth(), danger = true, icon = R.drawable.oc_sign_out) { confirmOut = true }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmOut) GhDialog("退出登录", { confirmOut = false }, confirm = "退出", danger = true, onConfirm = {
        confirmOut = false
        Session.signOut()
    }) { Text("将从本机移除 ${Session.login} 的 Token。", color = g.fg) }

    if (addNode) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        GhDialog("添加自定义加速节点", { addNode = false }, confirm = "添加", confirmEnabled = url.length > 10, onConfirm = {
            if (Net.addCustom(name, url)) { addNode = false; ctx.toast("已添加，正在测速") } else ctx.toast("地址必须以 https:// 开头")
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("填写 gh-proxy 类型的前缀代理地址，节点会以「前缀 + GitHub 原始链接」的方式访问。", fontSize = 13.sp, color = g.fgMuted)
                GhField(name, { name = it }, "名称（可选）")
                GhField(url, { url = it }, "前缀地址，如 https://ghfast.top/")
            }
        }
    }

    if (apiDialog) {
        var url by remember { mutableStateOf(Net.apiProxy) }
        GhDialog("API 反向代理（高级）", { apiDialog = false }, confirm = "保存", onConfirm = {
            apiDialog = false
            Net.setApi(url)
            ctx.toast(if (url.isBlank()) "已恢复官方 API" else "已启用 API 代理")
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "⚠️ 所有 API 请求（包括你的 Token）都会经过该地址。只填写你自己部署的代理（如 Cloudflare Worker 反代 api.github.com），切勿使用来路不明的公共代理。留空则使用官方 api.github.com。",
                    fontSize = 13.sp, color = g.attention,
                )
                GhField(url, { url = it }, "代理地址", placeholder = "https://gh-api.example.workers.dev")
            }
        }
    }

    if (showUpdate) {
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { showUpdate = false },
            containerColor = g.canvas,
            shape = RoundedCornerShape(12.dp),
            title = { Text("在线更新", color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("当前版本 v${BuildConfig.VERSION_NAME} · 设备架构 ${Update.abi}", fontSize = 13.sp, color = g.fgMuted)
                    Spacer(Modifier.height(12.dp))
                    val info = Update.available
                    when {
                        Update.checking -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = g.accent, strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("正在读取最新 Release…", color = g.fg, fontSize = 14.sp)
                            }
                        }
                        info != null -> {
                            Text("发现新版本 v${info.version}", color = g.fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(info.assetName + " · " + fmtSize(info.size), fontSize = 12.sp, color = g.fgMuted)
                            info.notes?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(10.dp))
                                Text(it.take(600), fontSize = 12.sp, lineHeight = 18.sp, color = g.fgMuted, maxLines = 8, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        else -> {
                            Text(Update.error ?: "已是最新版本", color = if (Update.error != null) g.danger else g.fg, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                val info = Update.available
                if (info != null) {
                    TextButton({
                        showUpdate = false
                        Update.download(info)
                        ctx.toast("正在下载，完成后在下载记录中安装")
                    }) { Text("下载更新", color = g.accent) }
                } else {
                    TextButton({ scope.launch { Update.check() } }) { Text("重新检查", color = g.accent) }
                }
            },
            dismissButton = { TextButton({ showUpdate = false }) { Text("关闭", color = g.fgMuted) } },
        )
        LaunchedEffect(showUpdate) { if (Update.available == null && !Update.checking) Update.check() }
    }
}

@Composable
private fun NetworkSection(onAdd: () -> Unit, onApi: () -> Unit) {
    val g = Gh.c
    LaunchedEffect(Unit) {
        // 结果超过 30 分钟则重新测速
        if (System.currentTimeMillis() - Net.lastTest > 30 * 60_000) Net.speedTestAsync()
    }
    SectionTitle("网络加速") {
        if (Net.testing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = g.fgMuted)
        else com.mobilegh.ui.components.TextLink("立即测速") { Net.speedTestAsync() }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Oc(R.drawable.oc_zap, g.fgMuted, 18.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("加速模式", fontSize = 15.sp, color = g.fg)
            Text(
                when (Net.mode) {
                    0 -> "自动：源码/图片 ${Net.rawNode().name} · 下载 ${Net.downloadNode().name}"
                    1 -> "手动：源码/图片 ${Net.rawNode().name} · 下载 ${Net.downloadNode().name}"
                    else -> "已关闭，全部直连 GitHub"
                },
                fontSize = 12.sp, color = g.fgMuted,
            )
        }
        val modes = listOf("自动（推荐）", "手动选择", "关闭")
        Dropdown(modes[Net.mode], modes, { Net.changeMode(it) })
    }
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column {
            val raw = Net.rawNode().id
            val dl = Net.downloadNode().id
            Net.nodes.forEachIndexed { i, n ->
                if (i > 0) HDivider()
                val ms = Net.latency[n.id]
                Row(
                    Modifier.fillMaxWidth().clickable { Net.setManual(n.id) }.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(8.dp).background(
                            when {
                                ms == null -> g.neutralMuted
                                ms < 0 -> g.danger
                                ms < 600 -> g.success
                                ms < 1500 -> g.attention
                                else -> Color(0xFFBC4C00)
                            },
                            CircleShape,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.name, fontSize = 14.sp, color = g.fg, fontWeight = if (n.id == raw || n.id == dl) FontWeight.SemiBold else null)
                        Text(
                            when (n.type) {
                                NodeType.Direct -> "官方地址"
                                NodeType.JsDelivr -> "CDN · 图片/文件（分支内容可能有缓存）"
                                NodeType.Prefix -> if (n.custom) "自定义代理 · 文件/下载/克隆" else "代理 · 文件/下载/克隆"
                            },
                            fontSize = 11.sp, color = g.fgMuted,
                        )
                    }
                    if (n.id == raw) { Pill("文件", g.accent); Spacer(Modifier.width(4.dp)) }
                    if (n.id == dl && n.type != NodeType.Direct) { Pill("下载", g.done); Spacer(Modifier.width(4.dp)) }
                    Text(
                        when { ms == null -> "—"; ms < 0 -> "超时"; else -> "${ms}ms" },
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = if (ms != null && ms < 0) g.danger else g.fgMuted,
                    )
                    if (n.custom) OcButton(R.drawable.oc_x, { Net.removeCustom(n.id) }, g.fgMuted)
                }
            }
        }
    }
    Text(
        "加速仅用于公开内容：README 图片、源码原文、Release 下载与克隆地址。Token 不会发送给加速节点；私有仓库始终直连。点按节点可手动指定。",
        Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = g.fgMuted,
    )
    Spacer(Modifier.height(10.dp))
    MenuGroup {
    MenuRow(R.drawable.oc_plus, "添加自定义节点", onClick = onAdd)
    MenuRow(R.drawable.oc_globe, "API 反向代理（高级）", value = if (Net.apiProxy.isBlank()) "官方" else "已启用", onClick = onApi)
    }
}
