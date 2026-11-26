package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobilegh.R
import com.mobilegh.data.ForkSort
import com.mobilegh.data.GitHub
import com.mobilegh.data.Api
import com.mobilegh.data.ProcessRefreshClaims
import com.mobilegh.data.Repo
import com.mobilegh.data.RepoFeedCache
import com.mobilegh.data.Session
import com.mobilegh.nav.Pager
import com.mobilegh.nav.PagerPage
import com.mobilegh.nav.RepoKind
import com.mobilegh.nav.rememberPager
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.PagedList
import com.mobilegh.ui.components.RepoItem
import com.mobilegh.ui.components.relTime
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 用户仓库与组织仓库：一个排序下拉 + 一份分页列表 */
@Composable
internal fun RepoFeedScreen(kind: RepoKind, login: String, name: String) {
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val pagerKey = "rl:$kind:${Session.login.orEmpty()}:$login:$name:$sort"
    val diskSnapshot = remember(pagerKey) { RepoFeedCache(File(Api.cacheDir, "repo-feeds")).read(pagerKey) }
    val pager = rememberPager(pagerKey, restore = { p -> diskSnapshot?.let { p.restoreFrom(it.items, it.pages, it.ended) } == true }) { page, force ->
        val order = SORTS[sort].first
        if (kind == RepoKind.Org) GitHub.orgRepos(login, order, page, force)
        else GitHub.userRepos(login, order, page, force)
    }
    rememberRepoFeedPersistence(pager, pagerKey)
    val state = rememberLazyListState()
    Page(repoFeedTitle(kind), subtitle = repoPageSubtitle(login, name)) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.padding(12.dp)) { Dropdown(SORTS[sort].second, SORTS.map { it.second }, { sort = it }) }
            HDivider()
            PagedList(pager, state = state, itemKey = { it.id }, empty = tr("没有可见的仓库"), emptyIcon = R.drawable.oc_repo) { RepoItem(it) }
        }
    }
}

/**
 * 仓库列表（非星标）的磁盘缓存与进入刷新。
 * 真实请求成功后写入磁盘；从磁盘恢复的数据不写回，避免被盖上新的时间戳。
 * 进入页面时只在本次启动第一次进入时静默刷新一次，之后直接显示缓存，直到下拉刷新或重新启动应用。
 */
@Composable
internal fun rememberRepoFeedPersistence(pager: Pager<Repo>, key: String) {
    val claimed = remember(pager, key) { ProcessRefreshClaims.claim(key) }
    val wasLoading = remember(pager) { BooleanArray(1) }
    LaunchedEffect(pager, pager.loading) {
        if (pager.loading) {
            wasLoading[0] = true
        } else if (wasLoading[0]) {
            wasLoading[0] = false
            if (pager.loadedOnce && pager.error == null) saveRepoFeed(key, pager)
        }
    }
    LaunchedEffect(pager, pager.silentRefreshVersion) {
        if (pager.silentRefreshSucceeded) saveRepoFeed(key, pager)
    }
    LaunchedEffect(pager) {
        if (claimed && pager.loadedOnce) pager.refreshSilently()
    }
}

private suspend fun saveRepoFeed(key: String, pager: Pager<Repo>) {
    val items = pager.items.toList()
    val pages = pager.committedPages
    val end = pager.end
    withContext(Dispatchers.IO) { RepoFeedCache(File(Api.cacheDir, "repo-feeds")).write(key, items, pages, end) }
}

@Composable
internal fun ForkListScreen(login: String, name: String) {
    val sort = Session.forkSort
    val labels = listOf(tr("最新创建"), tr("最早创建"), tr("Star 数"), tr("订阅人数"), tr("名称"))
    val pagerKey = "rl:forks:$login:$name:${sort.name}"
    val pager = if (sort == ForkSort.Name) {
        val cursors = retain("$pagerKey:cursors") { mutableMapOf<Int, String>() }
        rememberPager(pagerKey, fetchPage = { page, force ->
            val cursor = if (page == 1) null else cursors[page]
                ?: return@rememberPager PagerPage(emptyList(), hasMore = false)
            val result = GitHub.forksByName(login, name, cursor, force)
            PagerPage(result.repos, hasMore = result.nextCursor != null) {
                if (page == 1) cursors.clear()
                result.nextCursor?.let { cursors[page + 1] = it }
            }
        })
    } else {
        rememberPager(pagerKey) { page, force -> GitHub.forks(login, name, sort, page, force) }
    }
    Page("Fork", subtitle = repoPageSubtitle(login, name)) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.padding(12.dp)) { Dropdown(labels[sort.ordinal], labels, { Session.chooseForkSort(ForkSort.entries[it]) }) }
            HDivider()
            key(sort) {
                PagedList(
                    pager,
                    state = rememberLazyListState(),
                    itemKey = { it.fullName },
                    empty = tr("还没有人 Fork 这个仓库"),
                    emptyIcon = R.drawable.oc_repo,
                ) { repo -> RepoItem(repo, extra = relTime(repo.createdAt).takeIf(String::isNotEmpty)?.let { trf("创建于 {0}", it) }) }
            }
        }
    }
}

private fun repoFeedTitle(kind: RepoKind) = if (kind == RepoKind.Org) tr("组织仓库") else tr("仓库")

/** 页头副标题：Fork 页要看得出是「谁的哪个仓库」 */
internal fun repoPageSubtitle(login: String, name: String) = if (name.isNotEmpty()) "$login/$name" else login
