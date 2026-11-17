package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.data.GitHub
import com.mobilegh.data.Repo
import com.mobilegh.i18n.tr
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.theme.Gh

internal fun showShareRepositoryLoading(repositories: List<Repo>, loadedPage: List<Repo>?, error: String?): Boolean =
    repositories.isEmpty() && loadedPage == null && error == null

internal fun canLoadMoreSharedRepositories(repositories: List<Repo>, loadedPage: List<Repo>?, error: String?): Boolean =
    error != null || repositories.isNotEmpty() && loadedPage?.isNotEmpty() == true

/** 系统分享的仓库选择页；真正的编辑与创建仍复用原生 NewIssue。 */
@Composable
fun ShareIssueScreen(shareId: String) {
    val nav = LocalNav.current
    var query by rememberSaveable { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var repos by remember { mutableStateOf<List<Repo>>(emptyList()) }
    val loader = rememberLoader("share-repositories:$page") { GitHub.myRepos("owner,collaborator,organization_member", "updated", "all", page, it) }
    LaunchedEffect(loader.data) {
        loader.data?.let { loaded -> repos = if (page == 1) loaded else (repos + loaded).distinctBy { it.fullName.lowercase() } }
    }
    Page(tr("分享并新建 Issue"), subtitle = tr("选择仓库")) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            GhField(query, { query = it }, tr("搜索仓库"), Modifier.padding(16.dp))
            val visible = repos.filter { it.hasIssues && !it.archived && it.fullName.contains(query.trim(), true) }
            if (showShareRepositoryLoading(repos, loader.data, loader.error)) Loading(Modifier.fillMaxWidth().weight(1f))
            else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(visible, key = { it.fullName.lowercase() }) { repo ->
                    Column(Modifier.fillMaxWidth().clickable { nav.replace(Screen.NewIssue(repo.owner.login, repo.name, shareId = shareId)) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(repo.fullName, color = Gh.c.fg, fontSize = 15.sp)
                        repo.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = Gh.c.fgMuted, fontSize = 12.sp, maxLines = 2) }
                    }
                    HDivider()
                }
                if (visible.isEmpty()) item { Text(tr("没有匹配的仓库"), Modifier.padding(16.dp), color = Gh.c.fgMuted) }
                if (canLoadMoreSharedRepositories(repos, loader.data, loader.error)) item {
                    GhButton(if (loader.error == null) tr("加载更多") else tr("重试"), Modifier.padding(16.dp), enabled = !loader.loading) {
                        if (loader.error == null) page++ else loader.load(true)
                    }
                }
                loader.error?.let { item { Text(it, Modifier.padding(16.dp), color = Gh.c.attention) } }
            }
        }
    }
}
