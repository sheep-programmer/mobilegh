package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Contributions
import com.mobilegh.data.GitHub
import com.mobilegh.data.PinnedRepo
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Loader
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.nav.UserKind
import com.mobilegh.nav.act
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.BarChart
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.EventItem
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Heatmap
import com.mobilegh.ui.components.IconText
import com.mobilegh.ui.components.LangDot
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MenuRow
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.RatioRow
import com.mobilegh.ui.components.RepoItem
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.StatCell
import com.mobilegh.ui.components.UserItem
import com.mobilegh.ui.components.contribStats
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.fmtCount
import com.mobilegh.ui.components.fmtDate
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.share
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.components.MdText
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CoroutineScope

// ======================= 贡献热力图 =======================

class ContribState(scope: CoroutineScope, private val login: String) {
    var year by mutableStateOf<Int?>(null)
    var years by mutableStateOf<List<Int>>(emptyList())
    val loader: Loader<Contributions> = Loader(scope) {
        GitHub.contributions(login, year).also { c -> if (c.contributionYears.isNotEmpty()) years = c.contributionYears }
    }

    fun select(y: Int?) {
        year = y
        loader.load()
    }

    fun refresh() = loader.refresh()
}

@Composable
fun rememberContrib(login: String): ContribState = retain("contrib:$login") { e -> ContribState(e.scope, login).also { it.loader.load() } }

@Composable
fun ContributionBody(state: ContribState, full: Boolean) {
    val g = Gh.c
    val nav = LocalNav.current
    val c = state.loader.data
    Column(Modifier.padding(vertical = 12.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (c == null) "贡献" else "${state.year?.let { "$it 年" } ?: "最近一年"} ${fmtCount(c.contributionCalendar.totalContributions)} 次贡献",
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = g.fg, modifier = Modifier.weight(1f),
            )
            if (state.loader.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = g.fgMuted)
        }
        if (full && state.years.size > 1) {
            Chips(listOf("最近一年") + state.years.map { "$it" }, state.year?.let { state.years.indexOf(it) + 1 } ?: 0) { i ->
                state.select(if (i == 0) null else state.years[i - 1])
            }
        } else {
            Spacer(Modifier.height(10.dp))
        }
        when {
            c != null -> {
                Heatmap(c.contributionCalendar, Modifier.padding(horizontal = 12.dp))
                val st = contribStats(c.contributionCalendar)
                Spacer(Modifier.height(8.dp))
                HDivider()
                Row(Modifier.fillMaxWidth()) {
                    StatCell("${st.current}", "当前连续·天", Modifier.weight(1f))
                    StatCell("${st.longest}", "最长连续·天", Modifier.weight(1f))
                    StatCell("${st.activeDays}", "活跃天数", Modifier.weight(1f))
                    StatCell("${st.best?.contributionCount ?: 0}", "单日最高", Modifier.weight(1f))
                }
                if (full) {
                    HDivider()
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        val total = c.totalCommitContributions + c.totalPullRequestContributions + c.totalIssueContributions +
                            c.totalPullRequestReviewContributions + c.totalRepositoryContributions
                        Text("贡献构成", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg)
                        Spacer(Modifier.height(4.dp))
                        RatioRow("提交 Commits", c.totalCommitContributions, total, g.success)
                        RatioRow("拉取请求 PR", c.totalPullRequestContributions, total, g.accent)
                        RatioRow("代码审查 Review", c.totalPullRequestReviewContributions, total, g.done)
                        RatioRow("议题 Issues", c.totalIssueContributions, total, g.attention)
                        RatioRow("新建仓库", c.totalRepositoryContributions, total, g.fgMuted)
                        if (c.restrictedContributionsCount > 0) {
                            Text("另有 ${c.restrictedContributionsCount} 次私有贡献（对你不可见）", fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 4.dp))
                        }
                        st.best?.let { Text("单日最高：${it.date}，${it.contributionCount} 次", fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 4.dp)) }

                        Spacer(Modifier.height(14.dp))
                        Text("一周中的活跃分布", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg)
                        Spacer(Modifier.height(8.dp))
                        BarChart(st.byWeekday.toList(), g.success, height = 64.dp)
                        Row(Modifier.fillMaxWidth()) {
                            listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                                Text(it, Modifier.weight(1f), fontSize = 11.sp, color = g.fgMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                        if (c.commitContributionsByRepository.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text("提交最多的仓库", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg)
                            val max = c.commitContributionsByRepository.maxOf { it.contributions.totalCount }
                            c.commitContributionsByRepository.forEach { rc ->
                                val name = rc.repository.nameWithOwner
                                Box(Modifier.clickable {
                                    val (o, r) = name.split('/').let { it[0] to it.getOrElse(1) { "" } }
                                    nav.push(Screen.Repo(o, r))
                                }) {
                                    RatioRow(name + if (rc.repository.isPrivate) " 🔒" else "", rc.contributions.totalCount, max, g.success)
                                }
                            }
                        }
                    }
                }
            }
            state.loader.error != null -> ErrorState(state.loader.error!!, Modifier.fillMaxWidth().height(160.dp)) { state.loader.load() }
            else -> Loading(Modifier.fillMaxWidth().height(140.dp))
        }
    }
}

// ======================= 个人主页 =======================

@Composable
fun MeScreen() {
    val login = Session.login ?: return
    ProfileContent(login, me = true)
}

@Composable
fun ProfileScreen(login: String) {
    if (login.equals(Session.login, true)) ProfileContent(login, me = false, isSelf = true) else ProfileContent(login, me = false)
}

@Composable
private fun ProfileContent(login: String, me: Boolean, isSelf: Boolean = me) {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val ctx = rememberCtx()
    val g = Gh.c
    val user = rememberLoader("user:$login") { if (isSelf) GitHub.viewer(it) else GitHub.user(login, it) }
    val u = user.data
    if (u != null && u.type == "Organization") {
        OrgScreen(login)
        return
    }
    val contrib = rememberContrib(login)
    val pinned = rememberLoader("pinned:$login") { runCatching { GitHub.pinned(login) }.getOrDefault(emptyList()) }
    val orgs = rememberLoader("orgs:$login") { if (isSelf) GitHub.myOrgs(it) else GitHub.userOrgs(login, it) }
    val following = retain("following:$login") { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(login) {
        if (!isSelf && following.value == null) following.value = runCatching { GitHub.isFollowing(login) }.getOrNull()
    }
    val state = rememberLazyListState()
    if (me) LaunchedEffect(nav.reselect) { if (nav.reselect > 0 && nav.tab == Tab.Me) state.animateScrollToItem(0) }

    Page(
        if (me) "我的" else login, back = !me, contentWindowInsets = if (me) WindowInsets(0) else null,
        actions = {
            if (me) OcButton(R.drawable.oc_gear, { nav.push(Screen.Settings) })
            MoreMenu(
                listOf(
                    MenuAction("分享") { ctx.share("https://github.com/$login") },
                    MenuAction("复制链接") { ctx.copy("https://github.com/$login") },
                    MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/$login") },
                ),
            )
        },
    ) { pad ->
        when {
            u == null && user.error != null -> ErrorState(user.error!!, Modifier.padding(pad)) { user.load(true) }
            u == null -> Loading(Modifier.padding(pad).fillMaxSize())
            else -> PullToRefreshBox(user.refreshing, { user.refresh(); contrib.refresh(); pinned.refresh(); orgs.refresh() }, Modifier.padding(pad)) {
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    item { ProfileHeader(u, isSelf, following.value) { on ->
                        following.value = on
                        entry.act({ following.value = !on; ctx.toast(it) }) { GitHub.follow(login, on) }
                    } }
                    val orgList = orgs.data.orEmpty()
                    if (orgList.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            orgList.forEach { o -> Box(Modifier.clickable { nav.push(Screen.Org(o.login)) }) { Avatar(o.avatarUrl, 32.dp, square = true) } }
                        }
                    }
                    item { AchievementsStrip(login, self = isSelf) }
                    item { SectionTitle("贡献活动") { com.mobilegh.ui.components.TextLink("动态") { nav.push(Screen.Activity(login)) } } }
                    item { Card(Modifier.padding(horizontal = 16.dp)) { ContributionBody(contrib, full = true) } }
                    val pins = pinned.data.orEmpty()
                    if (pins.isNotEmpty()) {
                        item { SectionTitle("置顶") }
                        items(pins) { PinnedCard(it) }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                    item {
                        MenuGroup {
                            MenuRow(R.drawable.oc_repo, "仓库", Color(0xFF59636E), count = u.publicRepos + (if (isSelf) u.ownedPrivateRepos ?: 0 else 0)) {
                                if (isSelf) nav.select(Tab.Repos).also { nav.popToRoot() } else nav.push(Screen.Repos(RepoKind.User, login))
                            }
                            MenuRow(R.drawable.oc_star, "Star", Color(0xFFBF8700)) { nav.push(Screen.Repos(RepoKind.Starred, login)) }
                            MenuRow(R.drawable.oc_checklist, "Star 列表", Color(0xFFD4A72C)) { nav.push(Screen.StarLists(login)) }
                            MenuRow(R.drawable.oc_organization, "组织", Color(0xFFBC4C00), count = orgList.size.takeIf { it > 0 }) {
                                if (isSelf) nav.push(Screen.Orgs) else orgList.firstOrNull()?.let { nav.push(Screen.Org(it.login)) }
                            }
                            MenuRow(R.drawable.oc_code_square, "Gists", Color(0xFF24292F), count = u.publicGists + (u.privateGists ?: 0)) {
                                nav.push(Screen.Gists(if (isSelf) null else login))
                            }
                            MenuRow(R.drawable.oc_pulse, "公开动态", Color(0xFF0969DA)) { nav.push(Screen.Activity(login)) }
                            if (isSelf) {
                                MenuRow(R.drawable.oc_mail, "待处理邀请", Color(0xFF8250DF)) { nav.push(Screen.Invitations) }
                                MenuRow(R.drawable.oc_gear, "设置", Color(0xFF6E7781)) { nav.push(Screen.Settings) }
                            }
                        }
                    }
                    if (isSelf) item {
                        Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) { Column(Modifier.padding(14.dp)) {
                            Text("账号信息", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg)
                            Spacer(Modifier.height(6.dp))
                            Info("套餐", u.plan?.name ?: "-")
                            Info("私有仓库", "${u.ownedPrivateRepos ?: 0} 个自有 / ${u.totalPrivateRepos ?: 0} 个总计")
                            Info("磁盘占用", u.diskUsage?.let { fmtSize(it * 1024) } ?: "-")
                            Info("两步验证", if (u.twoFactorAuthentication == true) "已开启" else "未开启")
                            Info("注册于", fmtDate(u.createdAt))
                        } }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Info(k: String, v: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(k, fontSize = 13.sp, color = Gh.c.fgMuted, modifier = Modifier.width(80.dp))
        Text(v, fontSize = 13.sp, color = Gh.c.fg)
    }
}

@Composable
private fun ProfileHeader(u: User, isSelf: Boolean, following: Boolean?, onFollow: (Boolean) -> Unit) {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(u.avatarUrl, 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (!u.name.isNullOrBlank()) Text(u.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.login, fontSize = 17.sp, color = g.fgMuted)
                    if (u.siteAdmin) { Spacer(Modifier.width(6.dp)); Pill("Staff", g.done) }
                }
            }
        }
        if (!u.bio.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            MdText(u.bio, fontSize = 15.sp)
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            u.company?.takeIf { it.isNotBlank() }?.let { IconText(R.drawable.oc_organization, it, g.fg) }
            u.location?.takeIf { it.isNotBlank() }?.let { IconText(R.drawable.oc_location, it, g.fg) }
            u.blog?.takeIf { it.isNotBlank() }?.let { b ->
                IconText(R.drawable.oc_link, b, g.accent, Modifier.clickable { ctx.openBrowser(if (b.startsWith("http")) b else "https://$b") }, bold = true)
            }
            u.email?.takeIf { it.isNotBlank() }?.let { IconText(R.drawable.oc_mail, it, g.fg, Modifier.clickable { ctx.copy(it) }) }
            u.twitterUsername?.takeIf { it.isNotBlank() }?.let { IconText(R.drawable.oc_mention, "@$it", g.fg) }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Oc2(R.drawable.oc_people)
            Text(fmtCount(u.followers), fontWeight = FontWeight.SemiBold, color = g.fg, fontSize = 14.sp, modifier = Modifier.clickable { nav.push(Screen.Users(UserKind.Followers, u.login)) })
            Text(" 关注者 · ", color = g.fgMuted, fontSize = 14.sp)
            Text(fmtCount(u.following), fontWeight = FontWeight.SemiBold, color = g.fg, fontSize = 14.sp, modifier = Modifier.clickable { nav.push(Screen.Users(UserKind.Following, u.login)) })
            Text(" 关注中", color = g.fgMuted, fontSize = 14.sp)
        }
        if (!isSelf) {
            Spacer(Modifier.height(12.dp))
            if (following == true) GhButton("取消关注", Modifier.fillMaxWidth()) { onFollow(false) }
            else GhButton("关注", Modifier.fillMaxWidth(), primary = true, enabled = following != null) { onFollow(true) }
        }
    }
}

@Composable
private fun Oc2(icon: Int) {
    com.mobilegh.ui.components.Oc(icon, Gh.c.fgMuted, 14.dp)
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun PinnedCard(p: PinnedRepo) {
    val g = Gh.c
    val nav = LocalNav.current
    Card(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).clickable { nav.push(Screen.Repo(p.owner.login, p.name)) }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.mobilegh.ui.components.Oc(R.drawable.oc_repo, g.fgMuted)
                Spacer(Modifier.width(6.dp))
                Text(p.name, color = g.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f, false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Pill(if (p.isPrivate) "私有" else "公开")
            }
            if (!p.description.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                MdText(p.description, fontSize = 13.sp, color = g.fgMuted, maxLines = 2)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                p.primaryLanguage?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LangDot(it.name, it.color)
                        Spacer(Modifier.width(4.dp))
                        Text(it.name, fontSize = 12.sp, color = g.fgMuted)
                    }
                }
                IconText(R.drawable.oc_star, fmtCount(p.stargazerCount))
                IconText(R.drawable.oc_repo_forked, fmtCount(p.forkCount))
            }
        }
    }
}

// ======================= 组织 =======================

@Composable
fun OrgScreen(login: String) {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val org = rememberLoader("org:$login") { GitHub.org(login, it) }
    var sort by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val sorts = listOf("pushed" to "最近推送", "updated" to "最近更新", "full_name" to "名称", "created" to "创建时间")
    val repos = rememberPager("orgrepos:$login:$sort") { p, f -> GitHub.orgRepos(login, sorts[sort].first, p, f) }
    Page(
        org.data?.name ?: login, subtitle = "组织",
        actions = {
            MoreMenu(listOf(
                MenuAction("成员") { nav.push(Screen.Users(UserKind.Members, login)) },
                MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/$login") },
                MenuAction("分享") { ctx.share("https://github.com/$login") },
            ))
        },
    ) { pad ->
        PagedList(repos, Modifier.padding(pad), empty = "没有可见的仓库", onRefresh = { org.refresh() }, header = {
            item {
                val o = org.data
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(o?.avatarUrl, 60.dp, square = true)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(o?.name ?: login, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                                if (o?.isVerified == true) { Spacer(Modifier.width(6.dp)); Pill("已验证", g.success) }
                            }
                            Text(login, color = g.fgMuted, fontSize = 14.sp)
                        }
                    }
                    o?.description?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(10.dp)); Text(it, color = g.fg, fontSize = 14.sp) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        o?.location?.takeIf { it.isNotBlank() }?.let { IconText(R.drawable.oc_location, it) }
                        o?.blog?.takeIf { it.isNotBlank() }?.let { b -> IconText(R.drawable.oc_link, b, g.accent, Modifier.clickable { ctx.openBrowser(if (b.startsWith("http")) b else "https://$b") }) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhButton("成员", icon = R.drawable.oc_people) { nav.push(Screen.Users(UserKind.Members, login)) }
                        o?.let { GhButton("${fmtCount(it.publicRepos + (it.totalPrivateRepos ?: 0))} 个仓库", icon = R.drawable.oc_repo) {} }
                    }
                }
                HDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("仓库", fontWeight = FontWeight.SemiBold, color = g.fg, modifier = Modifier.weight(1f))
                    com.mobilegh.ui.components.Dropdown(sorts[sort].second, sorts.map { it.second }, { sort = it })
                }
                HDivider()
            }
        }) { RepoItem(it, showOwner = false) }
    }
}

// ======================= 用户列表 / 动态 =======================

@Composable
fun UsersScreen(kind: UserKind, a: String, b: String) {
    val title = when (kind) {
        UserKind.Followers -> "关注者"
        UserKind.Following -> "关注中"
        UserKind.Stargazers -> "Star 用户"
        UserKind.Watchers -> "Watch 用户"
        UserKind.Contributors -> "贡献者"
        UserKind.Members -> "成员"
    }
    val pager = rememberPager("users:$kind:$a:$b") { p, f ->
        when (kind) {
            UserKind.Followers -> GitHub.followers(a, p, f)
            UserKind.Following -> GitHub.following(a, p, f)
            UserKind.Stargazers -> GitHub.stargazers(a, b, p, f)
            UserKind.Watchers -> GitHub.watchers(a, b, p, f)
            UserKind.Contributors -> GitHub.contributors(a, b, p, f)
            UserKind.Members -> GitHub.orgMembers(a, p, f)
        }
    }
    Page(title, subtitle = if (b.isNotEmpty()) "$a/$b" else a) { pad ->
        PagedList(pager, Modifier.padding(pad)) { u ->
            UserItem(u, subtitle = u.contributions?.let { "$it 次提交" })
        }
    }
}

@Composable
fun ActivityScreen(login: String) {
    val pager = rememberPager("events:$login") { p, f -> GitHub.userEvents(login, p, f) }
    var cat by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(com.mobilegh.ui.components.EventCat.All) }
    Page("动态", subtitle = login) { pad ->
        Column(Modifier.padding(pad)) {
            Chips(com.mobilegh.ui.components.EventCat.entries.map { it.label }, com.mobilegh.ui.components.EventCat.entries.indexOf(cat)) {
                cat = com.mobilegh.ui.components.EventCat.entries[it]
            }
            HDivider()
            PagedList(
                pager, empty = "最近 90 天没有公开动态",
                itemKey = { it.id },
                itemFilter = { cat == com.mobilegh.ui.components.EventCat.All || com.mobilegh.ui.components.eventCat(it) == cat },
            ) { EventItem(it) }
        }
    }
}
