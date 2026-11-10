package com.mobilegh.ui

import androidx.compose.ui.Alignment
import com.mobilegh.ui.components.ExternalLinkDialogHost
import com.mobilegh.ui.components.GitHubWebLoginHost
import com.mobilegh.ui.components.LinkMenuHost
import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mobilegh.R
import com.mobilegh.data.AppLock
import com.mobilegh.data.GitHub
import com.mobilegh.data.Session
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.ui.components.AppLockScreen
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.DownloadHost
import com.mobilegh.ui.components.ClipboardLinkHost
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.screens.*
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import kotlinx.coroutines.launch

/**
 * 未读数用 StateFlow 保存，界面在组合里用 collectAsState 读取。
 * 不用 mutableIntStateOf：它在第一次访问时才创建，若第一次访问发生在组合快照里，其他快照读取会报「快照读取」错误。
 */
object Badges {
    private val flow = kotlinx.coroutines.flow.MutableStateFlow(0)
    val unreadFlow: kotlinx.coroutines.flow.StateFlow<Int> get() = flow
    var unread: Int
        get() = flow.value
        set(value) { flow.value = value }
}

/** 未读数两次拉取之间的最短间隔 */
internal const val BADGE_REFRESH_MIN_INTERVAL_MS = 60_000L

/** 距离上一次拉取开始是否已满足最短间隔；从未拉取过（[lastStartedMs] 为 null）时需要拉取。 */
internal fun badgeRefreshDue(lastStartedMs: Long?, nowMs: Long, minIntervalMs: Long = BADGE_REFRESH_MIN_INTERVAL_MS): Boolean =
    lastStartedMs == null || nowMs - lastStartedMs >= minIntervalMs

@Composable
fun AppRoot(nav: Navigator) {
    val dark = Gh.c.dark
    val view = LocalView.current
    SideEffect {
        val w = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(w, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    // 锁定时整体移出内容（连同其中的弹窗），可保存的界面状态由 holder 保留，解锁后原样恢复。
    // 两步验证弹窗也在内容内：锁定期间不弹出，请求保留在 MobileApprovalMonitor 中，解锁后再显示。
    val holder = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalNav provides nav) {
        Box(Modifier.fillMaxSize().background(Gh.c.canvas)) {
            if (AppLock.locked && Session.token != null) {
                AppLockScreen()
            } else {
                holder.SaveableStateProvider("app") {
                    Box(Modifier.fillMaxSize()) {
                        if (Session.token == null) {
                            CompositionLocalProvider(LocalEntry provides nav.root) { LoginScreen() }
                        } else {
                            Main(nav)
                        }
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomEnd) { DownloadHost() }
                        com.mobilegh.ui.components.ImageViewerHost()
                        com.mobilegh.ui.components.VideoViewerHost()
                        com.mobilegh.ui.components.YouTubeViewerHost()
                        com.mobilegh.ui.components.MobileApprovalHost()
                        com.mobilegh.ui.components.NotificationPermissionHost()
                        // 启动检查发现新版本时自动弹出更新提示
                        if (Session.token != null) UpdatePromptHost()
                        LinkMenuHost()
                        ExternalLinkDialogHost()
                        GitHubWebLoginHost()
                    }
                }
            }
        }
    }
}

@Composable
private fun Main(nav: Navigator) {
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.Home) {
        if (!nav.pop()) nav.tab = Tab.Home
    }
    // 移除已出栈页面的已保存状态
    val ids = remember { HashSet<String>() }
    LaunchedEffect(nav.stack.size) {
        val alive = nav.stack.map { it.id }.toSet()
        ids.filter { it !in alive }.forEach { holder.removeState(it); ids.remove(it) }
        ids.addAll(alive)
    }
    // 未读通知数：启动和每次回到前台时刷新（否则在其他页面读过通知后，角标会一直停在旧数字），60 秒内只拉取一次
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastBadgeFetch by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val now = SystemClock.elapsedRealtime()
            if (!badgeRefreshDue(lastBadgeFetch, now)) return@LifecycleEventObserver
            lastBadgeFetch = now
            scope.launch {
                runCatching {
                    val login = Session.login
                    val list = GitHub.notifications(false, false, 1, true)
                    Badges.unread = list.count { it.unread }
                    if (login != null && login == Session.login) com.mobilegh.data.WidgetStore.saveInbox(login, list, list.size >= GitHub.PER)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Token 被 GitHub 判定失效（账号登录的授权过期等）：引导重新登录
    if (Session.authExpired && nav.stack.lastOrNull()?.screen !in listOf(Screen.Login, Screen.Reauthorize)) {
        GhDialog(
            tr("GitHub 授权已失效"),
            onDismiss = { Session.authExpired = false },
            dismissOnClickOutside = false,
            confirm = tr("重新登录"),
            onConfirm = {
                Session.authExpired = false
                nav.push(Screen.Reauthorize)
            },
        ) { Text(tr("授权可能已过期或被撤销。请重新登录，新的账号授权默认长期有效。"), fontSize = 14.sp) }
    }

    // 登录时按 X-OAuth-Scopes 比对应用所需权限，缺权限一次性告知（参考 gh auth refresh 的提示思路）
    if (Session.scopeNoticeVisible && nav.stack.lastOrNull()?.screen !in listOf(Screen.Login, Screen.Reauthorize)) {
        val missing = Session.missingScopes
        GhDialog(
            tr("授权权限不完整"),
            onDismiss = { Session.dismissScopeNotice() },
            dismissOnClickOutside = false,
            confirm = tr("重新登录补齐"),
            onConfirm = {
                Session.dismissScopeNotice()
                nav.push(Screen.Reauthorize)
            },
        ) {
            Column {
                Text(tr("当前授权缺少以下权限，对应功能将无法使用："), fontSize = 14.sp)
                missing.forEach {
                    Text("• ${it}：${com.mobilegh.data.DeviceAuth.scopeFeature(it)}", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Gh.c.fgMuted)
                }
            }
        }
    }

    // 剪贴板提示与链接菜单叠在最上层，任何页面（包括详情页自己的 Scaffold）都不会把它们挡住
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Gh.c.canvas,
            contentWindowInsets = WindowInsets(0),
            bottomBar = { if (nav.stack.isEmpty()) RootNavigationBar(nav) },
        ) { pad ->
            AnimatedContent(
                modifier = Modifier.padding(pad),
                targetState = nav.stack.lastOrNull(),
                contentKey = { it?.id ?: "root" },
                transitionSpec = {
                    if (nav.lastWasPush) {
                        (slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(220))) togetherWith fadeOut(tween(120))
                    } else {
                        fadeIn(tween(180)) togetherWith (slideOutHorizontally(tween(200)) { it / 4 } + fadeOut(tween(200)))
                    }
                },
                label = "nav",
            ) { entry ->
                if (entry == null) {
                    RootTabs(nav, holder)
                } else {
                    CompositionLocalProvider(LocalEntry provides entry) {
                        holder.SaveableStateProvider(entry.id) {
                            Box(Modifier.fillMaxSize().background(Gh.c.canvas)) { ScreenHost(entry.screen) }
                        }
                    }
                }
            }
        }
    // 根页面底部有标签栏，提示要抬高到标签栏之上；详情页没有标签栏
    ClipboardLinkHost(
        nav,
        Modifier.align(Alignment.BottomCenter)
            .padding(bottom = if (nav.stack.isEmpty()) 80.dp else 0.dp)
            .navigationBarsPadding().imePadding(),
    )
    }
}

@Composable
private fun RootNavigationBar(nav: Navigator) {
    val g = Gh.c
    val bottomTabs = listOf(Tab.Home, Tab.Repos, Tab.Star, Tab.Explore)
    Column {
        HDivider()
        NavigationBar(containerColor = g.header, tonalElevation = 0.dp) {
            bottomTabs.forEach { t ->
                val sel = nav.tab == t
                val icon = when (t) {
                    Tab.Home -> if (sel) R.drawable.oc_home_fill else R.drawable.oc_home
                    Tab.Repos -> R.drawable.oc_repo
                    Tab.Star -> if (sel) R.drawable.oc_star_fill else R.drawable.oc_star
                    Tab.Explore -> if (sel) R.drawable.oc_telescope_fill else R.drawable.oc_telescope
                    Tab.Notifications -> if (sel) R.drawable.oc_bell_fill else R.drawable.oc_bell
                    Tab.Me -> if (sel) R.drawable.oc_person_fill else R.drawable.oc_person
                }
                NavigationBarItem(
                    selected = sel,
                    onClick = { nav.select(t) },
                    icon = { Oc(icon, if (sel) g.accent else g.fgMuted, 22.dp) },
                    label = { Text(t.title, fontSize = 11.sp, color = if (sel) g.accent else g.fgMuted) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                        selectedIconColor = g.accent,
                        unselectedIconColor = g.fgMuted,
                        selectedTextColor = g.accent,
                        unselectedTextColor = g.fgMuted,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RootTabs(nav: Navigator, holder: SaveableStateHolder) {
    Box(Modifier.fillMaxSize()) {
        val entry = nav.tabEntries.getValue(nav.tab)
        CompositionLocalProvider(LocalEntry provides entry) {
            holder.SaveableStateProvider("tab-${nav.tab.name}") {
                when (nav.tab) {
                    Tab.Home -> HomeScreen()
                    Tab.Repos -> ReposTab()
                    Tab.Star -> StarTab()
                    Tab.Explore -> ExploreScreen()
                    Tab.Notifications -> NotificationsScreen()
                    Tab.Me -> MeScreen()
                }
            }
        }
    }
}

@Composable
private fun ScreenHost(s: Screen) {
    when (s) {
        Screen.Root -> {}
        is Screen.Repo -> RepoScreen(s.owner, s.name)
        is Screen.Files -> FilesScreen(s.owner, s.name, s.path, s.ref)
        is Screen.Wiki -> WikiScreen(s.owner, s.name, s.page)
        is Screen.FileView -> FileViewScreen(s.owner, s.name, s.path, s.ref, s.lines)
        is Screen.Blob -> BlobScreen(s.owner, s.name, s.rest, s.lines)
        is Screen.FileFinder -> FileFinderScreen(s.owner, s.name, s.ref)
        is Screen.CodeSearch -> CodeSearchScreen(s.owner, s.name)
        is Screen.Search -> SearchScreen(s.type, s.query)
        is Screen.Blame -> BlameScreen(s.owner, s.name, s.path, s.ref)
        is Screen.EditFile -> EditFileScreen(s.owner, s.name, s.ref, s.path, s.dir)
        is Screen.UploadFiles -> UploadFilesScreen(s.owner, s.name, s.path, s.ref)
        is Screen.NewPull -> NewPullScreen(s.owner, s.name, s.head, s.base, s.title, s.body)
        is Screen.EditRelease -> EditReleaseScreen(s.owner, s.name, s.releaseId)
        is Screen.RunWorkflow -> RunWorkflowScreen(s.owner, s.name, s.workflowId, s.path, s.title, s.ref)
        Screen.RecentRepositories -> RecentRepositoriesScreen()
        Screen.SearchHistory -> SearchHistoryScreen()
        Screen.EditProfile -> EditProfileScreen()
        is Screen.Commits -> CommitsScreen(s.owner, s.name, s.ref, s.path, s.author, s.since, s.until)
        is Screen.CommitDetail -> CommitDetailScreen(s.owner, s.name, s.sha)
        is Screen.Compare -> CompareScreen(s.owner, s.name, s.base, s.head)
        is Screen.Issues -> IssuesScreen(s.owner, s.name, s.pulls, s.preset)
        is Screen.IssueDetail -> IssueDetailScreen(s.owner, s.name, s.number, s.isPull, s.commentId)
        is Screen.PullFiles -> PullFilesScreen(s.owner, s.name, s.number)
        is Screen.PullChecks -> PullChecksScreen(s.owner, s.name, s.number, s.sha)
        is Screen.NewIssue -> NewIssueScreen(s.owner, s.name)
        is Screen.Actions -> ActionsScreen(s.owner, s.name)
        is Screen.RunDetail -> RunDetailScreen(s.owner, s.name, s.runId)
        is Screen.JobLog -> JobLogScreen(s.owner, s.name, s.jobId, s.title, s.runId, s.stepNumber)
        is Screen.Releases -> ReleasesScreen(s.owner, s.name, s.tag, s.releaseId)
        is Screen.Pushes -> PushesScreen(s.owner, s.name)
        is Screen.Branches -> BranchesScreen(s.owner, s.name)
        is Screen.Insights -> InsightsScreen(s.owner, s.name)
        is Screen.SecurityAlerts -> SecurityAlertsScreen(s.owner, s.name)
        is Screen.MergeQueue -> MergeQueueScreen(s.owner, s.name)
        is Screen.BranchRules -> BranchRulesScreen(s.owner, s.name)
        is Screen.Contributors -> ContributorsScreen(s.owner, s.name)
        is Screen.Discussions -> DiscussionsScreen(s.owner, s.name)
        is Screen.DiscussionDetail -> DiscussionDetailScreen(s.owner, s.name, s.number)
        is Screen.RepoSettings -> RepoSettingsScreen(s.owner, s.name)
        is Screen.Projects -> ProjectsScreen(s.kind, s.login, s.name)
        is Screen.ProjectDetail -> ProjectDetailScreen(s.id, s.title)
        is Screen.Collaborators -> CollaboratorsScreen(s.owner, s.name)
        is Screen.Profile -> ProfileScreen(s.login)
        is Screen.Org -> OrgScreen(s.login)
        is Screen.Users -> UsersScreen(s.kind, s.a, s.b)
        is Screen.Repos -> when (s.kind) {
            RepoKind.Starred -> StarredRepoPage(s.login, s.name)
            RepoKind.Forks -> ForkListScreen(s.login, s.name)
            else -> RepoFeedScreen(s.kind, s.login, s.name)
        }
        Screen.Orgs -> OrgsScreen()
        Screen.Invitations -> InvitationsScreen()
        is Screen.Gists -> GistsScreen(s.login)
        is Screen.GistDetail -> GistDetailScreen(s.id)
        is Screen.GistEditor -> GistEditorScreen(s.id)
        is Screen.Activity -> ActivityScreen(s.login)
        is Screen.StarLists -> StarListsScreen(s.login, s.initialListId, s.local)
        is Screen.Achievements -> AchievementsScreen(s.login)
        Screen.Settings -> SettingsScreen()
        Screen.Logs -> LogsScreen()
        Screen.Drafts -> DraftsScreen()
        Screen.AddFavorites -> AddFavoritesScreen()
        Screen.Favorites -> FavoritesScreen()
        Screen.Saved -> SavedScreen()
        Screen.Authenticator -> AuthenticatorScreen()
        Screen.OrgDiagnostics -> OrgDiagnosticsScreen()
        Screen.CreateRepo -> CreateRepoScreen()
        Screen.Login -> LoginScreen(adding = true)
        Screen.Reauthorize -> LoginScreen(reauthorizing = true)
        is Screen.MyIssues -> MyIssuesScreen(s.pulls, s.preset)
        Screen.HomeCustomize -> HomeCustomizeScreen()
        Screen.NewShortcut -> NewShortcutScreen()
        Screen.Feed -> FeedScreen()
        Screen.Notifications -> NotificationsScreen()
        Screen.Me -> MeScreen()
        Screen.Trending -> TrendingScreen()
        Screen.CuratedLists -> CuratedListsScreen()
    }
}
