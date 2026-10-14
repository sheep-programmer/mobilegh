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
import com.mobilegh.data.SavedSearch
import com.mobilegh.ui.components.MenuIcon
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
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.NetImage
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.fmtCount
import com.mobilegh.ui.theme.Gh

/** 首页顶部的个人信息条，点击进入「我的」 */
@Composable
fun HomeProfile(login: String, user: User?, avatar: String?, onClick: () -> Unit) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user?.avatarUrl ?: avatar, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(user?.name?.takeIf { it.isNotBlank() } ?: login, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (user == null) "@$login" else "@$login · ${fmtCount(user.publicRepos + (user.ownedPrivateRepos ?: 0))} 仓库 · ${fmtCount(user.followers)} 关注者",
                fontSize = 13.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Oc(R.drawable.oc_chevron_right, g.fgMuted, 16.dp)
    }
}

/** id 对应 [BuiltinShortcut.id] 或保存的搜索的 slot id，用于按用户布局排序 */
data class HomeShortcut(@DrawableRes val icon: Int, val label: String, val color: Color, val onClick: () -> Unit, val id: String = label)

/** 首页默认的八个入口；颜色取自 Primer，暗色模式用对应的亮色版本 */
@Composable
fun homeShortcuts(
    onIssues: () -> Unit, onPulls: () -> Unit, onRepos: () -> Unit, onOrgs: () -> Unit,
    onStars: () -> Unit, onLists: () -> Unit, onGists: () -> Unit, onRecent: () -> Unit,
): List<HomeShortcut> {
    val g = Gh.c
    return listOf(
        HomeShortcut(R.drawable.oc_issue_opened, "Issues", g.success, onIssues, BuiltinShortcut.Issues.id),
        HomeShortcut(R.drawable.oc_git_pull_request, "PR", g.accent, onPulls, BuiltinShortcut.Pulls.id),
        HomeShortcut(R.drawable.oc_repo, "仓库", g.fgMuted, onRepos, BuiltinShortcut.Repos.id),
        HomeShortcut(R.drawable.oc_organization, "组织", g.severe, onOrgs, BuiltinShortcut.Orgs.id),
        HomeShortcut(R.drawable.oc_star_fill, "已 Star", g.attention, onStars, BuiltinShortcut.Stars.id),
        HomeShortcut(R.drawable.oc_checklist, "列表", g.done, onLists, BuiltinShortcut.Lists.id),
        HomeShortcut(R.drawable.oc_code_square, "Gists", g.sponsors, onGists, BuiltinShortcut.Gists.id),
        HomeShortcut(R.drawable.oc_history, "最近浏览", g.accent, onRecent, BuiltinShortcut.Recent.id),
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
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClickLabel = "打开仓库", onLongClickLabel = "调整收藏", onLongClick = { menu = true }, onClick = onOpen)
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
        DropdownMenu(menu, { menu = false }, containerColor = g.canvas) {
            if (canUp) DropdownMenuItem(text = { Text("上移", color = g.fg) }, onClick = { menu = false; onMove(-1) })
            if (canDown) DropdownMenuItem(text = { Text("下移", color = g.fg) }, onClick = { menu = false; onMove(1) })
            DropdownMenuItem(text = { Text("移出首页收藏", color = g.danger) }, onClick = { menu = false; onRemove() })
        }
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
                            .semantics { contentDescription = "打开${s.label}" }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(44.dp).background(s.color.copy(alpha = if (Gh.c.dark) 0.18f else 0.11f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Oc(s.icon, s.color, 22.dp)
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
        Text("你有 $count 个待处理邀请", Modifier.weight(1f).padding(horizontal = 10.dp), fontSize = 14.sp, color = g.fg)
        Oc(R.drawable.oc_chevron_right, g.fgMuted, 14.dp)
    }
}

/** 收藏列表的横向预览 */
@Composable
fun HomeLists(lists: List<GitHubStarList>?, error: String?, onList: (GitHubStarList) -> Unit, onAll: () -> Unit, onRetry: () -> Unit) {
    val g = Gh.c
    when {
        lists == null && error != null -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("列表暂时无法读取", Modifier.weight(1f), fontSize = 13.sp, color = g.fgMuted)
            TextLink("重试", onClick = onRetry)
        }
        lists == null -> Row(Modifier.horizontalScroll(rememberScrollState(), enabled = false).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { Box(Modifier.size(148.dp, 80.dp).background(g.canvasSubtle, RoundedCornerShape(8.dp))) }
        }
        else -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            lists.forEach { list ->
                ListTile(
                    if (list.isPrivate) R.drawable.oc_lock else R.drawable.oc_checklist, list.name, "${list.count} 个仓库",
                    "打开列表 ${list.name}",
                ) { onList(list) }
            }
            ListTile(R.drawable.oc_plus, if (lists.isEmpty()) "新建列表" else "管理列表", if (lists.isEmpty()) "整理喜欢的仓库" else "${lists.size} 个列表", "打开收藏列表", muted = true, onClick = onAll)
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
        Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = g.fgMuted, maxLines = 1)
    }
}

/** 贡献卡片底部的成就行：缩略徽章 + 数量 */
@Composable
fun HomeAchievementsRow(badges: List<Achievement>?, error: String?, onOpen: () -> Unit, onRetry: () -> Unit) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "打开我的成就" }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(R.drawable.oc_verified, g.done, 16.dp)
        Spacer(Modifier.width(8.dp))
        Text("成就", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                !badges.isNullOrEmpty() -> badges.take(5).forEach { b ->
                    NetImage(b.imageUrl, Modifier.size(26.dp), contentScale = ContentScale.Fit, placeholder = false)
                }
                badges != null -> Text("暂无公开徽章", fontSize = 13.sp, color = g.fgMuted)
                error != null -> Text("暂时无法读取", fontSize = 13.sp, color = g.fgMuted)
            }
        }
        when {
            !badges.isNullOrEmpty() -> Text("${badges.size} 个徽章", fontSize = 13.sp, color = g.fgMuted)
            badges == null && error != null -> TextLink("重试", onClick = onRetry)
        }
        Spacer(Modifier.width(4.dp))
        Oc(R.drawable.oc_chevron_right, g.fgMuted, 14.dp)
    }
}
