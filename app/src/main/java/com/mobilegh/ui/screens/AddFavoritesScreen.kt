package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.CompactSearchField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.ScrollbarLazyColumn
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.delay

/**
 * 添加首页收藏（对应官方 App 首页的 Favorites → Add favorites）：
 * 不输入时列出自己的仓库和 Star 的仓库，输入关键词后搜索全部仓库；点一行加入或移出收藏。
 */
@Composable
fun AddFavoritesScreen() {
    val ctx = rememberCtx()
    val g = Gh.c
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(input) {
        delay(400)
        query = input.trim()
    }
    // 收藏变化时刷新勾选状态（收藏只存在本机）
    var version by rememberSaveable { mutableIntStateOf(0) }
    val layout = remember(version) { HomePrefs.layout() }
    val mine = rememberLoader("fav-mine") { GitHub.myRepos("owner,collaborator,organization_member", "pushed", "all", 1, it) }
    val starred = rememberLoader("fav-starred") { GitHub.starred(Session.login, 1, it) }
    val search = rememberLoader("fav-search:$query") { force ->
        if (query.isBlank()) emptyList() else GitHub.searchRepos(query, null, 1, force).items
    }
    fun toggle(r: Repo) {
        val owner = r.owner.login
        if (!layout.isFavorite(owner, r.name) && layout.favorites.size >= HomeLayout.MAX_FAVORITES) {
            ctx.toast(trf("最多收藏 {0} 个仓库", HomeLayout.MAX_FAVORITES))
            return
        }
        val added = HomePrefs.toggleFavorite(owner, r.name)
        version++
        ctx.toast(if (added) trf("已加入收藏：{0}", r.fullName) else trf("已移出收藏：{0}", r.fullName))
    }
    Page(tr("添加收藏"), subtitle = trf("已收藏 {0} 个仓库", layout.favorites.size)) { pad ->
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
                        else -> items(list, key = { "s:" + it.fullName }) { FavoriteCandidate(it, layout.isFavorite(it.owner.login, it.name)) { toggle(it) } }
                    }
                } else {
                    item { SectionTitle(tr("你的仓库")) }
                    mine.data?.let { list -> items(list, key = { "m:" + it.fullName }) { FavoriteCandidate(it, layout.isFavorite(it.owner.login, it.name)) { toggle(it) } } }
                        ?: item { Loading(Modifier.fillMaxWidth().padding(24.dp)) }
                    item { SectionTitle(tr("Star 的仓库")) }
                    starred.data?.let { list -> items(list, key = { "st:" + it.fullName }) { FavoriteCandidate(it, layout.isFavorite(it.owner.login, it.name)) { toggle(it) } } }
                        ?: item { Loading(Modifier.fillMaxWidth().padding(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FavoriteCandidate(repo: Repo, favorite: Boolean, onToggle: () -> Unit) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(repo.owner.avatarUrl, 28.dp, square = repo.owner.type == "Organization")
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(repo.fullName, color = g.fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            repo.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = g.fgMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // 已收藏显示实心星，未收藏显示空心星；点整行切换
        Oc(if (favorite) R.drawable.oc_star_fill else R.drawable.oc_star, if (favorite) g.star else g.fgMuted, 20.dp)
    }
    HDivider()
}
