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
import androidx.compose.runtime.Composable
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
            Text(user?.name?.takeIf { it.isNotBlank() } ?: login, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (user == null) "@$login" else "@$login · ${fmtCount(user.publicRepos + (user.ownedPrivateRepos ?: 0))} 仓库 · ${fmtCount(user.followers)} 关注者",
                fontSize = 13.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Oc(R.drawable.oc_chevron_right, g.fgMuted, 16.dp)
    }
}

data class HomeShortcut(@DrawableRes val icon: Int, val label: String, val color: Color, val onClick: () -> Unit)

/** 首页默认的八个入口；颜色取自 Primer，暗色模式用对应的亮色版本 */
@Composable
fun homeShortcuts(
    onIssues: () -> Unit, onPulls: () -> Unit, onRepos: () -> Unit, onOrgs: () -> Unit,
    onStars: () -> Unit, onLists: () -> Unit, onGists: () -> Unit, onRecent: () -> Unit,
): List<HomeShortcut> {
    val g = Gh.c
    val severe = if (g.dark) Color(0xFFDB6D28) else Color(0xFFBC4C00)
    val sponsors = if (g.dark) Color(0xFFDB61A2) else Color(0xFFBF3989)
    return listOf(
        HomeShortcut(R.drawable.oc_issue_opened, "Issues", g.success, onIssues),
        HomeShortcut(R.drawable.oc_git_pull_request, "PR", g.accent, onPulls),
        HomeShortcut(R.drawable.oc_repo, "仓库", g.fgMuted, onRepos),
        HomeShortcut(R.drawable.oc_organization, "组织", severe, onOrgs),
        HomeShortcut(R.drawable.oc_star_fill, "已 Star", g.attention, onStars),
        HomeShortcut(R.drawable.oc_checklist, "列表", g.done, onLists),
        HomeShortcut(R.drawable.oc_code_square, "Gists", sponsors, onGists),
        HomeShortcut(R.drawable.oc_history, "最近浏览", g.accent, onRecent),
    )
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
