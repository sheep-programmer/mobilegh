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
import com.mobilegh.data.repoZipDownloadUrl
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
import com.mobilegh.ui.components.FileInfo
import com.mobilegh.ui.components.FileInfoDialog
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.CompactSearchField
import com.mobilegh.ui.components.FieldAction
import com.mobilegh.ui.components.Segmented
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.SectionTitle
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
import com.mobilegh.data.OrganizationDirectory
import com.mobilegh.data.validForkName
import com.mobilegh.data.finishFork
import com.mobilegh.data.Session
import com.mobilegh.nav.friendly
import com.mobilegh.ui.components.RadioRow
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.theme.Gh
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trc
import com.mobilegh.i18n.trf

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
    var zipInfo by remember { mutableStateOf<FileInfo?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val r = repo.data
    val admin = r?.permissions?.admin == true
    val canPush = r?.let { it.permissions?.push == true && !it.archived } == true
    val branch = ref.value ?: r?.defaultBranch

    Page(
        name, subtitle = owner,
        actions = {
            // Fork 是仓库页最常用的操作之一，直接放在标题栏，不用藏进三点菜单
            OcButton(R.drawable.oc_repo_forked, { showFork = true }, enabled = r != null, description = tr("Fork 此仓库"))
            MoreMenu(buildList {
                add(MenuAction(tr("分享")) { ctx.share("https://github.com/$owner/$name") })
                add(MenuAction(tr("复制链接")) { ctx.copy("https://github.com/$owner/$name") })
                add(MenuAction(tr("克隆地址…")) { showClone = true })
                // 和网页上 Code → Download ZIP 一样：把当前分支/标签的源码打包下来，不用 clone
                if (r != null) add(MenuAction(tr("下载源码 zip")) {
                    val refName = branch ?: r.defaultBranch
                    zipInfo = FileInfo(
                        name = "${r.name}-${refName.replace('/', '-')}.zip",
                        url = repoZipDownloadUrl(owner, name, refName, r.isPrivate),
                        isPrivate = r.isPrivate,
                        mimeType = "application/zip",
                        details = listOf(
                            tr("内容") to trf("分支 / 标签 {0} 的完整源代码（zip 压缩包）", refName),
                            tr("仓库") to "$owner/$name",
                            tr("大小") to tr("下载时由 GitHub 实时打包，大小未知"),
                            tr("说明") to tr("不含提交历史，也不含子模块内容"),
                        ),
                    )
                })
                if (r != null) add(MenuAction(tr("加入 Star 列表…")) { showLists = true })
                if (HomePrefs.layout().isFavorite(owner, name)) add(MenuAction(tr("移出首页收藏")) {
                    HomePrefs.toggleFavorite(owner, name)
                    ctx.toast(tr("已移出首页收藏"))
                }) else add(MenuAction(tr("加入首页收藏")) {
                    // 使用仓库返回的规范大小写，避免收藏里出现 Owner/Repo 与 owner/repo 两种写法
                    runCatching { HomePrefs.toggleFavorite(r?.owner?.login ?: owner, r?.name ?: name) }
                        .onSuccess { ctx.toast(tr("已加入首页收藏")) }
                        .onFailure { ctx.toast(it.message ?: tr("收藏失败")) }
                })
                add(MenuAction(tr("在浏览器打开")) { ctx.openBrowser("https://github.com/$owner/$name") })
                if (admin) add(MenuAction(tr("仓库设置")) { nav.push(Screen.RepoSettings(owner, name)) })
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
                                "${if (s) tr("已 Star") else "Star"} ${fmtCount(r.stargazersCount)}",
                                // 写入期间不改成禁用态，否则点一下按钮会灰一下；重复点击由 onClick 里的 starBusy 挡住
                                Modifier.weight(1f), icon = if (s) R.drawable.oc_star_fill else R.drawable.oc_star, iconTint = if (s) g.star else null,
                                enabled = entry.sameAccount() && starStatus.data != null && !starStatus.loading && !repo.loading,
                                // 和 GitHub 一样：点按切换 Star；只有已 Star 的仓库才能长按管理所在列表
                                onLongClick = {
                                    if (s) showLists = true
                                    else ctx.toast(tr("先 Star 这个仓库，再长按加入列表"))
                                },
                            ) {
                                if (!starBusy && entry.sameAccount()) {
                                    val on = !s
                                    val before = repo.data ?: r
                                    // 星标和计数立即变化；写入失败再整体回滚
                                    starStatus.accept(on)
                                    repo.accept(repoWithStar(before, on))
                                    starBusy = true
                                    entry.act({
                                        if (entry.sameAccount()) {
                                            starStatus.accept(s)
                                            repo.accept(before)
                                            ctx.toast(it)
                                        }
                                    }) {
                                        try {
                                            entry.guarded { GitHub.star(owner, name, on) }
                                            // 写入成功后静默同步服务器上的真实计数和状态，不出现加载态
                                            repo.refreshSilently()
                                            starStatus.refreshSilently()
                                        } finally { starBusy = false }
                                    }
                                }
                            }
                            GhButton(watching.data?.title ?: if (watching.loading) tr("读取订阅…") else tr("订阅设置"), Modifier.weight(1f), icon = R.drawable.oc_eye, enabled = entry.sameAccount()) {
                                showSubscription = true
                            }
                        }
                        starStatus.error?.let { InlineError(trf("Star 状态读取失败：{0}", it), Modifier.padding(horizontal = 16.dp)) { if (!starBusy && entry.sameAccount()) starStatus.refresh() } }
                        watching.error?.let { InlineError(trf("订阅状态读取失败：{0}", it), Modifier.padding(horizontal = 16.dp)) { if (entry.sameAccount()) watching.refresh() } }
                        Spacer(Modifier.height(16.dp))
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
                            GhButton(tr("分支"), icon = R.drawable.oc_git_branch) { nav.push(Screen.Branches(owner, name)) }
                        }
                        MenuGroup {
                            MenuRow(R.drawable.oc_code, tr("浏览代码"), g.accent) { nav.push(Screen.Files(owner, name, "", ref.value)) }
                            MenuRow(R.drawable.oc_search, tr("查找文件"), g.fgMuted) { nav.push(Screen.FileFinder(owner, name, branch)) }
                            // 仓库内代码搜索使用独立搜索页的代码类型，并预填 repo:，可继续组合语言/路径/文件名
                            MenuRow(R.drawable.oc_search, tr("搜索代码"), g.fgMuted) { nav.push(Screen.Search(SearchType.CODE, "repo:$owner/$name ")) }
                            // 选文件 / 选文件夹，一次上传只产生一个提交（没权限时不给入口，与「新建文件」一致）
                            if (canPush) MenuRow(R.drawable.oc_upload, tr("上传文件（夹）")) { nav.push(Screen.UploadFiles(owner, name, ref.value, "")) }
                            MenuRow(R.drawable.oc_git_commit, tr("提交历史")) { nav.push(Screen.Commits(owner, name, ref.value)) }
                            MenuRow(R.drawable.oc_git_branch, tr("比较分支与提交")) { nav.push(Screen.Compare(owner, name)) }
                        }
                    }
                    item {
                        // 协作与管理入口：四列图标网格，和首页「我的工作」一致，不再占一整屏竖排长菜单
                        SectionTitle(tr("协作与管理"))
                        HomeShortcuts(buildList {
                            add(HomeShortcut(R.drawable.oc_issue_opened, "Issues", g.success, { nav.push(Screen.Issues(owner, name, false)) }))
                            add(HomeShortcut(R.drawable.oc_git_pull_request, "PR", g.accent, { nav.push(Screen.Issues(owner, name, true)) }))
                            add(HomeShortcut(R.drawable.oc_play, "Actions", g.fgMuted, { nav.push(Screen.Actions(owner, name)) }))
                            add(HomeShortcut(R.drawable.oc_table, tr("项目"), g.success, { nav.push(Screen.Projects(ProjectOwnerKind.Repo, owner, name)) }))
                            if (r.hasDiscussions) add(HomeShortcut(R.drawable.oc_comment_discussion, tr("讨论"), g.done, { nav.push(Screen.Discussions(owner, name)) }))
                            add(HomeShortcut(R.drawable.oc_tag, "Releases", g.success, { nav.push(Screen.Releases(owner, name)) }))
                            add(HomeShortcut(R.drawable.oc_people, tr("贡献者"), g.severe, { nav.push(Screen.Contributors(owner, name)) }))
                            add(HomeShortcut(R.drawable.oc_graph, tr("洞察"), g.done, { nav.push(Screen.Insights(owner, name)) }))
                            if (admin) {
                                add(HomeShortcut(R.drawable.oc_person, tr("协作者"), g.sponsors, { nav.push(Screen.Collaborators(owner, name)) }))
                                add(HomeShortcut(R.drawable.oc_gear, tr("设置"), g.fgMuted, { nav.push(Screen.RepoSettings(owner, name)) }))
                            }
                        })
                    }
                    langs.data?.takeIf { it.isNotEmpty() }?.let { l ->
                        item {
                            Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(tr("语言"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = g.fg)
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
                                    else -> Text(tr("该仓库没有 README"), Modifier.padding(16.dp), color = g.fgMuted)
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

    zipInfo?.let { FileInfoDialog(it) { zipInfo = null } }
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
        GhDialog(tr("克隆"), onDismiss = { showClone = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val https = r.cloneUrl ?: "https://github.com/${r.fullName}.git"
                val fast = if (r.isPrivate) null else com.mobilegh.data.Net.cloneUrl(https)
                (listOf("HTTPS" to https) + listOfNotNull(fast?.let { trf("HTTPS 加速（{0}）", it.first) to "git clone ${it.second}" }) +
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
    val loadOrganizations = toOrg
    val orgs = rememberLoader("fork-orgs:$loadOrganizations") {
        if (loadOrganizations) OrganizationAccess.read(it, includeRepositoryOwners = false)
        else OrganizationDirectory(emptyList(), emptyList(), null)
    }
    var org by rememberSaveable { mutableStateOf("") }

    var newName by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf(repo.description.orEmpty()) }
    // 默认只复制默认分支：与 GitHub 网页端一致，也避免把无关分支一起带走
    var defaultOnly by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val trimmedName = newName.trim()
    val nameInvalid = trimmedName.isNotEmpty() && !validForkName(trimmedName)
    val orgMissing = toOrg && org.isBlank()
    val valid = !nameInvalid && !orgMissing && !busy

    fun submit() {
        val destination = if (toOrg) org.trim() else null
        val requestedName = trimmedName.ifEmpty { null }
        val requestedDescription = desc.trim()
        val descriptionChanged = requestedDescription != repo.description.orEmpty().trim()
        val requestedDefaultOnly = defaultOnly
        busy = true
        waiting = false
        error = null
        entry.scope.launch {
            try {
                val forked = entry.guarded {
                    GitHub.fork(
                        owner, name,
                        organization = destination,
                        newName = requestedName,
                        defaultBranchOnly = requestedDefaultOnly,
                    )
                }
                // Fork 是异步创建；不改描述也要等仓库可读，只对读取 404 做有限等待。
                waiting = true
                val followUpFailed = try {
                    finishFork(
                        if (descriptionChanged) requestedDescription else null,
                        read = { entry.guarded { GitHub.repo(forked.owner.login, forked.name, force = true); Unit } },
                        update = { description -> entry.guarded { GitHub.setDescription(forked.owner.login, forked.name, description) } },
                    )
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    true
                }
                check(entry.sameAccount()) { tr("账号已切换，旧账号的 Fork 结果不再打开") }
                busy = false
                waiting = false
                ctx.toast(
                    when {
                        followUpFailed && descriptionChanged -> trf("已 Fork 到 {0}，但等待创建或写入描述失败", forked.fullName)
                        followUpFailed -> trf("已 Fork 到 {0}，仓库仍在创建，请稍后刷新", forked.fullName)
                        else -> trf("已 Fork 到 {0}", forked.fullName)
                    }
                )
                onDone(forked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                busy = false
                waiting = false
                error = e.friendly()
            }
        }
    }

    GhDialog(
        tr("Fork 此仓库"),
        onDismiss = { if (!busy) onDismiss() },
        confirm = if (waiting) tr("等待创建…") else if (busy) tr("Fork 中…") else "Fork",
        confirmEnabled = valid,
        dismissEnabled = !busy,
        onConfirm = ::submit,
    ) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            Text("$owner/$name", fontSize = 12.sp, color = g.fgMuted)

            Spacer(Modifier.height(10.dp))
            RadioRow(!toOrg, tr("Fork 到我的账号"), login?.let { "@$it" }, enabled = !busy) { toOrg = false }
            RadioRow(toOrg, tr("Fork 到组织"), tr("需要对该组织有创建仓库的权限"), enabled = !busy) { toOrg = true }

            if (toOrg) {
                Spacer(Modifier.height(6.dp))
                val directory = orgs.data
                val list = directory?.orgs?.map { it.login }
                val warnings = directory?.warnings.orEmpty()
                if (warnings.isNotEmpty()) {
                    InlineError(trf("部分组织信息读取不完整：{0}", warnings.joinToString("\n"))) { orgs.refresh() }
                }
                when {
                    list != null && list.isEmpty() -> Text(
                        if (warnings.isEmpty()) tr("当前授权没有返回可用的组织")
                        else tr("未读取到可用组织；请先检查上方读取警告"),
                        fontSize = 12.sp, color = g.danger,
                    )
                    list != null -> {
                        Chips(list, list.indexOf(org), enabled = !busy, onSelect = { org = list[it] })
                        if (org.isBlank()) Text(tr("请选择组织"), fontSize = 12.sp, color = g.danger)
                    }
                    orgs.error != null -> InlineError(orgs.error!!) { orgs.refresh() }
                    else -> Loading(Modifier.fillMaxWidth().height(60.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            GhField(newName, { newName = it }, tr("仓库名称（可选）"), enabled = !busy, placeholder = name)
            Text(tr("留空沿用原名；可使用字母、数字、点、下划线和连字符。"), Modifier.padding(top = 4.dp), fontSize = 11.sp, color = g.fgMuted)
            if (nameInvalid) Text(tr("仓库名称无效"), fontSize = 12.sp, color = g.danger)

            Spacer(Modifier.height(10.dp))
            GhField(desc, { desc = it }, tr("描述"), singleLine = false, minLines = 2, enabled = !busy)

            Spacer(Modifier.height(10.dp))
            SwitchRow(
                tr("只复制默认分支"),
                tr("关闭后连同其余分支一起 fork；GitHub 无法只挑某几个分支"),
                defaultOnly,
                enabled = !busy,
            ) { defaultOnly = it }
            if (repo.defaultBranch.isNotBlank()) {
                Text(
                    trf("默认分支：{0}", repo.defaultBranch),
                    Modifier.padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
                    fontSize = 12.sp,
                    color = g.fgMuted,
                )
            }

            error?.let { InlineError(it) }
        }
    }
}

/** Star 写入时先在本地把计数加一或减一，界面立即变化；写入失败再回滚。计数不会低于 0。 */
internal fun repoWithStar(repo: Repo, on: Boolean): Repo =
    repo.copy(stargazersCount = (repo.stargazersCount + if (on) 1 else -1).coerceAtLeast(0))

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
            com.mobilegh.ui.components.VisibilityBadge(r.isPrivate, label = if (r.isPrivate) tr("私有") else if (r.visibility == "internal") tr("内部") else tr("公开"))
        }
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r.archived) Pill(tr("已归档"), g.attention)
            if (r.isTemplate) Pill(tr("模板"))
            if (r.disabled) Pill(tr("已禁用"), g.danger)
        }
        r.parent?.let { p ->
            Text(
                trf("Fork 自 {0}", p.fullName), color = g.accent, fontSize = 13.sp,
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
            IconText(R.drawable.oc_clock, trf("推送于 {0}", relTime(r.pushedAt)))
        }
    }
}

/**
 * 分支 / 标签选择：底部弹层，顶部分段切换，中间是紧凑搜索框和带分隔线的列表。
 * 当前选中的一项高亮并打勾，右侧显示提交的短 SHA，方便区分同名的分支和标签。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchPicker(owner: String, name: String, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val g = Gh.c
    var tab by rememberSaveable { mutableStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }
    val branches = rememberLoader("pick:branches") { GitHub.branches(owner, name, 1, it).map { b -> b.name to b.commit.sha } }
    val tags = rememberLoader("pick:tags") { GitHub.tags(owner, name, 1, it).map { t -> t.name to t.commit.sha } }
    val l = if (tab == 0) branches else tags
    val list = l.data?.filter { it.first.contains(q, true) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = g.canvasSubtle,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("切换分支 / 标签"), color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("$owner/$name", color = g.fgMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Segmented(listOf(tr("分支"), trc("标签", "git")), tab, Modifier.fillMaxWidth()) { tab = it }
            CompactSearchField(
                q, { q = it }, tr("筛选"), Modifier.fillMaxWidth(),
                trailing = if (q.isEmpty()) null else { { FieldAction(R.drawable.oc_x, tr("清空筛选关键词")) { q = "" } } },
            )
            when {
                list != null && list.isNotEmpty() -> LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(12.dp))
                        .background(g.canvas, RoundedCornerShape(12.dp)).border(1.dp, g.border, RoundedCornerShape(12.dp)),
                ) {
                    itemsIndexed(list) { i, (branch, sha) ->
                        val selected = branch == current
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(branch) }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Oc(if (tab == 0) R.drawable.oc_git_branch else R.drawable.oc_tag, if (selected) g.accent else g.fgMuted, 16.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                branch, Modifier.weight(1f),
                                color = if (selected) g.accent else g.fg, fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (sha.isNotBlank()) {
                                Text(sha.take(7), color = g.fgMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.width(8.dp))
                            }
                            if (selected) Oc(R.drawable.oc_check, g.accent, 16.dp)
                        }
                        if (i < list.lastIndex) HDivider()
                    }
                }
                list != null -> Text(
                    if (q.isBlank()) (if (tab == 0) tr("没有分支") else tr("没有标签")) else tr("无匹配结果"),
                    Modifier.fillMaxWidth().padding(vertical = 24.dp), color = g.fgMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
                l.error != null -> InlineError(l.error!!) { l.refresh() }
                else -> Loading(Modifier.fillMaxWidth().height(120.dp))
            }
        }
    }
}
