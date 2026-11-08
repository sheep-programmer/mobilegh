package com.mobilegh.ui.screens

import com.mobilegh.data.RepoWrite
import com.mobilegh.ui.components.verticalScrollWithBar
import com.mobilegh.ui.components.ScrollbarLazyColumn
import com.mobilegh.nav.OnRepoChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
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
import com.mobilegh.ui.components.addLinkActions
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.FileInfo
import com.mobilegh.ui.components.FileInfoDialog
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.GhTooltip
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.CompactSearchField
import com.mobilegh.ui.components.FieldAction
import com.mobilegh.ui.components.RegisterScrollTop
import com.mobilegh.ui.components.Segmented
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.lazy.itemsIndexed
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.HtmlAnchorRequest
import com.mobilegh.ui.components.ReadmeHeading
import com.mobilegh.ui.components.readmeHeadings
import com.mobilegh.ui.components.ReadmeZoomDialog
import com.mobilegh.ui.components.onPinchToZoom
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
import com.mobilegh.ui.components.ExternalLinkDialog
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
    val readme = rememberLoader("readme:${ref.value}") { force ->
        val html = GitHub.readme(owner, name, ref.value, force)
        // 缓存里的 README 可能带着已过期的视频令牌（请求会 404），这时重新取一份再渲染
        if (html != null && force.not() && com.mobilegh.data.MediaProxy.hasExpiredToken(html)) {
            GitHub.readme(owner, name, ref.value, true)
        } else {
            html
        }
    }
    val langs = rememberLoader("langs") { GitHub.languages(owner, name) }
    // 开放中的 Issue / PR 数：Issue 走搜索（与网页计数一致），PR 走 Link 头；搜索不可用时 Issue 数退回公式
    val openIssues = rememberLoader("open-issues") { GitHub.openIssueCount(owner, name, it) }
    val openPulls = rememberLoader("open-pulls") { GitHub.openPullCount(owner, name, it) }
    // 发布、讨论、贡献者的计数，显示在对应入口的角标上（0 不显示）
    val releaseCount = rememberLoader("release-count") { GitHub.releaseCount(owner, name, it) }
    val discussionInfo = rememberLoader("discussion-info") { GitHub.discussionInfo(owner, name, it) }
    val contributorCount = rememberLoader("contributor-count") { GitHub.contributorCount(owner, name, it) }
    // 新建、关闭 Issue / PR 后回到仓库页，Issue 与 PR 数随之更新
    OnRepoChanged(owner, name, RepoWrite.ISSUES) { openIssues.refresh(); openPulls.refresh() }
    val starStatus = rememberLoader("star-status") { entry.guarded { GitHub.isStarred(owner, name) } }
    val starBusyState = retain("star-busy") { mutableStateOf(false) }
    var starBusy by starBusyState
    val watching = rememberLoader("watch-status") { entry.guarded { SubscriptionSettings.current(owner, name) } }
    // 从子页面（Star 列表、分支、设置等）返回时页面会重新组合：静默同步一次仓库数据与星标状态，不出现加载态。
    // 首次进入时数据还没有，由初始加载负责。
    LaunchedEffect(Unit) {
        if (repo.data != null) repo.refreshSilently()
        if (starStatus.data != null) starStatus.refreshSilently()
    }
    var showBranches by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    var showFork by remember { mutableStateOf(false) }
    var showSubscription by remember { mutableStateOf(false) }
    var showLists by remember { mutableStateOf(false) }
    // README 目录：标题取自 README 的 HTML 源串；点选后经 anchorRequest 滚到该标题
    var tocOpen by remember { mutableStateOf(false) }
    var readmeZoomOpen by remember { mutableStateOf(false) }
    var initialZoomScale by remember { mutableFloatStateOf(1.5f) }
    var anchorRequest by remember { mutableStateOf<HtmlAnchorRequest?>(null) }
    val headings = remember(readme.data) { readme.data?.let(::readmeHeadings).orEmpty() }
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
                val savedId = "repo:$owner/$name"
                val isSaved = com.mobilegh.data.SavedItems.isSaved(savedId)
                add(MenuAction(if (isSaved) tr("移出稍后阅读") else tr("保存到稍后阅读")) {
                    com.mobilegh.data.SavedItems.toggle(
                        com.mobilegh.data.SavedItem(
                            id = savedId,
                            kind = com.mobilegh.data.SavedKind.Repo,
                            owner = owner,
                            name = name,
                            title = "$owner/$name",
                            state = null,
                        )
                    )
                    ctx.toast(if (isSaved) tr("已移出稍后阅读") else tr("已保存到稍后阅读"))
                })
                if (HomePrefs.layout().isFavorite(owner, name)) add(MenuAction(tr("移出收藏")) {
                    HomePrefs.toggleFavorite(owner, name)
                    ctx.toast(tr("已移出收藏"))
                }) else add(MenuAction(tr("加入收藏")) {
                    // 使用仓库返回的规范大小写，避免收藏里出现 Owner/Repo 与 owner/repo 两种写法
                    val targetOwner = r?.owner?.login ?: owner
                    val targetName = r?.name ?: name
                    runCatching { HomePrefs.toggleFavorite(targetOwner, targetName) }
                        .onSuccess {
                            ctx.toast(tr("已加入收藏"))
                        }
                        .onFailure { ctx.toast(it.message ?: tr("收藏失败")) }
                })
                addLinkActions(ctx, "https://github.com/$owner/$name")
                if (admin) add(MenuAction(tr("仓库设置")) { nav.push(Screen.RepoSettings(owner, name)) })
            })
        },
    ) { pad ->
        RegisterScrollTop(listState)
        when {
            r == null && repo.error != null -> ErrorState(repo.error!!, Modifier.padding(pad).fillMaxSize()) { repo.load(true) }
            r == null -> Loading(Modifier.padding(pad).fillMaxSize())
            else -> PullToRefreshBox(repo.refreshing, {
                if (!starBusy) {
                    // Star / 订阅状态静默刷新：按钮保持原样，不在刷新期间变灰再恢复（看起来就是闪一下）
                    repo.refresh(); readme.refresh(); langs.refresh(); starStatus.refreshSilently(); watching.refreshSilently()
                    // 下拉刷新时各入口的数量也一起重新读取
                    openIssues.refresh(); openPulls.refresh(); releaseCount.refresh(); discussionInfo.refresh(); contributorCount.refresh()
                }
            }, Modifier.padding(pad)) {
                // README 的下标由「是否有语言条」决定，和下方 LazyColumn 里的 item 顺序保持一致
                val readmeIndex = repoReadmeIndex(langs.data?.isNotEmpty() == true)
                ScrollbarLazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item { RepoHeader(r, onEdited = { repo.refresh() }) }
                    item {
                        // Star / Watch / Fork
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val s = starStatus.data == true
                            GhButton(
                                "${if (s) tr("已 Star") else "Star"} ${fmtCount(r.stargazersCount)}",
                                // 写入期间不改成禁用态，否则点一下按钮会灰一下；重复点击由 onClick 里的 starBusy 挡住
                                Modifier.weight(1f), icon = if (s) R.drawable.oc_star_fill else R.drawable.oc_star, iconTint = if (s) g.star else null,
                                // 只在第一次读取 Star 状态之前禁用；下拉刷新期间保持可用，按钮不会闪
                                enabled = entry.sameAccount() && starStatus.data != null,
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
                                            // 写入已成功：用服务器的最新数据替换。强制联网，不走 HTTP 缓存；
                                            // 不依赖 refreshSilently（它在加载中会直接跳过）。刷新失败时保留乐观更新，不回滚。
                                            try {
                                                repo.accept(entry.guarded { GitHub.repo(owner, name, true) })
                                                starStatus.accept(entry.guarded { GitHub.isStarred(owner, name) })
                                            } catch (cancel: CancellationException) {
                                                throw cancel
                                            } catch (_: Exception) {
                                            }
                                        } finally { starBusy = false }
                                    }
                                }
                            }
                            GhButton(watching.data?.title ?: if (watching.loading && watching.data == null) tr("读取订阅…") else tr("订阅设置"), Modifier.weight(1f), icon = R.drawable.oc_eye, enabled = entry.sameAccount()) {
                                showSubscription = true
                            }
                        }
                        starStatus.error?.let { InlineError(trf("Star 状态读取失败：{0}", it), Modifier.padding(horizontal = 16.dp)) { if (!starBusy && entry.sameAccount()) starStatus.refresh() } }
                        watching.error?.let { InlineError(trf("订阅状态读取失败：{0}", it), Modifier.padding(horizontal = 16.dp)) { if (entry.sameAccount()) watching.refresh() } }
                        Spacer(Modifier.height(8.dp))
                    }
                    item {
                        // 分支 + 代码
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.weight(1f)
                                    // 与右侧「分支」按钮同为 44dp，两个框并排时高度一致
                                    .heightIn(min = 44.dp)
                                    .border(1.dp, g.border, RoundedCornerShape(6.dp))
                                    .background(g.btnBg, RoundedCornerShape(6.dp))
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showBranches = true }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Oc(R.drawable.oc_git_branch, g.fgMuted)
                                Spacer(Modifier.width(6.dp))
                                Text(branch ?: "", fontWeight = FontWeight.SemiBold, color = g.fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Oc(R.drawable.oc_chevron_down, g.fgMuted, 12.dp)
                            }
                            Spacer(Modifier.width(8.dp))
                            GhButton(tr("分支"), Modifier.heightIn(min = 44.dp), icon = R.drawable.oc_git_branch) { nav.push(Screen.Branches(owner, name)) }
                        }
                        MenuGroup {
                            MenuRow(R.drawable.oc_code, tr("浏览代码"), g.accent) { nav.push(Screen.Files(owner, name, "", ref.value)) }
                            MenuRow(R.drawable.oc_search, tr("查找文件"), g.fgMuted) { nav.push(Screen.FileFinder(owner, name, branch)) }
                            // 仓库内代码搜索使用独立搜索页的代码类型，并预填 repo:，可继续组合语言/路径/文件名
                            MenuRow(R.drawable.oc_search, tr("搜索代码"), g.fgMuted) { nav.push(Screen.Search(SearchType.CODE, "repo:$owner/$name ")) }
                            // 选文件 / 选文件夹，一次上传只产生一个提交（没权限时不给入口，与「新建文件」一致）
                            if (canPush) MenuRow(R.drawable.oc_upload, tr("上传文件")) { nav.push(Screen.UploadFiles(owner, name, ref.value, "")) }
                            MenuRow(R.drawable.oc_git_commit, tr("提交历史")) { nav.push(Screen.Commits(owner, name, ref.value)) }
                            MenuRow(R.drawable.oc_git_branch, tr("比较分支与提交")) { nav.push(Screen.Compare(owner, name)) }
                        }
                    }
                    item {
                        // 协作与管理入口：四列图标网格，和首页「我的工作」一致，不再占一整屏竖排长菜单
                        SectionTitle(tr("协作与管理"))
                        HomeShortcuts(buildList {
                            // 加载中不显示角标，避免数字先跳再变；搜索失败时才退回 open_issues_count 减 PR（可能差 1）
                            val issueCount = openIssues.data
                                ?: openPulls.data?.takeIf { openIssues.error != null }?.let { (r.openIssuesCount - it).coerceAtLeast(0) }
                            add(HomeShortcut(R.drawable.oc_issue_opened, "Issues", g.success, { nav.push(Screen.Issues(owner, name, false)) }, id = "Issues", count = issueCount))
                            add(HomeShortcut(R.drawable.oc_git_pull_request, "Pull Requests", g.accent, { nav.push(Screen.Issues(owner, name, true)) }, id = "Pulls", count = openPulls.data))
                            val showDiscussions = r.hasDiscussions || discussionInfo.data?.first == true || (discussionInfo.data?.second ?: 0) > 0
                            if (showDiscussions) {
                                add(HomeShortcut(
                                    R.drawable.oc_comment_discussion,
                                    tr("讨论"),
                                    g.done,
                                    { nav.push(Screen.Discussions(owner, name)) },
                                    count = discussionInfo.data?.second?.takeIf { it > 0 }
                                ))
                            }
                            add(HomeShortcut(R.drawable.oc_play, "Actions", g.fgMuted, { nav.push(Screen.Actions(owner, name)) }))
                            add(HomeShortcut(R.drawable.oc_table, tr("项目"), g.success, { nav.push(Screen.Projects(ProjectOwnerKind.Repo, owner, name)) }))
                            if (r.hasWiki) {
                                add(HomeShortcut(R.drawable.oc_book, "Wiki", g.sponsors, { nav.push(Screen.Wiki(owner, name)) }))
                            }
                            add(HomeShortcut(R.drawable.oc_tag, "Releases", g.success, { nav.push(Screen.Releases(owner, name)) }, count = releaseCount.data?.takeIf { it > 0 }))
                            add(HomeShortcut(R.drawable.oc_people, tr("贡献者"), g.severe, { nav.push(Screen.Contributors(owner, name)) }, count = contributorCount.data?.takeIf { it > 0 }))
                            add(HomeShortcut(R.drawable.oc_graph, tr("洞察"), g.done, { nav.push(Screen.Insights(owner, name)) }))
                            // 安全告警需要安全相关的读取权限，分支规则需要管理员权限；没有权限时页面会显示 GitHub 返回的原因
                            if (canPush || admin) add(HomeShortcut(R.drawable.oc_shield_lock, tr("安全告警"), g.danger, { nav.push(Screen.SecurityAlerts(owner, name)) }))
                            if (admin) add(HomeShortcut(R.drawable.oc_git_branch, tr("分支规则"), g.success, { nav.push(Screen.BranchRules(owner, name)) }))
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
                        Card(
                            Modifier
                                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                                .then(if (readme.data != null) Modifier.onPinchToZoom { ratio ->
                                    initialZoomScale = ratio.coerceIn(1.2f, 3.0f)
                                    readmeZoomOpen = true
                                } else Modifier),
                        ) {
                            Column {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Oc(R.drawable.oc_book, g.fgMuted)
                                    Spacer(Modifier.width(8.dp))
                                    Text("README", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = g.fg)
                                    Spacer(Modifier.weight(1f))
                                    if (readme.data != null) {
                                        GhTooltip(tr("缩放查看")) {
                                            Box(
                                                Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(g.canvasSubtle)
                                                    .border(1.dp, g.border, CircleShape)
                                                    .clickable { readmeZoomOpen = true },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Oc(R.drawable.oc_screen_full, g.fg, 16.dp)
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    if (headings.isNotEmpty()) {
                                        GhTooltip(tr("README 目录")) {
                                            Box(
                                                Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(g.canvasSubtle)
                                                    .border(1.dp, g.border, CircleShape)
                                                    .clickable { tocOpen = true },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Oc(R.drawable.oc_list_unordered, g.fg, 16.dp)
                                            }
                                        }
                                    }
                                }
                                HDivider()
                                Spacer(Modifier.height(12.dp))
                                val html = readme.data
                                when {
                                    html != null -> ReadmeBody(
                                        html,
                                        baseUrl = "https://github.com/$owner/$name/blob/${branch ?: r?.defaultBranch ?: "main"}/",
                                        onAnchor = { y ->
                                            scope.launch {
                                                val targetPx = with(density) { (y + 44).dp.roundToPx().coerceAtLeast(0) }
                                                listState.animateScrollToItem(readmeIndex, targetPx)
                                            }
                                        },
                                        anchorRequest = anchorRequest,
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
    if (readmeZoomOpen && readme.data != null) {
        ReadmeZoomDialog(
            html = readme.data!!,
            baseUrl = "https://github.com/$owner/$name/blob/${branch ?: r?.defaultBranch ?: "main"}/",
            initialScale = initialZoomScale,
            onDismiss = {
                readmeZoomOpen = false
                initialZoomScale = 1.5f
            },
        )
    }
    if (showSubscription && entry.sameAccount()) SubscriptionDialog(owner, name, { showSubscription = false }) { watching.accept(it) }
    if (showLists && r != null) AddToStarListsDialog(r) { showLists = false }
    if (tocOpen && headings.isNotEmpty()) ReadmeTocSheet(headings, onDismiss = { tocOpen = false }) { h ->
        tocOpen = false
        anchorRequest = HtmlAnchorRequest(h.anchor)
    }
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
        Column(Modifier.heightIn(max = 460.dp).verticalScrollWithBar()) {
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
private fun RepoHeader(r: Repo, onEdited: () -> Unit = {}) {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()
    // 管理员和维护者可以直接在这里改描述、网址和主题，不用进「设置」
    val canEdit = (r.permissions?.admin == true || r.permissions?.maintain == true) && !r.archived
    var editing by remember { mutableStateOf(false) }
    if (editing) EditRepoDetailsDialog(r, onDismiss = { editing = false }, onSaved = { editing = false; onEdited() })

    val inlineContent = remember(canEdit, g.fgMuted) {
        if (!canEdit) emptyMap()
        else mapOf(
            "edit_pencil" to InlineTextContent(
                Placeholder(20.sp, 18.sp, PlaceholderVerticalAlign.Center)
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { editing = true }
                        .semantics { contentDescription = tr("编辑描述、网址和主题") },
                    contentAlignment = Alignment.Center,
                ) {
                    Oc(R.drawable.oc_pencil, g.fgMuted, 13.dp)
                }
            }
        )
    }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
        // 作者头像和登录名
        Row(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                    nav.push(if (r.owner.type == "Organization") Screen.Org(r.owner.login) else Screen.Profile(r.owner.login))
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(r.owner.avatarUrl, 20.dp, square = r.owner.type == "Organization")
            Spacer(Modifier.width(8.dp))
            Text(r.owner.login, color = g.fgMuted, fontSize = 14.sp)
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = g.fg, modifier = Modifier.weight(1f, false))
            Spacer(Modifier.width(8.dp))
            com.mobilegh.ui.components.VisibilityBadge(r.isPrivate, label = if (r.isPrivate) tr("私有") else if (r.visibility == "internal") tr("内部") else tr("公开"))
        }
        if (r.archived || r.isTemplate || r.disabled) {
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r.archived) Pill(tr("已归档"), g.attention)
                if (r.isTemplate) Pill(tr("模板"))
                if (r.disabled) Pill(tr("已禁用"), g.danger)
            }
        }
        r.parent?.let { p ->
            Text(
                trf("Fork 自 {0}", p.fullName), color = g.accent, fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(4.dp)).clickable { nav.push(Screen.Repo(p.owner.login, p.name)) }.padding(vertical = 2.dp),
            )
        }
        // 描述：紧跟一个小铅笔图标直接排在文本末尾，点开可改描述、网址和主题
        if (!r.description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            MdText(
                r.description,
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = g.fg,
                inlineContent = inlineContent,
                trailingInlineId = if (canEdit) "edit_pencil" else null,
            )
        } else if (canEdit) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { editing = true }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("还没有描述，点按添加"), color = g.fgMuted, fontSize = 14.sp)
                Spacer(Modifier.width(4.dp))
                Oc(R.drawable.oc_pencil, g.fgMuted, 13.dp, contentDescription = tr("编辑描述、网址和主题"))
            }
        }
        r.homepage?.takeIf { it.isNotBlank() }?.let { h ->
            Spacer(Modifier.height(4.dp))
            // 网址长度不受控，窄屏下折行显示完整地址，而不是截成省略号
            IconText(
                R.drawable.oc_link, h.removePrefix("https://").removePrefix("http://"), g.accent,
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        val u = if (h.startsWith("http")) h else "https://$h"
                        ExternalLinkDialog.show(u, h)
                    }
                    .padding(vertical = 2.dp),
                bold = true, maxLines = Int.MAX_VALUE,
            )
        }
        if (r.topics.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                r.topics.forEach { Topic(it) }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 仓库统计项紧凑排列
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val cell = Modifier.clip(RoundedCornerShape(4.dp))
            StatCell(R.drawable.oc_star, trf("{0} Star", fmtCount(r.stargazersCount)), cell) {
                nav.push(Screen.Users(UserKind.Stargazers, r.owner.login, r.name))
            }
            StatCell(R.drawable.oc_repo_forked, trf("{0} 个 Fork", fmtCount(r.forksCount)), cell) {
                nav.push(Screen.Repos(RepoKind.Forks, r.owner.login, r.name))
            }
            StatCell(R.drawable.oc_eye, trf("{0} 人关注", fmtCount(r.subscribersCount ?: r.watchersCount)), cell) {
                nav.push(Screen.Users(UserKind.Watchers, r.owner.login, r.name))
            }
            // 许可证：打开仓库里的许可证文件；名称可能很长（如自定义许可证的全称），窄屏下折行显示
            r.license?.let { lic ->
                StatCell(R.drawable.oc_law, lic.spdxId?.takeIf { s -> s != "NOASSERTION" } ?: lic.name, cell, maxLines = Int.MAX_VALUE) {
                    scope.launch {
                        runCatching { GitHub.repoLicense(r.owner.login, r.name) }
                            .onSuccess { nav.push(Screen.FileView(r.owner.login, r.name, it.path, null)) }
                            .onFailure { ctx.toast(tr("无法读取许可证文件")) }
                    }
                }
            }
            // 箱子图标与大小进入 Releases；推送时间进入最近推送记录（谁推到了哪个分支）
            StatCell(R.drawable.oc_package, fmtSize(r.size * 1024), cell) {
                nav.push(Screen.Releases(r.owner.login, r.name))
            }
            StatCell(R.drawable.oc_clock, trf("推送于 {0}", relTime(r.pushedAt)), cell) {
                nav.push(Screen.Pushes(r.owner.login, r.name))
            }
        }
    }
}

/** 仓库头部的一个统计项：图标加文字，整项可点按且紧凑排列。 */
@Composable
private fun StatCell(
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    onClick: () -> Unit,
) {
    IconText(icon, text, modifier = modifier.clickable(onClick = onClick).padding(vertical = 2.dp), maxLines = maxLines)
}

/**
 * 仓库页 LazyColumn 中 README 那一项的下标。顺序：0 头部、1 Star/Watch/Fork、2 分支行、3 快捷入口、
 * 4 语言条（仅有语言数据时）、之后才是 README。点 README 里的页内锚点据此滚动，不再取「倒数第二项」。
 */
internal fun repoReadmeIndex(hasLanguages: Boolean): Int = if (hasLanguages) 5 else 4

/** README 正文：完整展示。WebView 按完整内容高度布局，页面整体滚动。 */
@Composable
private fun ReadmeBody(
    html: String,
    baseUrl: String,
    onAnchor: (Int) -> Unit,
    anchorRequest: HtmlAnchorRequest?,
) {
    HtmlView(
        html,
        modifier = Modifier.fillMaxWidth(),
        baseUrl = baseUrl,
        onAnchor = onAnchor,
        anchorRequest = anchorRequest,
        fullHeight = true,
    )
}

/** README 目录：底部弹层列出标题（按层级缩进），点一项滚动到对应位置。标题来自 README 的 HTML 源串，不读 WebView。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadmeTocSheet(headings: List<ReadmeHeading>, onDismiss: () -> Unit, onPick: (ReadmeHeading) -> Unit) {
    val g = Gh.c
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = g.canvasSubtle,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("目录"), color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            ScrollbarLazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(12.dp))
                    .background(g.canvas, RoundedCornerShape(12.dp)).border(1.dp, g.border, RoundedCornerShape(12.dp)),
            ) {
                itemsIndexed(headings) { i, h ->
                    Text(
                        h.text,
                        color = if (h.level <= 2) g.fg else g.fgMuted,
                        fontSize = if (h.level <= 2) 15.sp else 14.sp,
                        fontWeight = if (h.level == 1) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(h) }
                            .padding(start = (12 + (h.level - 1) * 14).dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    )
                    if (i < headings.lastIndex) HDivider()
                }
            }
        }
    }
}

/**
 * 分支 / 标签选择：底部弹层，顶部分段切换，中间是紧凑搜索框和带分隔线的列表。
 * 当前选中的一项高亮并打勾，右侧显示提交的短 SHA，方便区分同名的分支和标签。
 * 列表会把当前标签页的分页全部读完，搜索才能覆盖所有分支 / 标签。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchPicker(owner: String, name: String, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val g = Gh.c
    var tab by rememberSaveable { mutableStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }
    val branches = com.mobilegh.nav.rememberPager("pick:branches", 100) { p, f -> GitHub.branches(owner, name, p, f).map { b -> b.name to b.commit.sha } }
    val tags = com.mobilegh.nav.rememberPager("pick:tags", 100) { p, f -> GitHub.tags(owner, name, p, f).map { t -> t.name to t.commit.sha } }
    val pager = if (tab == 0) branches else tags
    // 过滤只作用于已加载的条目，所以当前标签页要读完全部分页；出错后不自动重试，由按钮触发
    LaunchedEffect(tab, pager.loading, pager.end, pager.error) {
        if (!pager.loading && !pager.end && pager.error == null) pager.loadAll()
    }
    val list = pager.items.filter { refMatches(it.first, q) }
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
                list.isNotEmpty() -> ScrollbarLazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(12.dp))
                        .background(g.canvas, RoundedCornerShape(12.dp)).border(1.dp, g.border, RoundedCornerShape(12.dp)),
                ) {
                    itemsIndexed(list, key = { _, (branch, _) -> branch }) { i, (branch, sha) ->
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
                    // 还有剩余页在读取时，底部给出进度；出错时给出重试
                    if (!pager.end && pager.error == null) item {
                        Text(trf("已加载 {0} 个，继续读取…", pager.items.size), Modifier.fillMaxWidth().padding(12.dp), color = g.fgMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                    pager.error?.let { e -> item { InlineError(e) { pager.retry() } } }
                }
                pager.error != null -> InlineError(pager.error!!) { pager.retry() }
                pager.end -> Text(
                    if (q.isBlank()) (if (tab == 0) tr("没有分支") else tr("没有标签")) else tr("无匹配结果"),
                    Modifier.fillMaxWidth().padding(vertical = 24.dp), color = g.fgMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
                else -> Loading(Modifier.fillMaxWidth().height(120.dp))
            }
        }
    }
}
