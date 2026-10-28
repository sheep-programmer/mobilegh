package com.mobilegh.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.theme.Gh

/** 圆角方块里放一个图标：列表行、概览卡片共用的“头像” */
@Composable
internal fun IconBadge(@DrawableRes icon: Int, tint: Color, background: Color, size: Int = 36) {
    Box(Modifier.size(size.dp).background(background, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Oc(icon, tint, (size * 0.5f).dp)
    }
}

/**
 * 列表目录顶部的概览卡片：图标 + 标题 + 一行统计 + 说明，可选一个主按钮。
 * GitHub 列表与本机列表共用，两个页签看起来一致。
 */
@Composable
internal fun ListsSummaryCard(
    @DrawableRes icon: Int,
    title: String,
    summary: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    @DrawableRes actionIcon: Int? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
) {
    val g = Gh.c
    Column(
        modifier.fillMaxWidth()
            .border(1.dp, g.border, RoundedCornerShape(10.dp))
            .background(g.canvasSubtle, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, g.accent, g.accentSubtle, 44)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                Text(summary, fontSize = 13.sp, color = g.fgMuted)
            }
        }
        Text(hint, Modifier.padding(top = 10.dp), fontSize = 12.sp, lineHeight = 17.sp, color = g.fgMuted)
        if (action != null) GhButton(action, Modifier.padding(top = 12.dp).fillMaxWidth(), primary = true, icon = actionIcon, enabled = actionEnabled, onClick = onAction)
    }
}

/** 没有列表时的空状态：大图标 + 标题 + 说明 + 主操作；比通用空状态更像一页引导 */
@Composable
internal fun ListsEmptyHero(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    @DrawableRes actionIcon: Int? = null,
    onAction: () -> Unit = {},
) {
    val g = Gh.c
    Column(
        modifier.padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(72.dp).background(g.accentSubtle, CircleShape), contentAlignment = Alignment.Center) {
            Oc(icon, g.accent, 34.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = g.fg, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = g.fgMuted, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            GhButton(action, primary = true, icon = actionIcon, onClick = onAction)
        }
    }
}
