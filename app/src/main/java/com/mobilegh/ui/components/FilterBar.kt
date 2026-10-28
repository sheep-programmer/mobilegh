package com.mobilegh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** 与输入框同高的筛选入口，有生效条件时显示数量并高亮 */
@Composable
fun FilterEntry(count: Int, onClick: () -> Unit) {
    val g = Gh.c
    val active = count > 0
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (active) g.accent.copy(alpha = 0.6f) else g.border, RoundedCornerShape(8.dp))
            .background(if (active) g.accentSubtle else g.canvasSubtle, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (active) trf("筛选，已选 {0} 项", count) else tr("筛选") }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(R.drawable.oc_sliders, if (active) g.accent else g.fgMuted, 18.dp)
        if (active) {
            Spacer(Modifier.width(6.dp))
            Text(
                "$count",
                Modifier.background(g.accent, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
                fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 已生效的筛选条件 (key, 描述)，每个都能单独撤销 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveFilterChips(chips: List<Pair<String, String>>, onRemove: (String) -> Unit, onClear: () -> Unit) {
    val g = Gh.c
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { (key, label) ->
            Row(
                Modifier
                    .heightIn(min = 30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(g.accentSubtle)
                    .border(1.dp, g.accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .clickable(role = Role.Button) { onRemove(key) }
                    .semantics { contentDescription = trf("移除筛选条件 {0}", label) }
                    .padding(start = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, fontSize = 12.sp, color = g.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(4.dp))
                Oc(R.drawable.oc_x, g.accent, 12.dp)
            }
        }
        Text(
            tr("全部清除"),
            Modifier.heightIn(min = 30.dp).clip(RoundedCornerShape(50)).clickable { onClear() }.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 12.sp, color = g.fgMuted,
        )
    }
}

/** 标题 + 单选胶囊，用于筛选弹层里选项不多的条件 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoicePicker(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val g = Gh.c
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp, color = g.fgMuted, fontWeight = FontWeight.Medium)
        // 选项少且文字短：用等宽分段选择器；选项多或文字长才退回自动换行的胶囊
        if (options.size in 2..4 && options.all { it.length <= 8 }) {
            Segmented(options, selected, Modifier.fillMaxWidth(), onSelect = onSelect)
            return@Column
        }
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, text ->
                val sel = i == selected
                Text(
                    text,
                    Modifier
                        .background(if (sel) g.accentSubtle else g.canvasSubtle, RoundedCornerShape(50))
                        .border(1.dp, if (sel) g.accent.copy(alpha = 0.6f) else g.border, RoundedCornerShape(50))
                        .clip(RoundedCornerShape(50))
                        .selectable(selected = sel, role = Role.RadioButton) { onSelect(i) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    fontSize = 13.sp, color = if (sel) g.accent else g.fg,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * 筛选弹层的统一外壳：浅灰底、白色分组卡片，和 GitHub 设置页一样的“分组列表”观感。
 * 仓库排序筛选与搜索组合筛选共用，头部、滚动区和底部按钮栏都在这里排好。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    title: String,
    subtitle: String,
    closeDescription: String,
    onDismiss: () -> Unit,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = Gh.c
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = g.canvasSubtle,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(g.accentSubtle, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Oc(R.drawable.oc_sliders, g.accent, 20.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = g.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = g.fgMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                OcButton(R.drawable.oc_x, onDismiss, description = closeDescription)
            }
            HDivider()
            // 滚动区用 weight：弹层被键盘或矮屏压缩时，底部按钮仍在屏幕内，只有中间内容滚动
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
            Column(Modifier.fillMaxWidth().background(g.canvas)) {
                HDivider()
                // 按钮等宽排布，窄屏和大字体下也不会错落换行
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = footer)
            }
        }
    }
}

/** 弹层里的一个分组：灰色小标题在卡片外，白色圆角卡片装控件 */
@Composable
fun FilterCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val g = Gh.c
    Column(modifier.fillMaxWidth()) {
        if (title != null) Text(title, color = g.fgMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Column(
            Modifier.fillMaxWidth()
                .border(1.dp, g.border, RoundedCornerShape(12.dp))
                .background(g.canvas, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}
