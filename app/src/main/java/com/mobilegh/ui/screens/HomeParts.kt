package com.mobilegh.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.mobilegh.data.BuiltinShortcut
import com.mobilegh.data.FavoriteRepo
import com.mobilegh.data.HomeLayout
import com.mobilegh.data.HomePrefs
import com.mobilegh.data.SavedSearch
import com.mobilegh.ui.components.CardDropdownMenu
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MenuIcon
import com.mobilegh.ui.components.offerUndo
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.rememberTick
import com.mobilegh.ui.components.toast
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.Achievement
import com.mobilegh.data.GitHubStarList
import com.mobilegh.data.User
import com.mobilegh.data.UserStatus
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.NetImage
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.fmtCount
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement

/** 首页顶部的个人信息条，点击进入「我的」 */
@Composable
fun HomeProfile(
    login: String,
    user: User?,
    avatar: String?,
    status: UserStatus? = null,
    onSetStatus: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val g = Gh.c
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            // 上面只放姓名，避免长名字被截断
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(user?.avatarUrl ?: avatar, 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(user?.name?.takeIf { it.isNotBlank() } ?: login, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Oc(R.drawable.oc_chevron_right, g.fgMuted, 16.dp)
            }
            // 用户名与统计放在头像下面单独一块，每项之间用间距分开
            Spacer(Modifier.height(8.dp))
            Text("@$login", fontSize = 13.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (user != null) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    HomeStat(fmtCount(user.publicRepos + (user.ownedPrivateRepos ?: 0)), tr("仓库"))
                    HomeStat(fmtCount(user.followers), tr("关注者"))
                    HomeStat(fmtCount(user.following), tr("关注中"))
                    if (user.publicGists > 0) {
                        HomeStat(fmtCount(user.publicGists), "Gists")
                    }
                }
            }
        }
        if (status != null && !status.isEmpty) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .border(1.dp, g.border, RoundedCornerShape(16.dp))
                    .background(g.canvasSubtle, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = onSetStatus != null) { onSetStatus?.invoke() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (status.displayEmoji.isNotBlank()) Text(status.displayEmoji, fontSize = 14.sp)
                if (!status.message.isNullOrBlank()) Text(status.message, fontSize = 13.sp, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (status.indicatesLimitedAvailability) Pill(tr("忙碌"), g.attention)
            }
        } else if (onSetStatus != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .border(1.dp, g.borderMuted, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSetStatus() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("💬", fontSize = 12.sp)
                Text(tr("设置状态"), fontSize = 12.sp, color = g.fgMuted)
            }
        }
    }
}

@Composable
private fun HomeStat(value: String, label: String) {
    val g = Gh.c
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 13.sp, color = g.fgMuted)
    }
}

/** id 对应 [BuiltinShortcut.id] 或保存的搜索的 slot id，用于按用户布局排序 */
data class HomeShortcut(
    @DrawableRes val icon: Int, val label: String, val color: Color, val onClick: () -> Unit, val id: String = label,
    /** 非空时在图标右上角显示角标（与 GitHub 标签页的计数一致），标签文字保持干净 */
    val count: Int? = null,
)

/** 首页默认的八个入口；颜色取自 Primer，暗色模式用对应的亮色版本 */
@Composable
fun homeShortcuts(
    onIssues: () -> Unit, onPulls: () -> Unit, onRepos: () -> Unit, onOrgs: () -> Unit,
    onStars: () -> Unit, onLists: () -> Unit, onGists: () -> Unit, onRecent: () -> Unit,
    onFavorites: () -> Unit = {},
    onSaved: () -> Unit = {},
): List<HomeShortcut> {
    val g = Gh.c
    return listOf(
        HomeShortcut(R.drawable.oc_issue_opened, "Issues", g.success, onIssues, BuiltinShortcut.Issues.id),
        HomeShortcut(R.drawable.oc_git_pull_request, "Pull Requests", g.accent, onPulls, BuiltinShortcut.Pulls.id),
        HomeShortcut(R.drawable.oc_bookmark, tr("收藏夹"), g.severe, onFavorites, BuiltinShortcut.Favorites.id),
        HomeShortcut(R.drawable.oc_bookmark_fill, tr("稍后阅读"), g.accent, onSaved, BuiltinShortcut.Saved.id, count = com.mobilegh.data.SavedItems.count().takeIf { it > 0 }),
        HomeShortcut(R.drawable.oc_repo, tr("仓库"), g.fgMuted, onRepos, BuiltinShortcut.Repos.id),
        HomeShortcut(R.drawable.oc_organization, tr("组织"), g.severe, onOrgs, BuiltinShortcut.Orgs.id),
        HomeShortcut(R.drawable.oc_star_fill, tr("已 Star"), g.attention, onStars, BuiltinShortcut.Stars.id),
        HomeShortcut(R.drawable.oc_checklist, tr("Star 列表"), g.done, onLists, BuiltinShortcut.Lists.id),
        HomeShortcut(R.drawable.oc_code_square, "Gists", g.sponsors, onGists, BuiltinShortcut.Gists.id),
        HomeShortcut(R.drawable.oc_history, tr("最近浏览"), g.accent, onRecent, BuiltinShortcut.Recent.id),
    )
}

/** 保存的搜索在首页显示为快捷入口：Issue 用绿色、PR 用蓝色 */
@Composable
fun savedSearchShortcut(s: SavedSearch, onClick: () -> Unit): HomeShortcut =
    HomeShortcut(savedSearchIcon(s.icon), s.name, if (s.query.pulls) Gh.c.accent else Gh.c.success, onClick, HomeLayout.searchSlotId(s.id))

/** 按用户布局排出首页快捷入口；从未编辑过时与默认的八个入口完全一致 */
@Composable
fun arrangedShortcuts(layout: HomeLayout, builtins: List<HomeShortcut>, onSearch: (SavedSearch) -> Unit): List<HomeShortcut> {
    val byId = builtins.associateBy { it.id }
    return layout.visibleSlots().mapNotNull { slot ->
        byId[slot.id] ?: layout.search(slot.id)?.let { s -> savedSearchShortcut(s) { onSearch(s) } }
    }
}

/** 首页收藏的仓库：点按打开，长按可调整顺序或移出 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFavoriteRow(repo: FavoriteRepo, canUp: Boolean, canDown: Boolean, onOpen: () -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    val g = Gh.c
    val tick = rememberTick()
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClickLabel = tr("打开仓库"), onLongClickLabel = tr("调整收藏"), onLongClick = { menu = true }, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuIcon(R.drawable.oc_repo, g.fgMuted)
        Spacer(Modifier.width(14.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = g.fgMuted)) { append(repo.owner + " / ") }
                withStyle(SpanStyle(color = g.fg, fontWeight = FontWeight.SemiBold)) { append(repo.name) }
            },
            Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Oc(R.drawable.oc_chevron_right, g.fgMuted, 14.dp)
        CardDropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            actions = buildList {
                if (canUp) add(MenuAction(tr("上移"), icon = R.drawable.oc_arrow_up) { tick(); onMove(-1) })
                if (canDown) add(MenuAction(tr("下移"), icon = R.drawable.oc_arrow_down) { tick(); onMove(1) })
                add(MenuAction(tr("移出收藏"), danger = true, icon = R.drawable.oc_trash) { tick(); onRemove() })
            },
        )
    }
}

/** 四列快捷入口：浅色底图标 + 短标签，暗色模式下同样协调 */
@Composable
fun HomeShortcuts(items: List<HomeShortcut>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { s ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button, onClick = s.onClick)
                            .semantics { contentDescription = trf("打开{0}", s.label) }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(44.dp)) {
                            Box(Modifier.matchParentSize().background(s.color.copy(alpha = if (Gh.c.dark) 0.18f else 0.11f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                Oc(s.icon, s.color, 22.dp)
                            }
                            s.count?.let { n ->
                                Text(
                                    fmtCount(n),
                                    Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp)
                                        .background(Gh.c.neutralMuted, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                    fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = Gh.c.fg,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(s.label, fontSize = 12.sp, lineHeight = 16.sp, color = Gh.c.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 有待处理邀请时才出现的提醒条 */
@Composable
fun HomeInviteBanner(count: Int, onClick: () -> Unit) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp))
            .background(g.attentionSubtle).border(1.dp, g.attention.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(R.drawable.oc_mail, g.attention, 16.dp)
        Text(trf("你有 {0} 个待处理邀请", count), Modifier.weight(1f).padding(horizontal = 10.dp), fontSize = 14.sp, color = g.fg)
        Oc(R.drawable.oc_chevron_right, g.fgMuted, 14.dp)
    }
}

/** Star 列表的横向预览：[shown] 已按首页的搜索/排序处理，[total] 是列表总数（用于「管理列表」副标题） */
@Composable
fun HomeLists(shown: List<GitHubStarList>?, total: Int, error: String?, onList: (GitHubStarList) -> Unit, onAll: () -> Unit, onCreate: () -> Unit, onRetry: () -> Unit) {
    val g = Gh.c
    when {
        shown == null && error != null -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("列表暂时无法读取"), Modifier.weight(1f), fontSize = 13.sp, color = g.fgMuted)
            TextLink(tr("重试"), onClick = onRetry)
        }
        shown == null -> Row(Modifier.horizontalScroll(rememberScrollState(), enabled = false).padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { Box(Modifier.size(148.dp, 80.dp).background(g.canvasSubtle, RoundedCornerShape(8.dp))) }
        }
        // 与上面的搜索栏、排序按钮之间留出间距，卡片不再贴着按钮
        else -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (shown.isEmpty() && total > 0) ListTile(
                R.drawable.oc_search, tr("没有匹配的列表"), tr("换个关键词试试"), tr("没有匹配的列表"),
            ) { onAll() }
            shown.forEach { list ->
                ListTile(
                    if (list.isPrivate) R.drawable.oc_lock else R.drawable.oc_checklist, list.name, trf("{0} 个仓库", list.count),
                    trf("打开列表 {0}", list.name),
                ) { onList(list) }
            }
            // 没有列表时直接打开新建弹窗；已有列表时进入列表页管理
            if (total == 0) ListTile(R.drawable.oc_plus, tr("新建列表"), tr("整理喜欢的仓库"), tr("新建列表"), muted = true, onClick = onCreate)
            else ListTile(R.drawable.oc_plus, tr("管理列表"), trf("{0} 个列表", total), tr("打开 Star 列表"), muted = true, onClick = onAll)
        }
    }
}

@Composable
private fun ListTile(@DrawableRes icon: Int, title: String, subtitle: String, description: String, muted: Boolean = false, onClick: () -> Unit) {
    val g = Gh.c
    Column(
        Modifier.width(148.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp))
            .background(if (muted) g.canvasSubtle else g.canvas)
            .border(1.dp, g.border, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(12.dp),
    ) {
        Oc(icon, if (muted) g.fgMuted else g.done, 16.dp)
        Spacer(Modifier.height(8.dp))
        // 列表名允许两行，长名字不再只剩半截省略号
        Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = g.fgMuted, maxLines = 1)
    }
}

/** 成就行的显示状态；[count] 为 null 表示徽章列表尚未读到 */
enum class AchievementRowState { Loading, Empty, Badges, Error }

/** 纯逻辑：由徽章数量和是否出错决定成就行显示什么（有数据时优先于错误） */
fun achievementRowState(count: Int?, hasError: Boolean): AchievementRowState = when {
    count == null && hasError -> AchievementRowState.Error
    count == null -> AchievementRowState.Loading
    count == 0 -> AchievementRowState.Empty
    else -> AchievementRowState.Badges
}

/** 贡献卡片底部的成就行：缩略徽章 + 数量；重试是独立的可聚焦控件，不放进整行的合并描述里 */
@Composable
fun HomeAchievementsRow(badges: List<Achievement>?, error: String?, onOpen: () -> Unit, onRetry: () -> Unit) {
    val g = Gh.c
    val state = achievementRowState(badges?.size, error != null)
    val status = when (state) {
        AchievementRowState.Badges -> trf("{0} 个徽章", badges?.size ?: 0)
        AchievementRowState.Empty -> tr("暂无公开徽章")
        AchievementRowState.Error -> tr("暂时无法读取")
        AchievementRowState.Loading -> null
    }
    val description = listOfNotNull(tr("打开我的成就"), status).joinToString("，")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Oc(R.drawable.oc_verified, g.done, 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(tr("成就"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    AchievementRowState.Badges -> badges.orEmpty().take(5).forEach { b ->
                        NetImage(b.imageUrl, Modifier.size(26.dp), contentScale = ContentScale.Fit, placeholder = false)
                    }
                    AchievementRowState.Empty -> Text(tr("暂无公开徽章"), fontSize = 13.sp, color = g.fgMuted)
                    AchievementRowState.Error -> Text(tr("暂时无法读取"), fontSize = 13.sp, color = g.fgMuted)
                    AchievementRowState.Loading -> Unit
                }
            }
            if (state == AchievementRowState.Badges) Text(status.orEmpty(), fontSize = 13.sp, color = g.fgMuted)
            Spacer(Modifier.width(4.dp))
            Oc(R.drawable.oc_chevron_right, g.fgMuted, 14.dp)
        }
        if (state == AchievementRowState.Error) TextLink(tr("重试"), onClick = onRetry)
    }
}

/**
 * 移出首页收藏：立即生效，撤销窗口内可按原位放回。收藏只存在本机，不需要等待提交，所以不用延后写入。
 * 仓库本身不受影响。
 */
fun removeFavoriteWithUndo(scope: CoroutineScope, snackbar: SnackbarHostState, index: Int, repo: FavoriteRepo) {
    HomePrefs.removeFavorite(repo)
    scope.launch {
        if (snackbar.offerUndo(trf("已移出收藏「{0} / {1}」", repo.owner, repo.name))) {
            HomePrefs.restoreFavorite(index, repo)
        }
    }
}

/** 删除保存的搜索前的确认 */
@Composable
fun ConfirmDeleteSearchDialog(search: SavedSearch, onDismiss: () -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    GhDialog(tr("删除保存的搜索？"), onDismiss, confirm = tr("删除"), danger = true, onConfirm = {
        HomePrefs.removeSearch(search.id)
        ctx.toast(trf("已删除「{0}」", search.name))
        onDismiss()
    }) {
        Text(trf("「{0}」会从首页「我的工作」中移除。", search.name), color = g.fg)
    }
}
