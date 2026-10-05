package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.Repo
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.Screen
import com.mobilegh.nav.UserKind
import com.mobilegh.nav.act
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.boxedItems
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.ErrorState
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
import com.mobilegh.ui.theme.Gh
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
    val starred = retain("starred") { mutableStateOf<Boolean?>(null) }
    val watching = retain("watching") { mutableStateOf<Boolean?>(null) }
    var starDelta by remember { mutableStateOf(0) }
    var showBranches by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    var showLists by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        if (starred.value == null) starred.value = runCatching { GitHub.isStarred(owner, name) }.getOrNull()
        if (watching.value == null) watching.value = runCatching { GitHub.isWatching(owner, name) }.getOrNull()
    }
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
                add(MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/$owner/$name") })
                add(MenuAction("Fork 此仓库") {
                    entry.act({ ctx.toast(it) }) {
                        val f = GitHub.fork(owner, name)
                        ctx.toast("已 Fork 到 ${f.fullName}")
                        nav.push(Screen.Repo(f.owner.login, f.name))
                    }
                })
                if (admin) add(MenuAction("仓库设置") { nav.push(Screen.RepoSettings(owner, name)) })
            })
        },
    ) { pad ->
        when {
            r == null && repo.error != null -> ErrorState(repo.error!!, Modifier.padding(pad)) { repo.load(true) }
            r == null -> Loading(Modifier.padding(pad).fillMaxSize())
            else -> PullToRefreshBox(repo.refreshing, { repo.refresh(); readme.refresh(); langs.refresh() }, Modifier.padding(pad)) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item { RepoHeader(r) }
                    item {
                        // Star / Watch / Fork
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val s = starred.value == true
                            GhButton(
                                "${if (s) "已 Star" else "Star"} ${fmtCount(r.stargazersCount + starDelta)}",
                                Modifier.weight(1f), icon = if (s) R.drawable.oc_star_fill else R.drawable.oc_star, enabled = starred.value != null,
                            ) {
                                val on = !s
                                starred.value = on
                                starDelta += if (on) 1 else -1
                                entry.act({ starred.value = !on; starDelta -= if (on) 1 else -1; ctx.toast(it) }) { GitHub.star(owner, name, on) }
                            }
                            val w = watching.value == true
                            GhButton(if (w) "Watching" else "Watch", Modifier.weight(1f), icon = R.drawable.oc_eye, enabled = watching.value != null) {
                                val on = !w
                                watching.value = on
                                entry.act({ watching.value = !on; ctx.toast(it) }) { GitHub.watch(owner, name, on) }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    item {
                        MenuGroup {
                            MenuRow(R.drawable.oc_issue_opened, "Issues", Color(0xFF1A7F37)) { nav.push(Screen.Issues(owner, name, false)) }
                            MenuRow(R.drawable.oc_git_pull_request, "Pull Requests", Color(0xFF0969DA)) { nav.push(Screen.Issues(owner, name, true)) }
                            if (r.hasDiscussions) MenuRow(R.drawable.oc_comment_discussion, "Discussions", Color(0xFF8250DF)) { nav.push(Screen.Discussions(owner, name)) }
                            MenuRow(R.drawable.oc_play, "Actions", Color(0xFF6E7781)) { nav.push(Screen.Actions(owner, name)) }
                            MenuRow(R.drawable.oc_tag, "Releases", Color(0xFF2DA44E)) { nav.push(Screen.Releases(owner, name)) }
                            MenuRow(R.drawable.oc_people, "贡献者统计", Color(0xFFBC4C00)) { nav.push(Screen.Contributors(owner, name)) }
                            MenuRow(R.drawable.oc_graph, "洞察 · 流量统计", Color(0xFF8250DF)) { nav.push(Screen.Insights(owner, name)) }
                            if (admin) {
                                MenuRow(R.drawable.oc_person, "协作者管理", Color(0xFFBF3989)) { nav.push(Screen.Collaborators(owner, name)) }
                                MenuRow(R.drawable.oc_gear, "仓库设置", Color(0xFF424A53)) { nav.push(Screen.RepoSettings(owner, name)) }
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

    if (showLists && r != null) AddToStarListsDialog(r) { showLists = false }
    if (showBranches && r != null) {
        BranchPicker(owner, name, branch ?: r.defaultBranch, onDismiss = { showBranches = false }) {
            ref.value = if (it == r.defaultBranch) null else it
            showBranches = false
        }
    }
    if (showClone && r != null) {
        GhDialog("克隆", onDismiss = { showClone = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val https = r.cloneUrl ?: "https://github.com/${r.fullName}.git"
                val fast = if (r.isPrivate) null else com.mobilegh.data.Net.cloneUrl(https)
                (listOf("HTTPS" to https) + listOfNotNull(fast?.let { "HTTPS 加速（${it.first}）" to "git clone ${it.second}" }) +
                    listOf("SSH" to (r.sshUrl ?: "git@github.com:${r.fullName}.git"), "GitHub CLI" to "gh repo clone ${r.fullName}")).forEach { (k, v) ->
                    Column(Modifier.fillMaxWidth().clickable { ctx.copy(v) }) {
                        Text(k, fontSize = 12.sp, color = g.fgMuted)
                        Text(v, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = g.fg)
                    }
                }
                Text("点按即可复制", fontSize = 12.sp, color = g.fgMuted)
            }
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
            IconText(R.drawable.oc_link, h.removePrefix("https://").removePrefix("http://"), g.accent, Modifier.clickable { ctx.openBrowser(if (h.startsWith("http")) h else "https://$h") }, bold = true)
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
            r.license?.let { IconText(R.drawable.oc_law, it.spdxId?.takeIf { s -> s != "NOASSERTION" } ?: it.name) }
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
                    if (list.isEmpty()) item { Text("无结果", color = g.fgMuted, modifier = Modifier.padding(12.dp)) }
                }
                l.error != null -> Text(l.error!!, color = g.danger)
                else -> Loading(Modifier.fillMaxWidth().height(120.dp))
            }
        }
    }
}
