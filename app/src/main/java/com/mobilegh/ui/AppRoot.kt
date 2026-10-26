package com.mobilegh.ui

import android.app.Activity
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
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

object Badges {
    var unread by androidx.compose.runtime.mutableIntStateOf(0)
}

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
                        com.mobilegh.ui.components.MobileApprovalHost()
                        com.mobilegh.ui.components.NotificationPermissionHost()
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
    // 启动时拉取未读通知数
    LaunchedEffect(Unit) {
        runCatching {
            val login = Session.login
            val list = GitHub.notifications(false, false, 1, true)
            Badges.unread = list.count { it.unread }
            if (login != null && login == Session.login) com.mobilegh.data.WidgetStore.saveInbox(login, list, list.size >= GitHub.PER)
        }
    }
    // Token 被 GitHub 判定失效（账号登录的授权过期等）：引导重新登录
    if (Session.authExpired && nav.stack.lastOrNull()?.screen !in listOf(Screen.Login, Screen.Reauthorize)) {
        GhDialog(
            "GitHub 授权已失效",
            onDismiss = { Session.authExpired = false },
            confirm = "重新登录",
            onConfirm = {
                Session.authExpired = false
                nav.push(Screen.Reauthorize)
            },
        ) { Text("授权可能已过期或被撤销。请重新登录，新的账号授权默认长期有效。", fontSize = 14.sp) }
    }

    // 登录时按 X-OAuth-Scopes 比对应用所需权限，缺权限一次性告知（参考 gh auth refresh 的提示思路）
    if (Session.scopeNoticeVisible && nav.stack.lastOrNull()?.screen !in listOf(Screen.Login, Screen.Reauthorize)) {
        val missing = Session.missingScopes
        GhDialog(
            "授权权限不完整",
            onDismiss = { Session.dismissScopeNotice() },
            confirm = "重新登录补齐",
            onConfirm = {
                Session.dismissScopeNotice()
                nav.push(Screen.Reauthorize)
            },
        ) {
            Column {
                Text("当前授权缺少以下权限，对应功能将无法使用：", fontSize = 14.sp)
                missing.forEach {
                    Text("• ${it}：${com.mobilegh.data.DeviceAuth.scopeFeature(it)}", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Gh.c.fgMuted)
                }
            }
        }
    }

    Scaffold(
        containerColor = Gh.c.canvas,
        contentWindowInsets = WindowInsets(0),
        bottomBar = { if (nav.stack.isEmpty()) RootNavigationBar(nav) },
        snackbarHost = {
            val insets = if (nav.stack.isEmpty()) Modifier else Modifier.navigationBarsPadding()
            ClipboardLinkHost(nav, insets.imePadding())
        },
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
}

@Composable
private fun RootNavigationBar(nav: Navigator) {
    val g = Gh.c
    Column {
        HDivider()
        NavigationBar(containerColor = g.header, tonalElevation = androidx.compose.ui.unit.Dp.Hairline) {
            Tab.entries.forEach { t ->
                val sel = nav.tab == t
                val icon = when (t) {
                    Tab.Home -> if (sel) R.drawable.oc_home_fill else R.drawable.oc_home
                    Tab.Repos -> R.drawable.oc_repo
                    Tab.Notifications -> if (sel) R.drawable.oc_bell_fill else R.drawable.oc_bell
                    Tab.Explore -> R.drawable.oc_search
                    Tab.Me -> if (sel) R.drawable.oc_person_fill else R.drawable.oc_person
                }
                NavigationBarItem(
                    selected = sel,
                    onClick = { nav.select(t) },
                    icon = {
                        if (t == Tab.Notifications && Badges.unread > 0) {
                            BadgedBox(badge = { Badge(containerColor = g.accent) { Text(if (Badges.unread > 99) "99+" else "${Badges.unread}", fontSize = 9.sp) } }) {
                                Oc(icon, if (sel) g.fg else g.fgMuted, 22.dp)
                            }
                        } else {
                            Oc(icon, if (sel) g.fg else g.fgMuted, 22.dp)
                        }
                    },
                    label = { Text(t.title, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = g.neutralMuted,
                        selectedTextColor = g.fg,
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
                    Tab.Notifications -> NotificationsScreen()
                    Tab.Explore -> ExploreScreen()
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
        is Screen.FileView -> FileViewScreen(s.owner, s.name, s.path, s.ref)
        is Screen.FileFinder -> FileFinderScreen(s.owner, s.name, s.ref)
        is Screen.CodeSearch -> CodeSearchScreen(s.owner, s.name)
        is Screen.Search -> SearchScreen(s.type, s.query)
        is Screen.Blame -> BlameScreen(s.owner, s.name, s.path, s.ref)
        is Screen.EditFile -> EditFileScreen(s.owner, s.name, s.ref, s.path, s.dir)
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
        is Screen.IssueDetail -> IssueDetailScreen(s.owner, s.name, s.number, s.isPull)
        is Screen.PullFiles -> PullFilesScreen(s.owner, s.name, s.number)
        is Screen.PullChecks -> PullChecksScreen(s.owner, s.name, s.number, s.sha)
        is Screen.NewIssue -> NewIssueScreen(s.owner, s.name)
        is Screen.Actions -> ActionsScreen(s.owner, s.name)
        is Screen.RunDetail -> RunDetailScreen(s.owner, s.name, s.runId)
        is Screen.JobLog -> JobLogScreen(s.owner, s.name, s.jobId, s.title)
        is Screen.Releases -> ReleasesScreen(s.owner, s.name)
        is Screen.Branches -> BranchesScreen(s.owner, s.name)
        is Screen.Insights -> InsightsScreen(s.owner, s.name)
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
        Screen.Authenticator -> AuthenticatorScreen()
        Screen.OrgDiagnostics -> OrgDiagnosticsScreen()
        Screen.CreateRepo -> CreateRepoScreen()
        Screen.Login -> LoginScreen(adding = true)
        Screen.Reauthorize -> LoginScreen(reauthorizing = true)
        is Screen.MyIssues -> MyIssuesScreen(s.pulls, s.preset)
        Screen.HomeCustomize -> HomeCustomizeScreen()
    }
}
