package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.HomeLayout
import com.mobilegh.data.HomePrefs
import com.mobilegh.data.Repo
import com.mobilegh.data.Session
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.CompactSearchField
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.ButtonRowAdaptive
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.MenuGroup
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.ScrollbarLazyColumn
import com.mobilegh.ui.components.SectionActionChip
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 添加首页收藏（对应官方 App 首页的 Favorites → Add favorites）：
 * 不输入时列出 GitHub 官方置顶仓库、自己的仓库和 Star 的仓库，输入关键词后搜索全部仓库；点一行加入或移出收藏。
 */
@Composable
fun AddFavoritesScreen() {
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()
    val g = Gh.c
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(input) {
        delay(400)
        query = input.trim()
    }
    // 收藏变化时刷新勾选状态
    var version by rememberSaveable { mutableIntStateOf(0) }
    val layout = remember(version) { HomePrefs.layout() }
    val login = Session.login.orEmpty()
    val pinned = rememberLoader("fav-pinned") { if (login.isBlank()) emptyList() else runCatching { GitHub.pinned(login) }.getOrDefault(emptyList()) }
    val mine = rememberLoader("fav-mine") { GitHub.myRepos("owner,collaborator,organization_member", "pushed", "all", 1, it) }
    val starred = rememberLoader("fav-starred") { GitHub.starred(Session.login, 1, it) }
    val search = rememberLoader("fav-search:$query") { force ->
        if (query.isBlank()) emptyList() else GitHub.searchRepos(query, null, 1, force).items
    }

    fun toggle(owner: String, name: String, fullName: String) {
        if (!layout.isFavorite(owner, name) && layout.favorites.size >= HomeLayout.MAX_FAVORITES) {
            ctx.toast(trf("最多收藏 {0} 个仓库", HomeLayout.MAX_FAVORITES))
            return
        }
        val added = HomePrefs.toggleFavorite(owner, name)
        version++
        ctx.toast(if (added) trf("已加入收藏：{0}", fullName) else trf("已移出收藏：{0}", fullName))
    }

    fun syncAllPinned() {
        val pins = pinned.data.orEmpty()
        if (pins.isEmpty()) {
            ctx.toast(tr("GitHub 暂无置顶仓库"))
            return
        }
        HomePrefs.syncWithPinned(pins, merge = true)
        version++
        ctx.toast(trf("已同步 {0} 个 GitHub 置顶仓库", pins.size))
    }

    Page(
        tr("添加收藏"),
        subtitle = trf("已收藏 {0} 个仓库", layout.favorites.size),
        actions = {
            if (pinned.data.orEmpty().isNotEmpty()) {
                OcButton(R.drawable.oc_sync, { syncAllPinned() }, description = tr("一键同步 GitHub 置顶"))
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            CompactSearchField(input, { input = it }, tr("搜索仓库"), Modifier.padding(12.dp), hint = tr("仓库名或 owner/name"))
            ScrollbarLazyColumn(Modifier.fillMaxSize()) {
                if (query.isNotBlank()) {
                    item { SectionTitle(tr("搜索结果")) }
                    val list = search.data
                    when {
                        list == null && search.loading -> item { Loading(Modifier.fillMaxWidth().padding(24.dp)) }
                        search.error != null -> item { Text(search.error!!, Modifier.padding(16.dp), color = g.danger, fontSize = 13.sp) }
                        list.isNullOrEmpty() -> item { Text(tr("没有找到匹配的仓库"), Modifier.padding(16.dp), color = g.fgMuted, fontSize = 13.sp) }
                        else -> items(list, key = { "s:" + it.fullName }) { repo ->
                            FavoriteCandidate(
                                fullName = repo.fullName,
                                avatarUrl = repo.owner.avatarUrl,
                                isOrg = repo.owner.type == "Organization",
                                description = repo.description,
                                favorite = layout.isFavorite(repo.owner.login, repo.name),
                            ) { toggle(repo.owner.login, repo.name, repo.fullName) }
                        }
                    }
                } else {
                    val pins = pinned.data.orEmpty()
                    if (pins.isNotEmpty()) {
                        item {
                            SectionTitle(
                                tr("GitHub 置顶仓库"),
                                trailing = {
                                    SectionActionChip(tr("一键全部导入"), tooltip = tr("将全部置顶仓库加入收藏夹")) {
                                        syncAllPinned()
                                    }
                                },
                            )
                        }
                        items(pins, key = { "pin:" + it.owner.login + "/" + it.name }) { p ->
                            FavoriteCandidate(
                                fullName = "${p.owner.login}/${p.name}",
                                avatarUrl = "https://github.com/${p.owner.login}.png",
                                isOrg = false,
                                description = p.description,
                                favorite = layout.isFavorite(p.owner.login, p.name),
                                badgeText = tr("GitHub 置顶"),
                            ) { toggle(p.owner.login, p.name, "${p.owner.login}/${p.name}") }
                        }
                    }
                    item { SectionTitle(tr("你的仓库")) }
                    mine.data?.let { list ->
                        items(list, key = { "m:" + it.fullName }) { repo ->
                            FavoriteCandidate(
                                fullName = repo.fullName,
                                avatarUrl = repo.owner.avatarUrl,
                                isOrg = repo.owner.type == "Organization",
                                description = repo.description,
                                favorite = layout.isFavorite(repo.owner.login, repo.name),
                            ) { toggle(repo.owner.login, repo.name, repo.fullName) }
                        }
                    } ?: item { Loading(Modifier.fillMaxWidth().padding(24.dp)) }
                    item { SectionTitle(tr("Star 的仓库")) }
                    starred.data?.let { list ->
                        items(list, key = { "st:" + it.fullName }) { repo ->
                            FavoriteCandidate(
                                fullName = repo.fullName,
                                avatarUrl = repo.owner.avatarUrl,
                                isOrg = repo.owner.type == "Organization",
                                description = repo.description,
                                favorite = layout.isFavorite(repo.owner.login, repo.name),
                            ) { toggle(repo.owner.login, repo.name, repo.fullName) }
                        }
                    } ?: item { Loading(Modifier.fillMaxWidth().padding(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FavoriteCandidate(
    fullName: String,
    avatarUrl: String,
    isOrg: Boolean = false,
    description: String? = null,
    favorite: Boolean,
    badgeText: String? = null,
    onToggle: () -> Unit,
) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatarUrl, 28.dp, square = isOrg)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fullName, color = g.fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (badgeText != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        badgeText,
                        color = g.attention,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = g.fgMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // 已收藏显示实心星，未收藏显示空心星；点整行切换
        Oc(if (favorite) R.drawable.oc_star_fill else R.drawable.oc_star, if (favorite) g.star else g.fgMuted, 20.dp)
    }
    HDivider()
}

/**
 * 收藏夹列表（「我的工作」里的收藏夹入口）：按收藏顺序列出仓库，长按可上移、下移或移出，
 * 支持与 GitHub 官方置顶仓库双向打通与同步。
 */
@Composable
fun FavoritesScreen() {
    val g = Gh.c
    val nav = LocalNav.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val layout = HomePrefs.layout()
    val login = Session.login.orEmpty()

    fun syncPinned() {
        scope.launch {
            if (login.isBlank()) return@launch
            val pins = runCatching { GitHub.pinned(login) }.getOrDefault(emptyList())
            if (pins.isNotEmpty()) {
                HomePrefs.syncWithPinned(pins, merge = true)
                snackbar.showSnackbar(trf("已同步 {0} 个 GitHub 置顶仓库", pins.size))
            } else {
                snackbar.showSnackbar(tr("GitHub 暂无置顶仓库"))
            }
        }
    }

    Page(
        tr("收藏夹"), subtitle = trf("共 {0} 个仓库", layout.favorites.size), snackbar = snackbar,
        actions = {
            MoreMenu(
                listOf(
                    MenuAction(tr("同步 GitHub 置顶"), icon = R.drawable.oc_sync) { syncPinned() },
                    MenuAction(tr("添加收藏")) { nav.push(Screen.AddFavorites) },
                    if (layout.favorites.isNotEmpty()) MenuAction(tr("整理收藏")) { nav.push(Screen.HomeCustomize) } else null,
                ).filterNotNull(),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (layout.favorites.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Oc(R.drawable.oc_bookmark, g.fgMuted, 36.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(tr("还没有收藏的仓库"), color = g.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr("把常用仓库加入收藏，打开 App 就能一键进入。你可以直接同步 GitHub 官方置顶，也可以手动添加。"),
                        color = g.fgMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    ButtonRowAdaptive(spacing = 10.dp) {
                        GhButton(tr("同步 GitHub 置顶"), icon = R.drawable.oc_sync, primary = true) {
                            syncPinned()
                        }
                        GhButton(tr("手动添加"), icon = R.drawable.oc_plus) { nav.push(Screen.AddFavorites) }
                    }
                }
            } else {
                ScrollbarLazyColumn(Modifier.fillMaxSize()) {
                    item {
                        MenuGroup {
                            layout.favorites.forEachIndexed { i, f ->
                                if (i > 0) HDivider()
                                HomeFavoriteRow(
                                    f, canUp = i > 0, canDown = i < layout.favorites.lastIndex,
                                    onOpen = { nav.push(Screen.Repo(f.owner, f.name)) },
                                    onMove = { d -> HomePrefs.moveFavorite(i, d) },
                                    onRemove = { removeFavoriteWithUndo(scope, snackbar, i, f) },
                                )
                            }
                        }
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextLink(tr("同步 GitHub 置顶")) { syncPinned() }
                            Text("·", color = g.fgMuted)
                            TextLink(tr("添加更多收藏")) { nav.push(Screen.AddFavorites) }
                        }
                    }
                }
            }
        }
    }
}
