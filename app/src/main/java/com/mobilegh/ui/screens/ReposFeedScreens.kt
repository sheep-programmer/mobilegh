package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.rememberPager
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.RepoItem
import com.mobilegh.ui.components.relTime

/** 用户仓库与组织仓库：一个排序下拉 + 一份分页列表 */
@Composable
internal fun RepoFeedScreen(kind: RepoKind, login: String, name: String) {
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val pager = rememberPager("rl:$kind:$login:$name:$sort") { page, force ->
        val order = SORTS[sort].first
        if (kind == RepoKind.Org) GitHub.orgRepos(login, order, page, force)
        else GitHub.userRepos(login, order, page, force)
    }
    Page(repoFeedTitle(kind), subtitle = repoPageSubtitle(login, name)) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.padding(12.dp)) { Dropdown(SORTS[sort].second, SORTS.map { it.second }, { sort = it }) }
            HDivider()
            PagedList(pager, empty = "没有可见的仓库", emptyIcon = R.drawable.oc_repo) { RepoItem(it) }
        }
    }
}

/** Fork 列表：GitHub 按最新创建排序，没有自己的排序菜单 */
@Composable
internal fun ForkListScreen(login: String, name: String) {
    val pager = rememberPager("rl:forks:$login:$name") { page, force -> GitHub.forks(login, name, page, force) }
    Page("Fork", subtitle = repoPageSubtitle(login, name)) { pad ->
        Column(Modifier.padding(pad)) {
            PagedList(
                pager,
                empty = "还没有人 Fork 这个仓库",
                emptyIcon = R.drawable.oc_repo,
            ) { RepoItem(it, extra = "创建于 ${relTime(it.createdAt)}") }
        }
    }
}

private fun repoFeedTitle(kind: RepoKind) = if (kind == RepoKind.Org) "组织仓库" else "仓库"

/** 页头副标题：Fork 页要看得出是「谁的哪个仓库」 */
internal fun repoPageSubtitle(login: String, name: String) = if (name.isNotEmpty()) "$login/$name" else login
