package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.ProjectOwnerKind
import com.mobilegh.data.SearchType
import com.mobilegh.data.GitHub
import com.mobilegh.data.HomePrefs
import com.mobilegh.data.SubscriptionSettings
import com.mobilegh.data.Repo
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.Screen
import com.mobilegh.nav.UserKind
import com.mobilegh.nav.act
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.retain
import com.mobilegh.nav.guarded
import com.mobilegh.nav.sameAccount
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.boxedItems
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.IconText
import com.mobilegh.ui.components.LanguageBar
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MenuRow
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.Topic
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.fmtCount
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.share
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.components.MdText
import com.mobilegh.data.OrganizationAccess
import com.mobilegh.data.RepoWrite
import com.mobilegh.data.Session
import com.mobilegh.nav.friendly
import com.mobilegh.ui.components.RadioRow
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.theme.Gh
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun RepoScreen(owner: String, name: String) {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val g = Gh.c
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    val ref = retain("ref") { mutableStateOf<String?>(null) }
    val readme = rememberLoader("readme:${ref.value}") { GitHub.readme(owner, name, ref.value, it) }
    val langs = rememberLoader("langs") { GitHub.languages(owner, name) }
    val starStatus = rememberLoader("star-status") { entry.guarded { GitHub.isStarred(owner, name) } }
    val starBusyState = retain("star-busy") { mutableStateOf(false) }
    var starBusy by starBusyState
    val watching = rememberLoader("watch-status") { entry.guarded { SubscriptionSettings.current(owner, name) } }
    var showBranches by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    var showFork by remember { mutableStateOf(false) }
    var showSubscription by remember { mutableStateOf(false) }
    var showLists by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val r = repo.data
    val admin = r?.permissions?.admin == true
    val branch = ref.value ?: r?.defaultBranch

    Page(
        name, subtitle = owner,
        actions = {
            MoreMenu(buildList {
                add(MenuAction("分享") { ctx.share("https://github.com/$owner/$name") })
                add(MenuAction("复制链接") { ctx.copy("https://github.com/$owner/$name") })
                add(MenuAction("克隆地址…") { showClone = true })
                if (r != null) add(MenuAction("加入自定义列表") { showLists = true })
                if (HomePrefs.layout().isFavorite(owner, name)) add(MenuAction("移出首页收藏") {
                    HomePrefs.toggleFavorite(owner, name)
                    ctx.toast("已移出首页收藏")
                }) else add(MenuAction("加入首页收藏") {
                    // 使用仓库返回的规范大小写，避免收藏里出现 Owner/Repo 与 owner/repo 两种写法
                    runCatching { HomePrefs.toggleFavorite(r?.owner?.login ?: owner, r?.name ?: name) }
                        .onSuccess { ctx.toast("已加入首页收藏") }
                        .onFailure { ctx.toast(it.message ?: "收藏失败") }
                })
                add(MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/$owner/$name") })
                add(MenuAction("Fork 此仓库") { showFork = true })
                if (admin) add(MenuAction("仓库设置") { nav.push(Screen.RepoSettings(owner, name)) })
            })
        },
    ) { pad ->
        when {
            r == null && repo.error != null -> ErrorState(repo.error!!, Modifier.padding(pad)) { repo.load(true) }
            r == null -> Loading(Modifier.padding(pad).fillMaxSize())
            else -> PullToRefreshBox(repo.refreshing, { if (!starBusy) { repo.refresh(); readme.refresh(); langs.refresh(); starStatus.refresh(); watching.refresh() } }, Modifier.padding(pad)) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item { RepoHeader(r) }
                    item {
                        // Star / Watch / Fork
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val s = starStatus.data == true
                            GhButton(
                                "${if (s) "已 Star" else "Star"} ${fmtCount(r.stargazersCount)}",
                                Modifier.weight(1f), icon = if (s) R.drawable.oc_star_fill else R.drawable.oc_star, enabled = entry.sameAccount() && starStatus.data != null && !starBusy && !starStatus.loading && !repo.loading,
                                // 和 GitHub 一样：点按切换 Star，长按管理这个仓库所在的列表
                                onLongClick = { showLists = true },
                            ) {
                                if (!starBusy && entry.sameAccount()) {
                                    val on = !s
                                    starStatus.accept(on)
                                    starBusy = true
                                    entry.act({
                                        if (entry.sameAccount()) {
                                            starStatus.accept(s)
                                            starStatus.refresh()
                                            repo.refresh()
                                            ctx.toast(it)
                                        }
                                    }) {
                                        try {
                                            entry.guarded { GitHub.star(owner, name, on) }
                                            repo.refresh()
                                            starStatus.refresh()
                                        } finally { starBusy = false }
                                    }
                                }
                            }
                            GhButton(watching.data?.title ?: if (watching.loading) "读取订阅…" else "订阅设置", Modifier.weight(1f), icon = R.drawable.oc_eye, enabled = entry.sameAccount()) {
                                showSubscription = true
                            }
                        }
                        starStatus.error?.let { InlineError("Star 状态读取失败：$it", Modifier.padding(horizontal = 16.dp)) { if (!starBusy && entry.sameAccount()) starStatus.refresh() } }
                        watching.error?.let { InlineError("订阅状态读取失败：$it", Modifier.padding(horizontal = 16.dp)) { if (entry.sameAccount()) watching.refresh() } }
                        Spacer(Modifier.height(16.dp))
                    }
                    item {
                        MenuGroup {
                            MenuRow(R.drawable.oc_search, "查找文件", g.fgMuted) { nav.push(Screen.FileFinder(owner, name, branch)) }
                            // 仓库内代码搜索使用独立搜索页的代码类型，并预填 repo:，可继续组合语言/路径/文件名
                            MenuRow(R.drawable.oc_code, "搜索代码", g.fgMuted) { nav.push(Screen.Search(SearchType.CODE, "repo:$owner/$name ")) }
                            MenuRow(R.drawable.oc_issue_opened, "Issues", g.success) { nav.push(Screen.Issues(owner, name, false)) }
                            MenuRow(R.drawable.oc_git_pull_request, "Pull Requests", g.accent) { nav.push(Screen.Issues(owner, name, true)) }
                            if (r.hasDiscussions) MenuRow(R.drawable.oc_comment_discussion, "Discussions", g.done) { nav.push(Screen.Discussions(owner, name)) }
                            MenuRow(R.drawable.oc_table, "项目", g.success) { nav.push(Screen.Projects(ProjectOwnerKind.Repo, owner, name)) }
                            MenuRow(R.drawable.oc_play, "Actions", g.fgMuted) { nav.push(Screen.Actions(owner, name)) }
                            MenuRow(R.drawable.oc_tag, "Releases", g.success) { nav.push(Screen.Releases(owner, name)) }
                            MenuRow(R.drawable.oc_people, "贡献者统计", g.severe) { nav.push(Screen.Contributors(owner, name)) }
                            MenuRow(R.drawable.oc_graph, "洞察 · 流量统计", g.done) { nav.push(Screen.Insights(owner, name)) }
                            if (admin) {
                                MenuRow(R.drawable.oc_person, "协作者管理", g.sponsors) { nav.push(Screen.Collaborators(owner, name)) }
                                MenuRow(R.drawable.oc_gear, "仓库设置", g.fgMuted) { nav.push(Screen.RepoSettings(owner, name)) }
                            }
                        }
                    }
                    item {
                        // 分支 + 代码
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.weight(1f)
                                    .border(1.dp, g.border, RoundedCornerShape(6.dp))
                                    .background(g.btnBg, RoundedCornerShape(6.dp))
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showBranches = true }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Oc(R.drawable.oc_git_branch, g.fgMuted)
                                Spacer(Modifier.width(6.dp))
                                Text(branch ?: "", fontWeight = FontWeight.SemiBold, color = g.fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Oc(R.drawable.oc_chevron_down, g.fgMuted, 12.dp)
                            }
                            Spacer(Modifier.width(8.dp))
                            GhButton("分支", icon = R.drawable.oc_git_branch) { nav.push(Screen.Branches(owner, name)) }
                        }
                        MenuGroup {
                            MenuRow(R.drawable.oc_code, "浏览代码") { nav.push(Screen.Files(owner, name, "", ref.value)) }
                            MenuRow(R.drawable.oc_git_commit, "提交历史") { nav.push(Screen.Commits(owner, name, ref.value)) }
                            MenuRow(R.drawable.oc_git_branch, "比较分支与提交") { nav.push(Screen.Compare(owner, name)) }
                        }
                    }
                    langs.data?.takeIf { it.isNotEmpty() }?.let { l ->
                        item {
                            Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("语言", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = g.fg)
                                    Spacer(Modifier.height(10.dp))
                                    LanguageBar(l)
                                }
                            }
                        }
                    }
                    item {
                        Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                            Column {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Oc(R.drawable.oc_book, g.fgMuted)
                                    Spacer(Modifier.width(8.dp))
                                    Text("README", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = g.fg)
                                }
                                HDivider()
                                Spacer(Modifier.height(12.dp))
                                val html = readme.data
                                when {
                                    html != null -> HtmlView(
                                        html,
                                        baseUrl = "https://github.com/$owner/$name/blob/${branch ?: "HEAD"}/",
                                        onAnchor = { y -> scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 2, with(density) { (y + 64).dp.roundToPx() }) } },
                                    )
                                    readme.loading -> Loading(Modifier.fillMaxWidth().heightIn(min = 120.dp))
                                    readme.error != null -> ErrorState(readme.error!!, Modifier.fillMaxWidth()) { readme.load(true) }
                                    else -> Text("该仓库没有 README", Modifier.padding(16.dp), color = g.fgMuted)
                                }
                            }
                        }
                    }
                    item { ContributorPreview(owner, name) }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }

    if (showSubscription && entry.sameAccount()) SubscriptionDialog(owner, name, { showSubscription = false }) { watching.accept(it) }
    if (showLists && r != null) AddToStarListsDialog(r) { showLists = false }
    if (showBranches && r != null) {
        BranchPicker(owner, name, branch ?: r.defaultBranch, onDismiss = { showBranches = false }) {
            ref.value = if (it == r.defaultBranch) null else it
            showBranches = false
        }
    }
    if (showFork && r != null) {
        ForkDialog(
            owner = owner,
            name = name,
            repo = r,
            onDismiss = { showFork = false },
            onDone = { forked ->
                showFork = false
                nav.push(Screen.Repo(forked.owner.login, forked.name))
            },
        )
    }
    if (showClone && r != null) {
        GhDialog("克隆", onDismiss = { showClone = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val https = r.cloneUrl ?: "https://github.com/${r.fullName}.git"
                val fast = if (r.isPrivate) null else com.mobilegh.data.Net.cloneUrl(https)
                (listOf("HTTPS" to https) + listOfNotNull(fast?.let { "HTTPS 加速（${it.first}）" to "git clone ${it.second}" }) +
                    listOf("SSH" to (r.sshUrl ?: "git@github.com:${r.fullName}.git"), "GitHub CLI" to "gh repo clone ${r.fullName}")).forEach { (k, v) ->
                    Column {
                        Text(k, fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(bottom = 4.dp))
                        Row(
                            Modifier.fillMaxWidth().border(1.dp, g.border, RoundedCornerShape(6.dp)).background(g.canvasSubtle, RoundedCornerShape(6.dp))
                                .clip(RoundedCornerShape(6.dp)).clickable { ctx.copy(v) }.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 克隆地址是操作对象，必须完整可见（加速地址往往比原地址更长）
                            Text(v, Modifier.weight(1f), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = g.fg)
                            Spacer(Modifier.width(8.dp))
                            Oc(R.drawable.oc_copy, g.fgMuted, 16.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForkDialog(
    owner: String,
    name: String,
    repo: Repo,
    onDismiss: () -> Unit,
    onDone: (Repo) -> Unit,
) {
    val g = Gh.c
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val login = Session.login

    // 组织列表按需拉取：只 fork 到自己账号时不发这个请求
    var toOrg by rememberSaveable { mutableStateOf(false) }
    val orgs = rememberLoader("fork-orgs") { OrganizationAccess.read(it).orgs.map { o -> o.login } }
    var org by rememberSaveable { mutableStateOf("") }

    var newName by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf(repo.description.orEmpty()) }
    // 默认只复制默认分支：与 GitHub 网页端一致，也避免把无关分支一起带走
    var defaultOnly by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val trimmedName = newName.trim()
    // 留空表示沿用原仓库名
    val targetName = trimmedName.ifEmpty { name }
    val nameInvalid = trimmedName.isNotEmpty() && !RepoWrite.validBranch(trimmedName)
    val orgMissing = toOrg && org.isBlank()
    val valid = !nameInvalid && !orgMissing && !busy

    fun submit() {
        busy = true
        error = null
        entry.scope.launch {
            try {
                val forked = GitHub.fork(
                    owner, name,
                    organization = if (toOrg) org.trim() else null,
                    newName = trimmedName.ifEmpty { null },
                    defaultBranchOnly = defaultOnly,
                )
                // fork 已经建好；描述写失败只提示，不当作 fork 失败
                var descFailed = false
                if (desc.trim() != repo.description.orEmpty().trim()) {
                    descFailed = try {
                        GitHub.setDescription(forked.owner.login, forked.name, desc.trim()); false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        true
                    }
                }
                busy = false
                ctx.toast(
                    when {
                        descFailed -> "已 Fork 到 ${forked.fullName}，但描述未能写入"
                        else -> "已 Fork 到 ${forked.fullName}"
                    }
                )
                onDone(forked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                busy = false
                error = e.friendly()
            }
        }
    }

    GhDialog(
        "Fork 此仓库",
        onDismiss = { if (!busy) onDismiss() },
        confirm = if (busy) "Fork 中…" else "Fork",
        confirmEnabled = valid,
        onConfirm = ::submit,
    ) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            Text("$owner/$name", fontSize = 12.sp, color = g.fgMuted)

            Spacer(Modifier.height(10.dp))
            RadioRow(!toOrg, "Fork 到我的账号", login?.let { "@$it" }, enabled = !busy) { toOrg = false }
            RadioRow(toOrg, "Fork 到组织", "需要对该组织有创建仓库的权限", enabled = !busy) { toOrg = true }

            if (toOrg) {
                Spacer(Modifier.height(6.dp))
                val list = orgs.data
                when {
                    list != null && list.isEmpty() ->
                        Text("当前授权没有返回可用的组织", fontSize = 12.sp, color = g.danger)
                    list != null -> {
                        Chips(list, list.indexOf(org).coerceAtLeast(0), onSelect = { org = list[it] })
                        if (org.isBlank()) Text("请选择组织", fontSize = 12.sp, color = g.danger)
                    }
                    orgs.error != null -> InlineError(orgs.error!!) { orgs.refresh() }
                    else -> Loading(Modifier.fillMaxWidth().height(60.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            GhField(targetName, { newName = it }, "仓库名称", placeholder = name)
            if (nameInvalid) Text("仓库名称无效", fontSize = 12.sp, color = g.danger)

            Spacer(Modifier.height(10.dp))
            GhField(desc, { desc = it }, "描述", singleLine = false, minLines = 2)

            Spacer(Modifier.height(10.dp))
            SwitchRow(
                "只复制默认分支",
                "关闭后连同其余分支一起 fork；GitHub 无法只挑某几个分支",
                defaultOnly,
                enabled = !busy,
            ) { defaultOnly = it }
            if (repo.defaultBranch.isNotBlank()) {
                Text(
                    "默认分支：${repo.defaultBranch}",
                    Modifier.padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
                    fontSize = 12.sp,
                    color = g.fgMuted,
                )
            }

            error?.let { InlineError(it) }
        }
    }
}

@Composable
private fun RepoHeader(r: Repo) {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.clickable {
            nav.push(if (r.owner.type == "Organization") Screen.Org(r.owner.login) else Screen.Profile(r.owner.login))
        }, verticalAlignment = Alignment.CenterVertically) {
            Avatar(r.owner.avatarUrl, 22.dp, square = r.owner.type == "Organization")
            Spacer(Modifier.width(8.dp))
            Text(r.owner.login, color = g.fgMuted, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = g.fg, modifier = Modifier.weight(1f, false))
            Spacer(Modifier.width(8.dp))
            Pill(if (r.isPrivate) "私有" else if (r.visibility == "internal") "内部" else "公开")
        }
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r.archived) Pill("已归档", g.attention)
            if (r.isTemplate) Pill("模板")
            if (r.disabled) Pill("已禁用", g.danger)
        }
        r.parent?.let { p ->
            Text(
                "Fork 自 ${p.fullName}", color = g.accent, fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp).clickable { nav.push(Screen.Repo(p.owner.login, p.name)) },
            )
        }
        if (!r.description.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            MdText(r.description, fontSize = 15.sp)
        }
        r.homepage?.takeIf { it.isNotBlank() }?.let { h ->
            Spacer(Modifier.height(8.dp))
            // 网址长度不受控，窄屏下折行显示完整地址，而不是截成省略号
            IconText(
                R.drawable.oc_link, h.removePrefix("https://").removePrefix("http://"), g.accent,
                Modifier.clickable { ctx.openBrowser(if (h.startsWith("http")) h else "https://$h") },
                bold = true, maxLines = Int.MAX_VALUE,
            )
        }
        if (r.topics.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.topics.forEach { Topic(it) }
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IconText(R.drawable.oc_star, "${fmtCount(r.stargazersCount)} star", modifier = Modifier.clickable { nav.push(Screen.Users(UserKind.Stargazers, r.owner.login, r.name)) })
            IconText(R.drawable.oc_repo_forked, "${fmtCount(r.forksCount)} fork", modifier = Modifier.clickable { nav.push(Screen.Repos(RepoKind.Forks, r.owner.login, r.name)) })
            IconText(R.drawable.oc_eye, "${fmtCount(r.subscribersCount ?: r.watchersCount)} watching", modifier = Modifier.clickable { nav.push(Screen.Users(UserKind.Watchers, r.owner.login, r.name)) })
            // 许可证名称可能很长（如自定义许可证的全称），窄屏下折行显示
            r.license?.let { IconText(R.drawable.oc_law, it.spdxId?.takeIf { s -> s != "NOASSERTION" } ?: it.name, maxLines = Int.MAX_VALUE) }
            IconText(R.drawable.oc_package, fmtSize(r.size * 1024))
            IconText(R.drawable.oc_clock, "推送于 ${relTime(r.pushedAt)}")
        }
    }
}

/** 分支 / 标签选择 */
@Composable
fun BranchPicker(owner: String, name: String, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val g = Gh.c
    var tab by rememberSaveable { mutableStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }
    val branches = rememberLoader("pick:branches") { GitHub.branches(owner, name, 1, it).map { b -> b.name } }
    val tags = rememberLoader("pick:tags") { GitHub.tags(owner, name, 1, it).map { t -> t.name } }
    GhDialog("切换分支 / 标签", onDismiss = onDismiss) {
        Column {
            Chips(listOf("分支", "标签"), tab) { tab = it }
            GhField(q, { q = it }, "筛选")
            Spacer(Modifier.height(8.dp))
            val l = if (tab == 0) branches else tags
            val list = l.data?.filter { it.contains(q, true) }
            when {
                list != null -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    boxedItems(list) { b ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(b) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Oc(if (b == current) R.drawable.oc_check else if (tab == 0) R.drawable.oc_git_branch else R.drawable.oc_tag, if (b == current) g.accent else g.fgMuted)
                            Spacer(Modifier.width(10.dp))
                            Text(b, color = g.fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (list.isEmpty()) item { Text(if (q.isBlank()) (if (tab == 0) "没有分支" else "没有标签") else "无匹配结果", color = g.fgMuted, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center) }
                }
                l.error != null -> InlineError(l.error!!) { l.refresh() }
                else -> Loading(Modifier.fillMaxWidth().height(120.dp))
            }
        }
    }
}
