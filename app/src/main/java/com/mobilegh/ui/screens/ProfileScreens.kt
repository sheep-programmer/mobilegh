package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.mobilegh.ui.components.ScrollbarLazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Contributions
import com.mobilegh.data.Event
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.ReadmeInlineZoomBox
import com.mobilegh.ui.components.ReadmeZoomResetPill
import com.mobilegh.data.GitHub
import com.mobilegh.data.PinnedRepo
import com.mobilegh.data.ProjectOwnerKind
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.data.UserStatus
import com.mobilegh.data.UserStatusApi
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
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.EventCat
import com.mobilegh.ui.components.EventItem
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.DayContributionSheet
import com.mobilegh.ui.components.ContributionPieSheet
import com.mobilegh.ui.components.buildContributionSlices
import com.mobilegh.ui.components.WeekdayContributionSheet
import com.mobilegh.data.ContribDay
import com.mobilegh.data.ContribWeek
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Heatmap
import com.mobilegh.ui.components.IconText
import com.mobilegh.ui.components.LangDot
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MenuRow
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.HtmlParser
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.RatioRow
import com.mobilegh.ui.components.RegisterScrollTop
import com.mobilegh.ui.components.RepoItem
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.SectionActionChip
import com.mobilegh.ui.components.StatCell
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.UserItem
import com.mobilegh.ui.components.UserStatusBottomSheet
import com.mobilegh.ui.components.VisibilityBadge
import com.mobilegh.ui.components.parseInstant
import com.mobilegh.ui.components.contribStats
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.ExternalLinkDialog
import com.mobilegh.ui.components.fmtCount
import com.mobilegh.ui.components.fmtDate
import com.mobilegh.ui.components.fmtSize
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.share
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.components.MdText
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

// ======================= 贡献热力图 =======================

class ContribState(scope: CoroutineScope, val login: String) {
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
    var inspectingDay by remember { mutableStateOf<Pair<ContribDay, ContribWeek>?>(null) }
    var inspectingWeekday by remember { mutableStateOf<Int?>(null) }

    inspectingDay?.let { (day, week) ->
        DayContributionSheet(
            login = state.login,
            day = day,
            week = week,
            onDismiss = { inspectingDay = null },
            onPickDay = { inspectingDay = it to week },
        )
    }

    if (c != null) {
        inspectingWeekday?.let { wd ->
            WeekdayContributionSheet(
                login = state.login,
                weekday = wd,
                cal = c.contributionCalendar,
                onDismiss = { inspectingWeekday = null },
                onPickDay = { day, week ->
                    inspectingWeekday = null
                    inspectingDay = day to week
                },
            )
        }
    }

    Column(Modifier.padding(vertical = 12.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (c == null) tr("贡献") else trf("{0} {1} 次贡献", state.year?.let { trf("{0} 年", it) } ?: tr("最近一年"), fmtCount(c.contributionCalendar.totalContributions)),
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = g.fg, modifier = Modifier.weight(1f),
            )
            if (state.loader.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = g.fgMuted)
            if (full && state.years.size > 1) {
                val yearOptions = listOf(tr("最近一年")) + state.years.map { "$it" }
                Spacer(Modifier.width(8.dp))
                Dropdown(
                    label = state.year?.toString() ?: tr("最近一年"),
                    options = yearOptions,
                    onSelect = { i -> state.select(if (i == 0) null else state.years[i - 1]) },
                    active = state.year != null,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            c != null -> {
                Heatmap(
                    c.contributionCalendar,
                    Modifier.padding(horizontal = 12.dp),
                    onDoubleTapDay = { day, week -> inspectingDay = day to week },
                )
                val st = contribStats(c.contributionCalendar)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .background(g.canvasSubtle, RoundedCornerShape(10.dp))
                        .border(1.dp, g.borderMuted, RoundedCornerShape(10.dp)),
                ) {
                    // 读作「当前连续 N 天」：标签是类别，数值带单位
                    StatCell(trf("{0} 天", st.current), tr("当前连续"), Modifier.weight(1f))
                    StatCell(trf("{0} 天", st.longest), tr("最长连续"), Modifier.weight(1f))
                    StatCell("${st.activeDays}", tr("活跃天数"), Modifier.weight(1f))
                    StatCell("${st.best?.contributionCount ?: 0}", tr("单日最高"), Modifier.weight(1f))
                }
                if (full) {
                    HDivider()
                    var showPieSheet by remember { mutableStateOf(false) }
                    var pieInitialIndex by remember { mutableIntStateOf(-1) }
                    val total = c.totalCommitContributions + c.totalPullRequestContributions + c.totalIssueContributions +
                        c.totalPullRequestReviewContributions + c.totalRepositoryContributions
                    val slices = remember(c, g) {
                        buildContributionSlices(
                            commits = c.totalCommitContributions,
                            prs = c.totalPullRequestContributions,
                            reviews = c.totalPullRequestReviewContributions,
                            issues = c.totalIssueContributions,
                            repos = c.totalRepositoryContributions,
                            g = g,
                        )
                    }

                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("贡献构成"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg, modifier = Modifier.weight(1f))
                            SectionActionChip(
                                text = tr("饼状图"),
                                tooltip = tr("查看饼状图构成分析"),
                                onClick = {
                                    pieInitialIndex = -1
                                    showPieSheet = true
                                },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        RatioRow(tr("提交 Commits"), c.totalCommitContributions, total, g.success, onClick = { pieInitialIndex = 0; showPieSheet = true })
                        RatioRow(tr("拉取请求 Pull Requests"), c.totalPullRequestContributions, total, g.accent, onClick = { pieInitialIndex = 1; showPieSheet = true })
                        RatioRow(tr("代码审查 Review"), c.totalPullRequestReviewContributions, total, g.done, onClick = { pieInitialIndex = 2; showPieSheet = true })
                        RatioRow(tr("议题 Issues"), c.totalIssueContributions, total, g.attention, onClick = { pieInitialIndex = 3; showPieSheet = true })
                        RatioRow(tr("新建仓库"), c.totalRepositoryContributions, total, g.fgMuted, onClick = { pieInitialIndex = 4; showPieSheet = true })
                        if (c.restrictedContributionsCount > 0) {
                            Text(trf("另有 {0} 次私有贡献（对你不可见）", c.restrictedContributionsCount), fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 4.dp))
                        }
                        st.best?.let { Text(trf("单日最高：{0}，{1} 次", it.date, it.contributionCount), fontSize = 12.sp, color = g.fgMuted, modifier = Modifier.padding(top = 4.dp)) }

                        if (showPieSheet) {
                            ContributionPieSheet(
                                title = tr("全年贡献构成分析"),
                                slices = slices,
                                total = total,
                                restrictedCount = c.restrictedContributionsCount,
                                initialSelectedIndex = pieInitialIndex,
                                onDismiss = { showPieSheet = false },
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(tr("一周中的活跃分布"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg)
                        Spacer(Modifier.height(8.dp))
                        BarChart(
                            st.byWeekday.toList(),
                            g.success,
                            height = 64.dp,
                            labels = listOf(tr("周日"), tr("周一"), tr("周二"), tr("周三"), tr("周四"), tr("周五"), tr("周六")),
                            unit = tr(" 次贡献"),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            listOf(tr("日"), tr("一"), tr("二"), tr("三"), tr("四"), tr("五"), tr("六")).forEach { label ->
                                Text(
                                    label,
                                    Modifier
                                        .weight(1f)
                                        .padding(vertical = 4.dp),
                                    fontSize = 11.sp,
                                    color = g.fgMuted,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                        if (c.commitContributionsByRepository.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text(tr("提交最多的仓库"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg)
                            val max = c.commitContributionsByRepository.maxOf { it.contributions.totalCount }
                            c.commitContributionsByRepository.forEach { rc ->
                                val name = rc.repository.nameWithOwner
                                Box(Modifier.clickable {
                                    val (o, r) = name.split('/').let { it[0] to it.getOrElse(1) { "" } }
                                    nav.push(Screen.Repo(o, r))
                                }) {
                                    RatioRow(name, rc.contributions.totalCount, max, g.success, badge = { VisibilityBadge(rc.repository.isPrivate) })
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
    if (isSelf) com.mobilegh.ui.components.RefreshProfileOnChange(user)
    val u = user.data
    if (u != null && u.type == "Organization") {
        OrgScreen(login)
        return
    }
    val contrib = rememberContrib(login)
    val pinned = rememberLoader("pinned:$login") { runCatching { GitHub.pinned(login) }.getOrDefault(emptyList()) }
    val orgs = rememberLoader("orgs:$login") { if (isSelf) GitHub.myOrgs(it) else GitHub.userOrgs(login, it) }
    val profileReadme = rememberLoader("readme:$login") { force ->
        val html = runCatching { GitHub.readme(login, login, null, force) }.getOrNull()
        if (html != null) {
            HtmlParser.parseCached(html, "https://github.com/$login/$login/blob/main/")
        }
        html
    }
    val userStatus = rememberLoader("status:$login") { runCatching { UserStatusApi.getStatus(login, isViewer = isSelf) }.getOrNull() }
    var showStatusDialog by remember { mutableStateOf(false) }
    val following = retain("following:$login") { mutableStateOf<Boolean?>(null) }
    // 状态查询失败要记下来并给出重试；LaunchedEffect 只在登录名变化时跑一次，否则关注按钮会一直禁用
    val followFailed = retain("following-failed:$login") { mutableStateOf(false) }
    var followRetry by remember(login) { mutableIntStateOf(0) }
    LaunchedEffect(login, followRetry) {
        if (!isSelf && following.value == null) {
            try {
                following.value = GitHub.isFollowing(login)
                followFailed.value = false
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { followFailed.value = true }
        }
    }
    var showOrgs by remember(login) { mutableStateOf(false) }
    val state = rememberLazyListState()
    if (me) com.mobilegh.nav.OnTabReselect(Tab.Me) { state.animateScrollToItem(0) }

    Page(
        if (me) tr("我的") else login,
        back = !me || nav.stack.isNotEmpty(),
        contentWindowInsets = if (me && nav.stack.isEmpty()) WindowInsets(0) else null,
        actions = {
            if (me || isSelf) OcButton(R.drawable.oc_gear, { nav.push(Screen.Settings) }, description = tr("设置"))
            MoreMenu(
                listOfNotNull(
                    if (isSelf) MenuAction(tr("编辑资料")) { nav.push(Screen.EditProfile) } else null,
                    MenuAction(tr("复制链接")) { ctx.copy("https://github.com/$login") },
                    MenuAction(tr("分享")) { ctx.share("https://github.com/$login") },
                    MenuAction(tr("在浏览器打开")) { ctx.openBrowser("https://github.com/$login") },
                ),
            )
        },
    ) { pad ->
        when {
            u == null && user.error != null -> ErrorState(user.error!!, Modifier.padding(pad).fillMaxSize()) { user.load(true) }
            u == null -> Loading(Modifier.padding(pad).fillMaxSize())
            else -> PullToRefreshBox(user.refreshing, { user.refresh(); contrib.refresh(); pinned.refresh(); orgs.refresh(); profileReadme.refresh(); userStatus.refresh() }, Modifier.padding(pad)) {
                RegisterScrollTop(state)
                ScrollbarLazyColumn(Modifier.fillMaxSize(), state = state) {
                    item { ProfileHeader(
                        u, isSelf, following.value, followFailed.value,
                        status = userStatus.data,
                        onEditStatus = if (isSelf) ({ showStatusDialog = true }) else null,
                        onRetryFollow = { followFailed.value = false; followRetry++ },
                    ) { on ->
                        following.value = on
                        entry.act({ following.value = !on; ctx.toast(it) }) { GitHub.follow(login, on) }
                    } }
                    val orgList = orgs.data.orEmpty()
                    if (orgList.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            orgList.forEach { o ->
                                Box(Modifier.minimumInteractiveComponentSize().clickable { nav.push(Screen.Org(o.login)) }) { Avatar(o.avatarUrl, 32.dp, square = true) }
                            }
                        }
                    }
                    profileReadme.data?.let { html ->
                        item { ProfileReadmeCard(login, html) }
                    }
                    val pins = pinned.data.orEmpty()
                    if (pins.isNotEmpty()) {
                        item { SectionTitle(tr("置顶")) }
                        items(pins, key = { it.owner.login + "/" + it.name }) { PinnedCard(it) }
                    }
                    item { AchievementsStrip(login, self = isSelf) }
                    item { SectionTitle(tr("贡献活动")) { SectionActionChip(text = tr("动态"), tooltip = tr("动态")) { nav.push(Screen.Activity(login)) } } }
                    item { Card(Modifier.padding(horizontal = 16.dp)) { ContributionBody(contrib, full = true) } }
                    item { Spacer(Modifier.height(16.dp)) }
                    if (!isSelf) {
                        item {
                            MenuGroup {
                                MenuRow(R.drawable.oc_repo, tr("仓库"), g.fgMuted, count = u.publicRepos) {
                                    nav.push(Screen.Repos(RepoKind.User, login))
                                }
                                MenuRow(R.drawable.oc_star, "Star", g.attention) {
                                    nav.push(Screen.Repos(RepoKind.Starred, login))
                                }
                                MenuRow(R.drawable.oc_organization, tr("组织"), g.severe, count = orgList.size.takeIf { it > 0 }) {
                                    when (orgRowTarget(isSelf, orgList.size)) {
                                        OrgRowTarget.MyOrgs -> nav.push(Screen.Orgs)
                                        OrgRowTarget.Single -> nav.push(Screen.Org(orgList.first().login))
                                        OrgRowTarget.List -> { showOrgs = true }
                                    }
                                }
                                MenuRow(R.drawable.oc_code_square, "Gists", g.sponsors, count = u.publicGists) {
                                    nav.push(Screen.Gists(login))
                                }
                                MenuRow(R.drawable.oc_pulse, tr("动态"), g.accent) {
                                    nav.push(Screen.Activity(login))
                                }
                                MenuRow(R.drawable.oc_table, tr("项目"), g.success) {
                                    nav.push(Screen.Projects(ProjectOwnerKind.User, login))
                                }
                            }
                        }
                    }
                    if (isSelf) item {
                        Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) { Column(Modifier.padding(14.dp)) {
                            Text(tr("账号信息"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = g.fg)
                            Spacer(Modifier.height(6.dp))
                            Info(tr("套餐"), u.plan?.name ?: "-")
                            Info(tr("私有仓库"), trf("{0} 个自有 / {1} 个总计", u.ownedPrivateRepos ?: 0, u.totalPrivateRepos ?: 0))
                            Info(tr("磁盘占用"), u.diskUsage?.let { fmtSize(it * 1024) } ?: "-")
                            Info(tr("两步验证"), if (u.twoFactorAuthentication == true) tr("已开启") else tr("未开启"))
                            Info(tr("注册于"), fmtDate(u.createdAt))
                        } }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
    if (showOrgs) GhDialog(tr("组织"), { showOrgs = false }) {
        val list = orgs.data.orEmpty()
        Column {
            if (list.isEmpty()) Text(tr("暂无公开组织"), color = g.fgMuted, fontSize = 13.sp)
            list.forEach { o ->
                Row(
                    Modifier.fillMaxWidth().clickable { showOrgs = false; nav.push(Screen.Org(o.login)) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(o.avatarUrl, 32.dp, square = true)
                    Spacer(Modifier.width(12.dp))
                    Text(o.login, color = g.fg, fontSize = 15.sp)
                }
            }
        }
    }
    if (showStatusDialog) {
        UserStatusBottomSheet(
            current = userStatus.data,
            onDismiss = { showStatusDialog = false },
            onSaved = { userStatus.refresh() },
        )
    }
}

/** 其他用户主页的「组织」行去向：自己的组织走组织页，只有一个直接进入，多个或没有则弹出列表 */
enum class OrgRowTarget { MyOrgs, Single, List }

/** 纯逻辑：根据是否本人与组织数量决定组织行的行为 */
fun orgRowTarget(isSelf: Boolean, orgCount: Int): OrgRowTarget = when {
    isSelf -> OrgRowTarget.MyOrgs
    orgCount == 1 -> OrgRowTarget.Single
    else -> OrgRowTarget.List
}

@Composable
private fun Info(k: String, v: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(k, fontSize = 13.sp, color = Gh.c.fgMuted, modifier = Modifier.width(80.dp))
        Text(v, fontSize = 13.sp, color = Gh.c.fg)
    }
}

@Composable
private fun ProfileHeader(
    u: User,
    isSelf: Boolean,
    following: Boolean?,
    followFailed: Boolean,
    status: UserStatus? = null,
    onEditStatus: (() -> Unit)? = null,
    onRetryFollow: () -> Unit,
    onFollow: (Boolean) -> Unit,
) {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(u.avatarUrl, 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (!u.name.isNullOrBlank()) Text(u.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.login, fontSize = 18.sp, color = g.fgMuted)
                    if (u.siteAdmin) { Spacer(Modifier.width(6.dp)); Pill("Staff", g.done) }
                }
                if (status != null && !status.isEmpty) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .border(1.dp, g.border, RoundedCornerShape(16.dp))
                            .background(g.canvasSubtle, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = onEditStatus != null) { onEditStatus?.invoke() }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (status.displayEmoji.isNotBlank()) Text(status.displayEmoji, fontSize = 13.sp)
                        if (!status.message.isNullOrBlank()) Text(status.message, fontSize = 12.sp, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (status.indicatesLimitedAvailability) Pill(tr("忙碌"), g.attention)
                    }
                } else if (isSelf && onEditStatus != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .border(1.dp, g.borderMuted, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onEditStatus() }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("💬", fontSize = 12.sp)
                        Text(tr("设置状态"), fontSize = 12.sp, color = g.fgMuted)
                    }
                }
            }
        }
        if (!u.bio.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            MdText(u.bio, fontSize = 15.sp)
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // 公司、位置、网址、邮箱都可能有很长的值，折行显示而不是省略
            val wrap = Int.MAX_VALUE
            u.company?.takeIf { it.isNotBlank() }?.let { IconText(R.drawable.oc_organization, it, g.fg, maxLines = wrap) }
            u.location?.takeIf { it.isNotBlank() }?.let { IconText(R.drawable.oc_location, it, g.fg, maxLines = wrap) }
            u.blog?.takeIf { it.isNotBlank() }?.let { b ->
                val u = if (b.startsWith("http")) b else "https://$b"
                IconText(R.drawable.oc_link, b, g.accent, Modifier.clickable { ExternalLinkDialog.show(u, b) }, bold = true, maxLines = wrap)
            }
            u.email?.takeIf { it.isNotBlank() }?.let { IconText(R.drawable.oc_mail, it, g.fg, Modifier.clickable { ctx.copy(it) }, maxLines = wrap) }
            u.twitterUsername?.takeIf { it.isNotBlank() }?.let { IconText(R.drawable.oc_mention, "@$it", g.fg) }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Oc2(R.drawable.oc_people)
            // 计数是可点的小文字，扩展到 48dp 的触摸区域
            Text(fmtCount(u.followers), Modifier.minimumInteractiveComponentSize().clickable { nav.push(Screen.Users(UserKind.Followers, u.login)) },
                fontWeight = FontWeight.SemiBold, color = g.fg, fontSize = 14.sp)
            Text(tr(" 关注者 · "), color = g.fgMuted, fontSize = 14.sp)
            Text(fmtCount(u.following), Modifier.minimumInteractiveComponentSize().clickable { nav.push(Screen.Users(UserKind.Following, u.login)) },
                fontWeight = FontWeight.SemiBold, color = g.fg, fontSize = 14.sp)
            Text(tr(" 关注中"), color = g.fgMuted, fontSize = 14.sp)
        }
        if (!isSelf) {
            Spacer(Modifier.height(12.dp))
            if (following == true) GhButton(tr("取消关注"), Modifier.fillMaxWidth()) { onFollow(false) }
            // 状态未知时：查询中禁用；查询失败则允许直接关注（PUT 是幂等的，已关注时也只会确认），并提供重试
            else GhButton(tr("关注"), Modifier.fillMaxWidth(), primary = true, enabled = following != null || followFailed) { onFollow(true) }
            if (following == null && followFailed) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("关注状态读取失败"), Modifier.weight(1f), fontSize = 13.sp, color = g.fgMuted)
                TextLink(tr("重试"), onClick = onRetryFollow)
            }
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
                com.mobilegh.ui.components.VisibilityBadge(p.isPrivate)
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
    val sorts = listOf("pushed" to tr("最近推送"), "updated" to tr("最近更新"), "full_name" to tr("名称"), "created" to tr("创建时间"))
    val repos = rememberPager("orgrepos:$login:$sort") { p, f -> GitHub.orgRepos(login, sorts[sort].first, p, f) }
    val state = rememberLazyListState()
    Page(
        org.data?.name ?: login, subtitle = tr("组织"),
        actions = {
            MoreMenu(listOf(
                MenuAction(tr("成员")) { nav.push(Screen.Users(UserKind.Members, login)) },
                MenuAction(tr("项目")) { nav.push(Screen.Projects(ProjectOwnerKind.Org, login)) },
                MenuAction(tr("复制链接")) { ctx.copy("https://github.com/$login") },
                MenuAction(tr("分享")) { ctx.share("https://github.com/$login") },
                MenuAction(tr("在浏览器打开")) { ctx.openBrowser("https://github.com/$login") },
            ))
        },
    ) { pad ->
        PagedList(repos, Modifier.padding(pad), state = state, itemKey = { it.id }, empty = tr("没有可见的仓库"), onRefresh = { org.refresh() }, header = {
            item {
                val o = org.data
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(o?.avatarUrl, 60.dp, square = true)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(o?.name ?: login, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                                if (o?.isVerified == true) { Spacer(Modifier.width(6.dp)); Pill(tr("已验证"), g.success) }
                            }
                            Text(login, color = g.fgMuted, fontSize = 14.sp)
                        }
                    }
                    o?.description?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(10.dp)); MdText(it, fontSize = 14.sp) }
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        // 同一行放两个长文本时，窄屏下都会被挤成省略号；改为各占一行并允许折行
                        o?.location?.takeIf { it.isNotBlank() }?.let { IconText(R.drawable.oc_location, it, maxLines = Int.MAX_VALUE) }
                        o?.blog?.takeIf { it.isNotBlank() }?.let { b ->
                            val u = if (b.startsWith("http")) b else "https://$b"
                            IconText(R.drawable.oc_link, b, g.accent, Modifier.clickable { ExternalLinkDialog.show(u, b) }, maxLines = Int.MAX_VALUE)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GhButton(tr("成员"), icon = R.drawable.oc_people) { nav.push(Screen.Users(UserKind.Members, login)) }
                        o?.let { IconText(R.drawable.oc_repo, trf("{0} 个仓库", fmtCount(it.publicRepos + (it.totalPrivateRepos ?: 0)))) }
                    }
                }
                MenuGroup(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    MenuRow(R.drawable.oc_table, tr("项目"), g.success) { nav.push(Screen.Projects(ProjectOwnerKind.Org, login)) }
                }
                HDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("仓库"), fontWeight = FontWeight.SemiBold, color = g.fg, modifier = Modifier.weight(1f))
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
        UserKind.Followers -> tr("关注者")
        UserKind.Following -> tr("关注中")
        UserKind.Stargazers -> tr("Star 用户")
        UserKind.Watchers -> tr("Watch 用户")
        UserKind.Contributors -> tr("贡献者")
        UserKind.Members -> tr("成员")
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
    val state = rememberLazyListState()
    Page(title, subtitle = if (b.isNotEmpty()) "$a/$b" else a) { pad ->
        PagedList(pager, Modifier.padding(pad), state = state, itemKey = { it.id }) { u ->
            UserItem(u, subtitle = u.contributions?.let { trf("{0} 次提交", it) })
        }
    }
}

@Composable
fun ActivityScreen(login: String) {
    val pager = rememberPager("events:$login") { p, f -> GitHub.userEvents(login, p, f) }
    var cat by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(com.mobilegh.ui.components.EventCat.All) }
    val state = rememberLazyListState()
    Page(
        tr("动态"),
        subtitle = login,
        actions = {
            Dropdown(
                label = cat.label,
                options = EventCat.filterOptions.map { it.label },
                onSelect = { cat = EventCat.filterOptions[it] },
                active = cat != EventCat.All,
            )
        },
    ) { pad ->
        PagedList(
            pager, Modifier.padding(pad), state = state, empty = tr("最近 90 天没有动态"),
            itemKey = { it.id },
            // 按发生时间倒序严格排列；没有时间的事件放到最后
            itemSort = compareByDescending<Event> { parseInstant(it.createdAt)?.toEpochMilli() ?: Long.MIN_VALUE },
            // 非「全部」分类是本地过滤：关闭自动翻页，避免稀有分类连续拉取空页；底部保留「加载更多」
            autoLoad = cat == com.mobilegh.ui.components.EventCat.All,
            itemFilter = { cat == com.mobilegh.ui.components.EventCat.All || com.mobilegh.ui.components.eventCat(it) == cat },
        ) { EventItem(it) }
    }
}

@Composable
private fun ProfileReadmeCard(login: String, html: String) {
    val g = Gh.c
    var expanded by rememberSaveable(login) { mutableStateOf(true) }
    var readmeScale by rememberSaveable(login) { mutableFloatStateOf(1f) }
    var readmeOffset by remember { mutableStateOf(Offset.Zero) }
    Card(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.mobilegh.ui.components.Oc(R.drawable.oc_book, g.fgMuted, 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "$login / README.md",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = g.fg,
                    modifier = Modifier.weight(1f),
                )
                if (expanded && readmeScale > 1.05f) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(g.accent.copy(alpha = 0.12f))
                            .clickable {
                                readmeScale = 1f
                                readmeOffset = Offset.Zero
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Oc(R.drawable.oc_sync, g.accent, 12.dp)
                        Text(
                            text = "${(readmeScale * 100).toInt()}% · " + tr("还原"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = g.accent,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (expanded) tr("收起") else tr("展开"), fontSize = 12.sp, color = g.accent)
            }
            if (expanded) {
                HDivider()
                Box(Modifier.fillMaxWidth()) {
                    ReadmeInlineZoomBox(
                        scale = readmeScale,
                        offset = readmeOffset,
                        onTransform = { newScale, newOffset ->
                            readmeScale = newScale
                            readmeOffset = newOffset
                        },
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    ) {
                        HtmlView(html, baseUrl = "https://github.com/$login/$login/blob/main/")
                    }
                    if (readmeScale > 1.05f) {
                        ReadmeZoomResetPill(
                            scale = readmeScale,
                            onReset = {
                                readmeScale = 1f
                                readmeOffset = Offset.Zero
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}
