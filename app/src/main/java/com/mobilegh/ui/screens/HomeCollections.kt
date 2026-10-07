package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.mobilegh.ui.components.Card
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.theme.Gh

@Composable
fun HomeCollections(
    lists: List<GitHubStarList>?,
    badges: List<Achievement>?,
    listError: String?,
    badgeError: String?,
    onLists: () -> Unit,
    onList: (GitHubStarList) -> Unit,
    onStars: () -> Unit,
    onAchievements: () -> Unit,
    onRetryLists: () -> Unit,
    onRetryBadges: () -> Unit,
) {
    val g = Gh.c
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.clickable(role = Role.Button, onClick = onLists).semantics { contentDescription = "打开收藏列表" }) {
            Column {
                Row(Modifier.fillMaxWidth().background(g.accentSubtle).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(g.accent.copy(alpha = 0.12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Oc(R.drawable.oc_checklist, g.accent, 26.dp)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("收藏列表", color = g.fg, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(if (lists != null) "${lists.size} 个列表 · 查看和管理收藏" else "查看、新建和管理收藏列表", color = g.fgMuted, fontSize = 12.sp)
                    }
                    Oc(R.drawable.oc_chevron_right, g.accent, 20.dp)
                }
                when {
                    lists == null && listError != null -> Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("列表暂时无法读取", Modifier.weight(1f), fontSize = 12.sp, color = g.fgMuted)
                        TextLink("重试", onClick = onRetryLists)
                    }
                    lists == null -> Text("正在加载收藏列表…", Modifier.padding(16.dp), color = g.fgMuted, fontSize = 12.sp)
                    lists.isEmpty() -> Text("把喜欢的仓库整理成列表，点击这里创建", Modifier.padding(16.dp), color = g.fgMuted, fontSize = 12.sp)
                    else -> lists.take(3).forEach { list ->
                        HDivider()
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onList(list) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Oc(if (list.isPrivate) R.drawable.oc_lock else R.drawable.oc_star, g.attention, 16.dp)
                            Text(list.name, Modifier.weight(1f).padding(horizontal = 10.dp), fontWeight = FontWeight.Medium, color = g.fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${list.count} 个仓库", fontSize = 12.sp, color = g.fgMuted)
                            Spacer(Modifier.width(8.dp))
                            Oc(R.drawable.oc_chevron_right, g.fgMuted, 14.dp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeShortcut("已 Star", "查看全部收藏仓库", R.drawable.oc_star_fill, g.attention, Modifier.weight(1f), onStars)
            HomeShortcut("我的成就", when {
                badges != null && badges.isNotEmpty() -> "${badges.size} 个公开徽章"
                badges != null -> "查看公开成就"
                badgeError != null -> "打开成就或重新加载"
                else -> "查看徽章与等级"
            }, R.drawable.oc_verified, g.done, Modifier.weight(1f), onAchievements)
        }
        if (!badges.isNullOrEmpty()) {
            Card(Modifier.clickable(role = Role.Button, onClick = onAchievements).semantics { contentDescription = "查看全部成就" }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    badges.take(3).forEach { AchievementBadge(it) }
                    Oc(R.drawable.oc_chevron_right, g.done, 18.dp)
                }
            }
        } else if (badgeError != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("成就暂时无法读取", Modifier.weight(1f), color = g.fgMuted, fontSize = 12.sp)
                TextLink("重试", onClick = onRetryBadges)
            }
        }
    }
}

@Composable
private fun HomeShortcut(title: String, subtitle: String, icon: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "打开$title" }) {
        Column(Modifier.fillMaxWidth().heightIn(min = 108.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Oc(icon, color, 26.dp)
            Text(title, color = Gh.c.fg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(subtitle, color = Gh.c.fgMuted, fontSize = 11.sp, maxLines = 2)
        }
    }
}
