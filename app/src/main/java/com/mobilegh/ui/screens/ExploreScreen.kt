package com.mobilegh.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.*
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import org.jsoup.Jsoup
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 探索主页：对标官方 GitHub App 设计。
 * 顶部支持「活动」与「动态」双标签切换，默认展示「活动」。
 * 「活动」展示发现入口与最近热门推荐仓库（含简介与一半内容预览）；「动态」展示关注事件流。
 */
@Composable
fun ExploreScreen() {
    val nav = LocalNav.current
    val g = Gh.c
    val login = Session.login
    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0: 活动 (默认), 1: 动态
    var cat by rememberSaveable { mutableStateOf(EventCat.All) }
    var refreshSeed by rememberSaveable { mutableIntStateOf(Random.nextInt(1, 100000)) }

    val scope = rememberCoroutineScope()
    val refreshRotate = remember { Animatable(0f) }
    var isRefreshingBatch by remember { mutableStateOf(false) }

    val activityState = rememberLazyListState()
    val feedState = rememberLazyListState()

    // 活动标签：支持无限下滑自动懒加载不中断，刷新/换一批换源
    val hotPager = rememberPager(
        key = "explore-activity-stream:$refreshSeed",
        pageSize = 30,
        hasMore = { page -> page < 34 },
    ) { page, force ->
        val q = "created:>${LocalDate.now().minusDays(45)} stars:>30"
        val result = GitHub.searchRepos(q, "stars", page, force)
        result.items.shuffled(Random(refreshSeed + page * 41L))
    }

    // 动态标签：关注的人与 Star 的仓库动态
    val feed = rememberPager("explore-activity:${login ?: "none"}") { p, f ->
        if (login != null) GitHub.received(login, p, f) else emptyList()
    }

    com.mobilegh.nav.OnTabReselect(Tab.Explore) {
        if (selectedTab == 0) {
            activityState.animateScrollToItem(0)
        } else {
            feedState.animateScrollToItem(0)
        }
    }

    Page(
        title = tr("探索"),
        back = false,
        contentWindowInsets = WindowInsets(0),
        actions = {
            IconButton(onClick = { nav.push(Screen.Search()) }) {
                Oc(R.drawable.oc_search, g.fg, 20.dp)
            }
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            // 两个标签切换：一个是「活动」，一个是「动态」，默认是「活动」
            com.mobilegh.ui.components.Segmented(
                options = listOf(tr("活动"), tr("动态")),
                selected = selectedTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) { selectedTab = it }

            if (selectedTab == 0) {
                // 活动 Tab：无限自动懒加载推荐流，下拉刷新与点击换一批重新随机
                PagedList(
                    pager = hotPager,
                    modifier = Modifier.fillMaxSize(),
                    state = activityState,
                    boxed = false,
                    divider = false,
                    onRefresh = {
                        refreshSeed = Random.nextInt(1, 100000)
                    },
                    header = {
                        // 1. 发现模块
                        item {
                            Text(
                                tr("发现"),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = g.fg,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Column {
                                    DiscoverEntryRow(
                                        icon = R.drawable.oc_flame,
                                        iconBg = Color(0xFFFF5A5F),
                                        title = tr("热门仓库"),
                                        subtitle = tr("查看 GitHub 每日、每周、每月热门趋势"),
                                        onClick = { nav.push(Screen.Trending) },
                                    )
                                    HDivider()
                                    DiscoverEntryRow(
                                        icon = R.drawable.oc_bookmark,
                                        iconBg = Color(0xFF8A63D2),
                                        title = tr("精选列表"),
                                        subtitle = tr("探索优质开源项目集合与技术指南"),
                                        onClick = { nav.push(Screen.CuratedLists) },
                                    )
                                }
                            }
                        }

                        // 2. 热门推荐模块头部 + 换一批
                        item {
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Oc(R.drawable.oc_flame, Color(0xFFFF5A5F), 18.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        tr("热门推荐"),
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = g.fg,
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(g.accent.copy(alpha = 0.08f))
                                        .border(1.dp, g.accent.copy(alpha = 0.2f), RoundedCornerShape(50))
                                        .clickable(enabled = !isRefreshingBatch) {
                                            isRefreshingBatch = true
                                            refreshSeed = Random.nextInt(1, 100000)
                                            scope.launch {
                                                try {
                                                    var spins = 0
                                                    do {
                                                        spins++
                                                        refreshRotate.animateTo(
                                                            targetValue = refreshRotate.value + 360f,
                                                            animationSpec = tween(
                                                                durationMillis = 550,
                                                                easing = FastOutSlowInEasing,
                                                            ),
                                                        )
                                                    } while (hotPager.loading && spins < 8)
                                                } finally {
                                                    refreshRotate.snapTo(0f)
                                                    isRefreshingBatch = false
                                                }
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Oc(
                                        R.drawable.oc_sync,
                                        g.accent,
                                        13.dp,
                                        Modifier.graphicsLayer {
                                            rotationZ = refreshRotate.value
                                        },
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        tr("换一批"),
                                        fontSize = 12.sp,
                                        color = g.accent,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    },
                    itemKey = { it.id },
                ) { repo ->
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
                        RecommendedRepoCard(repo)
                    }
                }
            } else {
                // 动态 Tab：关注的开发者与 Star 仓库的事件动态流
                PagedList(
                    feed,
                    modifier = Modifier.fillMaxSize(),
                    state = feedState,
                    autoLoad = cat == EventCat.All,
                    itemFilter = { cat == EventCat.All || eventCat(it) == cat },
                    itemSort = compareByDescending<Event> { parseInstant(it.createdAt)?.toEpochMilli() ?: Long.MIN_VALUE },
                    itemKey = { it.id },
                    header = {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    tr("关注动态"),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = g.fg,
                                )
                                Dropdown(
                                    label = cat.label,
                                    options = EventCat.filterOptions.map { it.label },
                                    onSelect = { cat = EventCat.filterOptions[it] },
                                    active = cat != EventCat.All,
                                )
                            }
                        }
                        if (login == null) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Oc(R.drawable.oc_pulse, g.accent, 36.dp)
                                        Spacer(Modifier.height(10.dp))
                                        Text(tr("登录 GitHub 查看你关注的动态"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = g.fg)
                                        Spacer(Modifier.height(4.dp))
                                        Text(tr("关注的开发者与 Star 的仓库动态将在此展示"), fontSize = 12.sp, color = g.fgMuted)
                                        Spacer(Modifier.height(14.dp))
                                        GhButton(tr("去登录"), primary = true) { nav.push(Screen.Login) }
                                    }
                                }
                            }
                        }
                    },
                    empty = if (login != null) tr("关注一些开发者或 Star 一些仓库后，这里会显示动态") else "",
                    emptyIcon = R.drawable.oc_pulse,
                ) { event ->
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        ExploreActivityCard(event)
                    }
                }
            }
        }
    }
}

/** 仓库内容及简介预览缓存 */
private object RepoPreviewCache {
    val snippets = ConcurrentHashMap<String, String>()
    val descMap = ConcurrentHashMap<String, String>()
}

@Composable
internal fun rememberRepoPreviewSnippet(owner: String, name: String, desc: String?): String? {
    val key = "$owner/$name"
    var snippet by remember(key) { mutableStateOf(RepoPreviewCache.snippets[key]) }
    LaunchedEffect(key) {
        if (snippet != null || owner.isBlank() || name.isBlank()) return@LaunchedEffect
        val text = withContext(Dispatchers.IO) {
            val html = runCatching { GitHub.readme(owner, name, null) }.getOrNull()
            if (!html.isNullOrBlank()) {
                runCatching {
                    val doc = Jsoup.parseBodyFragment(html)
                    doc.select("script, style, img").remove()
                    doc.select("a").forEach { a ->
                        if (a.text().isBlank()) a.remove()
                    }
                    val lines = mutableListOf<String>()
                    val elements = doc.body().select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre")
                    for (el in elements) {
                        if (el.parents().any { it.tagName() == "li" }) continue
                        val t = el.text().trim()
                        if (t.isNotBlank()) {
                            when {
                                el.tagName() == "li" -> lines.add("• $t")
                                el.tagName().startsWith("h") -> lines.add("\n$t")
                                else -> lines.add(t)
                            }
                        }
                    }
                    val clean = lines.joinToString("\n").trim()
                    if (clean.isNotBlank()) clean.take(1500) else null
                }.getOrNull()
            } else null
        }
        if (!text.isNullOrBlank()) {
            RepoPreviewCache.snippets[key] = text
            snippet = text
        }
    }
    return snippet
}

@Composable
internal fun rememberRepoDesc(o: String, r: String): String? {
    val key = "$o/$r"
    var desc by remember(key) { mutableStateOf(RepoPreviewCache.descMap[key]) }
    LaunchedEffect(key) {
        if (desc != null || o.isBlank() || r.isBlank()) return@LaunchedEffect
        val d = withContext(Dispatchers.IO) {
            runCatching { GitHub.repo(o, r).description }.getOrNull()
        }
        if (!d.isNullOrBlank()) {
            RepoPreviewCache.descMap[key] = d
            desc = d
        }
    }
    return desc
}

/** 推荐仓库精美卡片（保证简介展示，限制一半内容预览，点击进入仓库详情） */
@Composable
private fun RecommendedRepoCard(repo: Repo) {
    val nav = LocalNav.current
    val g = Gh.c
    val owner = repo.owner.login
    val name = repo.name
    val snippet = rememberRepoPreviewSnippet(owner, name, repo.description)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { nav.push(Screen.Repo(owner, name)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            // 头部行：仓库头像 + owner / repo + 语言与 Star
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    repo.owner.avatarUrl,
                    size = 24.dp,
                    square = repo.owner.type == "Organization",
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = g.fgMuted)) { append("$owner / ") }
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = g.fg)) { append(name) }
                    },
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (!repo.language.isNullOrBlank()) {
                    LangDot(repo.language)
                    Spacer(Modifier.width(4.dp))
                    Text(repo.language, fontSize = 12.sp, color = g.fgMuted)
                    Spacer(Modifier.width(8.dp))
                }
                IconText(R.drawable.oc_star, fmtCount(repo.stargazersCount))
            }

            Spacer(Modifier.height(8.dp))

            // 简介 (Description): 保证展示，每个仓库起码简介要有
            val desc = repo.description?.takeIf { it.isNotBlank() }
                ?: repo.topics.takeIf { it.isNotEmpty() }?.joinToString(", ") { "#$it" }
                ?: tr("热门开源项目，点击查看代码与详细文档")

            Text(
                text = desc,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = g.fg,
                lineHeight = 20.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            // Topics tags 标签
            if (repo.topics.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repo.topics.take(4).forEach { topic ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(g.accent.copy(alpha = 0.1f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "#$topic",
                                fontSize = 11.sp,
                                color = g.accent,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 仓库内容展示区块（宽度更开阔，展示大体核心内容，底部平滑淡出）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(g.canvasSubtle)
                    .border(0.5.dp, g.borderMuted, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Oc(R.drawable.oc_book, g.fgMuted, 13.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "README",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = g.fgMuted,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            repo.defaultBranch,
                            fontSize = 11.sp,
                            color = g.fgMuted,
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    val fallbackContent = remember(repo, desc) {
                        buildString {
                            append(desc)
                            append("\n\n")
                            append(trf("默认分支：{0} · 最新推送：{1}", repo.defaultBranch, relTime(repo.pushedAt ?: repo.updatedAt)))
                            if (repo.forksCount > 0) {
                                append(trf(" · Forks：{0}", fmtCount(repo.forksCount)))
                            }
                            if (repo.openIssuesCount > 0) {
                                append(trf(" · Issues：{0}", fmtCount(repo.openIssuesCount)))
                            }
                            if (repo.license != null) {
                                append(trf(" · 协议：{0}", repo.license.name))
                            }
                        }
                    }

                    // 内容展示更宽裕：扩大高度范围至 230dp，支持展示 11 行文字
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 230.dp),
                    ) {
                        Text(
                            text = snippet ?: fallbackContent,
                            fontSize = 13.sp,
                            color = g.fgMuted,
                            lineHeight = 20.sp,
                            maxLines = 11,
                            overflow = TextOverflow.Clip,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            g.canvasSubtle.copy(alpha = 0.95f),
                                            g.canvasSubtle,
                                        )
                                    )
                                ),
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tr("查看仓库全部内容与代码"),
                            fontSize = 12.sp,
                            color = g.accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Oc(R.drawable.oc_chevron_right, g.accent, 12.dp)
                    }
                }
            }
        }
    }
}

/** 发现区入口行（热门仓库 / 精选列表） */
@Composable
private fun DiscoverEntryRow(
    icon: Int,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val g = Gh.c
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Oc(icon, Color.White, 20.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Oc(R.drawable.oc_chevron_right, g.fgMuted, 16.dp)
    }
}

/** 活动动态卡片描述数据 */
private data class ExploreCardDesc(
    val repoOwner: String,
    val repoName: String,
    val actorAction: String,
    val badgeIcon: Int,
    val badgeColor: Color,
    val title: String,
    val body: String?,
    val actionLabel: String,
    val target: Screen,
)

/** 构造与官方 GitHub App 一致的活动卡片内容 */
private fun buildExploreCard(e: Event, o: String, r: String): ExploreCardDesc {
    val p = e.payload
    val repo = Screen.Repo(o, r)
    return when (e.type) {
        "ReleaseEvent" -> {
            val rel = p.obj("release")
            val tag = rel.str("tag_name")
            val name = rel.str("name")?.takeIf { it.isNotBlank() } ?: tag ?: "$o/$r"
            ExploreCardDesc(
                repoOwner = o,
                repoName = r,
                actorAction = tr("发布了一个版本"),
                badgeIcon = R.drawable.oc_tag,
                badgeColor = Color(0xFF2DA44E),
                title = name,
                body = rel.str("body"),
                actionLabel = tr("查看发布详情"),
                target = Screen.Releases(o, r, tag = tag),
            )
        }
        "PullRequestEvent" -> {
            val pr = p.obj("pull_request")
            val merged = pr.str("merged") == "true"
            val act = if (merged) tr("合并了 PR") else if (p.str("action") == "opened") tr("发起了 PR") else tr("更新了 PR")
            val n = p.int("number") ?: pr.int("number") ?: 0
            ExploreCardDesc(
                repoOwner = o,
                repoName = r,
                actorAction = act,
                badgeIcon = R.drawable.oc_git_pull_request,
                badgeColor = if (merged) Color(0xFF8250DF) else Color(0xFF0969DA),
                title = pr.str("title") ?: trf("PR #{0}", n),
                body = pr.str("body"),
                actionLabel = tr("查看 PR"),
                target = Screen.IssueDetail(o, r, n, true),
            )
        }
        "IssuesEvent" -> {
            val issue = p.obj("issue")
            val closed = p.str("action") == "closed"
            val act = if (closed) tr("关闭了 Issue") else if (p.str("action") == "opened") tr("发起了 Issue") else tr("更新了 Issue")
            val n = issue.int("number") ?: 0
            ExploreCardDesc(
                repoOwner = o,
                repoName = r,
                actorAction = act,
                badgeIcon = if (closed) R.drawable.oc_issue_closed else R.drawable.oc_issue_opened,
                badgeColor = if (closed) Color(0xFF8250DF) else Color(0xFF2DA44E),
                title = issue.str("title") ?: trf("Issue #{0}", n),
                body = issue.str("body"),
                actionLabel = tr("查看 Issue"),
                target = Screen.IssueDetail(o, r, n, false),
            )
        }
        "WatchEvent" -> {
            ExploreCardDesc(
                repoOwner = o,
                repoName = r,
                actorAction = tr("Star 了仓库"),
                badgeIcon = R.drawable.oc_star_fill,
                badgeColor = Color(0xFFD4A72C),
                title = "$o / $r",
                body = tr("已将此项目加入关注收藏"),
                actionLabel = tr("查看仓库"),
                target = repo,
            )
        }
        "ForkEvent" -> {
            val forkee = p.obj("forkee").str("full_name") ?: "$o/$r"
            val fo = forkee.substringBefore('/')
            val fn = forkee.substringAfter('/')
            ExploreCardDesc(
                repoOwner = o,
                repoName = r,
                actorAction = tr("Fork 了仓库"),
                badgeIcon = R.drawable.oc_repo_forked,
                badgeColor = Color(0xFF0969DA),
                title = forkee,
                body = p.obj("forkee").str("description") ?: trf("从 {0} fork 该仓库", "$o/$r"),
                actionLabel = tr("查看 Fork 仓库"),
                target = Screen.Repo(fo, fn),
            )
        }
        "CreateEvent" -> {
            val t = p.str("ref_type")
            val act = if (t == "repository") tr("创建了仓库") else if (t == "tag") trf("创建了标签 {0}", p.str("ref") ?: "") else trf("创建了分支 {0}", p.str("ref") ?: "")
            val icon = if (t == "tag") R.drawable.oc_tag else if (t == "repository") R.drawable.oc_repo else R.drawable.oc_git_branch
            ExploreCardDesc(
                repoOwner = o,
                repoName = r,
                actorAction = act,
                badgeIcon = icon,
                badgeColor = Color(0xFF0969DA),
                title = if (t == "repository") "$o / $r" else trf("{0} 分支", p.str("ref") ?: ""),
                body = p.str("description"),
                actionLabel = tr("查看仓库"),
                target = repo,
            )
        }
        "PushEvent" -> {
            val branch = p.str("ref")?.removePrefix("refs/heads/") ?: "main"
            val commits = (p["commits"] as? JsonArray)?.mapNotNull { it.str("message")?.lineSequence()?.firstOrNull() }
            val size = p.int("size") ?: commits?.size ?: 1
            ExploreCardDesc(
                repoOwner = o,
                repoName = r,
                actorAction = trf("推送了 {0} 个提交到 {1}", size, branch),
                badgeIcon = R.drawable.oc_repo_push,
                badgeColor = Color(0xFF0969DA),
                title = commits?.firstOrNull() ?: trf("向 {0} 推送提交", branch),
                body = commits?.drop(1)?.take(3)?.joinToString("\n") { "• $it" },
                actionLabel = tr("查看提交"),
                target = pushEventTarget(o, r, branch, p),
            )
        }
        else -> {
            ExploreCardDesc(
                repoOwner = o,
                repoName = r,
                actorAction = e.type?.removeSuffix("Event") ?: tr("动态"),
                badgeIcon = R.drawable.oc_pulse,
                badgeColor = Color(0xFF6E7781),
                title = "$o / $r",
                body = null,
                actionLabel = tr("查看仓库"),
                target = repo,
            )
        }
    }
}

/** 类似官方 GitHub App 的精美活动卡片 */
@Composable
internal fun ExploreActivityCard(e: Event) {
    val nav = LocalNav.current
    val g = Gh.c
    val (o, r) = e.repo.name.split('/').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
    val card = remember(e) { buildExploreCard(e, o, r) }
    val repoDesc = rememberRepoDesc(o, r)

    Column(Modifier.fillMaxWidth()) {
        // 头部：用户头像 + 角标 + 行为描述 + 时间
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp)) {
                Avatar(
                    e.actor.avatarUrl,
                    size = 32.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .clickable { nav.push(Screen.Profile(e.actor.login)) },
                )
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(g.canvas)
                        .padding(1.dp)
                        .clip(CircleShape)
                        .background(card.badgeColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Oc(card.badgeIcon, Color.White, 9.dp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = g.fg)) {
                        append(e.actor.displayLogin ?: e.actor.login)
                    }
                    withStyle(SpanStyle(color = g.fgMuted)) {
                        append("  ${card.actorAction}")
                    }
                },
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(relTime(e.createdAt), fontSize = 12.sp, color = g.fgMuted)
        }

        // 卡片主体
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { nav.push(card.target) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            ) {
                // 仓库行（小头像 + owner/repo）
                Row(
                    Modifier.fillMaxWidth().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { nav.push(Screen.Repo(o, r)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar("https://github.com/$o.png", size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$o / $r",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = g.fgMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))

                // 大标题
                Text(
                    card.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = g.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // 预览正文（保证每个仓库起码简介要有）
                val displayBody = if (!repoDesc.isNullOrBlank() && (card.body.isNullOrBlank() || e.type == "WatchEvent")) {
                    repoDesc
                } else {
                    card.body
                }

                if (!displayBody.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        displayBody.trim(),
                        fontSize = 13.sp,
                        color = g.fgMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                    )
                }

                Spacer(Modifier.height(10.dp))

                // 底部胶囊按钮
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(g.canvasSubtle)
                        .border(1.dp, g.border, RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { nav.push(card.target) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            card.actionLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = g.fg,
                        )
                        Spacer(Modifier.width(4.dp))
                        Oc(R.drawable.oc_chevron_right, g.fgMuted, 12.dp)
                    }
                }
            }
        }
    }
}

/** 热门趋势页面：热门仓库 + 热门开发者，支持按周期和语言筛选 */
@Composable
fun TrendingScreen() {
    val g = Gh.c
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var trend by rememberSaveable { mutableIntStateOf(0) }
    var langIndex by rememberSaveable { mutableIntStateOf(0) }
    val trendRanges = listOf(7L to tr("本周"), 30L to tr("本月"), 1L to tr("今天"))
    val languages = listOf(
        tr("全部语言"), "Kotlin", "Java", "Python", "Go", "Rust", "TypeScript", "JavaScript", "Swift", "C++", "C#"
    )
    val repoState = rememberLazyListState()
    val userState = rememberLazyListState()

    var lastRepoFilter by rememberSaveable { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(trend, langIndex) {
        val current = trend to langIndex
        if (lastRepoFilter != null && lastRepoFilter != current) {
            repoState.scrollToItem(0)
        }
        lastRepoFilter = current
    }

    var lastUserLang by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(langIndex) {
        if (lastUserLang != null && lastUserLang != langIndex) {
            userState.scrollToItem(0)
        }
        lastUserLang = langIndex
    }

    Page(tr("热门仓库"), back = true) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Spacer(Modifier.height(10.dp))
            Chips(listOf(tr("热门仓库"), tr("热门开发者")), tab) { tab = it }
            val langPart = if (langIndex == 0) "" else " language:${languages[langIndex]}"

            if (tab == 0) {
                val days = trendRanges[trend].first
                val trending = rememberPager("trend-repo:$trend:$langIndex") { p, force ->
                    GitHub.searchRepos("created:>${LocalDate.now().minusDays(days)}$langPart", "stars", p, force).items
                }
                PagedList(trending, state = repoState, itemKey = { it.id }, header = {
                    item {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Oc(R.drawable.oc_flame, g.attention, 18.dp, Modifier.padding(end = 6.dp))
                                Text(tr("新晋热门仓库"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = g.fg)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Dropdown(languages[langIndex], languages, { langIndex = it })
                                Dropdown(trendRanges[trend].second, trendRanges.map { it.second }, { trend = it })
                            }
                        }
                        HDivider()
                    }
                }) { RepoItem(it, extra = it.createdAt?.take(10)?.let { d -> trf("创建于 {0}", d) }) }
            } else {
                val trendingUsers = rememberPager("trend-user:$langIndex") { p, _ ->
                    val q = "type:user followers:>30$langPart"
                    GitHub.searchUsers(q, p).items
                }
                PagedList(trendingUsers, state = userState, itemKey = { it.id }, header = {
                    item {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Oc(R.drawable.oc_people, g.accent, 18.dp, Modifier.padding(end = 6.dp))
                                Text(tr("热门开发者"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = g.fg)
                            }
                            Dropdown(languages[langIndex], languages, { langIndex = it })
                        }
                        HDivider()
                    }
                }) { UserItem(it) }
            }
        }
    }
}

/** 精选列表页面：Awesome 系列与优质开源项目集合 */
@Composable
fun CuratedListsScreen() {
    val nav = LocalNav.current
    var catIndex by rememberSaveable { mutableIntStateOf(0) }
    val categories = remember {
        listOf(
            tr("全部") to "topic:awesome stars:>5000",
            tr("移动开发") to "awesome-android OR awesome-ios OR awesome-flutter OR awesome-swift OR awesome-kotlin in:name stars:>300",
            tr("AI 与大模型") to "awesome-machine-learning OR awesome-llm OR awesome-chatgpt OR awesome-deep-learning OR awesome-generative-ai in:name stars:>300",
            tr("前端开发") to "awesome-react OR awesome-vue OR awesome-javascript OR awesome-frontend OR awesome-css in:name stars:>500",
            tr("后端与架构") to "awesome-go OR awesome-python OR awesome-java OR awesome-rust OR awesome-kubernetes OR awesome-docker in:name stars:>1000",
            tr("学习与面试") to "roadmap OR interview in:name stars:>5000",
        )
    }
    val query = categories[catIndex].second
    val pager = rememberPager("curated:$query") { p, force ->
        GitHub.searchRepos(query, "stars", p, force).items
    }
    val state = rememberLazyListState()
    LaunchedEffect(catIndex) {
        state.scrollToItem(0)
    }

    Page(tr("精选列表"), back = true) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Spacer(Modifier.height(8.dp))
            Chips(categories.map { it.first }, catIndex) { catIndex = it }
            Spacer(Modifier.height(6.dp))
            PagedList(
                pager,
                state = state,
                itemKey = { it.id },
            ) { repo ->
                RepoItem(repo)
            }
        }
    }
}
