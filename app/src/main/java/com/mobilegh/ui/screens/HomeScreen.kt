package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.Session
import com.mobilegh.data.StarListAccount
import com.mobilegh.data.UserLists
import com.mobilegh.data.Web
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.EventCat
import com.mobilegh.ui.components.EventItem
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.eventCat
import com.mobilegh.ui.components.IssueItem
import com.mobilegh.ui.components.MenuRow
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.theme.Gh

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val g = Gh.c
    val login = Session.login ?: return
    val me = rememberLoader("me") { GitHub.viewer(it) }
    val account = remember(login, Session.generation) { StarListAccount(login, Session.generation) }
    val lists = rememberLoader("home-collections:$login") { UserLists.lists(account, login) }
    val badges = rememberLoader("home-achievements:$login") { Web.achievements(login, self = true) }
    val listsRevision by UserLists.changes.collectAsState()
    val observedRevision = retain("home-collections-revision:$login") { mutableLongStateOf(listsRevision) }
    LaunchedEffect(listsRevision) {
        if (observedRevision.longValue != listsRevision) {
            observedRevision.longValue = listsRevision
            lists.refresh()
        }
    }
    com.mobilegh.ui.components.RefreshProfileOnChange(me)
    val invites = rememberLoader("inv") { f ->
        GitHub.repoInvitations(f).size + runCatching { GitHub.orgInvitations(f).size }.getOrDefault(0)
    }
    val contrib = rememberContrib(login)
    val feed = rememberPager("feed") { p, f -> GitHub.received(login, p, f) }
    var cat by rememberSaveable { mutableStateOf(EventCat.All) }
    val state = rememberLazyListState()
    LaunchedEffect(nav.reselect) { if (nav.reselect > 0 && nav.tab == Tab.Home) state.animateScrollToItem(0) }

    Page(
        "首页", back = false, contentWindowInsets = WindowInsets(0),
        actions = {
            OcButton(R.drawable.oc_plus, { nav.push(Screen.CreateRepo) })
            OcButton(R.drawable.oc_gear, { nav.push(Screen.Settings) })
        },
    ) { pad ->
        PagedList(
            feed, Modifier.padding(pad).fillMaxSize(), state,
            empty = "关注一些开发者或 star 一些仓库后，这里会显示动态",
            onRefresh = { me.refresh(); invites.refresh(); contrib.refresh(); lists.refresh(); badges.refresh() },
            itemFilter = { cat == EventCat.All || eventCat(it) == cat },
            itemKey = { it.id },
            header = {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(me.data?.avatarUrl ?: Session.avatar, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("你好，${me.data?.name?.takeIf { it.isNotBlank() } ?: login}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                            val u = me.data
                            Text(
                                if (u == null) "@$login" else "@$login · ${u.publicRepos + (u.ownedPrivateRepos ?: 0)} 个自有仓库 · ${u.followers} 关注者",
                                fontSize = 13.sp, color = g.fgMuted,
                            )
                        }
                    }
                    HDivider()
                }
                item {
                    HomeCollections(
                        lists.data, badges.data, lists.error, badges.error,
                        onLists = { nav.push(Screen.StarLists(login)) },
                        onList = { nav.push(Screen.StarLists(login, it.id)) },
                        onStars = { nav.push(Screen.Repos(RepoKind.Starred, login)) },
                        onAchievements = { nav.push(Screen.Achievements(login)) },
                        onRetryLists = { lists.load(true) },
                        onRetryBadges = { badges.load(true) },
                    )
                }
                item { SectionTitle("我的工作") }
                item {
                    MenuGroup {
                        MenuRow(R.drawable.oc_issue_opened, "Issues", Color(0xFF1A7F37)) { nav.push(Screen.MyIssues(false)) }
                        MenuRow(R.drawable.oc_git_pull_request, "Pull Requests", Color(0xFF0969DA)) { nav.push(Screen.MyIssues(true)) }
                        MenuRow(R.drawable.oc_repo, "仓库（含协作与组织）", Color(0xFF59636E)) { nav.select(Tab.Repos) }
                        MenuRow(R.drawable.oc_history, "最近浏览") { nav.push(Screen.RecentRepositories) }
                        MenuRow(R.drawable.oc_organization, "组织", Color(0xFFBC4C00)) { nav.push(Screen.Orgs) }
                        MenuRow(R.drawable.oc_mail, "待处理邀请", Color(0xFF8250DF), count = invites.data?.takeIf { it > 0 }) { nav.push(Screen.Invitations) }
                        MenuRow(R.drawable.oc_code_square, "Gists", Color(0xFF24292F)) { nav.push(Screen.Gists(null)) }
                    }
                }
                item { SectionTitle("贡献") { com.mobilegh.ui.components.TextLink("详情") { nav.select(Tab.Me) } } }
                item { Card(Modifier.padding(horizontal = 16.dp)) { ContributionBody(contrib, full = false) } }
                item { SectionTitle("动态") }
                item {
                    Chips(EventCat.entries.map { it.label }, EventCat.entries.indexOf(cat)) { cat = EventCat.entries[it] }
                }
            },
        ) { EventItem(it) }
    }
}

/** 我的 Issues / PR（官方网页的 github.com/issues 页面） */
@Composable
fun MyIssuesScreen(pulls: Boolean) {
    val nav = LocalNav.current
    val kinds = if (pulls) listOf("我创建的" to "author:@me", "待我审查" to "review-requested:@me", "指派给我" to "assignee:@me", "提及我" to "mentions:@me")
    else listOf("我创建的" to "author:@me", "指派给我" to "assignee:@me", "提及我" to "mentions:@me")
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var st by rememberSaveable { mutableIntStateOf(0) }
    val q = "is:${if (pulls) "pr" else "issue"} is:${if (st == 0) "open" else "closed"} ${kinds[kind].second} archived:false"
    val pager = rememberPager("my:$q") { p, _ -> GitHub.searchIssues(q, p).items }
    Page(if (pulls) "我的 Pull Requests" else "我的 Issues") { pad ->
        Column(Modifier.padding(pad)) {
            Chips(kinds.map { it.first }, kind) { kind = it }
            Chips(listOf("开启", "已关闭"), st, Modifier.padding(top = 0.dp)) { st = it }
            HDivider()
            PagedList(pager, empty = "没有匹配的${if (pulls) " PR" else " Issue"}") { issue ->
                val repo = issue.repositoryUrl?.substringAfter("/repos/") ?: ""
                IssueItem(issue, repoName = repo) {
                    val (o, r) = repo.split('/').let { it[0] to it.getOrElse(1) { "" } }
                    nav.push(Screen.IssueDetail(o, r, issue.number, issue.pullRequest != null))
                }
            }
        }
    }
}
