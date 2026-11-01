package com.mobilegh.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Checkbox
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.selection.toggleable
import com.mobilegh.R
import com.mobilegh.ui.components.Card
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.i18n.tr
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
 * 列表目录顶部的概览：左侧图标 + 数量与公开 / 私有统计，右侧一个圆形「+」按钮（新建），下方一行说明。
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
            .border(1.dp, g.border, RoundedCornerShape(12.dp))
            .background(g.canvasSubtle, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, g.accent, g.accentSubtle, 44)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                Text(summary, fontSize = 13.sp, color = g.fgMuted)
            }
            if (action != null) {
                // 新建用圆形「+」按钮：不占整行，读屏时说明用途
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (actionEnabled) g.accent else g.borderMuted)
                        .clickable(enabled = actionEnabled, role = Role.Button, onClickLabel = action, onClick = onAction)
                        .semantics { contentDescription = action },
                    contentAlignment = Alignment.Center,
                ) { Oc(actionIcon ?: R.drawable.oc_plus, Color.White, 20.dp) }
            }
        }
        if (hint.isNotEmpty()) Text(hint, Modifier.padding(top = 10.dp), fontSize = 12.sp, lineHeight = 17.sp, color = g.fgMuted)
    }
}

/** 小标签：私有、仓库数、更新时间等元信息。用胶囊形状，换行后也不会挤成一团。 */
@Composable
internal fun ListPill(text: String, @DrawableRes icon: Int? = null) {
    val g = Gh.c
    Row(
        Modifier.background(g.canvasSubtle, RoundedCornerShape(50))
            .border(1.dp, g.borderMuted, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Oc(icon, g.fgMuted, 11.dp)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 列表详情的头部卡片：徽标、名称、说明、元信息标签，以及可选的一行提示 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ListDetailHeader(
    name: String,
    description: String,
    isPrivate: Boolean,
    meta: String,
    hint: String?,
    modifier: Modifier = Modifier,
) {
    val g = Gh.c
    Card(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    if (isPrivate) R.drawable.oc_lock else R.drawable.oc_checklist,
                    if (isPrivate) g.fgMuted else g.accent,
                    if (isPrivate) g.canvasSubtle else g.accentSubtle,
                    44,
                )
                Spacer(Modifier.width(12.dp))
                Text(name, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (description.isNotEmpty()) Text(description, fontSize = 13.sp, lineHeight = 18.sp, color = g.fgMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isPrivate) ListPill(tr("私有"), R.drawable.oc_lock)
                ListPill(meta)
            }
            if (!hint.isNullOrEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Oc(R.drawable.oc_info, g.fgMuted, 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(hint, fontSize = 12.sp, lineHeight = 17.sp, color = g.fgMuted)
            }
        }
    }
}

/**
 * 选择列表用的一整行：徽标 + 名称 + 元信息 + 右侧复选框。
 * 选中时整行有强调色底和边框；整行都可以点，不只是复选框。
 */
@Composable
internal fun ListPickRow(
    name: String,
    isPrivate: Boolean,
    meta: String,
    checked: Boolean,
    enabled: Boolean,
    horizontalPadding: Dp = 16.dp,
    onToggle: () -> Unit,
) {
    val g = Gh.c
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 4.dp)
            .clip(shape)
            .background(if (checked) g.accentSubtle else g.canvas, shape)
            .border(1.dp, if (checked) g.accent else g.border, shape)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            if (isPrivate) R.drawable.oc_lock else R.drawable.oc_checklist,
            if (isPrivate) g.fgMuted else g.accent,
            if (isPrivate) g.canvasSubtle else g.accentSubtle,
            40,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = if (enabled) g.fg else g.fgMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, color = g.fgMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // 复选框自身不处理点击，点击交给整行
        Checkbox(checked, null, enabled = enabled)
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
